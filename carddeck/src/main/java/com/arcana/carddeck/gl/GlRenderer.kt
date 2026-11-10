package com.arcana.carddeck.gl

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * [GlEngine] → [GLSurfaceView.Renderer] 的适配器。
 *
 * 唯一持有 Android GL 回调类型的地方，且只在库内部出现：业务侧拿到的 [GlEngine]
 * 是纯 Kotlin 接口（数据 + 三个无参帧方法），既不认识 GLSurfaceView，也不认识 GL10 /
 * EGLConfig。换渲染宿主、换 GL 版本、甚至将来换 Vulkan 后端，业务代码都不用动。
 */
internal class GlRenderer(private val engine: GlEngine) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = engine.onGlCreated()

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) = engine.onGlResized(w, h)

    override fun onDrawFrame(gl: GL10?) = engine.onGlFrame()
}
