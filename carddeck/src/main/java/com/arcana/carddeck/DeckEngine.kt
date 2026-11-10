package com.arcana.carddeck

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import com.arcana.gfx.GlUtil

/**
 * 扇形牌堆的渲染契约 —— 与 [GlEngine]（自由桌面）/ [DrawController]（发牌序列）**并列**的第三种引擎。
 *
 * 为什么独立：`GLSurfaceHost` 的手势是「长按拖单牌 + 捏合缩放」，牌堆要的是
 * 「按下即横向转环 + 点选 + 松手吸附」；渲染上桌面牌需要 16×16 网格弯曲与正逆位，
 * 牌堆只需要牌背。硬塞进 TableEngine 会把两套语义缠在一起。
 *
 * 纪律同 `core/gl` 五规矩：接口只出现 Float / Int / Boolean / [Bitmap] / [DeckGeometry.Rect]，
 * 不出现任何 App 侧类型；图片解码由调用方完成后注入。
 */
interface DeckController : GLSurfaceView.Renderer {

    /** 整副牌（已抽走的由调用方过滤掉）。顺序即环上的顺序。 */
    fun submit(cardIds: List<String>)

    /** 牌背纹理源（调用方解码好传入；null 时用纯色占位）。回调可在任意线程。 */
    fun setBack(bitmap: Bitmap?)

    /** 背景渐变色 ARGB：inner = 光心，outer = 边缘。 */
    fun setCloth(inner: Int, outer: Int)

    /** 抽满 / 收尾时置 false：环还在，但不再接受手势，视觉也压暗一点。 */
    fun setEnabled(value: Boolean)

    /**
     * 是否绘制铺满全屏的渐变底色。
     *
     * true（默认）= 独立使用时自带深色底（demo / 占位）；
     * false = 透明背景，叠在别的 GL 层（如自由抽牌的桌面）之上时，让下层透出来，
     * 不自己再画一块黑底。
     */
    fun setBackgroundEnabled(enabled: Boolean)

    /** 环的旋转角（度，0 = 第一张在正上方）。拖动时由宿主逐帧写入。 */
    fun setRotation(degrees: Float)

    fun rotation(): Float

    /** 松手：吸附到最近的牌位，动画在 GL 线程推进。 */
    fun snap()

    /** 剩余牌数。 */
    fun count(): Int

    /** 命中测试：触点在**控件内**的 px 坐标，返回牌下标，null = 点在缝里。 */
    fun hitTest(x: Float, y: Float): Int?

    /** 第 [index] 张牌此刻在根坐标系的几何（px）。过场从这里起飞。 */
    fun revealRect(index: Int, originX: Float, originY: Float): DeckGeometry.Rect?
}

/** 默认实现。GL 调用只在 GL 线程；UI 线程只写 @Volatile 字段或替换不可变引用。 */
class DeckEngine(
    private val density: Float,
    private val gold: FloatArray = floatArrayOf(0.98f, 0.82f, 0.46f),
) : DeckController {

    private val lock = Any()

    @Volatile private var cardIds: List<String> = emptyList()
    @Volatile private var rot = 0f
    @Volatile private var enabled = true
    @Volatile private var backgroundEnabled = true
    @Volatile private var clothInner = 0xFF1B2140.toInt()
    @Volatile private var clothOuter = 0xFF070A14.toInt()
    @Volatile private var pendingBack: Bitmap? = null
    /** 最后一次注入的牌背，GL context 重建后要重传（pendingBack 是一次性的）。 */
    @Volatile private var lastBack: Bitmap? = null
    @Volatile private var needsSnap = false

    /** 吸附动画状态，只在 GL 线程读写。 */
    private var snapping = false
    private var snapFrom = 0f
    private var snapTo = 0f
    private var snapElapsed = 0f

    private var width = 1
    private var height = 1
    private var layout: DeckGeometry.Layout? = null
    private var startedAt = 0L
    private var lastFrameNanos = 0L

    private var backTexture = 0
    private var tableProgram = 0
    private var deckProgram = 0
    private var allAttribs = intArrayOf()

    private val proj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private var tableUMvp = 0
    private var tableURes = 0
    private var tableUInner = 0
    private var tableUOuter = 0
    private var tableAPos = 0
    private var tableAUv = 0

    private var deckUMvp = 0
    private var deckUBack = 0
    private var deckUTime = 0
    private var deckUAngle = 0
    private var deckUSelected = 0
    private var deckUAspect = 0
    private var deckUGold = 0
    private var deckAPos = 0
    private var deckAUv = 0

    /* ---------------- UI 线程入口 ---------------- */

    override fun submit(cardIds: List<String>) {
        this.cardIds = cardIds
        // 牌数变了步长就变了，rot 停在旧步长的整数倍上不再对齐牌位。
        // 这里只打标记：layout 要等下一帧在 GL 线程按新牌数重建后才能算正确的 stepAngle。
        needsSnap = true
    }

    override fun setBack(bitmap: Bitmap?) {
        lastBack = bitmap
        pendingBack = bitmap ?: fallbackBitmap()
    }

    override fun setCloth(inner: Int, outer: Int) {
        clothInner = inner
        clothOuter = outer
    }

    override fun setEnabled(value: Boolean) {
        enabled = value
    }

    override fun setBackgroundEnabled(enabled: Boolean) {
        backgroundEnabled = enabled
    }

    override fun setRotation(degrees: Float) {
        snapping = false
        rot = degrees
    }

    override fun rotation(): Float = rot

    override fun snap() {
        val l = layout ?: return
        snapFrom = rot
        snapTo = DeckGeometry.snapTarget(rot, l.stepAngle)
        snapElapsed = 0f
        snapping = abs(snapTo - rot) > 0.01f
    }

    override fun count(): Int = cardIds.size

    override fun hitTest(x: Float, y: Float): Int? {
        val l = layout ?: return null
        return DeckGeometry.hitTestIndex(l, rot, x, y)
    }

    override fun revealRect(index: Int, originX: Float, originY: Float): DeckGeometry.Rect? {
        val l = layout ?: return null
        if (index !in cardIds.indices) return null
        return DeckGeometry.revealRect(l, rot, index, originX, originY)
    }

    /* ---------------- GL 线程 ---------------- */

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.027f, 0.035f, 0.063f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        tableProgram = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.TABLE_FRAGMENT)
        deckProgram = GlUtil.buildProgram(GlShaders.PLAIN_VERTEX, GlShaders.DECK_FRAGMENT)

        tableUMvp = GlUtil.location(tableProgram, "uMvp")
        tableURes = GlUtil.location(tableProgram, "uResolution")
        tableUInner = GlUtil.location(tableProgram, "uInner")
        tableUOuter = GlUtil.location(tableProgram, "uOuter")
        tableAPos = GlUtil.attrib(tableProgram, "aPos")
        tableAUv = GlUtil.attrib(tableProgram, "aUv")

        deckUMvp = GlUtil.location(deckProgram, "uMvp")
        deckUBack = GlUtil.location(deckProgram, "uBack")
        deckUTime = GlUtil.location(deckProgram, "uTime")
        deckUAngle = GlUtil.location(deckProgram, "uAngle")
        deckUSelected = GlUtil.location(deckProgram, "uSelected")
        deckUAspect = GlUtil.location(deckProgram, "uAspect")
        deckUGold = GlUtil.location(deckProgram, "uGold")
        deckAPos = GlUtil.attrib(deckProgram, "aPos")
        deckAUv = GlUtil.attrib(deckProgram, "aUv")

        // quad(4 点) 与其余 pass 的属性数组互不干扰：每帧开头统一关一遍。
        allAttribs = intArrayOf(tableAPos, tableAUv, deckAPos, deckAUv)
            .filter { it >= 0 }.distinct().toIntArray()

        GLES20.glUseProgram(deckProgram)
        GLES20.glUniform1i(deckUBack, 0)

        startedAt = System.nanoTime()
        lastFrameNanos = 0L
        // context 重建后旧纹理 id 失效：先垫一张纯色，再把最后一次注入的牌背排队重传。
        backTexture = GlUtil.uploadBitmap(fallbackBitmap(), 512)
        pendingBack = lastBack ?: fallbackBitmap()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        // px 坐标系、y 向下：bottom=h / top=0 把 GL 的 +Y 翻成屏幕的 -Y，
        // 于是 DeckGeometry 算出来的 px 坐标可以直接喂给模型矩阵，不做二次换算。
        Matrix.orthoM(proj, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
        layout = DeckGeometry.layout(
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            cardWidthPx = DeckGeometry.CARD_WIDTH_DP * density,
            radiusCapPx = DeckGeometry.RADIUS_CAP_DP * density,
            count = cardIds.size,
        )
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.0005f, 1f / 30f)
        lastFrameNanos = now
        val t = (now - startedAt) / 1_000_000_000f

        if (snapping) {
            snapElapsed += dt
            val k = (snapElapsed / (DeckGeometry.SNAP_MS / 1000f)).coerceAtMost(1f)
            val ease = 1f - (1f - k) * (1f - k) * (1f - k)
            rot = snapFrom + (snapTo - snapFrom) * ease
            if (k >= 1f) {
                rot = snapTo
                snapping = false
            }
        }

        uploadPendingBack()

        // 牌数可能在 UI 线程被改过：layout 的 stepAngle 要按新牌数在 GL 线程重建，
        // 重建完才吸附，否则用的是旧步长，落点会差半张牌。
        val ids = cardIds
        if (layout?.count != ids.size) onSurfaceChanged(null, width, height)
        val l = layout ?: return
        if (needsSnap) {
            needsSnap = false
            snap()
        }

        // 背景开关：开着（独立使用）才铺渐变底；关着（叠在别的 GL 层上）清成透明，
        // 让下层透出来，而不是自己再画一块黑底。
        if (backgroundEnabled) {
            GLES20.glClearColor(0.027f, 0.035f, 0.063f, 1f)
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        clearAttribs()

        if (backgroundEnabled) drawTable()
        drawCards(l, t)
    }

    private fun uploadPendingBack() {
        val bmp = pendingBack ?: return
        pendingBack = null
        backTexture = GlUtil.uploadBitmap(bmp, 512)
    }

    private fun clearAttribs() {
        for (a in allAttribs) GLES20.glDisableVertexAttribArray(a)
    }

    private fun drawTable() {
        GLES20.glUseProgram(tableProgram)
        GLES20.glUniformMatrix4fv(tableUMvp, 1, false, proj, 0)
        GLES20.glUniform2f(tableURes, width.toFloat(), height.toFloat())
        val inner = GlUtil.argbToRgb(clothInner)
        val outer = GlUtil.argbToRgb(clothOuter)
        GLES20.glUniform3f(tableUInner, inner[0], inner[1], inner[2])
        GLES20.glUniform3f(tableUOuter, outer[0], outer[1], outer[2])
        GlUtil.bindQuad(tableAPos, tableAUv)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawCards(l: DeckGeometry.Layout, t: Float) {
        GLES20.glUseProgram(deckProgram)
        GLES20.glUniform1f(deckUTime, t)
        GLES20.glUniform1f(deckUAspect, DeckGeometry.CARD_ASPECT)
        GLES20.glUniform3f(deckUGold, gold[0], gold[1], gold[2])
        GLES20.glUniform1i(deckUBack, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backTexture)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val sel = DeckGeometry.selectedIndex(rot, l.stepAngle, l.count)
        GlUtil.bindQuad(deckAPos, deckAUv)
        // 全部按扇面顺序正常层叠：左牌在下、右牌在上，正上方那张落在中段、被右侧邻牌盖住 ——
        // 牌是「堆」出来的，不是某一张浮到最上。选中牌仍落在自然画序里，只把它略微放大(1.25) +
        // 金边略提亮来标识，绝不再像早期版本那样置顶浮起。
        for (index in DeckGeometry.visibleIndices(l, rot)) {
            val isSel = index == sel
            drawOneCard(
                l, index,
                if (isSel) DeckGeometry.SELECTED_SCALE else 1f,
                if (isSel) 1f else 0f,
            )
        }
    }

    /** 画单张牌：把牌背纹理按几何摆到环上。selected 控制金边描边亮度。 */
    private fun drawOneCard(l: DeckGeometry.Layout, index: Int, scale: Float, selected: Float) {
        val angle = DeckGeometry.angleOf(l, rot, index)
        val p = DeckGeometry.centerOf(l, rot, index)
        GLES20.glUniformMatrix4fv(
            deckUMvp, 1, false,
            matrixAt(p.x, p.y, angle, l.cardW / 2f, l.cardH / 2f, scale), 0,
        )
        GLES20.glUniform1f(deckUAngle, angle * 0.017453292f)
        GLES20.glUniform1f(deckUSelected, selected)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * 单位 quad(-1..1) → 牌矩形。
     *
     * 角度语义与 [DeckGeometry] / Compose 端统一：`angle` = 牌在环上的极角，
     * 0°=正上方、顺时针为正。这个符号天然对应「牌顶朝外（向外散开）」——
     * 右侧牌 angle 为正 → 顺时针 → 顶朝右外；左侧牌 angle 为负 → 逆时针 → 顶朝左外。
     * 所以这里**不取负**，直接按 angle 旋转，GL 渲染才和 `RevealRect.rot`（Compose
     * `rotationZ`）一致。之前取了 `-angle`，把整副牌翻成「顶朝内（向内聚拢）」，
     * 正是用户说的「左牌右旋、右牌左旋」反了的根因。
     */
    private fun matrixAt(
        cx: Float, cy: Float, angleDeg: Float,
        halfW: Float, halfH: Float, scale: Float,
    ): FloatArray {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, cx, cy, 0f)
        Matrix.rotateM(model, 0, angleDeg, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, halfW * scale, halfH * scale, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, model, 0)
        return mvp
    }

    /** 牌背还没注入时的占位：纯深蓝，避免没纹理时整片黑。 */
    private fun fallbackBitmap(): Bitmap = Bitmap.createBitmap(
        2, 2, Bitmap.Config.ARGB_8888,
    ).apply { eraseColor(Color.rgb(22, 28, 48)) }
}
