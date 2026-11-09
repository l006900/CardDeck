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
 * uniform sampler2D uTexture;   // 上一 pass 的输出（必填）
 * uniform sampler2D uTexture2;  // 第二路输入（仅 [ShaderPass.secondarySource] 非空时需要）
 * uniform vec2 uTexel;          // (1/宽, 1/高)，做邻域采样用
 * uniform vec4 uParams;         // 4 个自由参数，由 ShaderPass.params 传入
 * uniform float uTime;          // 秒，单调递增
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

    /* -------------------------------------------------- 单输入滤镜（工厂见 [ShaderPasses]） */

    const val INVERT = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            gl_FragColor = vec4(1.0 - c.rgb, c.a);
        }
    """

    const val SEPIA = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
            vec3 s = vec3(l) * vec3(1.07, 0.74, 0.43);
            gl_FragColor = vec4(clamp(s + uParams.x, 0.0, 1.0), c.a);
        }
    """

    /** uParams.x = 色阶数（2..16）。 */
    const val POSTERIZE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float n = max(uParams.x, 2.0);
            vec3 q = floor(c.rgb * n) / (n - 1.0);
            gl_FragColor = vec4(q, c.a);
        }
    """

    /** 像素化：uParams.x = 像素块边长（像素）。 */
    const val PIXELATE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            float cell = max(uParams.x, 1.0);
            vec2 grid = uTexel * cell;
            vec2 uv = (floor(vUv / grid) + 0.5) * grid;
            gl_FragColor = texture2D(uTexture, uv);
        }
    """

    /** 锐化：uParams.x = 强度（0..2）。 */
    const val SHARPEN = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            vec4 n = texture2D(uTexture, vUv + vec2(uTexel.x, 0.0))
                   + texture2D(uTexture, vUv - vec2(uTexel.x, 0.0))
                   + texture2D(uTexture, vUv + vec2(0.0, uTexel.y))
                   + texture2D(uTexture, vUv - vec2(0.0, uTexel.y));
            vec3 sharp = c.rgb + (c.rgb - n.rgb * 0.25) * uParams.x;
            gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
        }
    """

    /** 浮雕：uParams.x = 强度。 */
    const val EMBOSS = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            float c  = dot(texture2D(uTexture, vUv).rgb, vec3(0.299,0.587,0.114));
            float n  = dot(texture2D(uTexture, vUv + uTexel * 1.5).rgb, vec3(0.299,0.587,0.114));
            float d  = (c - n) * uParams.x;
            gl_FragColor = vec4(vec3(clamp(0.5 + d, 0.0, 1.0)), 1.0);
        }
    """

    /** 双色调：暗部→shadow 色，亮部→highlight 色，由 uParams.xy 旋转色相微调。 */
    const val DUOTONE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
            vec3 shadow = vec3(0.06, 0.10, 0.22) + vec3(uParams.x * 0.1, 0.0, 0.0);
            vec3 high   = vec3(0.98, 0.86, 0.55) + vec3(0.0, 0.0, uParams.y * 0.1);
            gl_FragColor = vec4(mix(shadow, high, clamp(l, 0.0, 1.0)), c.a);
        }
    """

    /** 色相旋转：uParams.x = 角度（度）。 */
    const val HUE_ROTATE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        vec3 hue(float a, vec3 col) {
            float c = cos(a), s = sin(a);
            mat3 m = mat3(0.213+c*0.787-s*0.213, 0.715-c*0.715-s*0.715, 0.072-c*0.072+s*0.928,
                          0.213-c*0.213+s*0.143, 0.715+c*0.285+s*0.140, 0.072-c*0.072-s*0.283,
                          0.213-c*0.213-s*0.787, 0.715-c*0.715+s*0.715, 0.072+c*0.928+s*0.072);
            return m * col;
        }
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            gl_FragColor = vec4(hue(radians(uParams.x), c.rgb), c.a);
        }
    """

    /** 伽马：uParams.x = gamma（>0）。 */
    const val GAMMA = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float g = max(uParams.x, 0.01);
            gl_FragColor = vec4(pow(c.rgb, vec3(1.0 / g)), c.a);
        }
    """

    /** 夜视：绿色通道 + 暗角 + 噪点。uParams.x = 噪点强度。 */
    const val NIGHTVISION = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }
        void main() {
            vec4 c = texture2D(uTexture, vUv);
            float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
            vec3 nv = vec3(0.1 * l, l, 0.1 * l);
            vec2 p = vUv - 0.5;
            float v = 1.0 - smoothstep(0.25, 0.8, length(p));
            float grain = (hash(vUv * 800.0 + uTime) - 0.5) * uParams.x;
            gl_FragColor = vec4(clamp(nv * v + grain, 0.0, 1.0), c.a);
        }
    """

    /** 老旧胶片：噪点 + 扫描线 + 轻微抖动。uParams.x = 抖动/噪点强度。 */
    const val OLD_FILM = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }
        void main() {
            vec2 jitter = vec2(
                (hash(vec2(uTime, vUv.y)) - 0.5) * uParams.x * 0.02,
                (hash(vec2(vUv.x, uTime)) - 0.5) * uParams.x * 0.01);
            vec4 c = texture2D(uTexture, vUv + jitter);
            float grain = (hash(vUv * 640.0 + fract(uTime) * 57.0) - 0.5) * uParams.x * 0.25;
            float scan = 0.92 + 0.08 * sin(vUv.y / uTexel.y * 1.5 + uTime * 8.0);
            gl_FragColor = vec4(clamp(c.rgb * scan + grain, 0.0, 1.0), c.a);
        }
    """

    /** 万花筒：uParams.x = 镜像扇区数（>0）。 */
    const val KALEIDOSCOPE = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec2 p = vUv - 0.5;
            float seg = 3.14159265 / max(uParams.x, 1.0);
            float a = atan(p.y, p.x);
            float r = length(p);
            a = abs(mod(a, 2.0 * seg) - seg);
            vec2 uv = 0.5 + vec2(cos(a), sin(a)) * r;
            gl_FragColor = texture2D(uTexture, clamp(uv, 0.0, 1.0));
        }
    """

    /** 桶形畸变（鱼眼）：uParams.x = 强度（约 -0.5..0.5）。 */
    const val LENS_DISTORT = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec2 p = vUv - 0.5;
            float r2 = dot(p, p);
            p *= 1.0 + uParams.x * r2;
            vec2 uv = p + 0.5;
            gl_FragColor = texture2D(uTexture, clamp(uv, 0.0, 1.0));
        }
    """

    /* -------------------------------------------------- 双输入：混合 / 转场 */

    /**
     * 两路混合：在 [ShaderPass.secondarySource] 指定的另一路源与本路结果之间做混合。
     * 合成器会先把第二路源 blit 成 2D 纹理绑到 `uTexture2`，所以 2D / 摄像头 / 视频源都可用。
     * uParams = (amount 0..1, 0, mode, 0)；mode：0 正片叠底 / 1 滤色 / 2 叠加 / 3 相加 / 4 差值。
     */
    const val BLEND = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform sampler2D uTexture2;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 a = texture2D(uTexture, vUv);
            vec4 b = texture2D(uTexture2, vUv);
            float amt = clamp(uParams.x, 0.0, 1.0);
            int mode = int(uParams.z + 0.5);
            vec3 r;
            if (mode == 0)      r = a.rgb * b.rgb;
            else if (mode == 1) r = 1.0 - (1.0 - a.rgb) * (1.0 - b.rgb);
            else if (mode == 2) r = mix(2.0 * a.rgb * b.rgb,
                                        1.0 - 2.0 * (1.0 - a.rgb) * (1.0 - b.rgb),
                                        step(0.5, a.rgb));
            else if (mode == 3) r = a.rgb + b.rgb;
            else                r = abs(a.rgb - b.rgb);
            gl_FragColor = vec4(mix(a.rgb, clamp(r, 0.0, 1.0), amt), a.a);
        }
    """

    /**
     * 两路交叉淡入淡出（转场）：把本路结果（A）与 [ShaderPass.secondarySource]（B）按
     * `fract(uTime / duration)` 做循环 A→B→A。uParams = (0, durationSec, 0, 0)。
     */
    const val TRANSITION = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform sampler2D uTexture2;
        uniform vec2 uTexel;
        uniform vec4 uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec4 a = texture2D(uTexture, vUv);
            vec4 b = texture2D(uTexture2, vUv);
            float dur = max(uParams.y, 0.5);
            float p = fract(uTime / dur);
            float m = smoothstep(0.0, 1.0, p);
            gl_FragColor = vec4(mix(a.rgb, b.rgb, m), a.a);
        }
    """
}
