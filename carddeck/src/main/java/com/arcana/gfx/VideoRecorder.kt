package com.arcana.gfx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.arcana.gl.GlUtil
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **把 GPU 合成结果编码成 MP4**：MediaCodec `InputSurface` + EGL window surface。
 *
 * 关键做法：不新建 EGLContext，而是**复用 GL 线程当前的 context**，只把 draw surface
 * 临时切到编码器那张 window surface。这样 program / texture 天然同一上下文，
 * 也不需要跨 context 共享纹理；画完立刻切回屏幕 surface，GLSurfaceView 的
 * `eglSwapBuffers` 才不会把帧送错地方。全程零 CPU 回读、零 RGB→YUV 拷贝。
 *
 * 线程分工：
 * - GL 线程：切 surface、blit、`eglPresentationTimeANDROID`、swap、切回；
 * - `gfx-encoder` 线程：drain MediaCodec 输出、MediaMuxer 写 MP4；
 * - 任意线程：[start] / [stop] / [close]。
 *
 * 用法：`start()` → `compositor.attachFrameSink(recorder)` → `stop()` →
 * `compositor.postGl { recorder.releaseGl(); compositor.attachFrameSink(null) }`。
 * [releaseGl] 必须在 GL 线程调用，见 [GpuCompositor.postGl]。
 * 用 [GpuCompositor.attachFrameSink] 而不是赋值 [GpuCompositor.frameSink]，
 * 是为了让合成器切到「按需渲染（WHEN_DIRTY）」、由源帧到达驱动录制，并在源帧率高于
 * [targetFps] 时降采样，从根上避免把旧帧反复编码成定帧 / 插帧。
 */
class VideoRecorder(
    private val output: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 2_000_000,
    private val onError: ((String) -> Unit)? = null,
) : GpuFrameSink, AutoCloseable {

    /** 录制目标帧率：合成器据此稳速出帧并生成均匀 PTS。 */
    override val targetFps: Int = fps

    @Volatile private var accepting = false
    @Volatile private var written = 0
    private val finished = AtomicBoolean(false)
    private val drainThread = HandlerThread("gfx-encoder").apply { start() }
    private val drainHandler = Handler(drainThread.looper)
    private val info = MediaCodec.BufferInfo()

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false

    // 以下 EGL / GL 对象只在 GL 线程读写。
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var aPos = -1
    private var aUv = -1
    private var uTexture = -1
    private var uCrop = -1
    private var uSTMatrix = -1
    private var uFlipY = -1
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** 任意线程：配置编码器并启动输出 drain。返回 false 表示设备编码器不可用。 */
    fun start(): Boolean = try {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        accepting = true
        drainHandler.post { drain() }
        true
    } catch (e: Throwable) {
        onError?.invoke("编码器启动失败: ${e.message}")
        releaseCodec()
        false
    }

    /** GL 线程：把这一帧合成纹理画进编码器 surface。 */
    override fun onFrame(texture: Int, sourceW: Int, sourceH: Int, ptsNs: Long) {
        if (!accepting || finished.get()) return
        if (!ensureGl()) return

        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val context = EGL14.eglGetCurrentContext()
        if (context == EGL14.EGL_NO_CONTEXT || display == EGL14.EGL_NO_DISPLAY) return

        // 复用当前 context，只换 draw/read surface —— 纹理与 program 都还在同一个上下文里。
        if (!EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, context)) {
            onError?.invoke("eglMakeCurrent(encoder) 失败 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniform4f(uCrop, 0f, 0f, 1f, 1f)
        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, identity, 0)
        // MediaCodec 的 input surface 与屏幕 framebuffer 的 y 轴相反，翻一次才不会录出倒像。
        GLES20.glUniform1f(uFlipY, 1f)
        GlUtil.bindQuad(aPos, aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)

        EGLExt.eglPresentationTimeANDROID(display, encoderSurface, ptsNs)
        EGL14.eglSwapBuffers(display, encoderSurface)
        written++

        // 必须切回：GLSurfaceView 在本帧返回后还要对自己的 surface 做 swap。
        EGL14.eglMakeCurrent(display, prevDraw, prevRead, context)
    }

    override fun framesWritten(): Int = written

    /** 任意线程：停止送帧，给编码器发 EOS；MP4 由 drain 线程收尾。 */
    fun stop() {
        accepting = false
        try { codec?.signalEndOfInputStream() } catch (_: Throwable) { }
    }

    /**
     * **GL 线程**：释放编码器 EGL surface 与 program。
     * 用 [GpuCompositor.postGl] 投递，不要在 UI 线程直接调。
     */
    fun releaseGl() {
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val context = EGL14.eglGetCurrentContext()
        if (display != EGL14.EGL_NO_DISPLAY && encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
            // 切回屏幕 surface：否则 GLSurfaceView 本帧的 swap 会作用在已解绑的 context 上。
            EGL14.eglMakeCurrent(display, prevDraw, prevRead, context)
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    /** 任意线程：兜底释放。正常流程应 [stop] 后等 drain 自己收尾。 */
    override fun close() {
        accepting = false
        try { codec?.signalEndOfInputStream() } catch (_: Throwable) { }
        drainHandler.post {
            finish()
            drainThread.quitSafely()
        }
    }

    /* -------------------------------------------------- 内部 */

    private fun ensureGl(): Boolean {
        if (program != 0 && encoderSurface != EGL14.EGL_NO_SURFACE) return true
        return try {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val current = EGL14.eglGetCurrentContext()
            if (d == EGL14.EGL_NO_DISPLAY || current == EGL14.EGL_NO_CONTEXT) return false

            // window surface 需要一个 config；直接取当前 context 的那一个，保证兼容。
            val configId = IntArray(1)
            EGL14.eglQueryContext(d, current, EGL14.EGL_CONFIG_ID, configId, 0)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                d, intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE), 0,
                configs, 0, 1, count, 0,
            )
            val config = configs[0] ?: return false
            val surface = inputSurface ?: return false
            val s = EGL14.eglCreateWindowSurface(d, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (s == EGL14.EGL_NO_SURFACE) {
                onError?.invoke("eglCreateWindowSurface 失败 0x${Integer.toHexString(EGL14.eglGetError())}")
                return false
            }
            val p = GlUtil.buildProgram(GfxShaders.BLIT_VERTEX, GfxShaders.BLIT_2D_FRAGMENT)
            display = d
            encoderSurface = s
            program = p
            aPos = GlUtil.attrib(p, "aPos")
            aUv = GlUtil.attrib(p, "aUv")
            uTexture = GlUtil.location(p, "uTexture")
            uCrop = GlUtil.location(p, "uCrop")
            uSTMatrix = GlUtil.location(p, "uSTMatrix")
            uFlipY = GlUtil.location(p, "uFlipY")
            true
        } catch (e: Throwable) {
            onError?.invoke("录制 GL 初始化失败: ${e.message}")
            false
        }
    }

    /** 编码器线程：只做 dequeue / mux，不碰 GL。 */
    private fun drain() {
        if (finished.get()) return
        val c = codec ?: return
        try {
            when (val idx = c.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer
                    if (m != null && !muxerStarted) {
                        trackIndex = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (idx >= 0) {
                    val data = c.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && muxerStarted && trackIndex >= 0 && !isConfig && info.size > 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, data, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        finish()
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            onError?.invoke("编码输出失败: ${e.message}")
            finish()
            return
        }
        drainHandler.post { drain() }
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        releaseCodec()
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        codec = null
        if (muxerStarted) {
            try { muxer?.stop() } catch (_: Throwable) { }
        }
        try { muxer?.release() } catch (_: Throwable) { }
        muxer = null
        try { inputSurface?.release() } catch (_: Throwable) { }
        inputSurface = null
    }
}
