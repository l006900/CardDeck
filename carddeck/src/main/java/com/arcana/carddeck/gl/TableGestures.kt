package com.arcana.carddeck.gl

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 牌桌手势机 —— 从 [GLSurfaceHost] 抽出来的公共部分，两个宿主共用同一套，
 * 保证「长按才拖、单击翻面、双指缩放、点空白收面板」的手感一字不差。
 *
 * 为什么不复制一份到合成宿主里：手势是这个产品最敏感的一块（长按 500ms 闸门、
 * 拖完抬手不能再补一次翻面），两份实现早晚会漂移成两种手感。
 *
 * **坐标归属**：所有回调都把「视图像素」换算成「引擎归一化坐标」后才交给引擎。
 * 默认换算是「除以视图宽高」（单路铺满整个 View）；将来一屏拼多路时，
 * 由宿主传入按 tile 矩形反算的两个 lambda，手势机本身不认识布局。
 */
internal class TableGestures(
    private val view: View,
    private val engine: GlEngine,
    private val density: Float,
    /** 视图像素 x → 引擎归一化 x（默认 = px / 视图宽）。 */
    private val toNormX: (px: Float) -> Float = { px ->
        val w = view.width.toFloat()
        if (w > 0f) px / w else 0f
    },
    /** 视图像素 y → 引擎归一化 y（默认 = py / 视图高）。 */
    private val toNormY: (py: Float) -> Float = { py ->
        val h = view.height.toFloat()
        if (h > 0f) py / h else 0f
    },
    /** 交给 [GlEngine.hitTest] 的视口尺寸（默认 = 视图宽高）。 */
    private val hitTestW: () -> Float = { view.width.toFloat() },
    private val hitTestH: () -> Float = { view.height.toFloat() },
) {

    /** 缩放回调：传相对倍率（1.2 / 1÷1.2），宿主不解释语义。 */
    var onZoom: ((factor: Float) -> Unit)? = null

    /** 单击空白处。 */
    var onTableTapped: (() -> Unit)? = null

    /** 已确认可以拖动的 uid；null = 这轮手势没升格成拖动。 */
    private var draggingUid: Long? = null

    /** DOWN 时命中的牌：先当潜在点击/长按对象，长按到点才升格成拖动。 */
    private var downUid: Long? = null
    private var downX = 0f
    private var downY = 0f

    /** 本轮手势是否已升格成拖动（= 长按到点了）。 */
    private var dragConfirmed = false

    /** 本轮手势不该再被当成单击：拖过牌，或者长按前就挪出去过。 */
    private var suppressNextTap = false

    /** 长按计时器；[cancelLongPressTimer] 会撤掉。 */
    private var longPress: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 系统触摸斜率（dp 已按密度换算成 px）。拖动/点击的分界线用它，别自己拍数字。 */
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop

    /** 长按闸门时长。取系统值（默认就是 500ms），不另拍一个数字。 */
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private fun hit(px: Float, py: Float): Long? =
        engine.hitTest(toNormX(px), toNormY(py), hitTestW(), hitTestH(), density)

    private val tapDetector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 刚拖完牌就抬手时，GestureDetector 仍可能补一次单击确认（它的位移判定
                // 与下面按 touchSlop 的判定不完全同步）—— 不拦就是「拖完顺手把牌翻了」。
                if (suppressNextTap) return true
                // 命中顺序：牌 → 空白。
                val uid = hit(e.x, e.y)
                if (uid != null) {
                    engine.tapCard(uid)
                    return true
                }
                onTableTapped?.invoke()
                return true
            }

            // 长按由本类自己计时（见 onTouchEvent 的 ACTION_DOWN）：
            // 这里必须留空，否则 GestureDetector 的长按与我们的计时会各算一套。
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (abs(factor - 1f) > 0.01f) onZoom?.invoke(factor)
                return true
            }
        },
    ).apply {
        // 关掉 quick scale：它默认开启（双击后拖动即缩放），而「双击同一张牌」是
        // 翻面/连续操作里很自然的动作 —— 手一抖就把牌桌缩放了。缩放只留两根手指捏合。
        isQuickScaleEnabled = false
    }

    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(event: MotionEvent): Boolean {
        // 两个 detector 都要吃**完整的事件流**（DOWN/MOVE/UP/CANCEL）：
        // GestureDetector 只有看到 UP 才会确认单击 —— 之前只喂 DOWN，
        // onSingleTapConfirmed 永远不触发，「点牌翻面」整个失灵。
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        // 双指时中断一切单指手势（用户意图切到缩放了）。
        if (event.pointerCount > 1) {
            cancelLongPressTimer()
            if (dragConfirmed) {
                draggingUid?.let { engine.dragEnd(it, moved = true) }
                draggingUid = null
                dragConfirmed = false
                suppressNextTap = true
            }
            downUid = null
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragConfirmed = false
                suppressNextTap = false
                draggingUid = null
                cancelLongPressTimer()
                // **按下不立刻 dragStart**：先当作潜在点击，长按到点才升格成拖动。
                // 若按下就拖，任何一次点按带的手抖都会让牌在指尖下滑一下再翻面。
                downUid = hit(event.x, event.y)
                downUid?.let { uid ->
                    val r = Runnable { beginDrag(uid) }
                    longPress = r
                    handler.postDelayed(r, longPressTimeout)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val uid = downUid ?: return true
                if (!dragConfirmed) {
                    // 还没到长按时间就挪出去 = 不是拖动：撤掉计时，这一下作废
                    // （既不拖牌，也不让抬手时触发翻面）。
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        cancelLongPressTimer()
                        downUid = null
                        suppressNextTap = true
                    }
                    return true
                }
                if (event.pointerCount == 1) {
                    engine.dragMove(uid, toNormX(event.x), toNormY(event.y))
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressTimer()
                val uid = draggingUid
                val wasDrag = dragConfirmed
                draggingUid = null
                downUid = null
                dragConfirmed = false
                // 拖动过（或长按前就挪出去过）就不算点击；真正没动的那一下留给 tapDetector。
                if (wasDrag) suppressNextTap = true
                if (uid != null && wasDrag) engine.dragEnd(uid, moved = true)
                return true
            }
        }
        return true
    }

    /**
     * 长按到点：确认还按在同一张牌上，升格成拖动。
     *
     * 用系统长按时长而不是「位移阈值」，是因为牌同时承载点击（翻面）与拖动两种语义，
     * 时间闸门比空间闸门更能表达用户意图：按住不放 = 想挪它，轻碰一下 = 想翻它。
     */
    private fun beginDrag(uid: Long) {
        if (downUid != uid || dragConfirmed) return
        dragConfirmed = true
        draggingUid = uid
        suppressNextTap = true
        engine.dragStart(uid)
        // 长按确认的触觉反馈：没有它，牌会在按满 0.5s 后毫无征兆地开始跟手。
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** 视图被移除 / 页面退出时调用，避免挂着的长按计时器在下一轮触发。 */
    fun cancel() {
        cancelLongPressTimer()
        downUid = null
        draggingUid = null
        dragConfirmed = false
    }

    private fun cancelLongPressTimer() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }
}
