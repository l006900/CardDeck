package com.arcana.carddeck.gl

/**
 * renderer 的活动场景（可变渲染状态），实现细节，外部只经 [GlEngine] 间接使用。
 *
 * 与调用方的分工：调用方（VM）持「事实」（抽了哪些牌、正逆位、牌位），
 * [TableScene] 持「渲染」（位置、动画插值、层级）。两者通过
 * `submit`（调用方 → 场景，重建/补牌时）与回调（场景 → 调用方，拖动结束时）同步。
 *
 * 坐标系：归一化 (0..1)，左上原点 —— 与触摸事件直接对应，换设备不变形。
 * 世界坐标（GL）由 renderer 在投影时换算，模型不关心屏幕比例。
 *
 * 动画用「每帧朝目标插值」而不是属性动画：GL 线程不适合跑 Android animator，
 * 而 lerp 系数调好的缓动手感与 60fps 撕裂无关 —— 掉帧时动画只会变慢，不会跳。
 */
internal class TableScene {

    /** 渲染条目：事实部分（来自 [TableCard]）+ 动画状态。 */
    class Item(
        val uid: Long,
        val cardId: String,
        val reversed: Boolean,
        /** 目标朝向：0 = 背面朝上，1 = 正面朝上。 */
        var targetFace: Float,
        var targetX: Float,
        var targetY: Float,
        /** 当前动画状态。x/y 每帧向目标 lerp；face 走 [flipEase] 时间曲线；z 是发牌高度。 */
        @Volatile var x: Float,
        @Volatile var y: Float,
        @Volatile var face: Float,
        @Volatile var z: Float,
        /** 被拖动中：插值暂停（不然牌会挣脱手指）。 */
        @Volatile var dragging: Boolean = false,
        /** 翻开瞬间的金色脉冲（0..1，随时间衰减）。 */
        @Volatile var glow: Float = 0f,
        /** 翻面时间轴：0..1，1 = 静止。face 由 [flipEase] 从 [flipFrom] 推到 [flipTo]。 */
        @Volatile var flipT: Float = 1f,
        @Volatile var flipFrom: Float = 0f,
        @Volatile var flipTo: Float = 0f,
        /**
         * 「停在眼前」时间轴：0..1，1 = 停留结束（不处于翻面演出中）。
         *
         * 三段严格串行：[flipT]（飞到眼前 + 翻）→ **holdT（牌正面朝上、停在屏幕中央）**
         * → [fallT]（滑回原位）。留这一段是因为翻完立刻往回缩时，牌名文字根本来不及看
         * —— 用户的原话是「放到最大时停留下，要停留到可以看清文字」。
         *
         * 停留期间牌完全静止（rise 恒为 1、face 恒为 targetFace），所以只要不推进它，
         * 画面就自然停在最大状态，渲染侧一行都不用改。
         */
        @Volatile var holdT: Float = 1f,
        /**
         * 落回时间轴：0..1，1 = 已经落回原位（不处于翻面演出中）。
         *
         * **为什么和 [flipT] 分开**：翻面是「飞到眼前 → 翻 → 停在眼前 → 滑回原位」四段，
         * 落回必须是**独立阶段** —— 塞进 flipT 尾部会让落回和翻面尾巴重叠（牌一边转完
         * 一边往回缩），时长也没法单独调。只有 [holdT] 走完之后才推进。
         */
        @Volatile var fallT: Float = 1f,
        /** 翻面中离桌高度（世界单位）：中段抬起、落定时带衰减回弹，驱动假阴影。 */
        @Volatile var lift: Float = 0f,
        /** 揭示粒子爆发的出生时刻（引擎绝对时钟，秒）；-1 = 未触发。 */
        @Volatile var burstAt: Float = -1f,
        /** 上一帧 face，用于检测「翻过一半」这一瞬（正反两个方向都算）。 */
        @Volatile var prevFace: Float = 0f,
        /** 本次拖动是否已记下「手指 → 牌心」的抓取偏移（见 [moveTo]）。 */
        @Volatile var grabSet: Boolean = false,
    )

    /**
     * 绘制顺序即 z 序（后画的在上）。
     *
     * **线程纪律**：这个 list 被 GL 线程每帧遍历、被 UI 线程改结构（submit 清场补牌、
     * 拖动置顶 remove+add）—— 两边必须都走 [lock]，任何一边裸遍历都是
     * ConcurrentModificationException（真机上多抽几张牌就崩，2026-11-02 教训）。
     * GL 线程的用法是 [snapshot]：锁内拷一份，遍历画；UI 线程的所有结构改动进锁。
     */
    val items = ArrayList<Item>()

    /** [items] 的结构锁。字段级动画状态（x/y/face/z，@Volatile）不走锁，锁只护结构。 */
    private val lock = Any()

    /** [snapshot] 的双缓冲（复用，不每帧 new）。 */
    private val snapshotBuffers = arrayOf(ArrayList<Item>(32), ArrayList<Item>(32))
    private var snapshotFlip = 0

    /**
     * 锁内拷贝一份用于 GL 线程逐帧绘制（Item 引用本身可跨线程读，字段是 @Volatile）。
     *
     * **双缓冲**：60fps 下每帧 `ArrayList(items)` 是每秒 60 个列表 + 一场数组扩容，
     * 年轻代 GC 尖刺表现为「偶发、不规律」的卡顿（不是固定位置卡）。这里预分配两块
     * 复用，只清空再 addAll —— 全程零分配。只有 GL 线程调用，交换游标无需加锁。
     */
    fun snapshot(): List<Item> = synchronized(lock) {
        val idx = snapshotFlip
        snapshotFlip = 1 - idx
        val buf = snapshotBuffers[idx]
        buf.clear()
        buf.addAll(items)
        buf
    }

    /** 拖动置顶：把命中的牌挪到绘制序列末尾（= 所有牌之上）。 */
    fun bringToFront(item: Item) {
        synchronized(lock) {
            items.remove(item)
            items.add(item)
        }
    }

    /** 按 uid 找条目（锁内），找不到返回 null。 */
    fun find(uid: Long): Item? = synchronized(lock) { items.firstOrNull { it.uid == uid } }

    /**
     * 牌位中心（归一化 `[x0,y0,x1,y1,...]`，左上原点，顺序即落位顺序）。
     * 空数组 = 自由桌面，不画 slot 框。
     *
     * 由调用方（自由抽牌）直接给坐标 —— 来源是占卜间里用户亲手摆的 `spreadPos`，
     * 不再是 GL 自己按牌数套网格。框和落点必须同一份数组，否则"框在这、牌在那"。
     */
    @Volatile var spreadPositions: FloatArray = FloatArray(0)

    /** 牌位数（= [spreadPositions] 里的点数）；0 = 没牌阵。 */
    val spreadCount: Int get() = spreadPositions.size / 2

    /** 牌阵区 slot 框显示开关（工具栏可隐藏，看牌不被框干扰）。 */
    @Volatile var spreadVisible: Boolean = true

    /**
     * 归一化**牌高**（占视图高的比例，0..1）。牌宽 = 牌高 × [CARD_ASPECT]（720:1200）。
     *
     * 口径提醒：这里的值是「牌高 / 屏高」。[hitTest] 的 `halfH = cardSize / 2` 与
     * renderer 的 `halfH = cardSize` 都按这个口径算，调用方负责把「牌宽 dp」先除
     * [CARD_ASPECT] 再除屏高（见 FreeDrawScreen 的 normOf）。老注释写成「归一化牌宽」
     * 是错的，照它改换算会得到小一半的牌。
     */
    @Volatile var cardSize: Float = 0.22f

    /** 单指拖动命中的条目；null = 没在拖。 */
    @Volatile var draggedItem: Item? = null

    /**
     * 「手指 → 牌心」的抓取偏移（归一化坐标）。
     *
     * 没有它的话，手指按在牌的上半部分再拖动，牌心会瞬间跳到指下（牌"跳一下"），
     * 因为 [moveTo] 拿到的是手指位置。偏移在本次拖动第一次 [moveTo] 时定死，
     * 之后牌相对手指的位置就一直不变。同一时刻只拖一张，所以放在场景级即可。
     */
    private var grabDX = 0f
    private var grabDY = 0f

    /**
     * VM → 场景全量同步。按 uid 对齐：已有的保留动画状态只更新目标，新的从
     * 底部牌环方向起飞（z 从高处落下），消失的（复位）直接移除 —— 复位是清场，
     * 不做"飞回牌环"的退场动画，那是给收回单张牌准备的（M2）。
     */
    fun submit(cards: List<TableCard>, cardSize: Float) {
        this.cardSize = cardSize
        synchronized(lock) {
            val old = items.associateBy { it.uid }
            val incoming = cards.associateBy { it.uid }
            // **保留原有绘制顺序**：绘制顺序就是层级（后画的在上，且深度阶梯按它给）。
            // 老实现无脑按 `cards` 的顺序重建，于是 [bringToFront] 的置顶效果被每一次
            // 重组抹掉 —— 拖到最上面的牌会「掉回」抽牌时的层级，被后来的牌盖住。
            // 新牌追加在末尾 = 最上层，与「刚抽出来的牌在上」的直觉一致。
            val kept = items.mapNotNull { it.uid }.filter { incoming.containsKey(it) }
            val keptSet = kept.toHashSet()
            val ordered = kept + cards.map { it.uid }.filterNot { it in keptSet }
            items.clear()
            for (uid in ordered) {
                val c = incoming[uid] ?: continue
                val prev = old[uid]
                if (prev != null) {
                    // 拖动中的牌位置归手指（[moveTo] 已经写好 x/y 与 targetX），
                    // 这里再按 VM 的事实坐标覆盖，松手前牌就会被拉回上一处。
                    if (!prev.dragging) {
                        prev.targetX = c.x
                        prev.targetY = c.y
                    }
                    val newFace = if (c.revealed) 1f else 0f
                    // 外部（复位/切换）改朝向 → 直接对齐，不重放翻面动画；
                    // 用户点牌翻面走 [flip]，那才是要走缓动曲线的路径。
                    if (prev.targetFace != newFace) {
                        prev.targetFace = newFace
                        prev.face = newFace
                        prev.flipFrom = newFace
                        prev.flipTo = newFace
                        prev.flipT = 1f
                        // 停留 / 落回一并收尾：外部强制改朝向是「立刻到位」，
                        // 少清这两个会让它莫名其妙再从屏幕中央落一次。
                        prev.holdT = 1f
                        prev.fallT = 1f
                        prev.lift = 0f
                        prev.burstAt = -1f
                        prev.prevFace = newFace
                    }
                    items.add(prev)
                } else {
                    // 新牌从底部牌环方向起飞，落点由 lerp 自然完成。
                    items.add(
                        Item(
                            uid = c.uid,
                            cardId = c.cardId,
                            reversed = c.reversed,
                        targetFace = if (c.revealed) 1f else 0f,
                        targetX = c.x,
                        targetY = c.y,
                        x = 0.5f,
                        y = 0.96f,
                        face = if (c.revealed) 1f else 0f,
                        z = 0.55f, // 起飞高度，随帧衰减
                        flipFrom = if (c.revealed) 1f else 0f,
                        flipTo = if (c.revealed) 1f else 0f,
                    ),
                    )
                }
            }
        }
    }

    /** 拖动中实时改位置（UI 线程写，等 up 才回传 VM 落定）。 */
    fun moveTo(uid: Long, x: Float, y: Float) {
        val item = find(uid) ?: return
        // 第一次移动时记下抓取偏移：牌心 - 手指（此时 item.x 还是按下前的位置）。
        // 之后按偏移跟随，牌就不会「跳」到手指正下方。
        if (!item.grabSet) {
            grabDX = item.x - x
            grabDY = item.y - y
            item.grabSet = true
        }
        val nx = x + grabDX
        val ny = y + grabDY
        item.x = nx
        item.y = ny
        item.targetX = nx
        item.targetY = ny
    }

    /**
     * 单击翻面：启动 [FLIP_DURATION] 的缓动时间轴，glow 脉冲点亮。
     *
     * **不排队、直接拒绝**：点击层（`TableRenderer.tapCard`）在整桌演出期间一律不响应，
     * 走到这里时场上一定是静的。这里再判一次 `busyLocked` 只是给「将来可能有别处
     * 直接调 flip」留的最后一道闸 —— 两张牌同时被推到屏幕中央是穿模的唯一来源
     * （z 阶梯 0.0025 只够分开平放在桌上的牌，压不住两块立体牌面），宁可丢掉
     * 这一下，也不要两张一起飞。
     *
     * 早先做过「排队、一张演完接下一张」，用户改口要「都拦截」后删掉了 ——
     * 排队会让连点变成 5 张牌各演 2.5s，反而像卡住。
     */
    fun flip(uid: Long) {
        synchronized(lock) {
            if (busyLocked()) return
            items.firstOrNull { it.uid == uid }?.let { startFlipLocked(it) }
        }
    }

    /** 是否有牌正处在演出中（抬升 / 翻 / 停 / 落 任一阶段）。调用方持 [lock]。 */
    private fun busyLocked(): Boolean =
        items.any { it.flipT < 1f || it.holdT < 1f || it.fallT < 1f }

    /**
     * 外部（渲染层的点击处理）查询「整桌是否正在演出」。
     *
     * 演出期间 `tapCard` 整桌冻结：不翻面、也不进牌意页。翻面撞车是穿模的根源；
     * 跳详情页则会把演出打断在半空，返回时牌停在半路、牌名浮层残留。
     * 用户原话：「翻页过程中点击牌桌不管是翻页还是卡片详情都不能」。
     */
    fun isBusy(): Boolean = synchronized(lock) { busyLocked() }

    /** 真正启动一张牌的翻面时间轴。调用方持 [lock] 并已确认 [busyLocked] 为 false。 */
    private fun startFlipLocked(item: Item) {
        val to = if (item.targetFace > 0.5f) 0f else 1f
        item.targetFace = to
        item.flipFrom = item.face
        item.flipTo = to
        item.flipT = 0f
        // 停留 + 落回两条时间轴同时归零待命：flipT 走完之后先停在眼前（holdT），
        // 停留到牌名读得完才滑回原位（fallT），见 [advance]。
        // 清成 1 会让牌翻完直接砸回原位，演出就没了「拿在眼前看」那一段。
        item.holdT = 0f
        item.fallT = 0f
        item.glow = 1f
        item.burstAt = -1f
        item.prevFace = item.face
    }

    /**
     * 命中测试：从最上层往下找第一张覆盖触摸点的牌。
     *
     * 牌的渲染宽高：高 = [cardSize] * 视图高，宽 = 高 * 0.6（720:1200 牌面比例）。
     * 命中区域用**未旋转**的包围盒 —— 翻转是绕 Y 轴的透视变换，包围盒近似对
     * 手指尺寸（±12dp）来说足够准，不值得为它做射线求交。
     */
    fun hitTest(nx: Float, ny: Float, viewWidth: Float, viewHeight: Float, densityScale: Float): Item? {
        // hitTest 也在 UI 线程（触摸事件）遍历 items，必须与 GL 线程的绘制互斥。
        return synchronized(lock) { hitTestLocked(nx, ny, viewWidth, viewHeight, densityScale) }
    }

    private fun hitTestLocked(
        nx: Float,
        ny: Float,
        viewWidth: Float,
        viewHeight: Float,
        densityScale: Float,
    ): Item? {
        val halfH = cardSize / 2f
        val halfW = halfH * CARD_ASPECT * (viewHeight / viewWidth.coerceAtLeast(1f))
        val slopX = 12f * densityScale / viewWidth
        val slopY = 12f * densityScale / viewHeight
        for (i in items.indices.reversed()) {
            val it = items[i]
            if (it.x - halfW - slopX <= nx && nx <= it.x + halfW + slopX &&
                it.y - halfH - slopY <= ny && ny <= it.y + halfH + slopY
            ) {
                return it
            }
        }
        return null
    }

    companion object {
        /** 牌面宽高比 720:1200（与 Web 卡牌、App 牌图同规格）。 */
        const val CARD_ASPECT = 0.6f

        /** 每帧位置插值系数（60fps 下约 0.18 → 到位 ≈ 220ms，带一点弹性感）。 */
        const val LERP = 0.18f

        /**
         * 「飞到眼前 + 翻到正面」的时长（秒）。
         *
         * 与非 GL 的 `CardReveal`（飞 420 + 翻 620 + 停 280 + 落 460）同一个量级，
         * 但牌桌不需要「停住让人看清」那一段 —— 看清是靠落回那一段的慢速滑行给的。
         *
         * **数值的真相在 [GlFlipTiming.TURN_MS]**（毫秒），这里只换算成秒：
         * Compose 层要用同一份时间表做牌名文字的延迟动画，两处各写一份必然错位。
         */
        val FLIP_DURATION = GlFlipTiming.TURN_MS / 1000f

        /**
         * 「停在眼前」的时长（秒）。翻面走完、落回开始之前的那一段静止。
         *
         * 这段时间牌定格在最大状态（rise=1、face=targetFace），是牌名文字唯一的全亮
         * 窗口。用拉长落回来换可读时间是错的方向（牌一边缩一边飘、字也跟着走），
         * 正确做法就是**停住**：用户原话「放到最大时停留下，要停留到可以看清文字」。
         *
         * 数值的真相在 [GlFlipTiming.HOLD_MS]。
         */
        val FLIP_HOLD_DURATION = GlFlipTiming.HOLD_MS / 1000f

        /**
         * 落回原位的时长（秒）。**独立阶段**，从 [Item.holdT] 走完那一刻才开始。
         *
         * 0.5s ≈ 原来（`0.26 × 0.98` 里那段尾巴）的两倍，但**不再往长里调**：
         * 试过 1s，牌一边缩小一边从屏幕中央飘回桌面，牌名跟着一起漂，反而更难看清 ——
         * 可读时间是靠 [FLIP_HOLD_DURATION] 的静止换来的，不是靠放慢这一段。
         */
        val FLIP_FALL_DURATION = GlFlipTiming.FALL_MS / 1000f

        /**
         * 起手蓄力占比：牌飞到眼前之前不要开始转。
         *
         * 与渲染侧 `FLIP_RISE_IN`（0.28）对齐、略早一点点 —— 牌落位到屏幕中心的那一瞬
         * 刚好开始翻，看着才像「拿稳了再翻」。原来 0.12 是纯蓄力，现在这段同时承担
         * 位移，太短会变成「一边飞一边翻」。
         */
        private const val HOLD = 0.26f

        /**
         * 翻面缓动曲线（拒绝匀速）：
         *  - 0..[HOLD]：蓄力，牌几乎不动（只走 1.5%）；
         *  - 主段：easeInOutCubic，起步加速、过半最快、临近到位减速；
         *  - 末段：轻微 overshoot 后回落，模拟纸牌落桌的弹性，而不是硬停。
         *
         * 角度本身不做过冲（会让 cos 符号翻面、正反面判断抖动）；回弹放在 [Item.lift]
         * 这个离桌高度上，视觉上一样有「啪」一下贴回桌面的重量感。
         */
        fun flipEase(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            if (t < HOLD) {
                val k = t / HOLD
                return 0.015f * k * k
            }
            val u = (t - HOLD) / (1f - HOLD)
            val eased = if (u < 0.5f) {
                4f * u * u * u
            } else {
                val p = -2f * u + 2f
                1f - p * p * p / 2f
            }
            // 末端 ~8% 的一次衰减回弹（只加在 0..1 之间，不越过目标角度）
            val settle = if (u > 0.82f) {
                val k = (u - 0.82f) / 0.18f
                0.028f * kotlin.math.sin(k * Math.PI.toFloat()) * (1f - k)
            } else 0f
            return (eased + settle).coerceIn(0f, 1f)
        }

        /**
         * 每帧推一推动画。
         * @param clock 引擎绝对时钟（秒），只用于给揭示粒子爆发打出生时间戳，与渲染侧同源。
         */
        fun advance(items: List<Item>, frameDelta: Float, clock: Float): Boolean {
            var animating = false
            // 位置插值也必须**跟时间走**：原来每帧固定乘 LERP，在 120Hz 上每秒插值次数翻倍，
            // 飞牌/归位就比 60Hz 快一倍（同样的「高刷手感不对」）。用指数衰减的等效系数
            // k = 1-(1-LERP)^(dt×60)：60fps 时正好等于 LERP，120fps 时每帧小一半、每秒一致。
            val lerpK = 1f - Math.pow(
                (1f - LERP).toDouble(),
                (frameDelta * 60f).toDouble(),
            ).toFloat()
            for (it in items) {
                // 拖动中**整段跳过**位置插值，而不是用系数 1 让它「一步到位」。
                // 老写法 `lx = 1f` 等价于 x := targetX，而拖动期间只要有一次 [submit]
                // （拖动中每次重组都会走 `AndroidView.update`）把 targetX 改回 VM 里的
                // 旧坐标，牌就被瞬间拽回原位 —— 手感是「拖不动 / 得按住一会儿才跟手」。
                // 拖动期间位置只由 [moveTo] 直接写，谁也别再插值。
                if (!it.dragging) {
                    it.x += (it.targetX - it.x) * lerpK
                    it.y += (it.targetY - it.y) * lerpK
                }

                if (it.flipT < 1f) {
                    it.flipT = (it.flipT + frameDelta / FLIP_DURATION).coerceAtMost(1f)
                    it.face = it.flipFrom + (it.flipTo - it.flipFrom) * flipEase(it.flipT)
                    // 翻过一半（露出正/背面的那一刻）点燃金色星辉爆发，正反两个方向都算。
                    if (it.burstAt < 0f && (it.prevFace - 0.5f) * (it.face - 0.5f) <= 0f) {
                        it.burstAt = clock
                    }
                    it.prevFace = it.face
                    val p = it.flipT
                    // 中段抬起；落桌时一次衰减振荡（回弹），随后归零。
                    val rise = kotlin.math.sin(p * Math.PI.toFloat())
                    val settle = if (p > 0.80f) {
                        val k = (p - 0.80f) / 0.20f
                        kotlin.math.sin(k * Math.PI.toFloat() * 2f) * (1f - k) * 0.018f
                    } else 0f
                    it.lift = rise * 0.052f + settle
                } else {
                    it.face = it.targetFace
                    it.lift = 0f
                    // 三段严格串行：翻面走完 → **停在眼前**（牌名文字的全亮时段就是它）
                    // → 才滑回原位。停留期间不推进 fallT，rise 恒为 1，牌自然定在最大状态。
                    if (it.holdT < 1f) {
                        it.holdT = (it.holdT + frameDelta / FLIP_HOLD_DURATION).coerceAtMost(1f)
                    } else if (it.fallT < 1f) {
                        it.fallT = (it.fallT + frameDelta / FLIP_FALL_DURATION).coerceAtMost(1f)
                    }
                }

                if (it.z > 0.001f) it.z *= (1f - 4.5f * frameDelta).coerceAtLeast(0f)
                if (it.glow > 0.001f) it.glow *= (1f - 1.8f * frameDelta).coerceAtLeast(0f)
                if (!it.dragging &&
                    (
                        kotlin.math.abs(it.targetX - it.x) > 0.0008f ||
                            kotlin.math.abs(it.targetY - it.y) > 0.0008f ||
                            it.flipT < 1f ||
                            it.holdT < 1f ||
                            it.fallT < 1f ||
                            it.z > 0.001f || it.glow > 0.001f
                        )
                ) {
                    animating = true
                }
            }
            return animating
        }
    }
}
