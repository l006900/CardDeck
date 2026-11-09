package com.arcana.gfx

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
 * **CompositeStage** —— 在 Compose 里放一层 GPU 合成管线。
 *
 * 调用方负责准备 [GpuCompositor]（注册画面源、给 [GpuCompositor.tiles] / passes），
 * 这里只负责 EGL 生命周期、帧调度策略与指标回调。文字、按钮、业务状态全留 Compose 层。
 *
 * ```
 * val compositor = remember { GpuCompositor() }
 * LaunchedEffect(Unit) {
 *     compositor.register("cam", ExternalTextureSource { surface -> player.setSurface(surface) })
 *     compositor.tiles = Tiles.grid(4).map { TileSpec("cam", it) }
 *     compositor.passes = ShaderPasses.blur(2f) + ShaderPasses.colorGrade()
 * }
 * CompositeStage(compositor, policy = RenderPolicy(RenderMode.ON_DEMAND, 30),
 *                modifier = Modifier.fillMaxSize(), onStats = { stats = it })
 * ```
 *
 * @param policy 帧调度；变化时会同步到 GLSurfaceView 的 renderMode。
 * @param onStats 约每 0.5s 一次的实测指标（已切回主线程）。
 * @param onError 自定义 GLSL 编译失败等可恢复错误。
 */
@Composable
fun CompositeStage(
    compositor: GpuCompositor,
    modifier: Modifier = Modifier,
    policy: RenderPolicy = RenderPolicy(),
    onStats: ((GpuStats) -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostRef = remember { mutableStateOf<CompositeSurfaceHost?>(null) }
    // 记录真正应用过的策略值。HUD 指标刷新会让本 Composable 重组，若 update 里无条件
    // applyPolicy→requestRender，就会形成「指标→重组→重画→指标」自激循环，
    // 把 ON_DEMAND 打成永久空转。策略值没变时绝不碰 GLSurfaceView。
    val appliedPolicy = remember { mutableStateOf<RenderPolicy?>(null) }
    val statsState = rememberUpdatedState(onStats)
    val errorState = rememberUpdatedState(onError)

    LaunchedEffect(compositor) {
        compositor.onStats = { statsState.value?.invoke(it) }
        compositor.onError = { errorState.value?.invoke(it) }
    }
    LaunchedEffect(compositor, policy.mode, policy.maxFps) { compositor.policy = policy }

    // GLSurfaceView 不自动跟随生命周期：不接的话页面进后台 GL 线程仍在连续渲染。
    DisposableEffect(lifecycleOwner, compositor) {
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
            compositor.release()
        }
    }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            CompositeSurfaceHost(ctx, compositor).apply {
                applyPolicy(policy)
                appliedPolicy.value = policy
                hostRef.value = this
            }
        },
        update = { host ->
            hostRef.value = host
            if (appliedPolicy.value != policy) {
                appliedPolicy.value = policy
                host.applyPolicy(policy)
            }
        },
    )
}
