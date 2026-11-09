package com.arcana.carddeck

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demo 用的循环测试视频：在设备上用 `MediaCodec` 现场编码一段 H.264/MP4。
 *
 * 为什么不塞一个 mp4 进 assets：① 保持 sample 零外部素材、可离线复现；
 * ② 顺带把「编码 → 封装 → 解码 → SurfaceTexture」这条播放链路跑通，
 *    而不是拿一张假动态贴图冒充视频。
 *
 * 图案刻意做成高对比的移动竖条 + 跳动圆 + 色带：肉眼能立刻判断
 * 画面是否在动、有没有上下镜像、四路裁切是不是取到了不同区域。
 *
 * 三个运动分量的步长都按 [FRAMES] 严格闭合，保证 90 帧 → 第 0 帧的接缝
 * 与任意相邻两帧一样平滑；否则每 3 秒 loop 一次就整幅跳一下，
 * 看起来像"闪回前几帧"：
 * - 条纹完整周期 54px（27 亮 + 27 暗），每帧移 3px → 90 帧共 270 = 5×54；
 * - 扫描线周期 24 行，每帧移 4 行 → 90 帧共 360 = 15×24；
 * - 圆心 sin(frame / 90 × 2π)，首尾相位连续。
 */
object TestVideo {

    private const val W = 320
    private const val H = 240          // 16 对齐，避免部分编码器静默失败
    private const val FPS = 30
    private const val FRAMES = 90      // 3 秒循环

    fun ensure(context: Context): File {
        val out = File(context.cacheDir, "gfx_loop.mp4")
        if (out.exists() && out.length() > 8_000) return out
        if (out.exists()) out.delete()
        encode(out)
        return out
    }

    private fun encode(out: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 900_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var frameIndex = 0
        var inputDone = false
        var outputDone = false
        var guard = 0
        val frameBytes = W * H * 3 / 2

        try {
            while (!outputDone && guard++ < 900) {
                if (!inputDone) {
                    val inputIdx = codec.dequeueInputBuffer(10_000)
                    if (inputIdx >= 0) {
                        val input = requireNotNull(codec.getInputBuffer(inputIdx))
                        input.clear()
                        fillNv12(input, frameIndex)
                        val ptsUs = frameIndex * 1_000_000L / FPS
                        val flags = if (frameIndex == FRAMES - 1) {
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        } else 0
                        codec.queueInputBuffer(inputIdx, 0, frameBytes, ptsUs, flags)
                        frameIndex++
                        if (frameIndex >= FRAMES) inputDone = true
                    }
                }

                val outputIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIdx >= 0 -> {
                        val data = requireNotNull(codec.getOutputBuffer(outputIdx))
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && muxerStarted && info.size > 0) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                        }
                        codec.releaseOutputBuffer(outputIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) { }
            codec.release()
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Throwable) { }
            }
            muxer.release()
        }
        check(out.exists() && out.length() > 8_000) { "H.264 编码未产出有效文件" }
    }

    /** NV12：Y 平面 W×H，随后交错的 UV 平面 W×(H/2)。 */
    private fun fillNv12(buffer: ByteBuffer, frame: Int) {
        val yPlane = ByteArray(W * H)
        val uvPlane = ByteArray(W * H / 2)
        val phase = frame.toFloat() / FRAMES * 2f * PI.toFloat()
        val cx = 0.5f + 0.27f * sin(phase)
        val cy = 0.5f

        for (y in 0 until H) {
            val v = y.toFloat() / H
            for (x in 0 until W) {
                val u = x.toFloat() / W
                val bar = ((x + frame * 3) / 27) % 2
                val dx = u - cx
                val dy = v - cy
                val circle = if (sqrt(dx * dx + dy * dy) < 0.11f) 1f else 0f
                val scan = if ((y + frame * 4) % 24 < 3) 0.18f else 0f
                val r = (0.20f + 0.55f * v + 0.20f * bar + circle * 0.55f + scan).coerceIn(0f, 1f)
                val g = (0.15f + 0.50f * u + circle * 0.25f).coerceIn(0f, 1f)
                val b = (0.72f - 0.38f * v + bar * 0.12f + scan).coerceIn(0f, 1f)
                // BT.601 limited range，避免部分解码器把 full-range 当视频级显示成灰片
                val luma = 16f + (0.299f * r + 0.587f * g + 0.114f * b) * 219f
                yPlane[y * W + x] = luma.toInt().toByte()
            }
        }
        var p = 0
        for (y in 0 until H step 2) {
            for (x in 0 until W step 2) {
                val u = x.toFloat() / W
                val uVal = 128f + (u - 0.5f) * 110f + sin(phase) * 12f
                val vVal = 128f + (y.toFloat() / H - 0.5f) * 90f
                uvPlane[p++] = uVal.toInt().toByte()
                uvPlane[p++] = vVal.toInt().toByte()
            }
        }
        buffer.put(yPlane)
        buffer.put(uvPlane)
    }
}
