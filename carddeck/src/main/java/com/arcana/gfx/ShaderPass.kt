package com.arcana.gfx

/**
 * 一个可组合的后处理 pass。
 *
 * **这是库开放自定义 shader 的地方**：`fragment` 是完整的 GLSL ES 2.0 片元着色器源码，
 * 只要遵守下面的 uniform 约定就能直接塞进链里，不需要改引擎、不需要重新编译库：
 *
 * ```
 * precision mediump float;
 * uniform sampler2D uTexture;   // 上一 pass 的输出（第 0 个纹理单元）
 * uniform vec2 uTexel;          // (1/宽, 1/高)
 * uniform vec4 uParams;         // 由 [params] 传入的 4 个自由参数
 * uniform float uTime;          // 秒
 * varying vec2 vUv;
 * void main() { gl_FragColor = ...; }
 * ```
 *
 * 链按列表顺序执行，每个 pass 从 read 纹理采样、写进 write 纹理后交换（ping-pong）。
 * `enabled = false` 的 pass 会被跳过但不从链里移除，方便做 A/B 对比。
 *
 * @param name 仅用于日志 / 指标展示，不参与编译。
 * @param fragment GLSL 源码；相同源码会被编译缓存复用。
 * @param params 恰好 4 个 float，对应 `uParams` 的 xyzw。
 */
data class ShaderPass(
    val name: String,
    val fragment: String,
    val params: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    val enabled: Boolean = true,
) {
    init {
        require(params.size == 4) { "ShaderPass.params 必须是 4 个 float（对应 uParams.xyzw）" }
        require(fragment.contains("uTexture")) { "自定义 pass 必须声明 uniform sampler2D uTexture" }
        require(fragment.contains("gl_FragColor")) { "自定义 pass 必须写 gl_FragColor" }
    }
}

/** 内置预设：都是遵守 [ShaderPass] 契约的普通 fragment，可直接读源码照着改。 */
object ShaderPasses {

    fun grayscale(): ShaderPass = ShaderPass("grayscale", GfxShaders.GRAYSCALE)

    /**
     * 调色：亮度 / 对比 / 饱和 / 暖色，一次 pass 完成，不额外增加 draw call。
     * @param brightness -0.3..0.3，contrast -0.5..1.0，saturation -1..1，warmth -1..1
     */
    fun colorGrade(
        brightness: Float = 0f,
        contrast: Float = 0.12f,
        saturation: Float = 0.18f,
        warmth: Float = 0.4f,
    ): ShaderPass = ShaderPass(
        "colorGrade", GfxShaders.COLOR_GRADE,
        floatArrayOf(brightness, contrast, saturation, warmth),
    )

    /**
     * 可分离高斯：返回**两个** pass（横向 + 纵向）。两次全屏 draw call，
     * 比 9×9 单 pass 81 次采样便宜一个数量级，是中低端机上唯一能实时跑的模糊做法。
     */
    fun blur(radiusPx: Float = 2.2f): List<ShaderPass> = listOf(
        ShaderPass("blurH", GfxShaders.BLUR, floatArrayOf(radiusPx, 0f, 0f, 0f)),
        ShaderPass("blurV", GfxShaders.BLUR, floatArrayOf(radiusPx, 1f, 0f, 0f)),
    )

    fun sobel(strength: Float = 1.6f): ShaderPass =
        ShaderPass("sobel", GfxShaders.SOBEL, floatArrayOf(strength, 0f, 0f, 0f))

    fun chromaShift(offsetPx: Float = 4f): ShaderPass =
        ShaderPass("chromaShift", GfxShaders.CHROMA_SHIFT, floatArrayOf(offsetPx, 0f, 0f, 0f))

    fun vignette(amount: Float = 0.55f, scanlines: Float = 0f): ShaderPass =
        ShaderPass("vignette", GfxShaders.VIGNETTE, floatArrayOf(amount, scanlines, 0f, 0f))

    /** 用调用方自己的 GLSL 建一个 pass。 */
    fun custom(
        name: String,
        fragment: String,
        p0: Float = 0f,
        p1: Float = 0f,
        p2: Float = 0f,
        p3: Float = 0f,
    ): ShaderPass = ShaderPass(name, fragment, floatArrayOf(p0, p1, p2, p3))

    /** 常用的「直播美颜」链：横竖模糊 + 提亮调色 + 暗角。 */
    fun beauty(): List<ShaderPass> = blur(1.6f) + colorGrade(0.02f, 0.06f, 0.10f, 0.25f) + vignette(0.35f)

    /** 常用的「故障 / 边缘」链：色差 + 边缘 + 扫描线。 */
    fun glitch(): List<ShaderPass> = listOf(chromaShift(6f), sobel(1.1f), vignette(0.4f, 0.25f))
}
