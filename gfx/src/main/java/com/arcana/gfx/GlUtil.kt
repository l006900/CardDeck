package com.arcana.gfx

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * GL 公共工具：program 编译 / 纹理上传 / quad 与卡牌网格 buffer / 颜色换算。
 *
 * 合成管线自身（[GpuCompositor] / [VideoRecorder] / [TextureBank]）用的就是这一套；
 * 同时对消费方公开 —— 自己往管线里塞一路 [FrameSource] 时，画牌/画背景要的
 * shader 编译、单位 quad、细分网格、Bitmap 上传都在这里，不必再抄一遍。
 *
 * 唯一需要留意的共享状态是那几个静态 buffer（[QUAD_POS] / [MESH_POS] 等）：
 * 它们是只读的，多个引擎复用没问题；但 `position()` 会被 [bindQuad] / [bindMesh]
 * 重置，所以**别在另一个线程边读边绑**。所有方法都要求当前线程已绑定 GL context。
 *
 * 纯 framework API，无三方依赖、无 Compose。
 */
object GlUtil {

    /** 单位 quad（TRIANGLE_STRIP）：pos 与 uv 分开两个 buffer。 */
    val QUAD_POS: FloatBuffer = wrap(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    val QUAD_UV: FloatBuffer = wrap(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    /** 绑定 pos/uv 两个属性（client-side 顶点数组，无字节偏移）。 */
    fun bindQuad(posHandle: Int, uvHandle: Int) {
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, QUAD_POS)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 8, QUAD_UV)
    }

    /* ------------------------------------------------------ 卡牌细分网格（物理弯曲用） */

    /**
     * 卡牌网格：4 顶点的 quad 永远是一块平板，翻起来就是「硬纸板转圈」。
     * 把它细分成 [CARD_GRID] × [CARD_GRID] 格，顶点着色器才能按 x 位置给 Z 施加
     * sin/cos 偏移，做出「中间凸起、边缘卷曲」的纸张形变（用户要的第 1 个灵魂）。
     * 16×16 = 289 顶点 / 1536 索引，UNSIGNED_SHORT 索引 ES 2.0 全设备支持。
     */
    const val CARD_GRID = 16

    private val meshPair: Pair<FloatArray, FloatArray> = buildCardMesh(CARD_GRID)
    val MESH_POS: FloatBuffer = wrap(meshPair.first)
    val MESH_UV: FloatBuffer = wrap(meshPair.second)
    val MESH_INDEX: ShortBuffer = buildCardIndex(CARD_GRID)
    val MESH_INDEX_COUNT: Int = CARD_GRID * CARD_GRID * 6

    /** 绑定卡牌网格（画牌用它，画桌布/阴影仍用 [bindQuad]）。 */
    fun bindMesh(posHandle: Int, uvHandle: Int) {
        MESH_POS.position(0)
        MESH_UV.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, MESH_POS)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 8, MESH_UV)
    }

    fun drawMesh() {
        MESH_INDEX.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, MESH_INDEX_COUNT, GLES20.GL_UNSIGNED_SHORT, MESH_INDEX)
    }

    private fun buildCardMesh(n: Int): Pair<FloatArray, FloatArray> {
        val count = (n + 1) * (n + 1)
        val pos = FloatArray(count * 2)
        val uv = FloatArray(count * 2)
        var k = 0
        for (j in 0..n) {
            val v = j.toFloat() / n
            for (i in 0..n) {
                val u = i.toFloat() / n
                pos[k] = u * 2f - 1f
                pos[k + 1] = v * 2f - 1f
                uv[k] = u
                uv[k + 1] = v
                k += 2
            }
        }
        return pos to uv
    }

    /** 爆发粒子池：每颗粒子 (dirX, dirY, speed, phase)，桌面引擎与抽卡引擎共用。 */
    fun burstSeeds(seed: Long, count: Int): FloatBuffer {
        val rnd = java.util.Random(seed)
        val data = FloatArray(count * 4)
        for (i in 0 until count) {
            val ang = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val r = 0.35f + rnd.nextFloat() * 0.65f
            data[i * 4] = kotlin.math.cos(ang) * r
            data[i * 4 + 1] = kotlin.math.sin(ang) * r
            data[i * 4 + 2] = 0.18f + rnd.nextFloat() * 0.42f
            data[i * 4 + 3] = rnd.nextFloat()
        }
        return wrap(data)
    }

    private fun buildCardIndex(n: Int): ShortBuffer {
        val idx = ShortArray(n * n * 6)
        var p = 0
        for (j in 0 until n) {
            for (i in 0 until n) {
                val a = j * (n + 1) + i
                val b = a + 1
                val c = a + (n + 1)
                val d = c + 1
                idx[p++] = a.toShort(); idx[p++] = b.toShort(); idx[p++] = c.toShort()
                idx[p++] = b.toShort(); idx[p++] = d.toShort(); idx[p++] = c.toShort()
            }
        }
        return ByteBuffer.allocateDirect(idx.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(idx); position(0) }
    }

    fun location(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    fun attrib(program: Int, name: String): Int = GLES20.glGetAttribLocation(program, name)

    fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src.trimIndent())
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    /** 上传 Bitmap 为 2D 纹理，超 [maxWidth] 先下采样（牌面 512 宽足够，省显存）。 */
    fun uploadBitmap(bmp: Bitmap, maxWidth: Int): Int {
        val scaled = if (bmp.width > maxWidth) {
            Bitmap.createScaledBitmap(
                bmp,
                maxWidth,
                (bmp.height * (maxWidth.toFloat() / bmp.width)).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bmp
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        if (tex[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaled, 0)
        return tex[0]
    }

    fun argbToRgb(argb: Int) = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )

    private fun wrap(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}
