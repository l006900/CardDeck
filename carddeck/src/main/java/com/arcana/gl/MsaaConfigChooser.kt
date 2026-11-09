package com.arcana.gl

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * EGL 配置：优先 4× MSAA，不支持就退回普通配置（不能因为要抗锯齿就直接黑屏）。
 *
 * GLSurfaceView 自带的 `setEGLConfigChooser(8,8,8,8,16,0)` 没有 sample 参数，
 * 想要多重采样必须自己走 `eglChooseConfig` 指定 `EGL_SAMPLE_BUFFERS / EGL_SAMPLES`。
 * 同时声明 `EGL_ALPHA_SIZE = 8`，透明背景（叠在别的 UI 之上）才有 alpha 通道可用。
 */
internal class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? =
        pick(egl, display, 4) ?: pick(egl, display, 0)

    private fun pick(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val attrs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL10.EGL_SAMPLES, samples,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_NONE,
        )
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(num[0])
        if (!egl.eglChooseConfig(display, attrs, configs, num[0], num) || num[0] <= 0) return null
        return configs.firstOrNull { it != null }
    }

    private companion object {
        /** EGL_OPENGL_ES2_BIT：ES 3 的 context 也用它作为 renderable 声明（向后兼容）。 */
        const val EGL_OPENGL_ES2_BIT = 4
    }
}
