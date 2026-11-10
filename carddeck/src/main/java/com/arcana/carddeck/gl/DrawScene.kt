package com.arcana.carddeck.gl

/**
 * 抽卡序列的活动场景（可变渲染状态），实现细节，外部只经 [DrawEngine] 间接使用。
 *
 * 与 [TableScene] 的区别：这里是一段**有时间轴的编排**（发牌 → 揭示），而不是
 * 任由用户摆弄的桌面。所有位置/朝向都从「序列时钟」[clock] 精确算出（确定性，
 * 不靠逐帧 lerp），掉帧只会让动画变慢、不会跳变，也方便单测锁死时序。
 *
 * 坐标系仍是归一化 (0..1)、左上原点 —— 与触摸、与 [TableScene] 同一套，换设备不变形。
 */
internal class DrawScene {

    /** 一张参与抽卡的牌：事实（id/正逆）+ 编排参数（发牌/翻面时刻）。 */
    class Item(
        val uid: Long,
        val cardId: String,
        val reversed: Boolean,
        /** 牌阵落点（归一化）。 */
        val slotX: Float,
        val slotY: Float,
        /** 发牌起点时刻（序列时钟秒，错峰 = uid 序 × stagger）。 */
        val dealStart: Float,
        /** 发牌时长（秒）。 */
        val dealDur: Float,
        /** 翻面时长（秒）。<0 = 暂不翻（等手动 revealNext）。 */
        val flipDur: Float,
        /** 翻面起点时刻（序列时钟秒）。-1 = 未排程（手动揭示用）。 */
        var revealStart: Float = -1f,
        /** 已翻到正面（供阶段判定与回调去重）。 */
        var revealed: Boolean = false,
        /** 翻开瞬间的金色脉冲（0..1，随时间衰减），驱动光晕强度。 */
        var glow: Float = 0f,
        /** 爆发粒子是否已在该牌触发（去重）。 */
        var burstFired: Boolean = false,
    )

    /** 一次爆发：牌心（归一化）+ 出生时刻（序列时钟秒）。 */
    class Burst(val cx: Float, val cy: Float, val birth: Float)

    val items = ArrayList<Item>()
    val bursts = ArrayList<Burst>()

    /** 序列时钟（秒，自 [deal] 起算）。引擎每帧写入。 */
    @Volatile var clock = 0f

    /** 归一化牌高（0..1，相对视图高）。 */
    @Volatile var cardSize = 0.22f

    /**
     * 发牌编排：把所有牌从中心牌堆错峰飞到 [slots]，并按 [autoReveal] 决定是否
     * 自动接续翻面。清空旧爆发，时钟归零。
     *
     * @param slots 归一化落点（与 cards 同序）；长度不足时循环复用最后一个。
     */
    fun deal(
        cards: List<TableCard>,
        cardSize: Float,
        slots: List<Pair<Float, Float>>,
        autoReveal: Boolean,
        stagger: Float = 0.22f,
        dealDur: Float = 0.55f,
        flipDur: Float = 0.6f,
        revealGap: Float = 0.18f,
    ) {
        this.cardSize = cardSize
        this.clock = 0f
        items.clear()
        bursts.clear()
        cards.forEachIndexed { i, c ->
            val slot = slots.getOrElse(i) { slots.lastOrNull() ?: Pair(0.5f, 0.5f) }
            val dStart = i * stagger
            val revealStart = if (autoReveal) dStart + dealDur + revealGap else -1f
            items.add(
                Item(
                    uid = c.uid,
                    cardId = c.cardId,
                    reversed = c.reversed,
                    slotX = slot.first,
                    slotY = slot.second,
                    dealStart = dStart,
                    dealDur = dealDur,
                    flipDur = flipDur,
                    revealStart = revealStart,
                ),
            )
        }
    }

    /** 手动揭示下一张尚未排程的牌（[revealStart] < 0 的第一张），从 now 起翻。 */
    fun revealNext(now: Float, stagger: Float = 0.25f) {
        val target = items.firstOrNull { it.revealStart < 0f } ?: return
        target.revealStart = now
        // 后续未排程的顺延，避免一次 tap 全翻。
        var t = now + stagger
        for (it in items) {
            if (it !== target && it.revealStart < 0f) {
                it.revealStart = t
                t += stagger
            }
        }
    }

    /** 手动揭示全部未排程的牌（错峰）。 */
    fun revealAll(now: Float, stagger: Float = 0.2f) {
        var t = now
        for (it in items) {
            if (it.revealStart < 0f) {
                it.revealStart = t
                t += stagger
            }
        }
    }

    fun reset() {
        items.clear()
        bursts.clear()
        clock = 0f
    }

    /* --------------------------------------------------- 时序计算 */

    /** 发牌进度 0..1（eased）。<0 表示还没到这张的发牌时刻。 */
    fun dealP(it: Item): Float {
        val raw = (clock - it.dealStart) / it.dealDur
        return if (raw <= 0f) 0f else easeOut(raw.coerceAtMost(1f))
    }

    /** 翻面进度 0..1（eased）。未排程 = 0。 */
    fun revealP(it: Item): Float {
        if (it.revealStart < 0f) return 0f
        val raw = (clock - it.revealStart) / it.flipDur
        return if (raw <= 0f) 0f else easeOut(raw.coerceAtMost(1f))
    }

    /** 当前牌心归一化坐标（发牌飞行中实时算）。 */
    fun centerX(it: Item): Float {
        val p = dealP(it)
        return 0.5f + (it.slotX - 0.5f) * p
    }

    fun centerY(it: Item): Float {
        val p = dealP(it)
        return 0.5f + (it.slotY - 0.5f) * p
    }

    /** 当前朝向角（弧度）：face 0 = 背面，π = 正面。 */
    fun faceAngle(it: Item): Float = revealP(it) * Math.PI.toFloat()

    /** 每帧推进一步：触发光晕脉冲 + 爆发，衰减 glow，清过期爆发。返回当前阶段。 */
    fun tick(dt: Float): DrawPhase {
        for (it in items) {
            // 翻面过半 → 点亮光晕并迸发一次粒子（去重）。
            if (it.revealStart >= 0f && !it.burstFired && revealP(it) >= 0.5f) {
                it.glow = 1f
                it.burstFired = true
                it.revealed = true
                bursts.add(Burst(centerX(it), centerY(it), clock))
            }
            if (it.glow > 0.001f) it.glow = (it.glow - dt * 1.6f).coerceAtLeast(0f)
        }
        bursts.removeAll { clock - it.birth > BURST_LIFE }
        return phase()
    }

    fun phase(): DrawPhase {
        if (items.isEmpty()) return DrawPhase.IDLE
        if (items.any { dealP(it) < 0.999f }) return DrawPhase.DEALING
        val revealing = items.any { it.revealStart < 0f || revealP(it) < 0.999f }
        return if (revealing) DrawPhase.REVEALING else DrawPhase.DONE
    }

    companion object {
        /** 牌面宽高比 720:1200。 */
        const val CARD_ASPECT = 0.6f

        /** 爆发粒子寿命（秒）。 */
        const val BURST_LIFE = 1.1f

        /** easeOutCubic：发牌/翻面都带一点减速感，比线性更「稳」。 */
        fun easeOut(x: Float): Float {
            val t = 1f - x
            return 1f - t * t * t
        }
    }
}
