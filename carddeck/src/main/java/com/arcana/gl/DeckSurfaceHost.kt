package com.arcana.gl

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.min

/**
 * 扇形牌堆的**专属宿主 View** —— 与 [GLSurfaceHost]（自由桌面）并列，互不复用。
 *
 * 手势语义完全不同，硬复用会把两套行为缠在一起：
 *  - 桌面：长按 500ms 才升格成拖单牌 + 捏合缩放 + 点空白收面板；
 *  - 牌堆：按下就能横向转整环，松手吸附到最近牌位，未拖动才算点选。
 *
 * 宿主只做「触摸 → 旋转/命中」的翻译，几何一律交给 [DeckGeometry]，
 * 渲染一律交给 [DeckController]；这里不出现任何业务类型。
 */
class DeckSurfaceHost(
    context: Context,
    val controller: DeckController,
) : GLSurfaceView(context) {

    /** 点中某张牌：给出环上下标 + 该牌此刻在根坐标系里的几何。 */
    var onPick: ((index: Int, rect: DeckGeometry.Rect) -> Unit)? = null

    /** 是否接受手势。抽满 / 收尾时置 false，环还在但不再响应。不能叫 enabled —— View 自己有 setEnabled()。 */
    var gesturesEnabled: Boolean = true

    /** 控件在根坐标系里的位置，由 Compose 侧 onGloballyPositioned 写入。 */
    var originX = 0f
    var originY = 0f

    private val density = context.resources.displayMetrics.density
    /** 与 Compose 版 CardWheel 的 4.dp 阈值逐字一致，两种控件手感不漂移。 */
    private val dragSlopPx = DeckGeometry.DRAG_SLOP_DP * density

    private var downX = 0f
    private var startRot = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(glVersion(context))
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(controller)
        // 半透明 surface：让牌堆清屏的 alpha（透明处 0、牌处 1）能和下层合成，
        // 而不是被当成不透明黑底。EGL 配置已带 EGL_ALPHA_SIZE=8。
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // SurfaceView 在窗口上会「挖一个洞」，透明像素露出的是**窗口底色**（默认白），
        // 而不是后面的 Compose 内容。把 surface 提到窗口之上，透明像素才会真正合成到
        // 普通 Compose/View 内容上 —— 这是「透明 GL 叠层」能成立的关键一步。
        setZOrderOnTop(true)
        // 流光与选中辉光都是常动的，按需渲染会让它们定格。
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * 拖动 ↔ 旋转的换算半径。必须与 [DeckGeometry.layout] 用的同一套公式
     * （min(宽×1.2, 460dp)），否则手指划过的弧长和画面转的角度对不上。
     */
    private fun radiusPx(): Float = min(
        width.toFloat() * 1.2f,
        DeckGeometry.RADIUS_CAP_DP * density,
    ).coerceAtLeast(1f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled || controller.count() == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                startRot = controller.rotation()
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                // 手抖闸门：没过阈值就还是"可能的点击"，不让牌堆跟着抖一下。
                if (!dragging && abs(dx) > dragSlopPx) dragging = true
                if (dragging) {
                    controller.setRotation(
                        DeckGeometry.rotationFromDrag(startRot, dx, radiusPx()),
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    controller.snap()
                } else {
                    val index = controller.hitTest(event.x, event.y)
                    if (index != null) {
                        val rect = controller.revealRect(index, originX, originY)
                        if (rect != null) onPick?.invoke(index, rect)
                    }
                }
                dragging = false
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) controller.snap()
                dragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun glVersion(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val es = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        return if (es >= 0x30000) 3 else 2
    }
}
