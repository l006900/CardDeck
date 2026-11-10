package com.arcana.carddeck.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.arcana.gfx.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * 牌桌引擎（[GlEngine] 的首个实现）：透视投影 + 绕 Y 翻牌 + 金箔高光 + GPU 粒子。
 *
 * 线程模型（这是 GL 代码最容易写错的地方，先钉死）：
 *  - GL 调用只发生在 GL 线程（[onGlFrame] / [onGlCreated]）；
 *  - 调用方线程经 [submit] / 手势桥写 [scene] 的目标值（@Volatile + 结构锁）；
 *  - 纹理上传必须在 GL 线程 —— [faceLoader] 回填的 bitmap 进 [pendingBitmaps]，
 *    下一帧 [onGlFrame] 开头统一 `texImage2D`。
 *
 * 引擎不碰网络与图片库：牌面由调用方经 [faceLoader] 异步回填 [Bitmap]，
 * 牌背直接给 [backBitmap] —— 换皮肤、缓存策略都是调用方的事。
 *
 * 渲染顺序：桌布（关深度）→ 粒子（加法混合、关深度写）→ 牌（数组序 = z 序）。
 */
internal class TableEngine(private val config: GlConfig) : GlEngine {

    /* --------------------------------------------------- GlEngine 公开面 */

    override var faceLoader: ((cardId: String, onReady: (Bitmap?) -> Unit) -> Unit)? = null

    override var backBitmap: Bitmap? = null

    override var onCardMoved: ((uid: Long, x: Float, y: Float) -> Unit)? = null

    override var onCardFlipped: ((uid: Long, nowRevealed: Boolean) -> Unit)? = null

    override var onCardActivated: ((uid: Long, cardId: String) -> Unit)? = null

    /* --------------------------------------------------- 内部状态 */

    val scene = TableScene()

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
    private var spreadProgram = 0
    private var particleProgram = 0
    private var shadowProgram = 0
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

    private var shadowUMvp = 0
    private var shadowUStrength = 0
    private var shadowAPos = 0
    private var shadowAUv = 0

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
    private var burstUZ = 0
    private var burstUScale = 0
    private var burstADir = 0
    private var burstASpeed = 0
    private var burstAPhase = 0

    private var tableUMvp = 0
    private var tableURes = 0
    private var tableUInner = 0
    private var tableUOuter = 0
    private var tableAPos = 0
    private var tableAUv = 0

    private var spreadUMvp = 0
    private var spreadUColor = 0
    private var spreadULine = 0
    private var spreadUAspectX = 0
    private var spreadAPos = 0
    private var spreadAUv = 0

    private var particleUMvp = 0
    private var particleURes = 0
    private var particleUTime = 0
    private var particleASeed = 0

    /* --------------------------------------------------- 几何与纹理 */

    /** 单位 quad（两个三角带）：pos 与 uv 分开两个 buffer —— client-side 顶点数组没有字节偏移参数。 */
    private val quadPos: FloatBuffer = wrap(QUAD_POS)
    private val quadUv: FloatBuffer = wrap(QUAD_UV)

    /** 粒子种子（x 位置 / 速度相位 / 大小亮度），启动时生成一次。 */
    private val particleSeeds: FloatBuffer = wrap(buildParticleSeeds(config.particleSeed, config.particleCount))

    /** 揭示爆发粒子池：翻面过半时在牌心迸发一束金色星辉（与抽卡引擎共用 shader）。 */
    private val burstSeeds: FloatBuffer = GlUtil.burstSeeds(2468L, BURST_POOL)

    private var backTexture = 0
    private val faceTextures = ConcurrentHashMap<String, Int>()

    /** 调用方回填、等 GL 线程上传的牌面。 */
    private val pendingBitmaps = ConcurrentHashMap<String, Bitmap>()

    /** 已发起加载的牌面（去重；失败移除允许重试）。 */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** mipmap 能力只在第一张纹理上确认并打一次日志。 */
    private var mipLogged = false

    private var width = 1
    private var height = 1

    /** 上一帧的 nanoTime（只在 GL 线程读写），用于算真实 delta。 */
    private var lastFrameNanos = 0L

    /**
     * 所有 pass 的顶点属性 location 汇总，[clearAttribs] 每帧遍历用。
     * 旧实现每 pass 都 `listOf(...).distinct().filter{}` —— 7 个 pass × 每帧 = 每秒几百个
     * 临时 List/数组，也是 GC 尖刺的一部分。locations 在 [onGlCreated] 后就固定，
     * 一次性收进 IntArray。
     */
    private var allAttribs: IntArray = IntArray(0)

    /** 本帧活跃的爆发粒子（复用，避免每帧 filter 出新 List）。 */
    private val burstItems = ArrayList<TableScene.Item>(8)

    /* --------------------------------------------------- GlEngine：状态提交 */

    override fun submit(cards: List<TableCard>, cardSizeNorm: Float) {
        scene.submit(cards, cardSizeNorm)
        cards.forEach { ensureFaceTexture(it.cardId) }
    }

    override fun setSpreadOverlay(positions: FloatArray, visible: Boolean) {
        scene.spreadPositions = positions
        scene.spreadVisible = visible
    }

    /** 桌布色（UI 线程写，GL 线程读 —— 单写多读的两个 float 组，@Volatile 足够）。
     *  默认 = 全局主题 --bg #0E1320：没应用占卜间时页面就是主题底色（Web free-root 同款），
     *  之前默认紫调 #231b35，跟全 App 主题对不上。 */
    @Volatile private var clothInner = floatArrayOf(0.055f, 0.075f, 0.125f)  // #0e1320
    @Volatile private var clothOuter = floatArrayOf(0.055f, 0.075f, 0.125f)

    override fun setCloth(inner: Int, outer: Int) {
        clothInner = argbToRgb(inner)
        clothOuter = argbToRgb(outer)
    }

    private fun argbToRgb(argb: Int) = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f,
    )

    /* --------------------------------------------------- GlEngine：手势桥 */

    override fun hitTest(nx: Float, ny: Float, viewWidth: Float, viewHeight: Float, densityScale: Float): Long? =
        scene.hitTest(nx, ny, viewWidth, viewHeight, densityScale)?.uid

    override fun dragStart(uid: Long) {
        val item = scene.find(uid) ?: return
        item.dragging = true
        // 新一轮拖动：抓取偏移要重新量（上一次拖的那张牌的位置早就变了）。
        item.grabSet = false
        // 提到最上层：拖动的牌应该盖在所有牌上。
        scene.bringToFront(item)
    }

    override fun dragMove(uid: Long, nx: Float, ny: Float) {
        scene.moveTo(uid, nx, ny)
    }

    override fun dragEnd(uid: Long, moved: Boolean) {
        val item = scene.find(uid) ?: return
        item.dragging = false
        if (moved) {
            item.targetX = item.x
            item.targetY = item.y
            onCardMoved?.invoke(uid, item.x, item.y)
        }
    }

    override fun tapCard(uid: Long) {
        // **演出期间整桌冻结**：抬升 → 翻 → 停 → 落 这约 2.5s 里，桌上任何一张牌都
        // 不响应 —— 既不翻面，也不进牌意页。用户原话：「翻页过程中点击牌桌不管是
        // 翻页还是卡片详情都不能」。
        //
        // 为什么不只拦被点的那一张（早先的做法）：
        //  1. 别的牌这时翻面会被一起推到屏幕中央的同一深度区间 —— z 阶梯（0.0025）
        //     只够分开平放在桌上的牌，两块立体牌面挤在一起就是穿模本身；
        //  2. 别的牌这时进详情，页面在牌还飞着的时候切走，返回时演出停在一半、
        //     牌名浮层残留。
        // 连点几下都只当没点：宁可吞掉操作，也不让画面上出现两张牌同时飞。
        if (scene.isBusy()) return
        val item = scene.find(uid) ?: return
        // 正面朝上 → 不再翻回背面：牌已经是"信息面"，点它的意思是「我要看牌意」，
        // 不是「把它盖回去」。盖回去等于把用户刚读懂的那张牌抽走，在自由抽牌里
        // 没有任何用处。
        if (item.targetFace > 0.5f) {
            onCardActivated?.invoke(uid, item.cardId)
            return
        }
        // 翻面期间这张牌必须在最上层：牌的纵深阶梯按绘制顺序给（见 CARD_Z_STEP），
        // 顺序不动的话它就是「下层」，掀起来的那半张会被上面那张牌切开。
        scene.bringToFront(item)
        scene.flip(uid)
        onCardFlipped?.invoke(uid, item.targetFace > 0.5f)
    }

    /* --------------------------------------------------- 帧回调（库内部宿主驱动） */

    override fun onGlCreated() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        cardProgram = buildProgram(GlShaders.CARD_VERTEX, GlShaders.CARD_FRAGMENT)
        tableProgram = buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.TABLE_FRAGMENT)
        spreadProgram = buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.SPREAD_FRAGMENT)
        particleProgram = buildProgram(GlShaders.PARTICLE_VERTEX, GlShaders.PARTICLE_FRAGMENT)
        shadowProgram = buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.SHADOW_FRAGMENT)
        haloProgram = buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.HALO_FRAGMENT)
        burstProgram = buildProgram(GlShaders.BURST_VERTEX, GlShaders.BURST_FRAGMENT)

        cardUMvp = location(cardProgram, "uMvp")
        cardUAngle = location(cardProgram, "uAngle")
        cardUFace = location(cardProgram, "uFace")
        cardUBack = location(cardProgram, "uBack")
        cardUTime = location(cardProgram, "uTime")
        cardUGlow = location(cardProgram, "uGlow")
        cardUReversed = location(cardProgram, "uReversed")
        cardUBend = location(cardProgram, "uBend")
        cardAPos = attrib(cardProgram, "aPos")
        cardAUv = attrib(cardProgram, "aUv")

        shadowUMvp = location(shadowProgram, "uMvp")
        shadowUStrength = location(shadowProgram, "uStrength")
        shadowAPos = attrib(shadowProgram, "aPos")
        shadowAUv = attrib(shadowProgram, "aUv")

        haloUMvp = location(haloProgram, "uMvp")
        haloUTime = location(haloProgram, "uTime")
        haloUStrength = location(haloProgram, "uStrength")
        haloUColor = location(haloProgram, "uColor")
        haloAPos = attrib(haloProgram, "aPos")
        haloAUv = attrib(haloProgram, "aUv")

        burstUMvp = location(burstProgram, "uMvp")
        burstUCenter = location(burstProgram, "uCenter")
        burstUTime = location(burstProgram, "uTime")
        burstUBirth = location(burstProgram, "uBirth")
        burstULife = location(burstProgram, "uLife")
        burstUColor = location(burstProgram, "uColor")
        burstUZ = location(burstProgram, "uZ")
        burstUScale = location(burstProgram, "uScale")
        burstADir = attrib(burstProgram, "aDir")
        burstASpeed = attrib(burstProgram, "aSpeed")
        burstAPhase = attrib(burstProgram, "aPhase")

        tableUMvp = location(tableProgram, "uMvp")
        tableURes = location(tableProgram, "uResolution")
        tableUInner = location(tableProgram, "uInner")
        tableUOuter = location(tableProgram, "uOuter")
        tableAPos = attrib(tableProgram, "aPos")
        tableAUv = attrib(tableProgram, "aUv")

        spreadUMvp = location(spreadProgram, "uMvp")
        spreadUColor = location(spreadProgram, "uColor")
        spreadULine = location(spreadProgram, "uLine")
        spreadUAspectX = location(spreadProgram, "uAspectX")
        spreadAPos = attrib(spreadProgram, "aPos")
        spreadAUv = attrib(spreadProgram, "aUv")

        particleUMvp = location(particleProgram, "uMvp")
        particleURes = location(particleProgram, "uResolution")
        particleUTime = location(particleProgram, "uTime")
        particleASeed = attrib(particleProgram, "aSeed")

        // 各 program 的 aPos 通常都落在 0，重复 disable 无副作用；一次性收集省掉每帧建表。
        allAttribs = intArrayOf(
            cardAPos, cardAUv, tableAPos, tableAUv, spreadAPos, spreadAUv,
            particleASeed, shadowAPos, shadowAUv, haloAPos, haloAUv,
            burstADir, burstASpeed, burstAPhase,
        )

        backBitmap?.let { backTexture = uploadBitmap(it) }
    }

    override fun onGlResized(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)

        // 透视投影。关键：frustum 的 top 按「z=0 牌面平面」校准 ——
        // 相机在 eyeZ，near 平面处 top=NEAR_TOP，则 z=0 处可视半高 =
        // NEAR_TOP * eyeZ / NEAR。把它正好调回 1，z=0 平面就精确映射
        // 归一化 ±1/±aspect：触摸命中、落点、渲染三者共用一套坐标。
        // 之前按 top=1 配置，牌面被压缩在屏幕中央 ~48% 区域，牌堆
        // 「看得见点不中」就是这个错位。
        val aspect = width.toFloat() / height
        val nearTop = NEAR / EYE_Z
        Matrix.frustumM(proj, 0, -aspect * nearTop, aspect * nearTop, -nearTop, nearTop, NEAR, 12f)
        Matrix.setLookAtM(viewM, 0, 0f, 0f, EYE_Z, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onGlFrame() {
        val now = System.nanoTime()
        // 真实帧间隔：固定 1/60 会让 90/120Hz 设备上的翻面快 1.5~2 倍（0.78s → 0.39s）。
        // 上限夹到 1/30：偶发掉帧时动画宁可慢一帧，也不要一次跳一大步。
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.0005f, 1f / 30f)
        }
        lastFrameNanos = now
        val t = (now - startedAt) / 1_000_000_000f

        // 本帧的绘制快照：调用方线程可能随时 submit 清场补牌（抽一张改一次结构），
        // 直接遍历 scene.items 是 ConcurrentModificationException（真机已崩过）。
        // 快照在锁内拷贝（双缓冲复用，零分配），Item 的动画字段（x/y/face，@Volatile）读到的都是最新值。
        val items = scene.snapshot()

        // 走场景的逐帧推进（只吃一个快照 List，不碰实例状态）。
        TableScene.advance(items, dt, t)

        // 先上传上一帧内回填好的牌面（GL 调用只能在 GL 线程，且每帧只传一张，见 uploadPending）。
        uploadPending()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        drawTable()
        drawSpreadZones()
        drawParticles(t)
        drawShadows(items)   // 假阴影：牌离桌越高越淡越大，给 3D 一个「落下去」的约束
        drawHalos(items, t)  // 翻面瞬间牌背后的金色体积光
        drawCards(items, t)
        drawBursts(items, t) // 翻过一半时的金色星辉爆发
    }

    /* --------------------------------------------------- 绘制 */

    /**
     * 每个 pass 开始前清干净顶点属性数组。
     *
     * 各 program 的 attrib location 会重叠（aPos 通常都是 0），上一个 pass 留下的
     * 数组若仍启用，当前 pass 顶点数更大时（如粒子 200 点 vs quad 4 点）会越界读到
     * 旧 buffer —— 轻则花屏、重则驱动报错。每个 pass 自己 bind 需要的即可。
     */
    private fun clearAttribs() {
        for (h in allAttribs) {
            if (h >= 0) GLES20.glDisableVertexAttribArray(h)
        }
    }

    private fun drawTable() {
        clearAttribs()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(tableProgram)
        GLES20.glUniformMatrix4fv(tableUMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(tableURes, width.toFloat(), height.toFloat())
        // @Volatile 数组引用读 —— 拿到的是某一帧的完整快照，线程安全够用。
        val ci = clothInner
        val co = clothOuter
        GLES20.glUniform3f(tableUInner, ci[0], ci[1], ci[2])
        GLES20.glUniform3f(tableUOuter, co[0], co[1], co[2])
        bindQuad(tableAPos, tableAUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * 牌位框（牌之下、桌布之上）。
     *
     * 坐标直接取 [TableScene.spreadPositions]（占卜间 `spreadPos` 的归一化版本），
     * 与自由抽牌的落点算法共用同一份数组 —— 这里**不再自己套网格公式**。以前 GL
     * 用固定 4 列网格画框、VM 用同一套公式算落点，看着自洽，但和用户在占卜间里摆的
     * 牌位毫无关系（占卜间存了 (50,50) 的位，框却画在 30% 高处）。
     */
    private fun drawSpreadZones() {
        val positions = scene.spreadPositions
        if (positions.size < 2 || !scene.spreadVisible) return
        clearAttribs()
        val aspect = width.toFloat() / height
        val halfH = scene.cardSize
        val halfW = halfH * TableScene.CARD_ASPECT

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(spreadProgram)
        GLES20.glUniformMatrix4fv(spreadUMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(spreadUColor, 0.96f, 0.84f, 0.55f, 1f) // 暖金（比牌面流光稍柔）
        // 线宽按**屏幕像素**给，不按牌尺寸的百分比 —— 牌位框大小会随牌缩放变，
        // 按比例算的话大屏上会变成一根手指粗的蜡笔线。
        // 1.4dp 左右（用屏宽比例近似，不额外依赖 density）。
        val linePx = width * 0.0034f
        val halfHeightPx = (halfH * height * 0.5f).coerceAtLeast(1f)
        // d 的单位是「半高」，所以线宽 = 像素 / 半高像素（不再乘 2）
        GLES20.glUniform1f(spreadULine, (linePx / halfHeightPx).coerceIn(0.008f, 0.06f))
        // 半宽/半高：着色器要把 uv 摊成等距空间，否则 3:5 窄长框的左右边会比上下边细
        GLES20.glUniform1f(spreadUAspectX, (halfW / halfH).coerceAtLeast(0.05f))
        bindQuad(spreadAPos, spreadAUv)

        var i = 0
        while (i + 1 < positions.size) {
            val cx = positions[i]
            val cy = positions[i + 1]
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, (cx * 2f - 1f) * aspect, 1f - cy * 2f, 0f)
            Matrix.scaleM(model, 0, halfW * 1.10f, halfH * 1.10f, 1f) // 框略大于牌，留一点呼吸
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)
            GLES20.glUniformMatrix4fv(spreadUMvp, 1, false, tmpM, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            i += 2
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawParticles(t: Float) {
        clearAttribs()
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE) // 加法混合：星是发光体
        GLES20.glUseProgram(particleProgram)
        GLES20.glUniformMatrix4fv(particleUMvp, 1, false, mvp, 0)
        GLES20.glUniform2f(particleURes, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(particleUTime, t)
        GLES20.glEnableVertexAttribArray(particleASeed)
        GLES20.glVertexAttribPointer(particleASeed, 3, GLES20.GL_FLOAT, false, 12, particleSeeds)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, config.particleCount)
        GLES20.glDisableVertexAttribArray(particleASeed)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    /**
     * 假阴影（AO）：牌下椭圆软影，随离桌高度放大、变淡、略向下偏 —— 牌翻到一半被
     * 「拈起来」，影子就撒开；落回桌面时收回一小圈实影，重量感就来自这个约束。
     */
    private fun drawShadows(items: List<TableScene.Item>) {
        clearAttribs()
        val aspect = width.toFloat() / height
        val halfH = scene.cardSize
        val halfW = halfH * TableScene.CARD_ASPECT
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(shadowProgram)
        GlUtil.bindQuad(shadowAPos, shadowAUv)
        for (item in items) {
            val lift = item.lift
            // 翻面时牌被"拿到眼前"，阴影要同步跟着走、摊大摊淡 —— 否则牌飞到屏幕中央，
            // 桌上那块影子还死死钉在原处，浮起感立刻露馅。
            val rise = flipRise(item.flipT, item.fallT)
            val cx = (item.x * 2f - 1f) * aspect
            val cy = 1f - item.y * 2f
            val spread = 1f + lift * 4.5f + rise * 0.85f
            val strength = (0.46f - lift * 3.2f - rise * 0.28f).coerceAtLeast(0.03f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(
                model, 0,
                cx * (1f - rise) + halfW * 0.12f,
                (cy - halfH * 0.16f) * (1f - rise) - lift * 0.35f +
                    rise * raiseYAt(-0.16f),
                -0.16f,
            )
            Matrix.scaleM(model, 0, halfW * 1.34f * spread, halfH * 1.22f * spread, 1f)
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)
            GLES20.glUniformMatrix4fv(shadowUMvp, 1, false, tmpM, 0)
            GLES20.glUniform1f(shadowUStrength, strength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** 体积光晕：翻面中（glow 脉冲）在牌背后点亮金色背光 + 竖向光锥。 */
    private fun drawHalos(items: List<TableScene.Item>, t: Float) {
        clearAttribs()
        val aspect = width.toFloat() / height
        val halfH = scene.cardSize
        val halfW = halfH * TableScene.CARD_ASPECT
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(haloProgram)
        GLES20.glUniform1f(haloUTime, t)
        GLES20.glUniform3f(haloUColor, 0.98f, 0.80f, 0.44f)
        GlUtil.bindQuad(haloAPos, haloAUv)
        for (item in items) {
            val strength = item.glow * 0.9f
            if (strength <= 0.001f) continue
            // 光晕跟着牌走：牌被拿到屏幕中央，背光还留在原位的话，会看到一团金光
            // 孤零零钉在桌上，牌却在中间翻。
            val rise = flipRise(item.flipT, item.fallT)
            val cx = (item.x * 2f - 1f) * aspect * (1f - rise)
            // 光晕贴着牌走，上移量也按自己的纵深换算（z=-0.14 更远，世界位移更大），
            // 否则牌往上飘走了、背光还留在原位。
            val cy = (1f - item.y * 2f) * (1f - rise) + rise * raiseYAt(-0.14f)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, cx, cy, -0.14f)
            Matrix.scaleM(model, 0, halfW * 2.1f, halfH * 2.0f, 1f)
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)
            GLES20.glUniformMatrix4fv(haloUMvp, 1, false, tmpM, 0)
            GLES20.glUniform1f(haloUStrength, strength)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    private fun drawCards(items: List<TableScene.Item>, t: Float) {
        clearAttribs()
        val aspect = width.toFloat() / height
        // scaleM 作用于 ±1 的网格、世界 y ∈ [-1,1] 又正好是一整屏：半高 = cardSize
        // ⇒ 牌总高 = cardSize × 屏高。**必须**与 TableScene.hitTest 的 `halfH = cardSize/2`
        // 同口径（那里是「半高占整屏」），否则牌比命中框小一半、看着总像缩水。
        val halfH = scene.cardSize
        val halfW = halfH * TableScene.CARD_ASPECT
        GLES20.glUseProgram(cardProgram)
        // 正/背面必须用**两个**纹理单元：旧实现把 uBack 和 uFace 都设成 0（同一张
        // 牌面纹理），于是 GL 牌桌上的「背面」一直被画成正面 —— 抽出来就该是牌背的
        // 语义在渲染层根本没落地。
        GLES20.glUniform1i(cardUFace, 0)
        GLES20.glUniform1i(cardUBack, 1)
        GLES20.glUniform1f(cardUTime, t)
        GlUtil.bindMesh(cardAPos, cardAUv)
        items.forEachIndexed { index, item ->
            val cx = (item.x * 2f - 1f) * aspect
            val cy = 1f - item.y * 2f
            val angleRad = item.face * Math.PI.toFloat()
            // 翻到一半（face≈0.5）弯曲最强；正/背面静止时归零，牌是平的。
            val bend = kotlin.math.sin(angleRad).coerceAtLeast(0f)
            val c = kotlin.math.cos(angleRad)
            val hinge = halfW * HINGE_OFFSET
            val tiltDeg = TILT_DEG * bend

            Matrix.setIdentityM(model, 0)
            // **深度阶梯**：整场景开着 `GL_DEPTH_TEST`（`GL_LESS`），同 z 的片元是
            // 「先画的赢」—— 与画家算法（后画的在上）相反。堆叠的牌 z 完全相同，
            // 于是越早抽的盖在上面，翻面那张会被上层牌切成两半（用户看到的「穿模」）。
            // 给每张牌按绘制顺序加一段微小纵深，让深度顺序与绘制顺序一致。
            // 阶梯（0.0025/张）远小于翻面中牌边的真实纵深（±halfW≈0.066），
            // 不会削弱掀牌本身的立体感。
            val zStep = index * CARD_Z_STEP
            // **翻面 = 拿到眼前翻**（对齐 Compose 版 `CardReveal` 的三段式：飞到眼前 → 翻 → 落回）。
            //
            // 抬升走 [flipRise]（flipT 的包络）而**不是** bend=sin(face·π)：后者的峰值永远
            // 落在翻到一半 —— 那时牌正横向压成一条线，推进多少都看不出来，观感就是「原地
            // 打个转」。改成「先飞到位、再翻、再落回」之后，翻的整个过程都在放大状态下进行。
            //
            // 位置与大小都由同一条包络驱动：rise=1 时牌心正好落在**屏幕中心**（世界原点），
            // 同时推进到 [FLIP_Z_LIFT]。放大是透视投影算出来的（EYE_Z=4.6 → 距离 2.55 约
            // 1.8 倍），牌边带近大远小的梯形，比线性 scale 更像真的被拿到眼前。
            //
            // 这段纵深同时根治了「堆叠翻面穿模」：牌绕 hinge 轴转时有一侧会往屏幕里走
            // （-hinge·sinθ ≈ -0.056），抬近之后整张牌稳稳压在所有牌之前。
            val rise = flipRise(item.flipT, item.fallT)
            val cardX = cx * (1f - rise)
            // 夹住推进上限：超过就会撞 NEAR 平面被近裁剪面切掉（见 MAX_CARD_Z）。
            val cardZ = (item.z * 1.6f + item.lift * 1.2f + zStep + rise * FLIP_Z_LIFT)
                .coerceAtMost(MAX_CARD_Z)
            // 「拿到眼前」不只是居中放大，还要整体上移（[GlFlipTiming.PEAK_RAISE_FRACTION]）
            // —— 居中时牌名会压在屏幕中线以下、下半屏空着，上移后组合落在视觉重心上。
            // 上移量按 rise 加权：牌升起来的同一拍里往上飘，落回时同步沉回去。
            val cardY = cy * (1f - rise) + item.z * 0.9f + item.lift * 0.55f +
                rise * raiseYAt(cardZ)
            Matrix.translateM(model, 0, cardX, cardY, cardZ)
            // 掀牌轴：绕「偏左的竖轴」转（hinge ≈ 左侧边缘），不是绕牌心 —— 绕牌心是旋转门。
            // 但纯绕边缘转会让牌心横移一整张牌宽（180° 后整张跑到左边），所以按
            // hinge×(1−cosθ) 补回来：牌心始终钉死原位，只留下「一侧进、一侧出」的纵深变化，
            // 这才是手捏住牌边把牌掀起来的样子。
            Matrix.translateM(model, 0, hinge * (1f - c), 0f, 0f)
            Matrix.translateM(model, 0, -hinge, 0f, 0f)
            Matrix.setIdentityM(rot, 0)
            Matrix.rotateM(rot, 0, Math.toDegrees(angleRad.toDouble()).toFloat(), 0f, 1f, 0f)
            // 再叠 8~10° 的 X 倾斜：纯 Y 轴旋转像转门，加一点「上沿朝我倾」立刻有手掀的动势。
            Matrix.rotateM(rot, 0, tiltDeg, 1f, 0f, 0f)
            Matrix.multiplyMM(tmpM, 0, model, 0, rot, 0)
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, hinge, 0f, 0f)
            Matrix.scaleM(model, 0, halfW, halfH, 1f)
            Matrix.multiplyMM(model, 0, tmpM, 0, model, 0)
            Matrix.multiplyMM(tmpM, 0, mvp, 0, model, 0)

            GLES20.glUniformMatrix4fv(cardUMvp, 1, false, tmpM, 0)
            GLES20.glUniform1f(cardUAngle, angleRad)
            GLES20.glUniform1f(cardUBend, bend)
            GLES20.glUniform1f(cardUGlow, item.glow)
            GLES20.glUniform1f(cardUReversed, if (item.reversed) 1f else 0f)

            // unit0 = 真实牌面（Coil 加载的 720×1200），unit1 = 牌背资源。
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, faceTextures[item.cardId] ?: backTexture)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTexture)

            GlUtil.drawMesh()
        }
    }

    /**
     * 翻面抬升包络（0..1）：两段独立时间轴相乘。
     *
     * **上段**（`t = flipT`）：前 [FLIP_RISE_IN] 把牌推到眼前，之后恒为 1 —— 牌停在
     * 眼前，直到翻面做完。
     *
     * **下段**（`fallT`）：落回是**独立阶段**，`flipT` 走完之后才开始。用 smoothstep
     * 反向（`1 - 3f²+2f³`）而不是 `cos`：`cos` 末段变化率最大，牌会「啪」一下撞回
     * 桌面；smoothstep 两端都平，落回收得住。
     *
     * 为什么用 `flipT`（线性时间轴）而不是 `face`（缓动后的角度）：用户的直觉是
     * 「按住牌，把它拿到眼前翻一下再放下」—— 抬起必须**先于**翻面完成。而任何
     * 基于角度的曲线（`sin(face·π)`）峰值都在 90°，那一刻牌正好侧立成一条线，
     * 抬多少都浪费。
     */
    private fun flipRise(t: Float, fallT: Float): Float {
        if (t <= 0f) return 0f
        val up = if (t < FLIP_RISE_IN) {
            kotlin.math.sin(t / FLIP_RISE_IN * (Math.PI.toFloat() / 2f))
        } else {
            1f
        }
        if (fallT >= 1f) return 0f
        val k = fallT.coerceIn(0f, 1f)
        return up * (1f - k * k * (3f - 2f * k))
    }

    /**
     * 「拿到眼前时整体上移」在纵深 [z] 处对应多少**世界 y**。
     *
     * 不能用常数：牌在 `z≈2.0`（距相机 2.6）、阴影 / 光晕在 `z≈-0.15`（距相机 4.75），
     * 同样的世界位移投到屏幕上的像素差 1.8 倍 —— 一个数会让牌上移 20%、影子只上移
     * 11%，牌立刻「飘出自己的影子」。所以统一按目标屏幕比例反推：世界位移 =
     * NDC 偏移 × 距离 × nearTop（`nearTop = NEAR/EYE_Z`，与 [Matrix.frustumM] 同口径）。
     */
    private fun raiseYAt(z: Float): Float =
        GlFlipTiming.PEAK_RAISE_FRACTION * 2f * (EYE_Z - z) * (NEAR / EYE_Z)

    /** 揭示粒子爆发：翻面过半时从牌心迸发一束金色星辉（与抽卡引擎同一套 shader）。 */
    private fun drawBursts(items: List<TableScene.Item>, t: Float) {
        // 复用成员 List：`items.filter{}` 每帧 new 一个 List，是纯浪费。
        burstItems.clear()
        for (item in items) {
            if (item.burstAt >= 0f && t - item.burstAt < BURST_LIFE) burstItems.add(item)
        }
        if (burstItems.isEmpty()) return
        clearAttribs()
        val aspect = width.toFloat() / height
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(burstProgram)
        GLES20.glUniformMatrix4fv(burstUMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(burstUTime, t)
        GLES20.glUniform1f(burstULife, BURST_LIFE)
        GLES20.glUniform3f(burstUColor, 0.98f, 0.82f, 0.48f)
        GLES20.glEnableVertexAttribArray(burstADir)
        GLES20.glEnableVertexAttribArray(burstASpeed)
        GLES20.glEnableVertexAttribArray(burstAPhase)
        burstSeeds.position(0)
        GLES20.glVertexAttribPointer(burstADir, 2, GLES20.GL_FLOAT, false, 16, burstSeeds)
        burstSeeds.position(2)
        GLES20.glVertexAttribPointer(burstASpeed, 1, GLES20.GL_FLOAT, false, 16, burstSeeds)
        burstSeeds.position(3)
        GLES20.glVertexAttribPointer(burstAPhase, 1, GLES20.GL_FLOAT, false, 16, burstSeeds)
        for (item in burstItems) {
            // 翻面的牌被拿到屏幕中央并推到眼前：粒子必须打在它身上、还要站得比它更靠前，
            // 否则整束星辉会被放大后的牌面挡住，一个点都看不见（等于白做）。
            val rise = flipRise(item.flipT, item.fallT)
            val burstZ = rise * FLIP_Z_LIFT + 0.10f
            GLES20.glUniform2f(
                burstUCenter,
                (item.x * 2f - 1f) * aspect * (1f - rise),
                // 粒子从牌心迸发：上移量按粒子自己的纵深（站在牌前面一点）换算。
                (1f - item.y * 2f) * (1f - rise) + rise * raiseYAt(burstZ),
            )
            GLES20.glUniform1f(burstUZ, burstZ)
            // 点大小是像素单位、不随深度缩放，所以牌放大多少倍，粒子就乘多少。
            GLES20.glUniform1f(burstUScale, 1f + rise * 0.85f)
            GLES20.glUniform1f(burstUBirth, item.burstAt)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, BURST_POOL)
        }
        GLES20.glDisableVertexAttribArray(burstADir)
        GLES20.glDisableVertexAttribArray(burstASpeed)
        GLES20.glDisableVertexAttribArray(burstAPhase)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    /* --------------------------------------------------- 纹理 */

    /**
     * 牌面纹理：向 [faceLoader] 要（去重），回填后由 GL 线程下一帧上传。
     * 降采样到 [GlConfig.maxFaceTextureWidth] 宽。
     */
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

    /**
     * 每帧最多上传**一张**牌面。
     *
     * 旧实现一帧把 pendingBitmaps 全传完：一张 720×1200 牌面 = `createScaledBitmap`
     * （CPU 重采样 3~5ms）+ `texImage2D` 传 3.5MB（2~4ms），一次翻 3 张就是 20ms+，
     * 必然突破 16.6ms 掉帧 —— 这才是「揭示那一下卡」的根因，跟 GL 性能无关。
     *
     * 摊到帧上后单帧只多 ~5ms；代价是最多晚 2~3 帧（<50ms）看到牌面，期间仍显示牌背，
     * 而翻面本身要 0.78s，完全看不出来。
     */
    private fun uploadPending() {
        if (backTexture == 0) {
            // 牌背晚于 surface 创建才到位（或首帧缺资源）时补上传。
            backBitmap?.let { backTexture = uploadBitmap(it) }
        }
        if (pendingBitmaps.isEmpty()) return
        val it = pendingBitmaps.entries.iterator()
        if (!it.hasNext()) return
        val e = it.next()
        val tex = uploadBitmap(e.value)
        if (tex != 0) faceTextures[e.key] = tex
        it.remove()
    }

    private fun uploadBitmap(bmp: Bitmap): Int {
        // 正常路径里 faceLoader 已按 maxFaceTextureWidth 解码（Coil size），这里只是兜底：
        // 万一调用方塞了原图，也别把「CPU 重采样」留在 GL 线程的热路径上。
        val scaled = if (bmp.width > config.maxFaceTextureWidth) {
            Bitmap.createScaledBitmap(
                bmp,
                config.maxFaceTextureWidth,
                (bmp.height * (config.maxFaceTextureWidth.toFloat() / bmp.width)).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bmp
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        if (tex[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaled, 0)
        // 缩小采样必须靠 mipmap，否则一张 512 宽纹理画到 100dp（≈275px）时是 1/2~1/6 的
        // 点采样，牌面细节会「沙沙」闪。512×853 是 NPOT：ES 2.0 不允许 NPOT 生成 mipmap，
        // 所以 GLSurfaceHost 优先申请 ES 3.0 上下文；万一失败（老机器）这里会拿到错误，
        // 老老实实退回 GL_LINEAR，绝不留下「纹理不完整 → 整张牌变黑」的坑。
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        val mipOk = GLES20.glGetError() == GLES20.GL_NO_ERROR
        if (!mipLogged) {
            mipLogged = true
            // 现场排查「牌面缩小后发糊/闪」时，先看这一行：false = 这台设备退回了 GL_LINEAR。
            android.util.Log.i("GlTable", "face mipmap supported = $mipOk")
        }
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            if (mipOk) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR,
        )
        return tex[0]
    }

    /* --------------------------------------------------- 几何与工具 */

    private fun bindQuad(posHandle: Int, uvHandle: Int) {
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, quadPos)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 8, quadUv)
    }

    private fun location(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    private fun attrib(program: Int, name: String): Int = GLES20.glGetAttribLocation(program, name)

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
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

    companion object {
        /** 相机距离与 near 平面（投影校准见 [onSurfaceChanged]）。 */
        const val EYE_Z = 4.6f
        const val NEAR = 2.2f

        /** 单张牌揭示爆发的粒子数与存活时长。 */
        const val BURST_POOL = 90
        const val BURST_LIFE = 0.85f

        /** 掀牌轴位置（占半宽的比例）：0.85 ≈ 贴左侧边缘。0 = 绕牌心（旋转门，别用）。 */
        const val HINGE_OFFSET = 0.85f

        /** 翻面时叠加的 X 轴倾斜峰值（度）：翻到一半最大，静止归零。 */
        const val TILT_DEG = 10f

        /**
         * 每张牌之间的深度阶梯（世界单位），按绘制顺序递增。
         *
         * 为什么要它：`GL_DEPTH_TEST` 用 `GL_LESS`，**同深度时先画的片元赢** ——
         * 与画家算法（后画的在上）正好相反。堆叠的牌 z 一样，不区分就会「早抽的
         * 压住后抽的」，翻面那张被切一刀。0.0025 在 EYE_Z=4.6 处约合 20 个深度
         * 单位（16 位缓冲），远大于精度下限；相对牌边翻面时的 ±0.066 纵深又只是
         * 零头，不影响掀牌的立体感。
         */
        const val CARD_Z_STEP = 0.0025f

        /**
         * 翻面时整张牌朝相机推进的距离（世界单位），按 [flipRise] 包络加权。
         *
         * 透视投影下这就等于「拿到眼前翻」：EYE_Z=4.6，推进 2.0 后距离 2.6，
         * 牌在屏上放大约 **1.8 倍**，同时带近大远小的梯形 —— 比乘一个线性 scale
         * 更像牌真的被举到你面前。翻完包络归零，牌滑回原位与原大小。
         *
         * 这段纵深顺带根治了「堆叠翻面穿模」：牌绕 hinge 轴转时有一侧会往屏幕里走
         * （-hinge·sinθ ≈ -0.056），抬近之后整张牌稳稳压在所有牌之前。
         *
         * 放大量受 [MAX_CARD_Z] 约束 —— 再往前就撞 NEAR 平面，牌会被近裁剪面切掉一角。
         */
        const val FLIP_Z_LIFT = 2.0f

        /**
         * 牌心能到达的最大纵深（世界单位）。
         *
         * 相机的近裁剪面在 `NEAR=2.2`、相机在 `EYE_Z=4.6`：纵深超过 2.4 就会有片元
         * 被裁掉。留 0.35 余量（牌本身绕 hinge 转时还有 ±0.056 的纵深、堆叠阶梯也有
         * 零点零几），取 2.05 —— 对应屏幕放大约 1.8 倍，也是翻面拿起来的视觉上限。
         */
        const val MAX_CARD_Z = 2.05f

        /**
         * 抬升包络的**上升段**时长（占翻面时间轴的比例），之后牌一直停在眼前。
         *
         * 上升段不只是抬高：牌同时从原位**滑到屏幕中心**。所以给它整整 28% 的时间，
         * 起步用 `sin` 的软加速，不会出现「牌唰一下窜到中间」。
         *
         * 下落段不在这里 —— 它已经独立成 [TableScene.FLIP_FALL_DURATION] 那条
         * 独立时间轴（落在 flipT 尾部会和翻面尾巴重叠，时长也没法单独调）。
         */
        const val FLIP_RISE_IN = 0.28f

        /** 单位 quad：两个三角带的顶点序列。 */
        private val QUAD_POS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_UV = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private val startedAt = System.nanoTime()

        fun buildParticleSeeds(seed: Long = 0L, count: Int = 0): FloatArray {
            val rnd = Random(seed)
            val data = FloatArray(count * 3)
            for (i in data.indices step 3) {
                data[i] = rnd.nextFloat()
                data[i + 1] = rnd.nextFloat()
                data[i + 2] = rnd.nextFloat()
            }
            return data
        }

        private fun wrap(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
