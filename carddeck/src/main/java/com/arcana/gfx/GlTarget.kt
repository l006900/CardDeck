package com.arcana.gfx

import android.opengl.GLES20

/**
 * 离屏渲染目标：一张 RGBA 纹理 + 一个 FBO。
 *
 * 所有方法**只能在持有 EGL 上下文的 GL 线程调用**。尺寸变化时重建（不保留旧内容），
 * 这样旋转 / 分屏切换不会留下上一帧的残影，也不会泄漏 GL 对象。
 *
 * 纹理字节数是显存预算的主要来源：[bytes] 参与 [GpuStats.textureBytes] 统计。
 */
internal class GlTarget {

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var framebuffer: Int = 0
        private set
    var texture: Int = 0
        private set

    fun ensure(w: Int, h: Int) {
        val tw = w.coerceAtLeast(1)
        val th = h.coerceAtLeast(1)
        if (tw == width && th == height && framebuffer != 0 && texture != 0) return
        release()
        width = tw
        height = th

        val textures = IntArray(1)
        val buffers = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glGenFramebuffers(1, buffers, 0)
        texture = textures[0]
        framebuffer = buffers[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "FBO incomplete: 0x${Integer.toHexString(status)} (${width}x${height})"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** 绑定到这个目标并把 viewport 设成整张纹理。 */
    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun bytes(): Long = width.toLong() * height * 4L

    /**
     * EGL 上下文重建后调用（**GL 线程或任意线程均可，不碰 GLES**）：只把 id 归零。
     * 旧对象随旧上下文由驱动回收；归零后下一次 [ensure] 会重新创建。
     */
    fun forgetGpu() {
        framebuffer = 0
        texture = 0
        width = 0
        height = 0
    }

    fun release() {
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
        width = 0
        height = 0
    }
}

/**
 * 双缓冲：后处理链每跑一个 pass 就「从 read 采样 → 写进 write → 交换」。
 * 两张纹理全程复用，帧内不建 GL 对象、不分配显存。
 */
internal class TargetPair {
    val a = GlTarget()
    val b = GlTarget()

    var read: GlTarget = a
        private set
    var write: GlTarget = b
        private set

    fun ensure(w: Int, h: Int) {
        a.ensure(w, h)
        b.ensure(w, h)
    }

    fun swap() {
        val t = read
        read = write
        write = t
    }

    /** 保证下一次场景合成写进的是 [write]，链结束后 [read] 指向最终画面。 */
    fun reset() {
        read = a
        write = b
    }

    fun bytes(): Long = a.bytes() + b.bytes()

    /** 上下文重建：丢弃失效 id，下次 [ensure] 重新创建（不碰 GLES）。 */
    fun forgetGpu() {
        a.forgetGpu()
        b.forgetGpu()
        reset()
    }

    fun release() {
        a.release()
        b.release()
        reset()
    }
}
