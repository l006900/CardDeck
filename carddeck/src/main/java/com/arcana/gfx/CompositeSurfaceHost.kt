package com.arcana.gfx

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import com.arcana.gl.MsaaConfigChooser

/**
 * 合成管线的 GL 宿主 View。
 *
 * 透明叠层三件套（与 SpriteSurfaceHost 一致）：带 alpha 的 EGL 配置、
 * `PixelFormat.TRANSLUCENT`、`setZOrderOnTop`，这样 GL 只占一块区域，
 * 透明像素仍能露出下面的 Compose UI。
 *
 * [applyPolicy] 把 [RenderMode.ON_DEMAND] 真正落到 `RENDERMODE_WHEN_DIRTY`：
 * 不只是「跳过绘制」，而是连 GL 线程的渲染回调都不触发，这是省电的关键。
 */
class CompositeSurfaceHost(
    context: Context,
    renderer: GpuCompositor,
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

    fun applyPolicy(policy: RenderPolicy) {
        val next = when (policy.mode) {
            RenderMode.CONTINUOUS -> RENDERMODE_CONTINUOUSLY
            RenderMode.ON_DEMAND -> RENDERMODE_WHEN_DIRTY
        }
        if (renderMode == next) return
        renderMode = next
        requestRender()
    }
}
