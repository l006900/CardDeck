package com.arcana.carddeck.gl

import android.graphics.Bitmap

/**
 * GL 渲染引擎的对外接口 —— feature 只认它，不认任何具体实现。
 *
 * 迁移纪律（见 build.gradle.kts 的五规矩）：这个接口的签名里**永远不许出现
 * App 侧类型**（AppError / AppContainer / 某某 State……一旦出现，本模块就永远
 * 抽不出独立仓库）。数据进出只用：[TableCard]、Int/Float/Boolean、[Bitmap]、
 * 函数类型。图片解码（Coil 等）不进引擎 —— 调用方实现 [faceLoader] 注入。
 */
interface GlEngine {

    /* ---------------- 帧回调（由库内宿主调用，业务侧不碰） ---------------- */

    /** GL 上下文就绪：建 program / 上传贴图。只在 GL 线程调。 */
    fun onGlCreated()

    /** 画布尺寸变化：重建投影矩阵。只在 GL 线程调。 */
    fun onGlResized(w: Int, h: Int)

    /** 画一帧。只在 GL 线程调。 */
    fun onGlFrame()

    /* ---------------- 状态提交（UI 线程） ---------------- */

    /**
     * UI → 引擎全量同步桌面事实。按 uid 对齐：已有的保留动画状态只更新目标，
     * 新牌从底部起飞，消失的直接移除。 [cardSizeNorm] = 牌半高占视图高的比例。
     */
    fun submit(cards: List<TableCard>, cardSizeNorm: Float)

    /**
     * 牌位框叠加层。
     *
     * @param positions 牌位中心，归一化坐标 `[x0,y0, x1,y1, ...]`（0..1，左上原点，
     *   与 [TableCard] 的落点同一套坐标系）。空数组 = 不画（自由桌面）。
     *   **顺序即抽牌落位顺序**，与调用方的落点算法共用这一份数组 —— 以前框在 GL 里
     *   用固定网格公式单独算一遍，等于同一件事写两个地方，占卜间里摆的牌位永远对不上。
     * @param visible 显示开关（工具栏可隐藏，看牌不被框干扰）。
     */
    fun setSpreadOverlay(positions: FloatArray, visible: Boolean)

    /**
     * 桌布颜色（ARGB）：inner = 渐变首色（光心），outer = 尾色。
     * 只有一个颜色时两者传同值（纯色桌布）。不调 = 默认深紫夜色。
     */
    fun setCloth(inner: Int, outer: Int)

    /* ---------------- 手势桥（GLSurfaceHost 调用，引擎实现） ---------------- */

    /** 命中测试：返回最上层覆盖触摸点的牌 uid，null = 点在空白。 */
    fun hitTest(nx: Float, ny: Float, viewWidth: Float, viewHeight: Float, densityScale: Float): Long?

    /** 拖动开始：置 dragging 并提到最上层。 */
    fun dragStart(uid: Long)

    /** 拖动中实时改位置（归一化坐标）。 */
    fun dragMove(uid: Long, nx: Float, ny: Float)

    /** 拖动结束。moved = 位移超过手抖阈值（false = 单击误拖，不回传落点）。 */
    fun dragEnd(uid: Long, moved: Boolean)

    /**
     * 单击牌。**语义按当前朝向分两种**：
     *  - 背面朝上 → 翻面，翻完的朝向经 [onCardFlipped] 回传；
     *  - 正面朝上 → **不再翻回去**，改为回调 [onCardActivated]（调用方拿去做「看牌意」）。
     *
     * 「点一下翻回背面」在牌桌上是可玩的，但牌一旦翻开就代表用户已经认过这张，
     * 再点它是想看得更细，而不是想把它盖回去 —— 所以翻开后的点击必须有别的去处。
     */
    fun tapCard(uid: Long)

    /* ---------------- 纹理供给（调用方注入，引擎不碰网络/图片库） ---------------- */

    /**
     * 牌面纹理加载器：引擎要某张牌面时回调，调用方异步解码后经 onReady 回填
     * （Bitmap 为 null 表示加载失败，引擎允许后续重试）。回调可在任意线程。
     */
    var faceLoader: ((cardId: String, onReady: (Bitmap?) -> Unit) -> Unit)?

    /** 牌背纹理源（调用方解码好传入；null 时引擎画黑底占位）。 */
    var backBitmap: Bitmap?

    /* ---------------- 引擎 → 外回调 ---------------- */

    /** 拖动落定（dragEnd 且 moved 时）：把最终位置交回 VM。 */
    var onCardMoved: ((uid: Long, x: Float, y: Float) -> Unit)?

    /** 翻面完成（tapCard 后）：nowRevealed = 翻完是否正面朝上。 */
    var onCardFlipped: ((uid: Long, nowRevealed: Boolean) -> Unit)?

    /**
     * 点击了一张**已经正面朝上**的牌（[tapCard] 不再翻面时回调）。
     *
     * 引擎只负责报「哪张牌被要了」，具体做什么（进牌意页 / 播放详情）由调用方决定 ——
     * `:core:gl` 不认识导航。
     */
    var onCardActivated: ((uid: Long, cardId: String) -> Unit)?
}

/**
 * 翻面动画的时间表（毫秒）—— **唯一真相**。
 *
 * 为什么要在 public 的 `:core:gl` 里放一份毫秒常量：翻面是**跨两层**的演出。
 * 牌和它的 3D 透视归 GL（按秒推进），叠在牌下方的牌名/正逆位归 Compose
 * （按毫秒做延迟动画）。如果两边各写一套时长，改一个数就会错位成
 * 「牌已经落回原位了、名字还孤零零挂在屏幕中央」。
 *
 * 引擎侧（`TableScene`）的秒制常量从这里换算，UI 侧直接用这里的毫秒。
 */
object GlFlipTiming {
    /** 「飞到眼前 + 翻到正面」的总时长。走完这一拍，牌正好正面朝上停在眼前。 */
    const val TURN_MS = 980

    /**
     * 「停在眼前」的时长。翻面走完之后的**静止段**：牌定格在最大状态，牌名与正逆位
     * 就靠这段被读完。
     *
     * 可读性不能靠拉长落回换来 —— 落回中牌在缩小、往桌面飘，字也跟着远离视线，
     * 放慢只会让「看不清」变成「看得更久的看不清」。1s 是「看清」和「不拖沓」的折中
     * （1.2s 用户嫌长）。
     */
    const val HOLD_MS = 1000

    /**
     * 牌停在眼前时，整体从屏幕正中**上移**的比例（占屏高）。
     *
     * 「牌 + 牌名」作为一个整体一起抬（渲染侧按纵深换算世界位移、UI 侧直接用屏高比例，
     * 两边同一个数）。只要一点点：牌在眼前已经放大到 ~1.8 倍，牌顶本来就离状态栏不远，
     * 20% 会把牌顶推出屏幕（用户实测「都移除屏幕了」）。
     */
    const val PEAK_RAISE_FRACTION = 0.08f

    /**
     * 落回原位的时长。**独立阶段**：停留结束之后才开始。
     *
     * 0.5s，比初始的 `0.26 × 0.98 ≈ 0.25s` 慢一倍：够稳，又不会占掉可读时间。
     */
    const val FALL_MS = 500

    /**
     * 牌面翻转过半、已经能看出正逆位的时刻（≈ `flipEase` 走到 0.5 的那一点）。
     * 牌名文字从这里开始淡入 —— 再早牌还是侧立的，文字就先跳出来了。
     */
    const val LABEL_IN_MS = 620

    /**
     * 牌停在眼前时在屏幕上的放大倍数，供 UI 层给牌名文字定位（文字要贴在牌下沿之外）。
     *
     * 由透视投影决定：`EYE_Z / (EYE_Z - MAX_CARD_Z)` = 4.6 / 2.55 ≈ 1.8。
     * **改 `TableRenderer.EYE_Z` 或 `MAX_CARD_Z` 时必须同步这里** —— 这是全项目
     * 唯一一处需要手抄的几何量，因为它是渲染私有的投影参数，而 UI 只关心结果。
     */
    const val PEAK_SCALE = 1.8f
}

/** 引擎工厂：feature 只从这里拿引擎，不认具体实现类（将来换 C++ 引擎只改这里）。 */
object GlEngines {

    /** 牌桌引擎（自由抽牌当前唯一的实现）。 */
    fun table(config: GlConfig = GlConfig()): GlEngine = TableEngine(config)

    /** 抽卡序列引擎（发牌编排 + 揭示 + 体积光晕 + 粒子爆发）。返回 [DrawController] 接口类型。 */
    fun draw(config: GlConfig = GlConfig()): DrawController = DrawEngine(config)

    // 扇形牌堆已经不在这里了：它是通用 2D 控件，整组实现（DeckGeometry / DeckEngine /
    // DeckSurfaceHost / 牌堆 GLSL）已在库的 `com.arcana.carddeck` 里，用 `CardDeck()` 直接调。
}

/** 抽卡阶段（引擎 → Compose 状态），public 以便调用方（carddraw 组件）引用。 */
enum class DrawPhase { IDLE, DEALING, REVEALING, DONE }

/**
 * 抽卡引擎的**编排**接口：发牌演出 / 揭示 / 重置。与 [GlEngine]（渲染与手势桥）分开——
 * 渲染契约是牌桌与抽卡共用的，编排是抽卡独有的。调用方只认 [DrawController]，不认实现。
 */
interface DrawController : GlEngine {
    /** 开始抽卡演出：牌沿弧线错峰飞入（牌阵铺开），落定后按 autoReveal 自动翻面。 */
    fun deal(cards: List<TableCard>, cardSizeNorm: Float, autoReveal: Boolean = true)

    /** 手动揭示下一张未翻开的牌。 */
    fun revealNext()

    /** 手动揭示全部未翻开的牌（错峰）。 */
    fun revealAll()

    /** 清空，回到待机。 */
    fun reset()

    /** 当前阶段。 */
    fun phase(): DrawPhase

    /** 阶段变化回调（引擎 → 外）。 */
    var onPhaseChanged: ((DrawPhase) -> Unit)?
}

/** 引擎配置：纯几何/性能参数，没有业务。 */
data class GlConfig(
    /** 牌面宽高比（宽/高）。720:1200 规格 = 0.6。 */
    val cardAspect: Float = 0.6f,
    /** 星辉粒子数。200 颗走 GPU 管线毫无压力，视觉密度也够。 */
    val particleCount: Int = 200,
    /** 粒子种子。固定 = 星空每次进来都一样，"这是同一张牌桌"。 */
    val particleSeed: Long = 20261102L,
    /** 牌面纹理降采样宽度上限：屏上牌面撑满 ~250dp，512 绰绰有余；78 张全量 ≈ 18MB 而非 90MB。 */
    val maxFaceTextureWidth: Int = 512,
)

/** 引擎输入：桌面上一张牌的**事实**（id、正逆、朝向、牌位、归一化位置）。 */
data class TableCard(
    /** 桌面唯一 id（调用方生成，自增）。 */
    val uid: Long,
    /** 牌 id（如 `fool`），决定牌面纹理（经 [GlEngine.faceLoader] 加载）。 */
    val cardId: String,
    val reversed: Boolean,
    /** 是否已翻开（false = 背面朝上）。 */
    val revealed: Boolean,
    /** 牌位下标；自由牌位为 null。 */
    val slot: Int? = null,
    /** 归一化位置（0..1，视图左上原点）。 */
    val x: Float,
    val y: Float,
)
