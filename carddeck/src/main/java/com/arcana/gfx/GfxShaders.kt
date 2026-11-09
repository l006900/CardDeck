package com.arcana.gfx

/**
 * 合成管线的 GLSL ES 2.0 源码。
 *
 * 三类程序：
 * - `BLIT_VERTEX` + `BLIT_2D_FRAGMENT` / `BLIT_OES_FRAGMENT`：把一路源画进 FBO 的某个
 *   viewport，或把 FBO 结果画到屏幕。OES 用 `samplerExternalOES` 接 SurfaceTexture。
 * - `PASS_VERTEX` + 调用方给的 fragment：一次后处理。
 * - 内置预设（[ShaderPasses]）：满足公开 uniform 约定的可直接用的 fragment。
 *
 * **公开 shader 契约**（自定义 pass 必须遵守，见 [ShaderPass]）：
 * ```
 * precision mediump float;
 * uniform sampler2D uTexture;  // 上一 pass 的输出
 * uniform vec2 uTexel;         // (1/宽, 1/高)，做邻域采样用
 * uniform vec4 uParams;        // 4 个自由参数，由 ShaderPass.params 传入
 * uniform float uTime;         // 秒，单调递增
 * varying vec2 vUv;
 * ```
 */
internal object GfxShaders {

    /** 全屏 quad：位置已是 NDC，不需要 MVP；uv.y=0 对应 NDC 下方。 */
    const val PASS_VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /**
     * 源 blit 的顶点着色器：一次完成「uv 裁切 → y 方向纠正 → SurfaceTexture 变换」。
     *
     * [uCrop] 让同一路视频的不同区域能拼进不同画面（拼接 / 放大局部）；
     * [uSTMatrix] 是 `SurfaceTexture.getTransformMatrix()` 的结果，2D 源传单位矩阵即可。
     */
    const val BLIT_VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        uniform vec4 uCrop;
        uniform mat4 uSTMatrix;
        uniform float uFlipY;
        varying vec2 vUv;
        void main() {
            vec2 uv = mix(uCrop.xy, uCrop.zw, aUv);
            if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
            vUv = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    const val BLIT_2D_FRAGMENT = """
        precision mediump float;
        uniform sampler2D uTexture;
        varying vec2 vUv;
        void main() {
            gl_FragColor = texture2D(uTexture, vUv);
        }
    """

    /**
     * 外部纹理（视频 / 摄像头 / 任意解码器输出）。
     * `#extension` 必须是源码第一行，否则部分驱动直接编译失败。
     */
    const val BLIT_OES_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(uTexture, vUv);
}
"""

    /* -------------------------------------------------- 内置后处理预设 */

    const val GRAYSCALE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
            gl_FragColor = vec4(vec3(l), c.a);
        }
    """

    /** uParams = (brightness, contrast, saturation, warmth) */
    const val COLOR_GRADE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            vec3 rgb = c.rgb + uParams.x;
            rgb = (rgb - 0.5) * (1.0 + uParams.y) + 0.5;
            float l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = mix(vec3(l), rgb, 1.0 + uParams.z);
            rgb += vec3(0.055, 0.006, -0.045) * uParams.w;
            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
        }
    """

    /**
     * 可分离高斯模糊（9-tap 线性采样近似），需要横竖各跑一遍。
     * uParams.x = 半径（像素），uParams.y = 0 横向 / 1 纵向。
     */
    const val BLUR = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec2 dir = mix(vec2(uTexel.x, 0.0), vec2(0.0, uTexel.y), uParams.y);
            float r = uParams.x;
            vec4 sum = texture2D(uTexture, vUv) * 0.227027;
            sum += (texture2D(uTexture, vUv + dir * r * 1.384615)
                  + texture2D(uTexture, vUv - dir * r * 1.384615)) * 0.316216;
            sum += (texture2D(uTexture, vUv + dir * r * 3.230769)
                  + texture2D(uTexture, vUv - dir * r * 3.230769)) * 0.070270;
            gl_FragColor = sum;
        }
    """

    /** Sobel 边缘，uParams.x = 强度。 */
    const val SOBEL = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        float lum(vec2 uv) {
            return dot(texture2D(uTexture, uv).rgb, vec3(0.299, 0.587, 0.114));
        }
        void main() {
            vec2 t = uTexel * 1.5;
            float tl = lum(vUv + vec2(-t.x,  t.y));
            float tc = lum(vUv + vec2( 0.0,  t.y));
            float tr = lum(vUv + vec2( t.x,  t.y));
            float ml = lum(vUv + vec2(-t.x,  0.0));
            float mr = lum(vUv + vec2( t.x,  0.0));
            float bl = lum(vUv + vec2(-t.x, -t.y));
            float bc = lum(vUv + vec2( 0.0, -t.y));
            float br = lum(vUv + vec2( t.x, -t.y));
            float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
            float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            float e = clamp(length(vec2(gx, gy)) * uParams.x, 0.0, 1.0);
            vec4 src = texture2D(uTexture, vUv);
            gl_FragColor = vec4(vec3(e), src.a);
        }
    """

    /** RGB 通道错位，uParams.x = 偏移量（像素）。 */
    const val CHROMA_SHIFT = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec2 off = vec2(uTexel.x * uParams.x, 0.0);
            float r = texture2D(uTexture, vUv + off).r;
            vec4 g = texture2D(uTexture, vUv);
            float b = texture2D(uTexture, vUv - off).b;
            gl_FragColor = vec4(r, g.g, b, g.a);
        }
    """

    /** uParams.x = 暗角强度，uParams.y = 扫描线强度（0 关闭）。 */
    const val VIGNETTE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            vec2 p = vUv - vec2(0.5);
            float v = 1.0 - smoothstep(0.20, 0.78, length(p)) * uParams.x;
            float scan = 1.0 - uParams.y * 0.5 * (0.5 + 0.5 * sin(vUv.y / uTexel.y * 0.25 + uTime * 4.0));
            gl_FragColor = vec4(c.rgb * v * scan, c.a);
        }
    """
}
