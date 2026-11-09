#include <jni.h>

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <cstring>

/**
 * GPU 帧耗时：GL_EXT_disjoint_timer_query 的 JNI 绑定。
 *
 * Android 的 Java 层（GLES20/GLES30/GLES31Ext）没有暴露 glGenQueriesEXT 这一组
 * 入口，所以 GPU 时间在纯 Kotlin 侧拿不到。这里用 eglGetProcAddress 动态解析扩展，
 * 在 GLES2 上下文上也能工作，不需要把整个库升到 GLES3。
 *
 * 三枚 query 轮换：begin 占一枚 free，end 标成 pending，poll 只取「已完成」的，
 * 全程不调用 glFinish / 不阻塞 GPU 流水线。GPU 发生 disjoint（频率切换 / 恢复上下文）
 * 时结果不可信，直接丢弃这一轮而不是报一个假数字。
 */
namespace {

PFNGLGENQUERIESEXTPROC pGenQueries = nullptr;
PFNGLDELETEQUERIESEXTPROC pDeleteQueries = nullptr;
PFNGLBEGINQUERYEXTPROC pBeginQuery = nullptr;
PFNGLENDQUERYEXTPROC pEndQuery = nullptr;
PFNGLGETQUERYOBJECTUIVEXTPROC pGetQueryObjectuiv = nullptr;
PFNGLGETQUERYOBJECTUI64VEXTPROC pGetQueryObjectui64v = nullptr;

constexpr int kFree = 0;
constexpr int kActive = 1;
constexpr int kPending = 2;
constexpr int kQueryCount = 3;

struct GpuTimer {
    GLuint queries[kQueryCount]{};
    int state[kQueryCount]{};
    int active = -1;
    GLuint64 lastNs = 0;
    bool supported = false;
};

inline GpuTimer* fromHandle(jlong handle) {
    return reinterpret_cast<GpuTimer*>(handle);
}

bool resolveExtensions() {
    pGenQueries = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(
            eglGetProcAddress("glGenQueriesEXT"));
    pDeleteQueries = reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(
            eglGetProcAddress("glDeleteQueriesEXT"));
    pBeginQuery = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(
            eglGetProcAddress("glBeginQueryEXT"));
    pEndQuery = reinterpret_cast<PFNGLENDQUERYEXTPROC>(
            eglGetProcAddress("glEndQueryEXT"));
    pGetQueryObjectuiv = reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(
            eglGetProcAddress("glGetQueryObjectuivEXT"));
    pGetQueryObjectui64v = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(
            eglGetProcAddress("glGetQueryObjectui64vEXT"));

    if (!pGenQueries || !pDeleteQueries || !pBeginQuery || !pEndQuery ||
        !pGetQueryObjectuiv || !pGetQueryObjectui64v) {
        return false;
    }
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    return extensions != nullptr && std::strstr(extensions, "GL_EXT_disjoint_timer_query") != nullptr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arcana_gfx_GpuTimer_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new GpuTimer());
}

/** GL 线程：EGL 上下文（重）建后调用。旧 query 属于旧上下文，不能复用。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_arcana_gfx_GpuTimer_nativeReset(JNIEnv*, jobject, jlong handle) {
    GpuTimer* timer = fromHandle(handle);
    if (timer == nullptr) return JNI_FALSE;

    timer->supported = false;
    timer->active = -1;
    timer->lastNs = 0;
    for (int i = 0; i < kQueryCount; ++i) {
        timer->queries[i] = 0;
        timer->state[i] = kFree;
    }
    if (!resolveExtensions()) return JNI_FALSE;

    pGenQueries(kQueryCount, timer->queries);
    for (int i = 0; i < kQueryCount; ++i) {
        if (timer->queries[i] == 0) return JNI_FALSE;
    }
    timer->supported = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arcana_gfx_GpuTimer_nativeBegin(JNIEnv*, jobject, jlong handle) {
    GpuTimer* timer = fromHandle(handle);
    if (timer == nullptr || !timer->supported || timer->active >= 0) return JNI_FALSE;

    for (int i = 0; i < kQueryCount; ++i) {
        if (timer->state[i] == kFree) {
            timer->state[i] = kActive;
            timer->active = i;
            pBeginQuery(GL_TIME_ELAPSED_EXT, timer->queries[i]);
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;   // 三枚都还没出结果：本帧不计时，绝不阻塞
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcana_gfx_GpuTimer_nativeEnd(JNIEnv*, jobject, jlong handle) {
    GpuTimer* timer = fromHandle(handle);
    if (timer == nullptr || !timer->supported || timer->active < 0) return;
    pEndQuery(GL_TIME_ELAPSED_EXT);
    timer->state[timer->active] = kPending;
    timer->active = -1;
}

/** @return 最近一次有效结果的毫秒数；-1 = 扩展不可用或本轮结果作废。 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_arcana_gfx_GpuTimer_nativePoll(JNIEnv*, jobject, jlong handle) {
    GpuTimer* timer = fromHandle(handle);
    if (timer == nullptr || !timer->supported) return -1.0f;

    GLint disjoint = 0;
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (disjoint != 0) {
        // 频率切换 / 上下文丢失期间的计时跨度没有意义，全部作废。
        for (int i = 0; i < kQueryCount; ++i) {
            if (timer->state[i] == kPending) timer->state[i] = kFree;
        }
        timer->lastNs = 0;
        return -1.0f;
    }

    for (int i = 0; i < kQueryCount; ++i) {
        if (timer->state[i] != kPending) continue;
        GLuint available = 0;
        pGetQueryObjectuiv(timer->queries[i], GL_QUERY_RESULT_AVAILABLE_EXT, &available);
        if (available == 0) continue;
        GLuint64 ns = 0;
        pGetQueryObjectui64v(timer->queries[i], GL_QUERY_RESULT_EXT, &ns);
        timer->lastNs = ns;
        timer->state[i] = kFree;
    }
    return timer->lastNs == 0 ? -1.0f
                              : static_cast<jfloat>(static_cast<double>(timer->lastNs) / 1.0e6);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcana_gfx_GpuTimer_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}
