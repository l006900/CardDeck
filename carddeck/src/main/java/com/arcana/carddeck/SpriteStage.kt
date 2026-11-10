package com.arcana.carddeck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * **SpriteStage** —— 在 Compose 里放一层 GPU 驱动的 2.5D 场景。
 *
 * 你把 [Scene]（一堆 [Node]：`Pose` + 贴图键 + [Effects]）和 [TextureBank]（贴图）交给它，
 * 它负责每帧的绘制、布局求值、命中与生命周期；**文字、业务规则、导航都留在 Compose 层**，
 * 库一个字都不画（一旦开始画文字就要管字体/i18n/RTL，体量翻三倍）。
 *
 * 这层是透明的：不设 [background] 时 GL 清成 `(0,0,0,0)`，Compose 的其它内容正常显示在
 * 图元之间。接入就是把它当普通组件塞进 Box/Column。
 *
 * ```
 * val scene = remember { Scene() }
 * val textures = remember { TextureBank().apply { register("tile", bitmap) } }
 * LaunchedEffect(Unit) { scene.submit(Layouts.grid(200, columns = 12).map { ... }) }
 * SpriteStage(
 *     scene = scene,
 *     textures = textures,
 *     modifier = Modifier.fillMaxWidth().height(300.dp),
 *     onPick = { pick -> /* pick.id + 四角点，供共享元素过场 */ },
 * )
 * ```
 *
 * @param scene 可提升的场景状态；[Scene.motion] 让位姿在 GL 线程每帧变化，主线程零负担。
 * @param textures 贴图注册表；Bitmap 由调用方持有，库不 recycle（见 [TextureBank]）。
 * @param background true = 画自带深色渐变底；false = 透明叠在下层 UI 上。
 * @param starfield true = 画背景星辉粒子（运动全在 GPU 顶点着色器里）。
 * @param onPick 点中某个图元，给 [Pick]（id + 四角点像素坐标）。
 * @param onDrag 拖动某个图元，给初始命中结果 + 手指归一化坐标；由调用方决定怎么改 pose。
 * @param onFps GL 线程实测帧率（约每 0.5s 一次，已切回主线程），用于性能面板。
 */
@Composable
fun SpriteStage(
    scene: Scene,
    textures: TextureBank,
    modifier: Modifier = Modifier,
    background: Boolean = false,
    starfield: Boolean = false,
    gesturesEnabled: Boolean = true,
    onPick: ((Pick) -> Unit)? = null,
    onDrag: ((Pick, Float, Float) -> Unit)? = null,
    onFps: ((Float) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember(scene, textures) { SceneRenderer(scene, textures) }
    val hostRef = remember { mutableStateOf<SpriteSurfaceHost?>(null) }
    // 持 State 而非解构值：AndroidView 的 factory 只在首次创建时执行，
    // lambda 里必须读 .value 才能拿到重组后的最新回调。
    val pickState = rememberUpdatedState(onPick)
    val dragState = rememberUpdatedState(onDrag)
    val fpsState = rememberUpdatedState(onFps)

    LaunchedEffect(scene, background) { scene.background = background }
    LaunchedEffect(scene, starfield) { scene.starfield = starfield }
    LaunchedEffect(renderer) { renderer.onFps = { fps -> fpsState.value?.invoke(fps) } }

    // GLSurfaceView 的 onPause/onResume 不会被 AndroidView 自动转发：不接生命周期，
    // 页面进后台后 GL 线程仍在连续渲染（耗电 + 上下文可能残留）。
    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> hostRef.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> hostRef.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hostRef.value?.onPause()
            renderer.release()
        }
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            SpriteSurfaceHost(ctx, renderer, scene).apply {
                this.onPick = { pick -> pickState.value?.invoke(pick) }
                this.onDrag = { pick, nx, ny -> dragState.value?.invoke(pick, nx, ny) }
                hostRef.value = this
            }
        },
        update = { host ->
            hostRef.value = host
            host.gesturesEnabled = gesturesEnabled
        },
    )
}
