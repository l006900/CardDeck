package com.arcana.gfx

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.arcana.carddeck.TextureBank
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 一路「画面源」的 GL 侧契约。
 *
 * **线程分工**（这是低延迟的关键）：
 * - 生产者（解码线程 / 摄像头回调 / 任意 HandlerThread）只投递帧到达事件，不碰 GLES；
 * - GL 线程独占 [onGlCreated] / [update] / [textureId] / [transform] 等所有 GLES 调用；
 * - UI 线程只改布局与参数。
 *
 * 外部解码器：FFmpeg / MediaCodec / ExoPlayer / Camera2 只要能把帧写进 [Surface]，
 * 就能接进这套合成管线——库不绑定任何播放器实现。
 */
interface FrameSource {

    /** true = `GL_TEXTURE_EXTERNAL_OES`（SurfaceTexture）；false = 普通 `GL_TEXTURE_2D`。 */
    val external: Boolean

    val textureTarget: Int

    /**
     * **生产者侧**帧到达钩子（解码线程 / 摄像头回调线程）。
     * [GpuCompositor.register] 会自动把它接到帧时钟与按需渲染，调用方不用手动连。
     * 默认空实现：没有外部生产者的源（静态贴图 / CPU 生成纹理）忽略即可。
     */
    var frameSink: ((Long) -> Unit)?
        get() = null
        set(_) = Unit

    /** GL 线程：创建纹理 / SurfaceTexture。可重复调用（幂等）。 */
    fun onGlCreated()

    /**
     * GL 上下文即将失效（**可在任意线程调用，禁止碰 GLES**）：丢弃 id 引用。
     * 驱动会随上下文回收纹理，这里只负责让下次 [onGlCreated] 重新建。
     */
    fun onGlLost()

    /**
     * GL 线程：**只查询**是否已有新帧待消费，不碰 GLES、不消费帧。
     *
     * 合成器靠它把「要不要画」交给帧到达决定，而不是用固定 fps 去猜时刻；
     * 若查询发生在被节流跳过的回调里，帧仍留在 BufferQueue，下次真正绘制时再取最新。
     * 默认 false：没有外部生产者的源（静态贴图 / CPU 动态纹理）不参与帧驱动。
     */
    fun hasPendingFrame(): Boolean = false

    /**
     * 这一路源**自己会产新内容**（CPU 每帧生成的纹理 / 程序化图案），不依赖外部推帧。
     *
     * 合成器据此判断「内容代次是否会变」：外部 OES 源看 [hasPendingFrame]，
     * 自产源看这个标志。调用方不用再手动设 [GpuCompositor.animating]——
     * 早先靠调用方记得设 animating，漏设一次画面就永久冻在最后一帧（「卡固定帧」）。
     */
    val selfAnimating: Boolean get() = false

    /**
     * GL 线程：把最新一帧锁进纹理。
     * @return 0 = 没有新帧；否则返回该帧的到达时刻（`System.nanoTime()` 基准），供帧延迟统计。
     */
    fun update(): Long

    fun textureId(): Int

    /** GL 线程：把 4×4 变换矩阵写进 [out] 并返回（2D 源是单位矩阵）。 */
    fun transform(out: FloatArray): FloatArray

    /**
     * 源的内禀像素尺寸与「[transform] 里施加的旋转角」，供合成器做**等比 cover 裁剪**，
     * 避免把 4:3 视频塞进竖列时被拉变形。
     *
     * 默认全 0 = 未知尺寸，**跳过适配**（保持旧行为，不会把不该裁的也在裁）；
     * 有确定尺寸的源（摄像头 / 已知 buffer 的视频）应覆写。
     */
    val contentWidth: Int get() = 0
    val contentHeight: Int get() = 0
    /** 与 [transform] 一致的旋转角（度），用于判断 90/270 时宽高是否对调。 */
    val contentRotationDeg: Int get() = 0

    /** 这一路源占的显存字节数，用于预算统计。 */
    fun byteCount(): Long
}

/**
 * 外部视频纹理：`SurfaceTexture` + `GL_TEXTURE_EXTERNAL_OES`。
 *
 * 这是 Android 上视频渲染的标准入口：解码器写进 [surface]，GPU 直接采样，
 * 中间不做 CPU 拷贝、不产生 YUV→RGB 的 Java 层转换（由驱动 / shader 完成）。
 *
 * [frameSink] 是**生产者侧**钩子：帧到达时在回调线程触发，用来把时间戳推进
 * [FrameTiming] 的无锁队列；GL 线程在 [update] 里做消费者侧 drain。
 *
 * @param onSurfaceReady GL 线程回调，拿到 Surface 后请切回自己的线程再交给播放器。
 * @param bufferWidth / bufferHeight 非零时给 SurfaceTexture 设默认缓冲尺寸。
 *   Camera2 这类**主动推流**的生产者必须设，否则预览会按 1×1 的缺省尺寸出图。
 */
class ExternalTextureSource(
    name: String = "video",
    private val onSurfaceReady: ((Surface) -> Unit)? = null,
    private val bufferWidth: Int = 0,
    private val bufferHeight: Int = 0,
) : FrameSource {

    override val external: Boolean = true
    override val textureTarget: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

    /** 生产者侧回调（解码线程）；典型实现是把它接到 FrameTiming.push。 */
    @Volatile
    override var frameSink: ((Long) -> Unit)? = null

    @Volatile
    var surface: Surface? = null
        private set

    private val callbackThread = HandlerThread("gfx-frame-$name").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val matrix = FloatArray(16)
    private val matrixLock = Any()

    @Volatile private var pendingCount = 0L
    @Volatile private var lastArrivalNs = 0L
    private var consumedCount = 0L          // 仅 GL 线程访问
    private var surfaceTexture: SurfaceTexture? = null
    private var texId = 0
    private var created = false

    init {
        Matrix.setIdentityM(matrix, 0)
    }

    override fun onGlCreated() {
        if (created) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val st = SurfaceTexture(texId)
        if (bufferWidth > 0 && bufferHeight > 0) st.setDefaultBufferSize(bufferWidth, bufferHeight)
        st.setOnFrameAvailableListener({ _ ->
            val now = System.nanoTime()
            pendingCount++
            lastArrivalNs = now
            // 生产者：解码线程，无锁入队，绝不碰 GLES。
            frameSink?.invoke(now)
        }, callbackHandler)
        surfaceTexture = st
        surface?.release()
        val s = Surface(st)
        surface = s
        created = true
        onSurfaceReady?.invoke(s)
    }

    override fun hasPendingFrame(): Boolean = pendingCount != consumedCount

    override fun update(): Long {
        val st = surfaceTexture ?: return 0L
        val pending = pendingCount
        if (pending == consumedCount || texId == 0) return 0L
        // 一次最多消费 4 帧：长时间后台回来时不至于卡在 while 里，多余帧下次再追。
        val delta = min(pending - consumedCount, 4L).toInt()
        try {
            repeat(delta) { st.updateTexImage() }
            synchronized(matrixLock) { st.getTransformMatrix(matrix) }
        } catch (e: IllegalStateException) {
            // 生产端把缓冲「放弃」了（切换源、Surface 重建、播放器 release 都会走到这里），
            // SurfaceTexture 会抛 `Unable to update texture contents`。
            // 这里吞掉并丢弃这批计数：让这一帧显示上一张纹理，绝不能把 GL 线程崩掉。
            consumedCount = pending
            return 0L
        }
        consumedCount = pending
        return lastArrivalNs
    }

    /** 累计到达的帧数（生产者侧），用来区分「采集没推帧」和「推了但采样成黑」。 */
    fun framesArrived(): Long = pendingCount

    override fun textureId(): Int = texId

    override fun transform(out: FloatArray): FloatArray {
        synchronized(matrixLock) { System.arraycopy(matrix, 0, out, 0, 16) }
        return out
    }

    /** 已知 buffer 尺寸时（Camera2 必设）用于 cover 裁剪；未知则 0，跳过适配。 */
    override val contentWidth: Int get() = bufferWidth
    override val contentHeight: Int get() = bufferHeight
    override val contentRotationDeg: Int get() = 0

    override fun byteCount(): Long = 0L   // 由 BufferQueue 持有，不计入库自己的显存预算

    override fun onGlLost() {
        created = false
        texId = 0
        surfaceTexture = null
        consumedCount = 0L
        pendingCount = 0L
        // Surface 由调用方在 onSurfaceReady 里换新的；这里只做引用清理，不碰 GLES。
    }

    /** 页面销毁：停回调线程并释放 Surface（非 GLES 调用，任意线程安全）。 */
    fun close() {
        surface?.release()
        surface = null
        callbackThread.quitSafely()
    }
}

/** 把 [TextureBank] 里的一张静态贴图当作一路源（图片 / 台标 / 遮罩层）。 */
class Texture2DSource(
    private val bank: TextureBank,
    private val key: String,
    private val width: Int,
    private val height: Int,
) : FrameSource {
    override val external: Boolean = false
    override val textureTarget: Int = GLES20.GL_TEXTURE_2D
    override fun onGlCreated() = Unit
    override fun onGlLost() = Unit
    override fun update(): Long = 0L
    override fun textureId(): Int = bank.texture(key)
    override fun transform(out: FloatArray): FloatArray {
        Matrix.setIdentityM(out, 0)
        return out
    }
    override fun byteCount(): Long = width.toLong() * height * 4L
}

/**
 * CPU 生成的动态 2D 纹理源：每帧用 `glTexSubImage2D` 更新一张 RGBA 纹理。
 *
 * 存在的意义有两个：① 没有视频源时也能单独验证合成链与 shader 链；
 * ② 量化「纹理上传带宽」这条开销（真视频走 OES 是零拷贝，这一路是拷贝路径，可对比）。
 */
class DynamicTexture2DSource(
    private val width: Int,
    private val height: Int,
    private val fill: (ByteArray, Int) -> Unit,
) : FrameSource {
    override val external: Boolean = false
    override val textureTarget: Int = GLES20.GL_TEXTURE_2D

    private val pixels = ByteArray(width * height * 4)
    private val direct = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
    private var texId = 0
    private var frameIndex = 0
    private var created = false

    /** 关掉后本路不再产新帧：才能真正停在一帧上（省电验证用）。 */
    @Volatile
    var active: Boolean = true

    /** 只要还在产帧，内容代次每帧都变，合成器不需要外部再设 animating。 */
    override val selfAnimating: Boolean get() = active

    override fun onGlCreated() {
        if (created) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        created = true
    }

    override fun update(): Long {
        if (!active || !created || texId == 0) return 0L
        fill(pixels, frameIndex++)
        direct.clear()
        direct.put(pixels)
        direct.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, direct,
        )
        return System.nanoTime()
    }

    override fun textureId(): Int = texId
    override fun transform(out: FloatArray): FloatArray {
        Matrix.setIdentityM(out, 0)
        return out
    }
    override val contentWidth: Int get() = width
    override val contentHeight: Int get() = height
    override val contentRotationDeg: Int get() = 0
    override fun byteCount(): Long = width.toLong() * height * 4L
    override fun onGlLost() {
        created = false
        texId = 0
    }
}
