package com.arcana.gl

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.arcana.carddeck.Pick
import com.arcana.carddeck.Scene
import kotlin.math.abs

/**
 * 场景的 GL 宿主 View：只做「触摸 → 命中/拖动」的翻译，不出现任何业务类型。
 *
 * 透明叠层三件套（缺一不可）：EGL 配置带 alpha（[MsaaConfigChooser]）、
 * [PixelFormat.TRANSLUCENT]、[setZOrderOnTop] —— 最后一步让 SurfaceView 的"洞"
 * 之上仍能合成普通 Compose 内容，否则透明像素露出的是窗口白底。
 */
class SpriteSurfaceHost(
    context: Context,
    internal val renderer: SceneRenderer,
    private val scene: Scene,
) : GLSurfaceView(context) {

    /** 点中某个图元（未发生拖动时）。 */
    var onPick: ((Pick) -> Unit)? = null

    /** 拖动命中的图元：给出初始命中结果 + 手指当前归一化坐标。 */
    var onDrag: ((Pick, Float, Float) -> Unit)? = null

    /** 是否接受手势。 */
    var gesturesEnabled: Boolean = true

    private val density = context.resources.displayMetrics.density
    private val dragSlop = 8f * density

    private var downX = 0f
    private var downY = 0f
    private var activePick: Pick? = null
    private var dragging = false

    init {
        setEGLContextClientVersion(glVersion(context))
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(renderer)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        // 流光、星辉、翻面都是常动的；按需渲染会让它们定格。
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                activePick = scene.hitTest(event.x, event.y, width.toFloat(), height.toFloat())
                return activePick != null || onDrag != null
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(event.x - downX) > dragSlop || abs(event.y - downY) > dragSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val pick = activePick
                    if (pick != null) {
                        onDrag?.invoke(pick, event.x / width, event.y / height)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) activePick?.let { onPick?.invoke(it) }
                activePick = null
                dragging = false
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePick = null
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
