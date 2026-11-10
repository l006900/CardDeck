package com.arcana.carddeck.gl.composite

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.arcana.carddeck.gl.GlEngine
import com.arcana.carddeck.gl.TableGestures
import com.arcana.carddeck.gl.detectGlVersion
import com.arcana.gfx.CompositeSurfaceHost
import com.arcana.gfx.GpuCompositor
import com.arcana.gfx.MsaaConfigChooser
import com.arcana.gfx.RenderPolicy
import com.arcana.gfx.TileSpec
import com.arcana.gfx.Tiles
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 把一个 [GlEngine] 放进库的合成管线里渲染的宿主视图。
 *
 * 对业务是 [com.arcana.carddeck.gl.GLSurfaceHost] 的**等价替换**：同样的构造参数、同样的
 * [engine] / [onZoom] / [onTableTapped]，连手势都由同一个 [TableGestures] 驱动，
 * 所以调用方换过来只改一个类名。
 *
 * ## 为什么不直接用库的 [CompositeSurfaceHost]
 *
 * 它有两处与本项目冲突，都是硬伤，不是偏好：
 *
 * 1. **它硬编码 ES 2.0 上下文**（`setEGLContextClientVersion(2)`）。牌面是 512×853
 *    这种 NPOT 尺寸，ES 2.0 下不允许生成 mipmap，缩小采样只能点采样 —— 牌面会
 *    「沙沙」闪。原来的 `GLSurfaceHost` 就是为此特意升到 ES 3.0 的，不能在这里退回去。
 * 2. **它是透明叠层**（`PixelFormat.TRANSLUCENT` + `setZOrderOnTop(true)`），
 *    为的是让 GL 只占一块区域、透出下面的 Compose UI。牌桌页正相反：GL 铺满整屏，
 *    工具栏浮在它**上面**。用了叠层，工具栏会被 GL 盖住。
 *
 * 所以这里自己写宿主 View（44 行的事），只用库的 [GpuCompositor] —— 那才是能力本体。
 * 这也正是中间件该有的分工：库出渲染器，宿主按本项目的 EGL 需求建。
 *
 * ## 现在的画面构成
 *
 * 单路全屏（[Tiles.full]），即「引擎输出 → 合成分辨率 → 屏幕」，中间多了一次
 * FBO 往返与一次 blit。这一步的目的是把引擎接进管线，画面应与原来一致；
 * 第二路源（摄像头 / 视频 / 背景）和后处理链是下一步的事，加源不需要再动业务代码。
 */
class CompositeTableHost(
    context: Context,
    val engine: GlEngine,
) : GLSurfaceView(context) {

    /** 合成器本体。加第二路源、挂后处理、接录制都从这里进（见类注释末段）。 */
    val compositor: GpuCompositor = GpuCompositor()

    private val engineSource = GlEngineSource(
        engine = engine,
        // 多采样 renderbuffer 是 ES 3.0 起的接口；ES 2.0 设备由适配器自动退化。
        useMsaa = detectGlVersion(context) >= 3,
    )

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
        // 与 GLSurfaceHost 完全一致的上下文与抗锯齿配置。
        setEGLContextClientVersion(detectGlVersion(context))
        setEGLConfigChooser(MsaaConfigChooser())

        compositor.register(SOURCE_ENGINE, engineSource)
        compositor.tiles = listOf(TileSpec(SOURCE_ENGINE, Tiles.full()))
        compositor.policy = RenderPolicy(maxFps = 60)

        // 合成器是 Renderer 本体，但尺寸变化要同步给引擎（它按自己的宽高算投影矩阵），
        // 所以在中间夹一层转发。除此之外三个回调都是原样透传。
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                compositor.onSurfaceCreated(gl, config)
            }

            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
                compositor.onSurfaceChanged(gl, w, h)
                // 引擎渲染尺寸 = 本路画面在屏幕上的像素尺寸。单路全屏时就是整窗尺寸；
                // 将来拼多路，这里换成该 tile 的 viewport 宽高，引擎的投影会自动跟上。
                engineSource.resize(w, h)
            }

            override fun onDrawFrame(gl: GL10?) {
                compositor.onDrawFrame(gl)
            }
        })
        // attach 必须在上面的 setRenderer 之后：合成器要用它做 queueEvent / requestRender。
        compositor.attach(this)

        // 恒为连续回调。省电交给合成器的内容代次门控，不靠改渲染模式
        // （WHEN_DIRTY 下被门控跳过的帧没有补偿机会，会表现为「一直卡帧」）。
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDetachedFromWindow() {
        gestures.cancel()
        // 只清手势计时器，**不动渲染线程也不 release**：
        // · GLSurfaceView 自己在 detach 时退出 GL 线程、attach 时重建，手动 onPause()
        //   会打乱这个节奏（重建后没人调 onResume，画面就冻住）；
        // · compositor.release() 是不可逆的（released 标志置位后 invalidate / postGl
        //   全部失效），而 Compose 的 AndroidView 会反复 detach / attach。
        // 需要彻底释放时由调用方显式调 [release]（页面临终销毁）。
        super.onDetachedFromWindow()
    }

    /**
     * 彻底释放合成器（停帧时钟线程、清统计）。
     *
     * **只在整个页面/View 不会再回来时调用**，比如 Compose 的 `DisposableEffect`
     * 退出回调。调过之后这个实例不可复用。
     */
    fun release() {
        gestures.cancel()
        compositor.postGl { engineSource.releaseGl() }
        compositor.release()
    }

    /** 画面源 key。阶段 2 加摄像头 / 视频时，新源的 key 不要与它重名。 */
    companion object {
        const val SOURCE_ENGINE = "table"
    }
}
