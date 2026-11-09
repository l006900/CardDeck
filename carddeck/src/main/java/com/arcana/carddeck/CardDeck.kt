package com.arcana.carddeck

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arcana.gl.DeckSurfaceHost
import com.arcana.gl.GlEngines

/**
 * **CardDeck** —— 一个 OpenGL 渲染的扇形牌堆控件。
 *
 * 把一整副面朝下的牌排成 ∩ 形拱（圆心在控件下方，中间牌最高、两侧向外下垂、边缘被裁切），
 * 约十余张重叠成手牌状；拖动横向转环、松手吸附到最近牌位、点中某张回调 [onPick]。
 * 牌背质感（圆角裁切 + 金边 + 斜向流光 + 选中描边提亮）全在 GPU 上算，UI 线程只更新角度。
 *
 * 与业务完全解耦：只认 [cardIds] 字符串顺序，不认识「塔罗/扑克/关卡」，牌背由调用方
 * 传入 [backBitmap]。已抽走的牌用 [drawnIds] 过滤，直接从环上移除、不留透明占位。
 *
 * ```
 * CardDeck(
 *     cardIds = allCards.map { it.id },
 *     drawnIds = drawn,
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 *     background = false,          // 透明叠在你自己的背景上
 *     backBitmap = myCardBack,
 * ) { id, rect -> /* 从 rect 起飞做翻牌过场 */ }
 * ```
 *
 * @param cardIds 整副牌（按环上顺序）。顺序即渲染顺序。
 * @param drawnIds 已经抽走的牌 id，直接从环上移除。
 * @param modifier 尺寸/位置。控件高度决定扇形露出多少（建议 190–260dp）。
 * @param enabled 抽满/收尾时置 false：环还在，但不接受手势。
 * @param background true = 自带深色渐变底（独立 demo）；false = 透明背景，叠在别的 UI 之上。
 * @param backBitmap 牌背位图；null 时用纯色占位（保证不黑屏）。
 * @param emptyText 牌抽空时的占位文案；null = 什么都不画。
 * @param onPick 点中某张牌：id + 它在根坐标系里的几何（过场起飞点）。
 */
@Composable
fun CardDeck(
    cardIds: List<String>,
    modifier: Modifier = Modifier,
    drawnIds: Set<String> = emptySet(),
    enabled: Boolean = true,
    background: Boolean = true,
    backBitmap: Bitmap? = null,
    emptyText: String? = null,
    onPick: (id: String, rect: RevealRect) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current

    val remaining = remember(cardIds, drawnIds) { cardIds.filterNot { it in drawnIds } }
    val ids = remaining

    // 引擎与宿主跨重组复用：控制器只在 density 变化时重建。
    val controller = remember(density) { GlEngines.deck(density) }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val hostRef = remember { mutableStateOf<DeckSurfaceHost?>(null) }

    // GL 回调发生在触摸事件里，读不到 Compose 的最新 state —— 用 updated state 取最新值，
    // 免得每次重组都要重设 onPick lambda。
    val currentIds by rememberUpdatedState(ids)
    val currentOnPick by rememberUpdatedState(onPick)

    if (remaining.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (emptyText != null) {
                BasicText(
                    text = emptyText,
                    style = TextStyle(color = Color(0xFF8A8FA3), fontSize = 12.sp),
                )
            }
        }
        return
    }

    // 状态提交只在依赖变化时做：放进 AndroidView.update 会被每次重组触发，
    // submit() 会重新吸附、setBack() 会重复上传纹理。
    LaunchedEffect(ids) { controller.submit(ids) }
    LaunchedEffect(backBitmap) { controller.setBack(backBitmap) }
    LaunchedEffect(enabled) { controller.setEnabled(enabled) }
    LaunchedEffect(background) { controller.setBackgroundEnabled(background) }

    // GLSurfaceView 的 onPause/onResume 不会被 AndroidView 自动转发：
    // 不接生命周期，页面进后台后 GL 线程还在连续渲染（耗电 + 上下文可能残留）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> hostRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> hostRef.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned {
                origin.value = it.positionInRoot()
                hostRef.value?.let { host ->
                    host.originX = origin.value.x
                    host.originY = origin.value.y
                }
            },
        factory = { ctx ->
            DeckSurfaceHost(ctx, controller).apply {
                this.onPick = { index, rect ->
                    currentIds.getOrNull(index)?.let { id ->
                        currentOnPick(
                            id,
                            RevealRect(rect.cx, rect.cy, rect.w, rect.h, rect.rot),
                        )
                    }
                }
                this.originX = origin.value.x
                this.originY = origin.value.y
                this.gesturesEnabled = enabled
            }
        },
        update = { host ->
            hostRef.value = host
            host.gesturesEnabled = enabled
            host.originX = origin.value.x
            host.originY = origin.value.y
        },
    )
}
