# CardDeck

一个 Android 上的 OpenGL ES 2.0 渲染库，提供三个可以直接放进 Compose 的组件：

| 你想要 | 用这个 | 包 |
| --- | --- | --- |
| 扇形牌堆 / 抽牌、翻牌过场 | `CardDeck` | `com.arcana.carddeck` |
| 几百个图元每帧变换、翻面、辉光、粒子 | `SpriteStage` | `com.arcana.carddeck` |
| 多路视频 / 纹理拼画面，每路单独套滤镜 | `CompositeStage` | `com.arcana.gfx` |

三者都支持**透明叠层**：GL 只画自己的图元，其余 UI 继续用 Compose 写
（`CardDeck` 默认自带深色底，设 `background = false` 即透明）。库不画文字、不认识「牌 / 塔罗 / 抽奖」，只认 id、贴图键和位姿。

> 目录：[安装](#安装) · [快速上手](#快速上手) · [坐标与所有权约定](#坐标与所有权约定) · [API 速查](#api-速查) · [自定义 shader](#自定义-shader) · [帧调度与性能怎么量](#帧调度与性能怎么量) · [排错](#排错) · [示例工程](#示例工程) · [边界](#边界)

---

## 我该不该用

**适合**

- 同屏几百张卡片 / 贴纸，且每帧都要变位置、角度或透明度；
- 抽奖转盘、牌堆、粒子爆发这类需要 GPU 顶点/片元计算的动效；
- 多路画面拼接（监控、画中画、直播分屏），且每路要套不同滤镜；
- 已有 Compose 页面，只想把某一块换成 GPU 渲染，不想重写整个界面。

**不适合**

- 静态列表、几十个 View —— Compose 原生就够，上 GL 只是多一层 Surface；
- 需要画文字、图表、输入控件 —— 库一个字都不画，请用 Compose 叠在旁边；
- 需要解码器、播放器、音轨、音画同步、seek —— 库只接收一个 `Surface`，播放由你负责。

> 判断标准：**数量 × 每帧变化量 × 特效数，三个同时大**才划算。元素少或基本不动时，这套东西没有优势。

---

## 安装

### 环境要求

| 项 | 要求 |
| --- | --- |
| Android | `minSdk 24`，设备支持 OpenGL ES 2.0 |
| UI | Jetpack Compose（以源码 module 接入时，`ui` / `foundation` 由库用 `api` 传递，不用重复声明） |
| ABI | 默认只打 `arm64-v8a`、`armeabi-v7a` |
| 源码构建 | 需要 NDK 27 与 CMake 3.22.1（版本写在 `carddeck/build.gradle.kts`）；Kotlin / AGP / Compose BOM 版本以 `gradle/libs.versions.toml` 为准 |
| 用已构建好的 AAR | 消费方不需要 NDK / CMake（只有从源码构建 AAR 时才需要） |

库**目前没有发布到 Maven Central / JitPack**，用下面两种方式之一接入。

### 方式 A：源码 module（推荐，依赖可传递）

把 `carddeck/` 整个模块复制进你的工程：

```kotlin
// settings.gradle.kts
include(":carddeck")

// app/build.gradle.kts
dependencies {
    implementation(project(":carddeck"))
}
```

同时把本仓库的 `gradle/libs.versions.toml` 与 `build.gradle.kts` 里的插件别名对齐，或改成你自己的版本号直接写依赖。

### 方式 B：AAR

```bash
./gradlew :carddeck:assembleRelease
# 产物：carddeck/build/outputs/aar/carddeck-release.aar（内含 jni/<abi>/libarcana_frameclock.so）
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/carddeck-release.aar"))
    // AAR 不带 pom，依赖不会传递，下面这些要自己声明：
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
```

### 两个容易踩的安装坑

**1. 混淆（只有你的 app 开了 `minifyEnabled` 才相关）**

本仓库的 demo 工程**没有开启混淆**（`app/build.gradle.kts` 的 release 里 `optimization.enable = false`），
所以在这里构建、运行不会触发下面这个问题。

它只针对把 AAR 接进自己项目的场景：正式 app 的 release 通常会开 R8，而帧时钟用的是 JNI
静态注册，native 符号名写死成 `Java_com_arcana_gfx_FrameTiming_nativeCreate` 这种形式，
**同时包含完整类名和方法名**。两者任一被 R8 改名，运行时按原名就找不到符号，抛
`UnsatisfiedLinkError`。为此 AAR 里已随包带了下面这条保留规则，
通过 Gradle 依赖接入时会自动合并，**你不用做任何事**：

```proguard
-keep class com.arcana.gfx.FrameTiming { *; }
```

只有当你**手工集成 AAR**（不走 Gradle 依赖、自己写 proguard 配置）时，才需要手动把这一行加进去。

**2. x86 模拟器**：默认 ABI 不含 x86。要在模拟器上跑，在 `carddeck/build.gradle.kts` 的
`ndk.abiFilters` 里加 `"x86_64"` 后重新构建。

---

## 快速上手

以下示例省略 `androidx.compose.*` 的常规 import，代码片段均按当前库 API 编译验证过，可直接复制改参数。

### 1. 扇形牌堆 `CardDeck`

```kotlin
@Composable
fun DrawScreen(cardBack: Bitmap) {
    var drawn by remember { mutableStateOf(setOf<String>()) }
    val ids = remember { (0 until 78).map { "card-$it" } }

    CardDeck(
        cardIds = ids,
        drawnIds = drawn,
        backBitmap = cardBack,
        background = false,                       // 透明，叠在你自己的桌布上
        modifier = Modifier.fillMaxWidth().height(220.dp),
        emptyText = "牌已抽完",
    ) { id, rect ->
        // rect.cx / cy / width / height / rotation 是根坐标系 px，
        // 直接拿它当翻牌过场的起飞位置
        drawn = drawn + id
    }
}
```

`drawnIds` 里的牌会直接从环上移除，不留透明占位。`enabled = false` 时保留画面但不收手势。
牌背为 `null` 时用纯色占位，不会黑屏。

### 2. 场景图 `SpriteStage`

```kotlin
@Composable
fun CardWall(tile: Bitmap) {
    val scene = remember { Scene() }
    val textures = remember { TextureBank().apply { register("tile", tile) } }

    LaunchedEffect(Unit) {
        scene.submit(
            Layouts.grid(200, columns = 12).mapIndexed { i, pose ->
                Node(id = "n$i", pose = pose, front = "tile")
            }
        )
        // Motion 跑在 GL 线程：主线程不重组，几百个节点每帧动也不掉帧
        scene.motion = Motion { t, i, base ->
            base.copyWith(rotation = base.rotation + kotlin.math.sin(t + i * 0.1f) * 6f)
        }
    }

    SpriteStage(
        scene = scene,
        textures = textures,
        modifier = Modifier.fillMaxSize(),
        onPick = { pick ->
            // pick.corners 是 FloatArray(8)：左上→右上→右下→左下，像素坐标
            scene.burst(0.5f, 0.5f, count = 96)
        },
    )
}
```

`Layouts` 另有 `fan` / `stack` / `scatter` / `arc`；也可以自己生成 `Pose` 列表，布局函数只是纯计算。
想改单个节点用 `scene.update { list -> ... }`，想整体替换用 `scene.submit(...)`。

### 3. 多路视频合成 `CompositeStage`

库不绑定播放器。任何能把帧写进 `Surface` 的东西（MediaPlayer / ExoPlayer / MediaCodec / Camera2 / 自研解码器）都能接：

```kotlin
@Composable
fun FourUp(videoPath: String) {
    val compositor = remember { GpuCompositor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    var stats by remember { mutableStateOf(GpuStats()) }

    val source = remember {
        ExternalTextureSource("cam") { surface ->
            // 这个回调在 GL 线程；MediaPlayer 必须回主线程操作
            mainHandler.post {
                player.value = MediaPlayer().apply {
                    setSurface(surface)
                    setDataSource(videoPath)
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            }
        }
    }

    DisposableEffect(Unit) {
        // register 可以早于 View 创建：GL 初始化会自动投到 GL 线程
        compositor.register("cam", source)
        compositor.tiles = Tiles.grid(4, columns = 2).mapIndexed { i, tile ->
            TileSpec(
                source = "cam",
                tile = tile,
                crop = floatArrayOf(0f, 0f, 1f, 1f),   // 取源画面的哪一块 uv
                passes = if (i == 1) listOf(ShaderPasses.grayscale()) else emptyList(),
            )
        }
        compositor.passes = listOf(ShaderPasses.colorGrade())  // 全局链
        onDispose {
            player.value?.release()
            source.close()
            compositor.unregister("cam")
        }
    }

    Column {
        // 必须固定高度：见下面「按需渲染」那条
        Text(
            stats.summary(), maxLines = 1, softWrap = false,
            modifier = Modifier.fillMaxWidth().height(18.dp),
        )
        Box(Modifier.weight(1f)) {
            CompositeStage(
                compositor = compositor,
                policy = RenderPolicy(RenderMode.ON_DEMAND, maxFps = 30),
                modifier = Modifier.fillMaxSize(),
                onStats = { stats = it },
                onError = { Log.w("CardDeck", it) },
            )
        }
    }
}
```

`TileSpec.passes` 是**这一路独有**的链，`compositor.passes` 是拼接完成后的全局链。
`Tiles` 提供 `grid` / `columns` / `pictureInPicture` / `full`，也可以直接手写 `Tile`。

> 一个 `GpuCompositor` 只对应一个 `CompositeStage` 的生命周期：`CompositeStage` 销毁时会调用
> `compositor.release()`，跨页面请各自新建，不要复用同一个实例。

---

## 坐标与所有权约定

这几条写错是最常见的"黑屏 / 位置不对"来源：

| 概念 | 约定 |
| --- | --- |
| `Pose.x / y` | 归一化 `[0,1]`，**左上原点**，乘视图宽高才是像素 |
| `Pose.width / height` | 相对视图宽 / 高的比例，**不是 dp 也不是 px** |
| `Pose.rotation` | 度，屏幕**顺时针为正**（与 `graphicsLayer.rotationZ` 一致） |
| `Pose.flip` | `0` = 显示 `Node.back`，`1` = 显示 `Node.front`，中间值由顶点着色器做纸张弯曲 |
| `Tile.x / y` | 归一化 `[0,1]`，左上原点；到 GL viewport 的 y 翻转由库统一处理，别自己翻 |
| `TileSpec.crop` | uv 子区域 `(u0, v0, u1, v1)`；源上下颠倒时用 `flipY = true` |
| `Node.front / back` | 是 `TextureBank` 里的**字符串键**，不是 Bitmap |
| `Pick.corners` | 长度 8 的 `FloatArray`，四个角点像素坐标，顺序 左上→右上→右下→左下 |

**纹理所有权**：`TextureBank` 会保留你传入的 Bitmap 引用（GL 上下文重建后要重新上传），
但**不会调用 `Bitmap.recycle()`** —— 那是你的对象。页面退出调 `textures.clear()` 释放引用。

**线程**：`ExternalTextureSource` 的 `onSurfaceReady` 在 GL 线程，帧到达回调在解码线程；
`onStats` / `onError` / `onPick` / `onDrag` / `onFps` 已切回主线程。不要在 GL/解码线程回调里碰 GLES 或 UI 状态。

**透明叠层**：宿主用的是 `setZOrderOnTop(true)`，GL surface 会盖住**同区域**的 Compose View。
文字、按钮请放在 GL 区域之外，或叠在另一个不重叠的层级上。

---

## API 速查

### `com.arcana.carddeck`

| 签名 | 说明 |
| --- | --- |
| `CardDeck(cardIds, modifier, drawnIds, enabled, background, backBitmap, emptyText, onPick)` | 扇形牌堆；默认 `background = true`。`onPick: (String, RevealRect) -> Unit` |
| `SpriteStage(scene, textures, modifier, background, starfield, gesturesEnabled, onPick, onDrag, onFps)` | 2.5D 场景；默认 `background = false`（透明） |
| `Scene()` | `submit(list)` / `update { }` / `motion` / `burst(nx, ny, color, count)` / `starfield` / `background` / `size` / `hitTest(...)` |
| `Pose` / `Effects` / `Node` / `Pick` / `Motion` | 场景图的数据类与每帧位姿函数 |
| `Layouts.fan / grid / stack / scatter / arc` | 返回 `List<Pose>` 的纯函数 |
| `TextureBank` | `register(key, bitmap)` / `registerAll(map)` / `clear()` |
| `RevealRect(cx, cy, width, height, rotation)` | 点中牌在根坐标系里的几何，单位 px |

### `com.arcana.gfx`

| 签名 | 说明 |
| --- | --- |
| `GpuCompositor()` | `tiles` / `passes` / `policy` / `animating` / `onStats` / `onError`；`register(key, source)` / `unregister` / `invalidate()` / `release()` |
| `CompositeStage(compositor, modifier, policy, onStats, onError)` | 把合成管线放进 Compose，并接管生命周期 |
| `FrameSource` 实现 | `ExternalTextureSource(name, onSurfaceReady)`（OES 视频）、`Texture2DSource(bank, key, w, h)`（静态贴图）、`DynamicTexture2DSource(w, h, fill)`（CPU 动态纹理，可用 `active` 停帧） |
| `Tile` / `TileSpec` / `Tiles` | 画面位置、uv 裁切、每路独立链、常用布局 |
| `ShaderPass` / `ShaderPasses` | 自定义 GLSL 与内置预设 |
| `RenderPolicy(mode, maxFps)` | 默认 `CONTINUOUS` / `60`；`maxFps` 取值 `1..120` |
| `GpuStats` | `summary()` 可直接放进 HUD |
| `FrameTiming(capacity)` | 帧延迟时钟；`System.loadLibrary` 在类初始化时自动完成 |

内置预设：`grayscale()`、`colorGrade(brightness, contrast, saturation, warmth)`、
`blur(radiusPx)`（返回横竖两个 pass）、`sobel(strength)`、`chromaShift(offsetPx)`、
`vignette(amount, scanlines)`、`beauty()`、`glitch()`、`custom(name, fragment, ...)`。

---

## 自定义 shader

给 `ShaderPass` 一段完整的 GLSL ES 2.0 片元源码即可，不用改引擎、不用重新编译库：

```kotlin
val ripple = ShaderPasses.custom(
    name = "ripple",
    fragment = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2  uTexel;
        uniform vec4  uParams;
        uniform float uTime;
        varying vec2 vUv;
        void main() {
            vec2 p = vUv - vec2(0.5);
            vec2 dir = normalize(p + vec2(0.0001));
            float wave = sin(length(p) * 55.0 - uTime * 4.0) * 0.010;
            vec4 c = texture2D(uTexture, vUv + dir * wave);
            c.rgb += vec3(0.10, 0.06, 0.0) * (0.5 + 0.5 * sin(uTime + length(p) * 12.0));
            gl_FragColor = c;
        }
    """.trimIndent(),
)
compositor.passes = listOf(ripple)
```

| uniform | 含义 |
| --- | --- |
| `uTexture` | 上一个 pass 的输出，绑定在纹理单元 0 |
| `uTexel` | `(1/宽, 1/高)`，做邻域采样用；每路独立链里是**那一格**的尺寸 |
| `uParams` | `ShaderPass.params` 的 xyzw，恰好 4 个 float |
| `uTime` | 秒，单调递增 |
| `vUv` | 由顶点着色器传入的 uv |

规则：`ShaderPass` 构造时会校验源码里出现 `uTexture` 与 `gl_FragColor`、`params` 长度为 4，
不满足直接抛异常；GLSL 本身写错则不会崩 GL 线程，只回调 `onError`。
相同源码只编译一次并缓存 program。`enabled = false` 的 pass 会被跳过但留在链里，方便做 A/B 对比。

---

## 帧调度与性能怎么量

```kotlin
compositor.policy = RenderPolicy(RenderMode.ON_DEMAND, maxFps = 30)
compositor.animating = true   // 有依赖 uTime 的动态 shader 时必须开，否则按需渲染会停在静态帧
```

- `CONTINUOUS`：按 `maxFps` 硬上限出帧，没到间隔直接跳过整帧绘制。
- `ON_DEMAND`：落到 `RENDERMODE_WHEN_DIRTY`，只在**新帧到达 / 参数变化 / 显式 `invalidate()`** 时绘制，
  连 GL 渲染回调都不触发。视频源会自然贴合解码帧率；静态源会完全停住。

`GpuStats`（约每 0.5s 回调一次，已切主线程）里的数字都是实测值，不是估算：

| 字段 | 含义 |
| --- | --- |
| `fps` | 只统计**真正渲染**的帧，被策略跳过的帧不计入 |
| `cpuFrameMs` | GL 线程该帧从进入到返回的墙钟时间（含 GLES 提交，不含 GPU 执行） |
| `drawCalls` / `gpuPasses` | 这一帧实际提交的 draw call 与 pass 数 |
| `textureBytes` | 库自己持有的 FBO + 纹理字节（不含 BufferQueue） |
| `latencyP50Ms` / `latencyP95Ms` | 帧到达 → 被消费的延迟分布，来自 C++ 帧时钟 |
| `droppedFrames` | 生产者入队时队列已满的次数，背压信号 |

**按需渲染的两个前提**（自己写 HUD 时最容易忽略）：

1. HUD 文本必须**固定高度**：`maxLines = 1, softWrap = false`，并给状态行预留固定高度。
   文本在 1 行 / 2 行之间抖动会带动下方 `GLSurfaceView` 高度抖动，每次 `onSurfaceChanged`
   都会唤醒按需渲染，看起来像"停不下来"。
2. 完全空闲时 GL 线程不会回调 `onStats`，HUD 会停在上一次快照。自己加一个一次性延迟判定，
   超过 1s 没新指标就显示「空闲 · 0 fps」，别把 0 帧显示成上一次的高 fps；**不要**用常驻轮询，
   那本身又是一次次重组。

其余生命周期（页面进后台暂停 GL 线程、surface 销毁释放 GL 对象、上下文重建重传纹理）
三个组件都已接管，不用手动调。

---

## 排错

| 现象 | 原因 / 处理 |
| --- | --- |
| 自己项目的 release 包报 `UnsatisfiedLinkError: nativeCreate`（且开了 `minifyEnabled`） | R8 改了 JNI 类名；加上面的 `-keep` 规则，或改走 Gradle 依赖让规则自动合并 |
| 模拟器装不上 / 找不到 `.so` | 默认 ABI 只有 arm64-v8a、armeabi-v7a，补 `x86_64` 重新构建 |
| 画面全黑 | 贴图键没注册进 `TextureBank`；或 `cardIds` 全在 `drawnIds` 里；或源没 `register` |
| 画面全白 / 露出窗口底色 | 宿主是透明 EGL + `setZOrderOnTop`，下层没有可透出的内容；给容器设自己的背景色 |
| GL 区域里的 Compose 文字不见了 | `setZOrderOnTop` 会盖住同区域 View，把文字移出该区域 |
| 某一路上下颠倒 | 给 `TileSpec` 设 `flipY = true` |
| 自定义 shader 没效果 | 看 `onError`；检查是否声明 `uTexture`、是否写了 `gl_FragColor`、`enabled` 是否为 true |
| 动态 shader 在 `ON_DEMAND` 下不动 | 设 `compositor.animating = true`，或改用 `CONTINUOUS` |
| 页面回来黑屏 | 用 `CompositeStage` / `SpriteStage` / `CardDeck` 时已自动处理；若自己裸接 `GpuCompositor`，上下文重建后要重新 `register` |
| 退到后台仍在耗电 | 同上：三个组件已接 `ON_PAUSE` / `ON_RESUME`；裸接 `GLSurfaceView` 要自己转发 |

---

## 示例工程

`app/` 模块有四个可运行页面：扇形牌堆、场景图、性能对比、GPU 合成（含设备现场编码的测试视频）。

```bash
./gradlew :app:assembleDebug :carddeck:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.arcana.carddeck/.MainActivity
```

---

## 边界

库只负责「把画面画出来」：给 `Surface` 就能接视频，给 Bitmap 就能画图元，给 GLSL 就能加特效。
它**不提供**解码器、播放器、音频、音画同步、文字渲染或跨平台后端；FFmpeg / GStreamer、
ExoPlayer、Camera2 都通过 `ExternalTextureSource` 的 `Surface` 契约接进来，由你自己的工程负责。
