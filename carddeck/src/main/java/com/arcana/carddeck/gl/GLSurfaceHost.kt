package com.arcana.carddeck.gl

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.arcana.gfx.MsaaConfigChooser

/**
 * GL 宿主视图：[GLSurfaceView] + 手势识别，对外只有两个回调（[onZoom] / [onTableTapped]），
 * 牌面交互全部经 [GlEngine] 手势桥转发 —— 宿主不认识任何业务。
 *
 * 手势实现见 [TableGestures]（与合成宿主 [com.arcana.carddeck.gl.composite.CompositeTableHost]
 * 共用同一套，保证两条渲染路径的手感不会漂移）。手势表：
 * ```
 * 单指长按 0.5s 后拖动   → GlEngine.dragStart / dragMove / dragEnd
 * 单击牌                 → GlEngine.tapCard（翻面）
 * 双指捏合               → onZoom（宿主只透传，缩放语义归调用方）
 * 单击空白处             → onTableTapped（收浮条 / 面板用）
 * ```
 *
 * **拖牌必须长按**：牌是能被随手点开的东西，如果按下即拖，任何一次点按都带着
 * 几像素的手抖，牌会在指尖下「滑」一下再翻面 —— 观感上像误操作。所以拖动
 * 用系统长按时长（[android.view.ViewConfiguration.getLongPressTimeout]，默认 500ms）做闸门。
 *
 * **与合成宿主的分工**：这个类把一个引擎直接挂给窗口，是「一条路直达屏幕」；
 * `CompositeTableHost` 让库的合成器当窗口主人，把引擎降级成一路画面源。业务代码只认
 * 两者的共同签名（engine / onZoom / onTableTapped），换宿主不改调用方。
 *
 * EGL 配置不对齐自己写：4× MSAA 的选择器在库里（[com.arcana.gfx.MsaaConfigChooser]），
 * 牌桌、牌堆、合成宿主三处共用同一份，谁也别再抄第四遍。
 */
class GLSurfaceHost(
    context: Context,
    val engine: GlEngine,
) : GLSurfaceView(context) {

    private val gestures = TableGestures(
        view = this,
        engine = engine,
        density = context.resources.displayMetrics.density,
    )

    /** 缩放回调：传相对倍率（1.2 / 1÷1.2），宿主不解释语义。 */
    var onZoom: ((factor: Float) -> Unit)?
        get() = gestures.onZoom
        set(value) { gestures.onZoom = value }

    /** 单击空白处。 */
    var onTableTapped: (() -> Unit)?
        get() = gestures.onTableTapped
        set(value) { gestures.onTableTapped = value }

    init {
        // ES 3.0 上下文（老机器回落 2.0）：唯一目的是给 NPOT 纹理生成 mipmap ——
        // 512×853 的牌面在 ES 2.0 里不允许 mipmap，缩小采样只能点采样，牌面会「沙沙」闪。
        // 着色器仍然是 GLSL ES 1.00（ES 3.0 兼容 1.00 shader），不用改一行 shader。
        setEGLContextClientVersion(detectGlVersion(context))
        // 4× MSAA：牌边在翻转/拖动时是斜线，没有多重采样就是一把锯齿。
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(GlRenderer(engine))
        // 保持连续渲染：星空粒子是常动的（缓慢上升 + 闪烁），
        // 换 RENDERMODE_WHEN_DIRTY 会让背景在静止时定格，那是产品层面的选择，不在这里偷偷改。
        // 真机手感的问题在「帧间隔被写死 1/60」，已在 TableRenderer 改成真实 delta。
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        gestures.cancel()
        super.onDetachedFromWindow()
    }
}

/** 设备支持 ES 3.x 就用 3，否则 2 —— 三个宿主共用同一判定。 */
internal fun detectGlVersion(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val es = am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
    return if (es >= 0x30000) 3 else 2
}
