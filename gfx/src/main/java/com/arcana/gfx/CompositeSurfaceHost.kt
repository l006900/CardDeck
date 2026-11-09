package com.arcana.gfx

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView

/**
 * 合成管线的 GL 宿主 View。
 *
 * 透明叠层三件套（与 SpriteSurfaceHost 一致）：带 alpha 的 EGL 配置、
 * `PixelFormat.TRANSLUCENT`、`setZOrderOnTop`，这样 GL 只占一块区域，
 * 透明像素仍能露出下面的 Compose UI。
 *
 * **渲染模式恒为 `RENDERMODE_CONTINUOUSLY`，不接受外部配置。**
 *
 * 这是踩坑之后的结论：`RENDERMODE_WHEN_DIRTY` 下每次 `requestRender()` 只换一次
 * `onDrawFrame` 回调，一旦这次回调被 fps 节流或内容门控跳过，**没有下一次补偿**，
 * 该帧就永久丢失 → 表现为「一直卡帧」。连续回调保证节拍永不断链，
 * 省电交给门控：内容代次没变时 `onDrawFrame` 只做几次比较就 return，
 * 不碰 GLES、不产生 draw call、不 swap，代价可以忽略。
 */
class CompositeSurfaceHost(
    context: Context,
    private val renderer: GpuCompositor,
) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser())
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        renderer.attach(this)
    }

    /** 策略变化只需同步帧率 / 分辨率 / 显存预算；渲染模式恒为连续回调，不再接受覆写。 */
    fun applyPolicy(policy: RenderPolicy) {
        if (renderMode != RENDERMODE_CONTINUOUSLY) {
            renderMode = RENDERMODE_CONTINUOUSLY
            requestRender()
        }
    }
}
