package com.arcana.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.arcana.carddeck.Node
import com.arcana.carddeck.Pose
import com.arcana.carddeck.Scene
import com.arcana.carddeck.TextureBank
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.sin

/**
 * 通用 2.5D 场景渲染器 —— **不认识塔罗、不认识牌、不认识业务**。
 *
 * 输入只有 [Scene] 的节点快照（[Pose] + 贴图键 + [Effects] 强度），输出是若干
 * GPU pass：渐变底 → 背景星辉 → 接触阴影 → 体积光晕 → 弯曲图元 → 揭示爆发。
 * 所有特效 shader 都是可组合的，任何贴图都能套（卡牌只是其中一种用法）。
 *
 * 性能约定：
 * - 每帧主线程零参与；[Scene.motion] 在 GL 线程求值，位姿不经过 Compose 重组。
 * - 所有顶点缓冲、矩阵、快照列表预分配复用，帧内不建大对象。
 * - 图元按 z 序逐个 draw call（ES 2 无 instancing），但每次只有几个 uniform，
 *   几百个元素的成本主要在填充率而不是 CPU。
 *
 * @param scene 场景状态（UI 线程写、GL 线程读快照）。
 * @param textures 贴图注册表（上传延迟到 GL 线程执行）。
 */
class SceneRenderer(
    private val scene: Scene,
    private val textures: TextureBank,
) : GLSurfaceView.Renderer {

    /** GL 线程实测帧率回调（约每 0.5s 一次，已 post 回主线程）。 */
    var onFps: ((Float) -> Unit)? = null

    /** 自带渐变底的两种颜色（ARGB），由 [SpriteStage] 传入。 */
    var backdropInner: Int = 0xFF1B2140.toInt()
    var backdropOuter: Int = 0xFF070A14.toInt()

    private val proj = FloatArray(16)
    private val projNdc = FloatArray(16)
    private val identity = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private var viewW = 1
    private var viewH = 1

    private val gold = floatArrayOf(0.98f, 0.82f, 0.46f)
    private val mainHandler = Handler(Looper.getMainLooper())

    /* ---------------- program / handle ---------------- */

    private var pBackdrop = 0
    private var pShadow = 0
    private var pHalo = 0
    private var pSprite = 0
    private var pStar = 0
    private var pBurst = 0
    private var attribs = intArrayOf()
    private var whiteTexture = 0

    private val h = Handles()

    private class Handles {
        var backdropMvp = 0; var backdropUv = 0; var backdropPos = 0
        var backdropResolution = 0; var backdropInner = 0; var backdropOuter = 0

        var shadowMvp = 0; var shadowStrength = 0; var shadowPos = 0; var shadowUv = 0
        var haloMvp = 0; var haloTime = 0; var haloStrength = 0; var haloColor = 0
        var haloPos = 0; var haloUv = 0

        var spriteMvp = 0; var spriteAngle = 0; var spriteBend = 0
        var spriteFace = 0; var spriteBack = 0; var spriteTime = 0
        var spriteGlow = 0; var spriteReversed = 0; var spriteFoil = 0; var spriteAlpha = 0
        var spritePos = 0; var spriteUv = 0

        var starMvp = 0; var starResolution = 0; var starTime = 0; var starSeed = 0
        var burstMvp = 0; var burstCenter = 0; var burstZ = 0; var burstScale = 0
        var burstTime = 0; var burstBirth = 0; var burstLife = 0; var burstColor = 0
        var burstDir = 0; var burstSpeed = 0; var burstPhase = 0
    }

    /* ---------------- 预分配缓冲 ---------------- */

    private val poseBuffer = ArrayList<Pose>(512)
    private val starSeeds: FloatBuffer = makeStarSeeds(STAR_COUNT)
    private val burstDirs: FloatBuffer
    private val burstSpeeds: FloatBuffer
    private val burstPhases: FloatBuffer

    private var activeBurst: BurstState? = null

    private class BurstState(
        val cx: Float, val cy: Float, val birth: Float, val color: Int, val count: Int,
    )

    init {
        val rnd = java.util.Random(20261109L)
        val dirs = FloatArray(BURST_POOL * 2)
        val speeds = FloatArray(BURST_POOL)
        val phases = FloatArray(BURST_POOL)
        for (i in 0 until BURST_POOL) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = 0.35f + rnd.nextFloat() * 0.65f
            dirs[i * 2] = kotlin.math.cos(a) * r
            dirs[i * 2 + 1] = kotlin.math.sin(a) * r
            speeds[i] = 0.18f + rnd.nextFloat() * 0.42f
            phases[i] = rnd.nextFloat()
        }
        burstDirs = wrap(dirs)
        burstSpeeds = wrap(speeds)
        burstPhases = wrap(phases)
        Matrix.setIdentityM(identity, 0)
    }

    private val startNanos = System.nanoTime()
    private var frameCount = 0
    private var fpsWindowStart = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        pBackdrop = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.TABLE_FRAGMENT)
        pShadow = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.SHADOW_FRAGMENT)
        pHalo = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.HALO_FRAGMENT)
        pSprite = GlUtil.buildProgram(GlShaders.CARD_VERTEX, GlShaders.CARD_FRAGMENT)
        pStar = GlUtil.buildProgram(GlShaders.PARTICLE_VERTEX, GlShaders.PARTICLE_FRAGMENT)
        pBurst = GlUtil.buildProgram(GlShaders.BURST_VERTEX, GlShaders.BURST_FRAGMENT)

        h.backdropMvp = GlUtil.location(pBackdrop, "uMvp")
        h.backdropPos = GlUtil.attrib(pBackdrop, "aPos")
        h.backdropUv = GlUtil.attrib(pBackdrop, "aUv")
        h.backdropResolution = GlUtil.location(pBackdrop, "uResolution")
        h.backdropInner = GlUtil.location(pBackdrop, "uInner")
        h.backdropOuter = GlUtil.location(pBackdrop, "uOuter")

        h.shadowMvp = GlUtil.location(pShadow, "uMvp")
        h.shadowStrength = GlUtil.location(pShadow, "uStrength")
        h.shadowPos = GlUtil.attrib(pShadow, "aPos")
        h.shadowUv = GlUtil.attrib(pShadow, "aUv")

        h.haloMvp = GlUtil.location(pHalo, "uMvp")
        h.haloTime = GlUtil.location(pHalo, "uTime")
        h.haloStrength = GlUtil.location(pHalo, "uStrength")
        h.haloColor = GlUtil.location(pHalo, "uColor")
        h.haloPos = GlUtil.attrib(pHalo, "aPos")
        h.haloUv = GlUtil.attrib(pHalo, "aUv")

        h.spriteMvp = GlUtil.location(pSprite, "uMvp")
        h.spriteAngle = GlUtil.location(pSprite, "uAngle")
        h.spriteBend = GlUtil.location(pSprite, "uBend")
        h.spriteFace = GlUtil.location(pSprite, "uFace")
        h.spriteBack = GlUtil.location(pSprite, "uBack")
        h.spriteTime = GlUtil.location(pSprite, "uTime")
        h.spriteGlow = GlUtil.location(pSprite, "uGlow")
        h.spriteReversed = GlUtil.location(pSprite, "uReversed")
        h.spriteFoil = GlUtil.location(pSprite, "uFoil")
        h.spriteAlpha = GlUtil.location(pSprite, "uAlpha")
        h.spritePos = GlUtil.attrib(pSprite, "aPos")
        h.spriteUv = GlUtil.attrib(pSprite, "aUv")

        h.starMvp = GlUtil.location(pStar, "uMvp")
        h.starResolution = GlUtil.location(pStar, "uResolution")
        h.starTime = GlUtil.location(pStar, "uTime")
        h.starSeed = GlUtil.attrib(pStar, "aSeed")

        h.burstMvp = GlUtil.location(pBurst, "uMvp")
        h.burstCenter = GlUtil.location(pBurst, "uCenter")
        h.burstZ = GlUtil.location(pBurst, "uZ")
        h.burstScale = GlUtil.location(pBurst, "uScale")
        h.burstTime = GlUtil.location(pBurst, "uTime")
        h.burstBirth = GlUtil.location(pBurst, "uBirth")
        h.burstLife = GlUtil.location(pBurst, "uLife")
        h.burstColor = GlUtil.location(pBurst, "uColor")
        h.burstDir = GlUtil.attrib(pBurst, "aDir")
        h.burstSpeed = GlUtil.attrib(pBurst, "aSpeed")
        h.burstPhase = GlUtil.attrib(pBurst, "aPhase")

        attribs = intArrayOf(
            h.backdropPos, h.backdropUv, h.shadowPos, h.shadowUv,
            h.haloPos, h.haloUv, h.spritePos, h.spriteUv,
            h.starSeed, h.burstDir, h.burstSpeed, h.burstPhase,
        ).filter { it >= 0 }.distinct().toIntArray()

        whiteTexture = makeWhiteTexture()
        // 若这是重建的 surface：旧纹理 id 已随旧上下文失效，重新排队上传。
        textures.forgetGpu()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w.coerceAtLeast(1)
        viewH = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewW, viewH)
        // 屏幕坐标 y 向下：top=0 → NDC +1（屏幕顶），bottom=h → NDC -1（屏幕底）。
        Matrix.orthoM(proj, 0, 0f, viewW.toFloat(), viewH.toFloat(), 0f, -1f, 1f)
        Matrix.orthoM(projNdc, 0, -1f, 1f, -1f, 1f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        textures.flushUploads()
        scene.consumeDirty()
        scene.consumeBurst()?.let {
            activeBurst = BurstState(
                cx = it.nx * 2f - 1f,
                cy = 1f - it.ny * 2f,
                birth = t,
                color = it.color,
                count = it.count.coerceAtMost(BURST_POOL),
            )
        }

        // 清成全透明：surface 是 TRANSLUCENT + setZOrderOnTop，透明像素会露出下面的 Compose UI。
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        clearAttribs()

        val nodes = scene.snapshot()
        val motion = scene.motion
        poseBuffer.clear()
        if (motion == null) {
            for (n in nodes) poseBuffer.add(n.pose)
        } else {
            for (i in nodes.indices) poseBuffer.add(motion.poseAt(t, i, nodes[i].pose))
        }

        if (scene.background) drawBackdrop()
        if (scene.starfield) drawStars(t)
        drawShadows(nodes)
        drawHalos(nodes, t)
        drawSprites(nodes, t)
        drawBurst(t)

        // GL 线程自己的帧率统计，post 回主线程展示（不阻塞渲染）。
        frameCount++
        if (t - fpsWindowStart >= 0.5f) {
            val fps = frameCount / (t - fpsWindowStart)
            frameCount = 0
            fpsWindowStart = t
            val cb = onFps
            if (cb != null) mainHandler.post { cb(fps) }
        }
    }

    /**
     * View 销毁时调用（**主线程**，因此这里绝不碰 GLES）：只丢弃引用并让贴图重新排队。
     * GL 纹理由驱动随上下文回收；下次 surface 创建后会自动重传，不会泄漏也不会崩。
     */
    fun release() {
        whiteTexture = 0
        textures.forgetGpu()
    }

    /* ---------------- pass ---------------- */

    private fun drawBackdrop() {
        GLES20.glUseProgram(pBackdrop)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(
            h.backdropMvp, 1, false,
            matrixAt(viewW / 2f, viewH / 2f, 0f, viewW / 2f, viewH / 2f), 0,
        )
        GLES20.glUniform2f(h.backdropResolution, viewW.toFloat(), viewH.toFloat())
        val inner = GlUtil.argbToRgb(backdropInner)
        val outer = GlUtil.argbToRgb(backdropOuter)
        GLES20.glUniform3f(h.backdropInner, inner[0], inner[1], inner[2])
        GLES20.glUniform3f(h.backdropOuter, outer[0], outer[1], outer[2])
        GlUtil.bindQuad(h.backdropPos, h.backdropUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawStars(t: Float) {
        GLES20.glUseProgram(pStar)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUniformMatrix4fv(h.starMvp, 1, false, identity, 0)
        GLES20.glUniform2f(h.starResolution, viewW.toFloat(), viewH.toFloat())
        GLES20.glUniform1f(h.starTime, t)
        starSeeds.position(0)
        GLES20.glEnableVertexAttribArray(h.starSeed)
        GLES20.glVertexAttribPointer(h.starSeed, 3, GLES20.GL_FLOAT, false, 12, starSeeds)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STAR_COUNT)
    }

    private fun drawShadows(nodes: List<Node>) {
        GLES20.glUseProgram(pShadow)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GlUtil.bindQuad(h.shadowPos, h.shadowUv)
        for (i in nodes.indices) {
            val e = nodes[i].effects
            if (e.shadow <= 0f) continue
            val p = poseBuffer[i]
            val halfW = p.width * viewW * p.scale * 0.5f
            val halfH = p.height * viewH * p.scale * 0.5f
            val strength = (0.40f * e.shadow * (1f - p.lift * 2.2f)).coerceIn(0.04f, 0.55f)
            GLES20.glUniform1f(h.shadowStrength, strength)
            GLES20.glUniformMatrix4fv(
                h.shadowMvp, 1, false,
                matrixAt(
                    p.x * viewW, p.y * viewH + halfH * 0.86f, p.rotation,
                    halfW * (1.18f + p.lift), halfH * (0.46f + p.lift * 0.6f),
                ), 0,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun drawHalos(nodes: List<Node>, t: Float) {
        GLES20.glUseProgram(pHalo)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUniform1f(h.haloTime, t)
        GLES20.glUniform3f(h.haloColor, gold[0], gold[1], gold[2])
        GlUtil.bindQuad(h.haloPos, h.haloUv)
        for (i in nodes.indices) {
            val e = nodes[i].effects
            if (e.halo <= 0f) continue
            val p = poseBuffer[i]
            val halfW = p.width * viewW * p.scale * 0.5f
            val halfH = p.height * viewH * p.scale * 0.5f
            GLES20.glUniform1f(h.haloStrength, e.halo)
            GLES20.glUniformMatrix4fv(
                h.haloMvp, 1, false,
                matrixAt(p.x * viewW, p.y * viewH, p.rotation, halfW * 1.9f, halfH * 1.55f), 0,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    private fun drawSprites(nodes: List<Node>, t: Float) {
        GLES20.glUseProgram(pSprite)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform1f(h.spriteTime, t)
        GLES20.glUniform1i(h.spriteFace, 0)
        GLES20.glUniform1i(h.spriteBack, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GlUtil.bindMesh(h.spritePos, h.spriteUv)

        var lastFront = -1
        var lastBack = -1
        for (i in nodes.indices) {
            val n = nodes[i]
            val p = poseBuffer[i]
            if (p.alpha <= 0.001f) continue
            val front = textures.texture(n.front).takeIf { it != 0 } ?: whiteTexture
            val back = textures.texture(n.back).takeIf { it != 0 } ?: whiteTexture
            if (front != lastFront) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, front)
                lastFront = front
            }
            if (back != lastBack) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, back)
                lastBack = back
            }

            val flip = p.flip.coerceIn(0f, 1f)
            val angle = flip * PI.toFloat()
            val bend = sin(angle) * n.effects.bend
            val halfW = p.width * viewW * p.scale * (1f + p.lift) * 0.5f
            val halfH = p.height * viewH * p.scale * (1f + p.lift) * 0.5f

            GLES20.glUniformMatrix4fv(
                h.spriteMvp, 1, false,
                matrixAt(p.x * viewW, p.y * viewH, p.rotation, halfW, halfH), 0,
            )
            GLES20.glUniform1f(h.spriteAngle, angle)
            GLES20.glUniform1f(h.spriteBend, bend)
            GLES20.glUniform1f(h.spriteGlow, n.effects.glow)
            GLES20.glUniform1f(h.spriteFoil, n.effects.foil)
            GLES20.glUniform1f(h.spriteAlpha, p.alpha)
            GLES20.glUniform1f(h.spriteReversed, 0f)
            GlUtil.drawMesh()
        }
        // 释放属性数组绑定，避免下一帧别的 pass 误用
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawBurst(t: Float) {
        val b = activeBurst ?: return
        val age = t - b.birth
        if (age > BURST_LIFE) {
            activeBurst = null
            return
        }
        GLES20.glUseProgram(pBurst)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUniformMatrix4fv(h.burstMvp, 1, false, projNdc, 0)
        GLES20.glUniform2f(h.burstCenter, b.cx, b.cy)
        GLES20.glUniform1f(h.burstZ, 0f)
        GLES20.glUniform1f(h.burstScale, viewH / 720f)
        GLES20.glUniform1f(h.burstTime, t)
        GLES20.glUniform1f(h.burstBirth, b.birth)
        GLES20.glUniform1f(h.burstLife, BURST_LIFE)
        val c = GlUtil.argbToRgb(b.color)
        GLES20.glUniform3f(h.burstColor, c[0], c[1], c[2])

        burstDirs.position(0)
        burstSpeeds.position(0)
        burstPhases.position(0)
        GLES20.glEnableVertexAttribArray(h.burstDir)
        GLES20.glVertexAttribPointer(h.burstDir, 2, GLES20.GL_FLOAT, false, 8, burstDirs)
        GLES20.glEnableVertexAttribArray(h.burstSpeed)
        GLES20.glVertexAttribPointer(h.burstSpeed, 1, GLES20.GL_FLOAT, false, 4, burstSpeeds)
        GLES20.glEnableVertexAttribArray(h.burstPhase)
        GLES20.glVertexAttribPointer(h.burstPhase, 1, GLES20.GL_FLOAT, false, 4, burstPhases)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, b.count)
    }

    private fun clearAttribs() {
        for (a in attribs) GLES20.glDisableVertexAttribArray(a)
    }

    /** 单位 quad(-1..1) → 屏幕像素矩形；正角在 y 向下的投影里表现为顺时针。 */
    private fun matrixAt(
        cx: Float, cy: Float, rotationDeg: Float, halfW: Float, halfH: Float,
    ): FloatArray {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, cx, cy, 0f)
        Matrix.rotateM(model, 0, rotationDeg, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, halfW, halfH, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        return mvp
    }

    private fun makeWhiteTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        val px = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(px)
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf,
        )
        return ids[0]
    }

    companion object {
        private const val STAR_COUNT = 120
        private const val BURST_POOL = 128
        private const val BURST_LIFE = 1.1f

        private fun makeStarSeeds(count: Int): FloatBuffer {
            val rnd = java.util.Random(8811L)
            val a = FloatArray(count * 3)
            for (i in 0 until count) {
                a[i * 3] = rnd.nextFloat()
                a[i * 3 + 1] = rnd.nextFloat()
                a[i * 3 + 2] = rnd.nextFloat()
            }
            return wrap(a)
        }

        private fun wrap(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
    }
}
