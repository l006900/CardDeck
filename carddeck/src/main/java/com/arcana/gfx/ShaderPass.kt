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
 * uniform float uTime;          // 秒，单调递增
 * varying vec2 vUv;
 * void main() { gl_FragColor = ...; }
 * ```
 *
 * 链按列表顺序执行，每个 pass 从 read 纹理采样、写进 write 纹理后交换（ping-pong）。
 * `enabled = false` 的 pass 会被跳过但不从链里移除，方便做 A/B 对比。
 *
 * **双输入（混合 / 转场）**：把 [secondarySource] 设成另一个已注册源（源 key）即可。
 * 合成器会先把那一路源 blit 成 2D 纹理并绑到可选的 `uniform sampler2D uTexture2`，
 * 于是 fragment 能同时拿到本路结果（`uTexture`）与另一路（`uTexture2`），做混合或转场。
 * 2D / 摄像头 / 视频源都支持（OES 源也会先转成 2D 再喂给 `uTexture2`）。
 *
 * @param name 仅用于日志 / 指标展示，不参与编译。
 * @param fragment GLSL 源码；相同源码会被编译缓存复用。
 * @param params 恰好 4 个 float，对应 `uParams` 的 xyzw。
 * @param enabled 是否启用（false 时跳过但不移除）。
 * @param secondarySource 可选的第二输入源 key；非空时 fragment 需声明 `uTexture2`。
 */
data class ShaderPass(
    val name: String,
    val fragment: String,
    val params: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
    val enabled: Boolean = true,
    val secondarySource: String? = null,
) {
    init {
        require(params.size == 4) { "ShaderPass.params 必须是 4 个 float（对应 uParams.xyzw）" }
        require(fragment.contains("uTexture")) { "自定义 pass 必须声明 uniform sampler2D uTexture" }
        require(fragment.contains("gl_FragColor")) { "自定义 pass 必须写 gl_FragColor" }
        if (secondarySource != null) {
            require(fragment.contains("uTexture2")) { "声明了 secondarySource 的 pass 必须声明 uniform sampler2D uTexture2" }
        }
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

    /* ----------------------------------------- 新增单输入滤镜 */

    fun invert(): ShaderPass = ShaderPass("invert", GfxShaders.INVERT)
    fun sepia(tint: Float = 0f): ShaderPass =
        ShaderPass("sepia", GfxShaders.SEPIA, floatArrayOf(tint, 0f, 0f, 0f))
    fun posterize(levels: Float = 6f): ShaderPass =
        ShaderPass("posterize", GfxShaders.POSTERIZE, floatArrayOf(levels, 0f, 0f, 0f))
    fun pixelate(cellPx: Float = 16f): ShaderPass =
        ShaderPass("pixelate", GfxShaders.PIXELATE, floatArrayOf(cellPx, 0f, 0f, 0f))
    fun sharpen(amount: Float = 1f): ShaderPass =
        ShaderPass("sharpen", GfxShaders.SHARPEN, floatArrayOf(amount, 0f, 0f, 0f))
    fun emboss(amount: Float = 1.4f): ShaderPass =
        ShaderPass("emboss", GfxShaders.EMBOSS, floatArrayOf(amount, 0f, 0f, 0f))
    fun duotone(hueShift: Float = 0f): ShaderPass =
        ShaderPass("duotone", GfxShaders.DUOTONE, floatArrayOf(hueShift, hueShift, 0f, 0f))
    fun hueRotate(deg: Float = 90f): ShaderPass =
        ShaderPass("hueRotate", GfxShaders.HUE_ROTATE, floatArrayOf(deg, 0f, 0f, 0f))
    fun gamma(g: Float = 1.4f): ShaderPass =
        ShaderPass("gamma", GfxShaders.GAMMA, floatArrayOf(g, 0f, 0f, 0f))
    fun nightVision(grain: Float = 0.18f): ShaderPass =
        ShaderPass("nightVision", GfxShaders.NIGHTVISION, floatArrayOf(grain, 0f, 0f, 0f))
    fun oldFilm(strength: Float = 1f): ShaderPass =
        ShaderPass("oldFilm", GfxShaders.OLD_FILM, floatArrayOf(strength, 0f, 0f, 0f))
    fun kaleidoscope(segments: Float = 6f): ShaderPass =
        ShaderPass("kaleidoscope", GfxShaders.KALEIDOSCOPE, floatArrayOf(segments, 0f, 0f, 0f))
    fun lensDistort(strength: Float = 0.3f): ShaderPass =
        ShaderPass("lensDistort", GfxShaders.LENS_DISTORT, floatArrayOf(strength, 0f, 0f, 0f))

    /* ----------------------------------------- 双输入：混合 / 转场 */

    /**
     * 两路混合：本路结果 与 [secondaryKey] 指定的另一路源之间做混合。
     * @param amount 0..1 混合比例
     * @param mode 0 正片叠底 / 1 滤色 / 2 叠加 / 3 相加 / 4 差值
     */
    fun blend(
        secondaryKey: String,
        amount: Float = 0.5f,
        mode: Int = 2,
    ): ShaderPass = ShaderPass(
        "blend:$secondaryKey", GfxShaders.BLEND,
        floatArrayOf(amount, 0f, mode.toFloat(), 0f),
        secondarySource = secondaryKey,
    )

    /**
     * 两路交叉淡入淡出（循环转场）：本路（A）与 [secondaryKey]（B）按时间循环 A→B→A。
     * @param durationSec 一个完整 A→B→A 周期的秒数
     */
    fun crossfade(secondaryKey: String, durationSec: Float = 4f): ShaderPass = ShaderPass(
        "crossfade:$secondaryKey", GfxShaders.TRANSITION,
        floatArrayOf(0f, durationSec, 0f, 0f),
        secondarySource = secondaryKey,
    )

    /** 用调用方自己的 GLSL 建一个 pass。 */
    fun custom(
        name: String,
        fragment: String,
        p0: Float = 0f,
        p1: Float = 0f,
        p2: Float = 0f,
        p3: Float = 0f,
        secondarySource: String? = null,
    ): ShaderPass = ShaderPass(name, fragment, floatArrayOf(p0, p1, p2, p3), secondarySource = secondarySource)

    /* ----------------------------------------- 组合链预设 */

    /** 常用的「直播美颜」链：横竖模糊 + 提亮调色 + 暗角。 */
    fun beauty(): List<ShaderPass> = blur(1.6f) + colorGrade(0.02f, 0.06f, 0.10f, 0.25f) + vignette(0.35f)

    /** 常用的「故障 / 边缘」链：色差 + 边缘 + 扫描线。 */
    fun glitch(): List<ShaderPass> = listOf(chromaShift(6f), sobel(1.1f), vignette(0.4f, 0.25f))

    /** 电影感：轻微提饱和 + 暖调 + 暗角。 */
    fun cinematic(): List<ShaderPass> =
        listOf(colorGrade(0.0f, 0.18f, 0.22f, 0.25f), vignette(0.45f))

    /** 复古：棕褐 + 暗角 + 颗粒。 */
    fun retro(): List<ShaderPass> =
        listOf(sepia(0.05f), vignette(0.5f), oldFilm(0.6f))

    /** 赛博朋克：色差 + 冷调高对比 + 暗角。 */
    fun cyberpunk(): List<ShaderPass> =
        listOf(chromaShift(5f), colorGrade(-0.02f, 0.3f, 0.15f, -0.3f), vignette(0.5f))

    /** 梦境：模糊 + 高饱和 + 暗角。 */
    fun dream(): List<ShaderPass> =
        blur(2.4f) + colorGrade(0.03f, 0.06f, 0.4f, 0.1f) + vignette(0.4f)
}
