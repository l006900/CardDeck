package com.arcana.gl

/**
 * 引擎工厂 —— 库对外的唯一「造引擎」入口。
 *
 * 目前只有扇形牌堆（[deck]）；后续布局/场景引擎都从这里出，调用方不需要 new 具体实现。
 */
object GlEngines {

    /** 扇形牌堆引擎（牌背环，拖动转环 / 点选 / 松手吸附）。 */
    fun deck(density: Float): DeckController = DeckEngine(density)
}
