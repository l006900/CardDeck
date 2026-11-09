#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>

namespace {

struct FrameRecord {
    int64_t arrival_ns;
    int64_t interval_ns;   // 与上一帧到达的间隔；<=0 表示首帧或异常
};

/**
 * 单生产者 / 单消费者环形队列。
 *
 * head/tail 是**单调递增的序号**（不取模），用 head-tail 判断满/空，
 * 避免了「空和满都表现为 head==tail」需要额外状态位的经典坑。
 * 内存序：生产者 release 写、消费者 acquire 读，保证 FrameRecord 的写入
 * 在序号可见之前完成；反向同理。全程无 mutex、无 new。
 */
constexpr size_t kStatWindow = 240;

struct FrameClock {
    const uint32_t capacity;
    std::vector<FrameRecord> ring;
    std::atomic<uint64_t> head{0};
    std::atomic<uint64_t> tail{0};
    std::atomic<uint64_t> dropped{0};
    std::atomic<int64_t> last_arrival_ns{0};  // 只由生产者写

    // 分位数基于**滑动窗口**而不是单次 drain：GL 线程每帧都 drain，
    // 恰好没有新帧到达的那一次若直接输出 0，HUD 会把真实的延迟/抖动显示成没有。
    float latency_window[kStatWindow]{};
    float interval_window[kStatWindow]{};
    size_t stat_count = 0;
    size_t stat_cursor = 0;

    std::vector<float> scratch;    // 仅消费者线程使用：排序缓冲
    std::vector<float> intervals;  // 仅消费者线程使用：排序缓冲

    explicit FrameClock(uint32_t cap) : capacity(cap), ring(cap) {
        scratch.reserve(kStatWindow);
        intervals.reserve(kStatWindow);
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

    const int64_t previous = clock->last_arrival_ns.load(std::memory_order_relaxed);
    int64_t interval = 0;
    if (previous > 0) {
        interval = static_cast<int64_t>(arrival_ns) - previous;
        // 后台回来 / 时钟基准异常的超长间隔不参与抖动统计。
        if (interval <= 0 || interval > 1000000000LL) interval = 0;
    }
    clock->last_arrival_ns.store(static_cast<int64_t>(arrival_ns), std::memory_order_relaxed);

    FrameRecord& slot = clock->ring[static_cast<size_t>(h % clock->capacity)];
    slot.arrival_ns = static_cast<int64_t>(arrival_ns);
    slot.interval_ns = interval;
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

    uint64_t cursor = tail;
    while (cursor < head) {
        const FrameRecord& record = clock->ring[static_cast<size_t>(cursor % clock->capacity)];
        const double ms = static_cast<double>(static_cast<int64_t>(render_ns) - record.arrival_ns) / 1.0e6;
        const float interval_ms = record.interval_ns > 0
                                      ? static_cast<float>(record.interval_ns) / 1.0e6f
                                      : 0.0f;
        // 过滤时钟基准不一致 / 长时间后台产生的异常值，避免污染分位数。
        if (ms >= 0.0 && ms < 10000.0 && interval_ms >= 0.0f && interval_ms < 1000.0f) {
            const size_t at = clock->stat_cursor;
            clock->latency_window[at] = static_cast<float>(ms);
            clock->interval_window[at] = interval_ms;
            clock->stat_cursor = (at + 1) % kStatWindow;
            if (clock->stat_count < kStatWindow) ++clock->stat_count;
        }
        ++cursor;
    }
    const uint64_t consumed_this_frame = cursor - tail;
    clock->tail.store(cursor, std::memory_order_release);

    // 对整个滑动窗口排序取分位数；窗口固定 240 个 float，消费者线程零分配。
    clock->scratch.assign(clock->latency_window, clock->latency_window + clock->stat_count);
    clock->intervals.assign(clock->interval_window, clock->interval_window + clock->stat_count);
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

    // 播放流畅度：帧到达间隔的长尾抖动 = p95(interval) - p50(interval)。
    // 首帧的 interval 是 0，因此至少要有 3 个非零间隔才报数，否则显示 0 更诚实。
    size_t valid_intervals = 0;
    for (float v : clock->intervals) if (v > 0.0f) ++valid_intervals;
    float jitter = 0.0f;
    if (valid_intervals >= 3) {
        std::sort(clock->intervals.begin(), clock->intervals.end());
        // 跳过前面的 0（首帧），只对有效间隔求分位。
        const size_t offset = clock->intervals.size() - valid_intervals;
        const size_t n = valid_intervals;
        const size_t idx95 = std::min(n - 1, static_cast<size_t>(static_cast<double>(n) * 0.95));
        jitter = clock->intervals[offset + idx95] - clock->intervals[offset + n / 2];
    }

    const uint64_t dropped = clock->dropped.exchange(0, std::memory_order_relaxed);
    jfloat values[6] = {
        static_cast<jfloat>(consumed_this_frame),
        p50,
        p95,
        max_ms,
        static_cast<jfloat>(dropped),
        jitter,
    };
    env->SetFloatArrayRegion(out, 0, 6, values);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcana_gfx_FrameTiming_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}
