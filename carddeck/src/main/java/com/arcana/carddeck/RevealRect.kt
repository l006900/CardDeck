package com.arcana.carddeck

/**
 * 被点中那张牌此刻在**根坐标系**里的几何（px）。
 *
 * 翻牌 / 飞牌过场从这里起飞：拿 `cx/cy` 当起点、`width/height` 当起始尺寸、`rotation`
 * 当起始角度，animator 直接读这几个值就能无缝接上牌堆里那张牌。
 */
data class RevealRect(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
)
