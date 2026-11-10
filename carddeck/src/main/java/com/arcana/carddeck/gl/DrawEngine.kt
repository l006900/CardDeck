package com.arcana.carddeck.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.Matrix
import com.arcana.gfx.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * 抽卡序列引擎（[GlEngine] 的第二个实现）：发牌编排 + 揭示 + 体积光晕 + 粒子爆发。
 *
 * 与 [TableEngine]（可摆弄的桌面）正交 —— 这里是**一段有时间轴的演出**：
 * 牌从中心牌堆沿弧线错峰飞到牌阵位（牌阵铺开），落定后自动/手动翻面（3D 翻牌），
 * 翻面过半迸发金色粒子（粒子光效爆发）并点亮背后体积光晕（体积光）。
 *
 * 完全复用 [GlShaders] 的 card/table 着色器（翻牌质感与牌桌一致），只新增
 * [GlShaders.HALO_FRAGMENT] / burst 两个 program。零改动 [TableEngine]，freedraw 的牌桌不受影响。
 *
 * 线程模型同 [TableEngine]：GL 调用只在 GL 线程；调用方线程经 [deal]/[revealNext]
 * 写 [DrawScene] 的编排参数（@Volatile + 结构锁）；纹理回填等 GL 线程上传。
 */
internal class DrawEngine(private val config: GlConfig) : GlEngine, DrawController {

    /* --------------------------------------------------- GlEngine 公开面 */

    override var faceLoader: ((cardId: String, onReady: (Bitmap?) -> Unit) -> Unit)? = null
    override var backBitmap: Bitmap? = null
    override var onCardMoved: ((uid: Long, x: Float, y: Float) -> Unit)? = null
    override var onCardFlipped: ((uid: Long, nowRevealed: Boolean) -> Unit)? = null
    override var onCardActivated: ((uid: Long, cardId: String) -> Unit)? = null

    /** 阶段变化回调（引擎 → Compose 状态）。 */
    override var onPhaseChanged: ((DrawPhase) -> Unit)? = null

    /* --------------------------------------------------- 内部状态 */

    val scene = DrawScene()

    /* --------------------------------------------------- 矩阵 */

    private val mvp = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val model = FloatArray(16)
    private val rot = FloatArray(16)
    private val tmpM = FloatArray(16)

    /* --------------------------------------------------- program / 句柄 */

    private var cardProgram = 0
    private var tableProgram = 0
    private var haloProgram = 0
    private var burstProgram = 0

    private var cardUMvp = 0
    private var cardUAngle = 0
    private var cardUFace = 0
    private var cardUBack = 0
    private var cardUTime = 0
    private var cardUGlow = 0
    private var cardUReversed = 0
    private var cardUBend = 0
    private var cardAPos = 0
    private var cardAUv = 0

    private var tableUMvp = 0
    private var tableURes = 0
    private var tableUInner = 0
    private var tableUOuter = 0
    private var tableAPos = 0
    private var tableAUv = 0

    private var haloUMvp = 0
    private var haloUTime = 0
    private var haloUStrength = 0
    private var haloUColor = 0
    private var haloAPos = 0
    private var haloAUv = 0

    private var burstUMvp = 0
    private var burstUCenter = 0
    private var burstUTime = 0
    private var burstUBirth = 0
    private var burstULife = 0
    private var burstUColor = 0

    /* --------------------------------------------------- 几何与纹理 */

    /** 爆发粒子池（方向/速度/相位），启动生成一次，每次揭示复用（只换中心与出生时刻）。 */
    private val burstSeeds: FloatBuffer = wrap(buildBurstSeeds(1337L, BURST_POOL))

    @Volatile private var clothInner = floatArrayOf(0.055f, 0.075f, 0.125f) // #0e1320
    @Volatile private var clothOuter = floatArrayOf(0.055f, 0.075f, 0.125f)

    private var backTexture = 0
    private val faceTextures = ConcurrentHashMap<String, Int>()
    private val pendingBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val requested = ConcurrentHashMap.newKeySet<String>()

    private var width = 1
    private var height = 1
    private val startedAt = System.nanoTime()
    /**
     * 序列时钟起点（纳秒）：[deal] 后等**首帧渲染**才置为当前时刻，使 [DrawScene.clock]
     * 从 0 起算。
     *
     * 关键：覆盖层是按需挂载的（点抽牌才出现），GL surface 创建有延迟；若直接拿
     * 「引擎构造到现在」的绝对时间当序列时钟，首帧时时钟已跳到 1s+，1.3s 的发牌→
     * 翻面→爆发全被跳过 —— 桌上只剩一张静止的正脸牌 + 微弱余光。改为「deal 后首帧
     * 归零」，演出必定从 0 开始，与 surface 何时就绪无关。[startedAt] 仍用于 shader
     * 时间（halo/粒子持续动画需要一条只增不减的轴），两轴各司其职。
     */
    private var seqStartNanos = startedAt
    /** deal 已排程、等待首帧才把 seqStartNanos 拨到现在的标志。 */
    private var dealPending = false
    private var lastPhase: DrawPhase? = null

    /* --------------------------------------------------- GlEngine：状态提交 */

    override fun submit(cards: List<TableCard>, cardSizeNorm: Float) {
        // 即时落位（无动画）：抽卡组件走 [deal]，submit 仅作「直接摆好」的契约实现。
        scene.cardSize = cardSizeNorm
        scene.items.clear()
        scene.bursts.clear()
        scene.clock = 0f
        cards.forEach { c ->
            scene.items.add(
                DrawScene.Item(
                    uid = c.uid,
                    cardId = c.cardId,
                    reversed = c.reversed,
                    slotX = c.x,
                    slotY = c.y,
                    dealStart = 0f,
                    dealDur = 0.0001f,
                    flipDur = 0.0001f,
                    revealStart = if (c.revealed) 0f else -1f,
                    revealed = c.revealed,
                ),
            )
        }
        cards.forEach { ensureFaceTexture(it.cardId) }
        // submit 是「直接摆好」契约：已翻开的牌不应在首帧误触发爆发（burstFired 默认 false
        // 会让 tick 误判翻面过半）。标记掉，避免落桌瞬间凭空炸一束粒子。
        scene.items.forEach { if (it.revealed) it.burstFired = true }
        lastPhase = null
    }

    override fun setSpreadOverlay(positions: FloatArray, visible: Boolean) {
        // 抽卡组件不画静态牌位框（牌阵由发牌动画呈现），no-op。
    }

    override fun setCloth(inner: Int, outer: Int) {
        clothInner = GlUtil.argbToRgb(inner)
        clothOuter = GlUtil.argbToRgb(outer)
    }

    /* --------------------------------------------------- GlEngine：手势桥 */

    override fun hitTest(nx: Float, ny: Float, viewWidth: Float, viewHeight: Float, densityScale: Float): Long? {
        val halfH = scene.cardSize / 2f
        val halfW = halfH * DrawScene.CARD_ASPECT * (viewHeight / viewWidth.coerceAtLeast(1f))
        val slopX = 12f * densityScale / viewWidth
        val slopY = 12f * densityScale / viewHeight
        // 最上层优先（列表末位在上）。
        for (i in scene.items.indices.reversed()) {
            val it = scene.items[i]
            val x = scene.centerX(it)
            val y = scene.centerY(it)
            if (x - halfW - slopX <= nx && nx <= x + halfW + slopX &&
                y - halfH - slopY <= ny && ny <= y + halfH + slopY
            ) {
                return it.uid
            }
        }
        return null
    }

    override fun dragStart(uid: Long) = Unit
    override fun dragMove(uid: Long, nx: Float, ny: Float) = Unit
    override fun dragEnd(uid: Long, moved: Boolean) = Unit

    override fun tapCard(uid: Long) {
        val it = scene.items.firstOrNull { it.uid == uid } ?: return
        if (it.revealStart < 0f) {
            // 未揭示 → 现在翻开。
            it.revealStart = scene.clock
            onCardFlipped?.invoke(uid, true)
            return
        }
        // 已揭示 → 与牌桌引擎同一套语义：**不翻回背面**，改为把「要了这张牌」报给调用方。
        // 想重新盖回去由编排层显式调 reset()（抽卡演出是程序驱动的，用户的点击
        // 在这类场景里只表示「让我看看这张」，不是「收起来」）。
        onCardActivated?.invoke(uid, it.cardId)
    }

    /* --------------------------------------------------- 抽卡编排（引擎扩展方法） */

    /**
     * 开始一次抽卡演出：牌沿弧线错峰飞入（牌阵铺开），落定后按 [autoReveal] 自动翻面。
     * 落点用弧线排布（中间高、两端低），比直线更有「展开」的仪式感。
     */
    override fun deal(cards: List<TableCard>, cardSizeNorm: Float, autoReveal: Boolean) {
        val slots = arcSlots(cards.size)
        scene.deal(cards, cardSizeNorm, slots, autoReveal)
        cards.forEach { ensureFaceTexture(it.cardId) }
        // 序列时钟留给首帧再归零（见 seqStartNanos 注释）：覆盖层按需挂载、surface 有延迟，
        // 不能在这里直接 clock=0 —— 会被下一帧的绝对时间覆盖掉。
        dealPending = true
        lastPhase = null
    }

    /** 手动揭示下一张未翻开的牌。 */
    override fun revealNext() = scene.revealNext(scene.clock)

    /** 手动揭示全部未翻开的牌（错峰）。 */
    override fun revealAll() = scene.revealAll(scene.clock)

    /** 清空，回到待机。 */
    override fun reset() {
        scene.reset()
        dealPending = false
        seqStartNanos = System.nanoTime()
        lastPhase = null
    }

    /** 当前阶段（引擎 → 外）。 */
    override fun phase(): DrawPhase = scene.phase()

    /** 弧线落点：中间高两端低，水平均匀铺开。 */
    private fun arcSlots(n: Int): List<Pair<Float, Float>> {
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(0.5f to 0.5f)
        return (0 until n).map { i ->
            val frac = i.toFloat() / (n - 1)
            val x = 0.18f + 0.64f * frac
            val y = 0.54f - 0.10f * (1f - kotlin.math.abs(2f * frac - 1f))
            x to y
        }
    }

    /* --------------------------------------------------- 帧回调（库内部宿主驱动） */

    override fun onGlCreated() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        cardProgram = GlUtil.buildProgram(GlShaders.CARD_VERTEX, GlShaders.CARD_FRAGMENT)
        tableProgram = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.TABLE_FRAGMENT)
        haloProgram = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.HALO_FRAGMENT)
        burstProgram = GlUtil.buildProgram(GlShaders.BURST_VERTEX, GlShaders.BURST_FRAGMENT)

        cardUMvp = GlUtil.location(cardProgram, "uMvp")
        cardUAngle = GlUtil.location(cardProgram, "uAngle")
        cardUFace = GlUtil.location(cardProgram, "uFace")
        cardUBack = GlUtil.location(cardProgram, "uBack")
        cardUTime = GlUtil.location(cardProgram, "uTime")
        cardUGlow = GlUtil.location(cardProgram, "uGlow")
        cardUReversed = GlUtil.location(cardProgram, "uReversed")
        cardUBend = GlUtil.location(cardProgram, "uBend")
        cardAPos = GlUtil.attrib(cardProgram, "aPos")
        cardAUv = GlUtil.attrib(cardProgram, "aUv")

        tableUMvp = GlUtil.location(tableProgram, "uMvp")
        tableURes = GlUtil.location(tableProgram, "uResolution")
        tableUInner = GlUtil.location(tableProgram, "uInner")
        tableUOuter = GlUtil.location(tableProgram, "uOuter")
        tableAPos = GlUtil.attrib(tableProgram, "aPos")
        tableAUv = GlUtil.attrib(tableProgram, "aUv")

        haloUMvp = GlUtil.location(haloProgram, "uMvp")
        haloUTime = GlUtil.location(haloProgram, "uTime")
        haloUStrength = GlUtil.location(haloProgram, "uStrength")
        haloUColor = GlUtil.location(haloProgram, "uColor")
        haloAPos = GlUtil.attrib(haloProgram, "aPos")
        haloAUv = GlUtil.attrib(haloProgram, "aUv")

        burstUMvp = GlUtil.location(burstProgram, "uMvp")
        burstUCenter = GlUtil.location(burstProgram, "uCenter")
        burstUTime = GlUtil.location(burstProgram, "uTime")
        burstUBirth = GlUtil.location(burstProgram, "uBirth")
        burstULife = GlUtil.location(burstProgram, "uLife")
        burstUColor = GlUtil.location(burstProgram, "uColor")
        // 爆发粒子属性句柄（program link 后才能取）。
        burstADir = GlUtil.attrib(burstProgram, "aDir")
        burstASpeed = GlUtil.attrib(burstProgram, "aSpeed")
        burstAPhase = GlUtil.attrib(burstProgram, "aPhase")

        backBitmap?.let { backTexture = GlUtil.uploadBitmap(it, config.maxFaceTextureWidth) }
    }

    override fun onGlResized(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height
        val nearTop = NEAR / EYE_Z
        Matrix.frustumM(proj, 0, -aspect * nearTop, aspect * nearTop, -nearTop, nearTop, NEAR, 12f)
        Matrix.setLookAtM(viewM, 0, 0f, 0f, EYE_Z, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onGlFrame() {
        val now = System.nanoTime()
        // shader 时间轴（halo/粒子持续动画，只增不减）。
        val t = (now - startedAt) / 1_000_000_000f
        // 序列时钟轴：deal 后等首帧再归零，保证演出从 0 开始（与 surface 何时就绪解耦）。
        if (dealPending) {
            seqStartNanos = now
            dealPending = false
        }
        scene.clock = (now - seqStartNanos) / 1_000_000_000f
        val phase = scene.tick(1f / 60f)
        if (phase != lastPhase) {
            lastPhase = phase
            onPhaseChanged?.invoke(phase)
        }

        uploadPending()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        drawTable(t)
        drawHalos(t)
        drawCards(t)
        // 爆发粒子用**序列时钟**（与 burst.birth 同源），否则 age = 绝对时间 - 序列时间 = 乱值，
        // 粒子瞬间淡出归零、整束看不见。
        drawBursts(scene.clock)
    }

    /* --------------------------------------------------- 绘制 */

    /** 每个 pass 前清空顶点属性数组，避免 quad(4 点) / 网格(289 点) / 粒子之间越界互读。 */
    private fun clearAttribs() {
        listOf(cardAPos, cardAUv, tableAPos, tableAUv, haloAPos, haloAUv,
            burstADir, burstASpeed, burstAPhase)
            .distinct().filter { it >= 0 }
            .forEach { GLES20.glDisableVertexAttribArray(it) }
    }

    private fun drawTable(t: Float) {
        clearAttribs()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(tableProgram)
        GLES20.glUniformMatrix4fv(tableUMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(tableURes, width.toFloat(), height.toFloat())
        val ci = clothInner
        val co = clothOuter
        GLES20.glUniform3f(tableUInner, ci[0], ci[1], ci[2])
        GLES20.glUniform3f(tableUOuter, co[0], co[1], co[2])
        GlUtil.bindQuad(tableAPos, tableAUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** 体积光晕：已翻开（或正在翻）的牌背后，按 glow 强度发光的金色背光。 */
    private fun drawHalos(t: Float) {
        clearAttribs()
        val aspect = width.toFloat() / height
        val halfH = scene.cardSize / 2f
        val halfW = halfH * DrawScene.CARD_ASPECT
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE) // 加法混合，光是叠加的
        GLES20.glUseProgram(haloProgram)
        GLES20.glUniform1f(haloUTime, t)
        GLES20.glUniform3f(haloUColor, 0.98f, 0.80f, 0.42f) // 金
        GlUtil.bindQuad(haloAPos, haloAUv)
        for (it in scene.items) {
            // 背面（revealP<0.5）时强度随翻面淡入；已翻开保留一点常驻底光。
            val strength = it.glow.coerceAtLeast(if (scene.revealP(it) > 0.5f) 0.18f else 0f)
            if (strength <= 0.001f) continue
            val cx = (scene.centerX(it) * 2f - 1f) * aspect
            val cy = 1f - scene.centerY(it) * 2f
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, cx, cy, -0.2f)
            Matrix.scaleM(model, 0, halfW * 1.9f, halfH * 1.9f, 1f) // 光晕比牌大近一倍
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)
            GLES20.glUniformMatrix4fv(haloUMvp, 1, false, tmpM, 0)
            GLES20.glUniform1f(haloUStrength, strength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    private fun drawCards(t: Float) {
        clearAttribs()
        val aspect = width.toFloat() / height
        val halfH = scene.cardSize / 2f
        val halfW = halfH * DrawScene.CARD_ASPECT
        GLES20.glUseProgram(cardProgram)
        // 正/背面分两个纹理单元（同 TableRenderer），否则牌背会被画成牌面。
        GLES20.glUniform1i(cardUFace, 0)
        GLES20.glUniform1i(cardUBack, 1)
        GLES20.glUniform1f(cardUTime, t)
        GlUtil.bindMesh(cardAPos, cardAUv)
        for (it in scene.items) {
            val cx = (scene.centerX(it) * 2f - 1f) * aspect
            val cy = 1f - scene.centerY(it) * 2f
            val dealP = scene.dealP(it)
            val z = (1f - dealP) * 0.5f // 发牌飞行高度，落定归零
            val angleRad = scene.faceAngle(it)
            val frac = if (scene.items.size > 1) {
                scene.items.indexOf(it).toFloat() / (scene.items.size - 1)
            } else {
                0.5f
            }
            val tilt = (frac - 0.5f) * 26f // 弧线扇形微倾，纯视觉

            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, cx, cy + z * 0.9f, z * 1.6f)
            Matrix.rotateM(model, 0, tilt, 0f, 0f, 1f)
            Matrix.setIdentityM(rot, 0)
            Matrix.rotateM(rot, 0, Math.toDegrees(angleRad.toDouble()).toFloat(), 0f, 1f, 0f)
            Matrix.multiplyMM(tmpM, 0, model, 0, rot, 0)
            Matrix.setIdentityM(model, 0)
            Matrix.scaleM(model, 0, halfW, halfH, 1f)
            Matrix.multiplyMM(model, 0, tmpM, 0, model, 0)
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)

            GLES20.glUniformMatrix4fv(cardUMvp, 1, false, tmpM, 0)
            GLES20.glUniform1f(cardUAngle, angleRad)
            GLES20.glUniform1f(cardUBend, kotlin.math.sin(angleRad).coerceAtLeast(0f))
            GLES20.glUniform1f(cardUGlow, it.glow)
            GLES20.glUniform1f(cardUReversed, if (it.reversed) 1f else 0f)

            val faceTex = faceTextures[it.cardId]
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTex ?: backTexture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTexture)

            GlUtil.drawMesh()
        }
    }

    /** 揭示粒子爆发：每个活跃爆发在牌心迸发一束金色粒子。
     *  @param clock 序列时钟（与 burst.birth 同源），驱动 age = clock - birth。 */
    private fun drawBursts(clock: Float) {
        if (scene.bursts.isEmpty()) return
        clearAttribs()
        val aspect = width.toFloat() / height
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(burstProgram)
        GLES20.glUniformMatrix4fv(burstUMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(burstUTime, clock)
        GLES20.glUniform1f(burstULife, DrawScene.BURST_LIFE)
        GLES20.glUniform3f(burstUColor, 0.98f, 0.82f, 0.48f)
        GLES20.glEnableVertexAttribArray(burstADir)
        GLES20.glEnableVertexAttribArray(burstASpeed)
        GLES20.glEnableVertexAttribArray(burstAPhase)
        // 同一 buffer 的三个属性，按 position 偏移读取：dir(0) / speed(2) / phase(3)。
        // 每帧复位 position(0)，否则上轮的 position(3) 会污染下一帧的 dir 指针。
        burstSeeds.position(0)
        GLES20.glVertexAttribPointer(burstADir, 2, GLES20.GL_FLOAT, false, 16, burstSeeds)
        burstSeeds.position(2)
        GLES20.glVertexAttribPointer(burstASpeed, 1, GLES20.GL_FLOAT, false, 16, burstSeeds)
        burstSeeds.position(3)
        GLES20.glVertexAttribPointer(burstAPhase, 1, GLES20.GL_FLOAT, false, 16, burstSeeds)
        for (b in scene.bursts) {
            val cx = (b.cx * 2f - 1f) * aspect
            val cy = 1f - b.cy * 2f
            GLES20.glUniform2f(burstUCenter, cx, cy)
            GLES20.glUniform1f(burstUBirth, b.birth)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, BURST_POOL)
        }
        GLES20.glDisableVertexAttribArray(burstADir)
        GLES20.glDisableVertexAttribArray(burstASpeed)
        GLES20.glDisableVertexAttribArray(burstAPhase)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    // 爆发粒子属性句柄（懒初始化：需在 program link 后拿 location）。
    private var burstADir = 0
    private var burstASpeed = 0
    private var burstAPhase = 0

    /* --------------------------------------------------- 纹理 */

    private fun ensureFaceTexture(cardId: String) {
        if (faceTextures.containsKey(cardId) || !requested.add(cardId)) return
        val loader = faceLoader
        if (loader == null) {
            requested.remove(cardId)
            return
        }
        loader(cardId) { bmp ->
            if (bmp != null) pendingBitmaps[cardId] = bmp else requested.remove(cardId)
        }
    }

    private fun uploadPending() {
        if (backTexture == 0) backBitmap?.let { backTexture = GlUtil.uploadBitmap(it, config.maxFaceTextureWidth) }
        if (pendingBitmaps.isEmpty()) return
        for ((cardId, bmp) in pendingBitmaps) {
            val tex = GlUtil.uploadBitmap(bmp, config.maxFaceTextureWidth)
            if (tex != 0) faceTextures[cardId] = tex
            pendingBitmaps.remove(cardId)
        }
    }

    /* --------------------------------------------------- 工具 */

    private fun wrap(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    /** 爆发粒子池：每个粒子一组 (dirX, dirY, speed, phase)。方向圆内均匀、速度随机。 */
    private fun buildBurstSeeds(seed: Long, count: Int): FloatArray {
        val rnd = Random(seed)
        val data = FloatArray(count * 4)
        for (i in 0 until count) {
            val ang = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val r = 0.35f + rnd.nextFloat() * 0.65f
            data[i * 4] = kotlin.math.cos(ang) * r
            data[i * 4 + 1] = kotlin.math.sin(ang) * r
            data[i * 4 + 2] = 0.18f + rnd.nextFloat() * 0.42f // 世界单位/秒
            data[i * 4 + 3] = rnd.nextFloat() // 相位/大小
        }
        return data
    }

    companion object {
        const val EYE_Z = 4.6f
        const val NEAR = 2.2f
        const val BURST_POOL = 80
    }
}
