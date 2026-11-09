#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>

namespace {

struct FrameRecord {
    int64_t arrival_ns;
};

/**
 * 单生产者 / 单消费者环形队列。
 *
 * head/tail 是**单调递增的序号**（不取模），用 head-tail 判断满/空，
 * 避免了「空和满都表现为 head==tail」需要额外状态位的经典坑。
 * 内存序：生产者 release 写、消费者 acquire 读，保证 FrameRecord 的写入
 * 在序号可见之前完成；反向同理。全程无 mutex、无 new。
 */
struct FrameClock {
    const uint32_t capacity;
    std::vector<FrameRecord> ring;
    std::atomic<uint64_t> head{0};
    std::atomic<uint64_t> tail{0};
    std::atomic<uint64_t> dropped{0};
    std::vector<float> scratch;  // 仅消费者线程使用，构造时 reserve 一次

    explicit FrameClock(uint32_t cap) : capacity(cap), ring(cap) {
        scratch.reserve(cap);
    }
};

inline FrameClock* fromHandle(jlong handle) {
    return reinterpret_cast<FrameClock*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arcana_gfx_FrameTiming_nativeCreate(JNIEnv*, jobject, jint capacity) {
    const uint32_t cap = capacity > 0 ? static_cast<uint32_t>(capacity) : 240u;
    return reinterpret_cast<jlong>(new FrameClock(cap));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_arcana_gfx_FrameTiming_nativePush(JNIEnv*, jobject, jlong handle, jlong arrival_ns) {
    FrameClock* clock = fromHandle(handle);
    if (clock == nullptr) return JNI_FALSE;

    const uint64_t h = clock->head.load(std::memory_order_relaxed);
    const uint64_t t = clock->tail.load(std::memory_order_acquire);
    if (h - t >= clock->capacity) {
        // 消费者落后一整圈：不覆盖尚未消费的记录，直接计一次背压丢帧。
        clock->dropped.fetch_add(1, std::memory_order_relaxed);
        return JNI_FALSE;
    }
    clock->ring[static_cast<size_t>(h % clock->capacity)].arrival_ns =
        static_cast<int64_t>(arrival_ns);
    clock->head.store(h + 1, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcana_gfx_FrameTiming_nativeDrain(
    JNIEnv* env, jobject, jlong handle, jlong render_ns, jfloatArray out) {
    FrameClock* clock = fromHandle(handle);
    if (clock == nullptr || out == nullptr) return;

    const uint64_t tail = clock->tail.load(std::memory_order_relaxed);
    const uint64_t head = clock->head.load(std::memory_order_acquire);

    clock->scratch.clear();
    uint64_t cursor = tail;
    while (cursor < head) {
        const int64_t arrival = clock->ring[static_cast<size_t>(cursor % clock->capacity)].arrival_ns;
        const double ms = static_cast<double>(static_cast<int64_t>(render_ns) - arrival) / 1.0e6;
        // 过滤时钟基准不一致 / 长时间后台产生的异常值，避免污染分位数。
        if (ms >= 0.0 && ms < 10000.0) {
            clock->scratch.push_back(static_cast<float>(ms));
        }
        ++cursor;
    }
    clock->tail.store(cursor, std::memory_order_release);

    float p50 = 0.0f;
    float p95 = 0.0f;
    float max_ms = 0.0f;
    if (!clock->scratch.empty()) {
        std::sort(clock->scratch.begin(), clock->scratch.end());
        const size_t n = clock->scratch.size();
        p50 = clock->scratch[n / 2];
        const size_t idx95 = std::min(n - 1, static_cast<size_t>(static_cast<double>(n) * 0.95));
        p95 = clock->scratch[idx95];
        max_ms = clock->scratch[n - 1];
    }

    const uint64_t dropped = clock->dropped.exchange(0, std::memory_order_relaxed);
    jfloat values[5] = {
        static_cast<jfloat>(clock->scratch.size()),
        p50,
        p95,
        max_ms,
        static_cast<jfloat>(dropped),
    };
    env->SetFloatArrayRegion(out, 0, 5, values);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcana_gfx_FrameTiming_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}
