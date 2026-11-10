# CardDeck

一个 Android 上的 OpenGL ES 2.0 渲染库，提供三个可以直接放进 Compose 的组件：

| 你想要 | 用这个 | 包 |
| --- | --- | --- |
| 扇形牌堆 / 抽牌、翻牌过场 | `CardDeck` | `com.arcana.carddeck` |
| 几百个图元每帧变换、翻面、辉光、粒子 | `SpriteStage` | `com.arcana.carddeck` |
| 多路视频 / 纹理拼画面，每路单独套滤镜 | `CompositeStage` | `com.arcana.gfx` |
| 摄像头实时采集、把合成结果录成 MP4 | `CameraFrameSource` / `VideoRecorder` | `com.arcana.gfx` |

三个 UI 组件都支持**透明叠层**：GL 只画自己的图元，其余 UI 继续用 Compose 写
（`CardDeck` 默认自带深色底，设 `background = false` 即透明）。库不画文字、不认识「牌 / 塔罗 / 抽奖」，只认 id、贴图键和位姿。
采集与录制是两个配合 `CompositeStage` 使用的类，不是独立控件。

> 目录：[安装](#安装) · [两个 artifact](#两个-artifact按需选) · [快速上手](#快速上手) · [坐标与所有权约定](#坐标与所有权约定) · [API 速查](#api-速查) · [自定义 shader](#自定义-shader) · [帧调度与性能怎么量](#帧调度与性能怎么量) · [采集与录制](#采集与录制) · [视频源接入](#视频源接入不绑定解码器) · [排错](#排错) · [示例工程](#示例工程) · [边界](#边界)

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
- 需要自研解码器、音轨、音画同步、seek、倍速 —— 库只提供采集源和编码输出，
  解码播放仍交给 `MediaPlayer` / ExoPlayer，库只接收它们写进的那个 `Surface`。

> 判断标准：**数量 × 每帧变化量 × 特效数，三个同时大**才划算。元素少或基本不动时，这套东西没有优势。

---

## 两个 artifact，按需选

| 坐标 | 内容 | 什么时候用 |
| --- | --- | --- |
| `com.arcana:carddeck:0.5.1` | 全部：`CardDeck` / `SpriteStage` 牌桌控件 + `com.arcana.gfx` 合成层 | 要现成的牌堆 / 抽牌 / 翻面 UI |
| `com.arcana:gfx:0.5.1` | 只有合成层：`CompositeSurfaceHost` / `GpuCompositor` / `Tiles` / `RenderPolicy` / `VideoRecorder` | 自己有渲染/控件，只要多路合成 |

`carddeck` 用 `api` 依赖 `gfx`，所以**依赖 `carddeck` 的项目照样能直接用 `com.arcana.gfx.*`**，
两者不需要重复声明。

`gfx` 包内自带 `GlUtil` / `MsaaConfigChooser` / `TextureBank`，**不引用任何别的包**，
而且这三样对消费方**公开**：

| 类 | 用途 |
| --- | --- |
| `GlUtil` | program 编译 / uniform 与 attrib 定位 / Bitmap 上传纹理 / 单位 quad / 16×16 卡牌网格 buffer / 颜色换算 |
| `MsaaConfigChooser` | 4× MSAA 的 EGL 配置选择器；自建 `GLSurfaceView` 时 `setEGLConfigChooser(MsaaConfigChooser())` 即可 |
| `TextureBank` | Bitmap → 纹理的缓存与复用（按 key 去重、超宽自动下采样） |

**别在消费方再抄一份**：自己往管线里塞 `FrameSource` 时直接 `import com.arcana.gfx.GlUtil`。
两份同名工具类最容易出现的情形是「改了一处、另一处忘了」，最后调试分不清跑的是哪份。

库不引用宿主的东西，宿主也不该抄库的东西 —— 分界只有一句话：**库管「怎么把画面拼起来并送出去」，
你管「画面里画什么」**。牌面光照、翻牌形变、粒子、牌堆几何这些内容库一概不提供，全在你自己那边写。

## 安装

### 环境要求

| 项 | 要求 |
| --- | --- |
| Android | `minSdk 24`，设备支持 OpenGL ES 2.0 |
| UI | Jetpack Compose（以源码 module 接入时，`ui` / `foundation` 由库用 `api` 传递，不用重复声明） |
| ABI | release 只打 `arm64-v8a`、`armeabi-v7a`；debug 额外带 `x86_64`（模拟器可跑） |
| 源码构建 | 需要 NDK 27 与 CMake 3.22.1（版本写在 `gfx/build.gradle.kts`）；Kotlin / AGP / Compose BOM 版本以 `gradle/libs.versions.toml` 为准 |
| 用已构建好的 AAR | 消费方不需要 NDK / CMake（只有从源码构建 AAR 时才需要） |

**16 KB 页兼容**：库里的 `libarcana_frameclock.so`（两个 ABI）在 CMake 里用
`-Wl,-z,max-page-size=16384` 链接，ELF `PT_LOAD` 的 `p_align = 0x4000`，
Android 15+ 的 16 KB 内核可直接加载，可调试应用也不会再弹「应用兼容性」警告。
从源码自行构建时别删掉 `target_link_options`，否则会退回 lld 默认的 4 KB 对齐。
接入后若仍看到该警告，用 `llvm-readelf -l your.so` 检查**你自己的其它 .so**——
系统会把 APK 里所有未对齐的库一起列出来（其中一项报「未知错误」也可能只是另一项未对齐的连带结果）。

库自带 `maven-publish` 配置。三种接入方式按推荐顺序排列。

### 方式 A：依赖坐标（推荐，一行搞定）

**别人怎么用**（发布见下一节；JitPack 首次拉取会现编一次，之后是缓存好的 AAR）：

```kotlin
// settings.gradle.kts —— 加一次，全项目可用
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.l006900.CardDeck:gfx:0.5.1")         // 只要合成层
    // implementation("com.github.l006900.CardDeck:carddeck:0.5.1")  // 连牌桌控件一起
}
```

多模块仓库的坐标规则是 `com.github.<user>.<repo>:<artifactId>:<tag>`，
所以 `:gfx` 模块的 artifactId 就是 `gfx`（写在 `gfx/build.gradle.kts` 的 `publication` 里）。

**本机怎么发**（自己调试用，别人用不到这一步）：

```bash
./gradlew :gfx:publishToMavenLocal    # → ~/.m2/repository/com/arcana/gfx/
./gradlew :carddeck:publishToMavenLocal
```

**上面这一行依赖就够了**：`ui` / `foundation` / Compose BOM / lifecycle 都由库用 `api` 传递，
R8 保留规则随 AAR 自动合并，`libarcana_frameclock.so`（两个 ABI）在最终 APK 打包时自动并入。
消费方**不需要 NDK / CMake**，也不需要抄 `libs.versions.toml`。
这条路径已在两个独立工程实测（其中一个宿主自己就有 `com.arcana.gl.*` 全套引擎）：
只写一行依赖 → 编译通过、无 duplicate class → APK 内出现 native 库 → 真机 45 fps 跑通合成。

> `0.x` 阶段建议锁精确版本（`gfx:0.5.1`），别写 `0.+`：MINOR 位可能带公开 API 变更。

## 发布到 JitPack

三步，之后别人就能用上面那行坐标：

```bash
git add -A && git commit -m "release 0.5.1" && git push
git tag 0.5.1 && git push origin 0.5.1
# 打开 https://jitpack.io/#l006900/CardDeck → Look up → 选 0.5.1 → Get it
```

**tag 名不要带 `v`**：JitPack 拿 tag 名当版本号，坐标里的 `0.5.1` 才和 POM 的 `version = "0.5.1"` 对齐。

`jitpack.yml` 已经处理好三个容易翻车的点：

- 只装 `:gfx` 与 `:carddeck`，不碰 `:app`（demo 不发布）；
- `sdkmanager` 先装 **NDK 27.0.12077973 + CMake 3.22.1** —— gfx 那 320 行 C++ 要它们，JitPack 镜像默认没有这组版本；
- `openjdk17`（AGP 9 的最低要求）+ `--no-daemon`。

构建完成后自检：

```bash
curl -sI https://jitpack.io/com/github/l006900/CardDeck/gfx/0.5.1/gfx-0.5.1.aar | head -1
# HTTP/1.1 200 OK 就说明 AAR 已生成，全世界都能解析
```

> **前提：仓库必须是 public。** JitPack 只能读公开仓库，私有仓库要走它的付费计划。

### Maven Central（要「别人零配置」只有这条）

好处是消费方连 `maven { url }` 都不用加 —— `mavenCentral()` 默认就在仓库列表里。
代价是首次发布要人工几步：

1. Group 必须换：`com.arcana` 需要证明拥有 `arcana.com` 域名，一般用
   `io.github.l006900`（GitHub 用户名即可验证）。坐标会变成 `io.github.l006900:gfx:0.5.1`。
2. [central.sonatype.com](https://central.sonatype.com) 注册命名空间 → 验证。
3. 生成 GPG 密钥并配置 `signing` 插件（AAR 也要签）。
4. `./gradlew publishToMavenCentral`（或上传 zip bundle），审核通过后全世界可解析。

`publishing` 里的 `pom`、`withSourcesJar()`、`consumerProguardFiles` 已经按 Central 要求备好，
差的只有 group 改名、签名和账号凭据三样。

### 私有 Maven（Nexus / Artifactory / GitHub Packages）

在 `gfx/build.gradle.kts` 与 `carddeck/build.gradle.kts` 的 `publishing.repositories` 里换成你的地址即可，
`publication` 部分不用动。GitHub Packages 的坐标是 `https://maven.pkg.github.com/<user>/<repo>`，
消费方需要带 token 的 `credentials {}`。

### 方式 B：源码 module（要改库本身时用）

把 `carddeck/` 与 `gfx/` 两个模块一起复制进你的工程：

```kotlin
// settings.gradle.kts
include(":carddeck")
include(":gfx")

// app/build.gradle.kts
dependencies {
    implementation(project(":carddeck"))   // 会通过 api 带上 :gfx
    // 只要合成层：implementation(project(":gfx"))
}
```

同时把本仓库的 `gradle/libs.versions.toml` 与 `build.gradle.kts` 里的插件别名对齐，或改成你自己的版本号直接写依赖。

### 方式 C：AAR 文件

```bash
./gradlew :carddeck:assembleRelease :gfx:assembleRelease
# 产物：gfx/build/outputs/aar/gfx-release.aar
#       carddeck/build/outputs/aar/carddeck-release.aar
# 两者都内含 jni/<abi>/libarcana_frameclock.so（carddeck 通过 api 依赖 gfx，但 AAR 不会内嵌）
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/gfx-release.aar"))         // 必须先加，carddeck 依赖它
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

它只针对把 AAR 接进自己项目的场景：正式 app 的 release 通常会开 R8，而帧时钟与
GPU 计时用的是 JNI 静态注册，native 符号名写死成 `Java_com_arcana_gfx_FrameTiming_nativeCreate`
这种形式，**同时包含完整类名和方法名**。两者任一被 R8 改名，运行时按原名就找不到符号，抛
`UnsatisfiedLinkError`。为此 AAR 里已随包带了下面这条保留规则，
通过 Gradle 依赖接入时会自动合并，**你不用做任何事**：

```proguard
-keep class com.arcana.gfx.FrameTiming { *; }
-keep class com.arcana.gfx.GpuTimer { *; }
```

只有当你**手工集成 AAR**（不走 Gradle 依赖、自己写 proguard 配置）时，才需要手动把这一行加进去。

**2. x86 模拟器**：**debug 变体已带 `x86_64`**，在模拟器上直接能跑；只有
**release AAR 刻意只打 `arm64-v8a` + `armeabi-v7a`**（控制发布体积）。如果你的正式包需要
x86 平板 / ChromeOS，在 `carddeck/build.gradle.kts` 的 `defaultConfig.ndk.abiFilters` 里补 `"x86_64"`。

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
        // 必须固定高度：见下面「HUD 的三个前提」。
        // 用 BasicText（compose foundation，随库传递）；想要 Material 的 Text 请自行加 material3。
        BasicText(
            stats.summary(), maxLines = 1, softWrap = false,
            modifier = Modifier.fillMaxWidth().height(18.dp),
        )
        Box(Modifier.weight(1f)) {
            CompositeStage(
                compositor = compositor,
                policy = RenderPolicy(maxFps = 30),
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
| `GpuCompositor()` | `tiles` / `passes` / `policy` / `animating` / `frameSink` / `onStats` / `onError`；`register(key, source)` / `unregister` / `invalidate()` / `postGl { }` / `release()` |
| `CompositeStage(compositor, modifier, policy, onStats, onError)` | 把合成管线放进 Compose，并接管生命周期 |
| `FrameSource` 实现 | `ExternalTextureSource(name, onSurfaceReady, bufferWidth, bufferHeight)`（OES 视频/摄像头；`framesArrived()` 查推帧数）、`Texture2DSource(bank, key, w, h)`（静态贴图）、`DynamicTexture2DSource(w, h, fill)`（CPU 动态纹理，可用 `active` 停帧）。自定义源可覆写 `hasPendingFrame()` 参与帧驱动（只查询、不消费，默认 false） |
| `CameraFrameSource(context, width, height, onFailure)` | Camera2 采集源；`framesArrived()` / `rotationDeg` / `ready` / `close()` |
| `VideoRecorder(file, width, height, fps, bitRate, onError)` | `start()` / `stop()` / `releaseGl()` / `framesWritten()` / `close()`；实现 `GpuFrameSink` |
| `GpuFrameSink` | 自定义一帧输出接出点；`onFrame(texture, w, h, ptsNs)` 在 GL 线程回调 |
| `Tile` / `TileSpec` / `Tiles` | 画面位置、uv 裁切、每路独立链、常用布局 |
| `ShaderPass` / `ShaderPasses` | 自定义 GLSL 与内置预设 |
| `RenderPolicy(maxFps, resolutionScale, maxTextureBytes)` | 渲染模式恒为连续回调（不可配置），由内容代次门控决定画不画；默认 `60` / `1.0` / `0`；`chooseScale(w, h, maxScale, budget)` 纯函数 |
| `GpuStats` | `summary()` / `systemLine()` / `flowLine()` 可直接放进 HUD；三行都要固定单行高度，并按可用宽度自适应字号 |
| `FrameTiming(capacity)` | C++ 无锁帧时钟：延迟 p50/p95、丢帧、帧间隔抖动；`System.loadLibrary` 自动完成 |
| `GpuTimer` | C++ 侧 `GL_EXT_disjoint_timer_query`；`supported` / `pollMs()`，设备不支持时返回 -1 |
| `SystemMeters(context)` | `sample()` → `SystemSample`（进程 CPU%、PSS/native/graphics、整机功率） |

内置预设：单输入滤镜 `grayscale()`、`colorGrade(brightness, contrast, saturation, warmth)`、
`blur(radiusPx)`（返回横竖两个 pass）、`sobel(strength)`、`chromaShift(offsetPx)`、
`vignette(amount, scanlines)`、`invert()`、`sepia(tint)`、`posterize(levels)`、`pixelate(cellPx)`、
`sharpen(amount)`、`emboss(amount)`、`duotone(hueShift)`、`hueRotate(deg)`、`gamma(g)`、
`nightVision(grain)`、`oldFilm(strength)`、`kaleidoscope(segments)`、`lensDistort(strength)`；
组合链 `beauty()`、`glitch()`、`cinematic()`、`retro()`、`cyberpunk()`、`dream()`；
**双输入** `blend(secondaryKey, amount, mode)`（正片叠底/滤色/叠加/相加/差值）、
`crossfade(secondaryKey, durationSec)`（循环转场）；
`custom(name, fragment, p0..p3, secondarySource = ...)`。

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

### 双输入：混合 / 转场（两路源合成）

把 `ShaderPass.secondarySource` 设成另一个**已注册源**的 key，合成器会先把那一路源
（2D / 摄像头 / 视频都行，OES 源会先 blit 成 2D 再喂入）渲染进一块 scratch FBO，
再绑给可选的 `sampler2D uTexture2`。于是 shader 能同时拿到本路结果（`uTexture`）与
另一路（`uTexture2`），做混合或转场，不需要调用方自己管理第二张纹理。

```kotlin
// 本路（视频）与已注册的 "proc" 程序化源做叠加混合（mode=2 叠加，amount=0.5）
compositor.passes = listOf(ShaderPasses.blend("proc", amount = 0.5f, mode = 2))
// 或循环转场：A（本路）→ B（proc）→ A，每 durationSec 秒一个完整周期
compositor.passes = listOf(ShaderPasses.crossfade("proc", durationSec = 4f))
compositor.animating = true   // 转场依赖 uTime，必须开
```

`blend` 的 `uParams = (amount, 0, mode, 0)`，`crossfade` 的 `uParams = (0, durationSec, 0, 0)`，
进度由 `uTime` 推导，不占 `uParams` 自由度。自定义双输入 pass 也要在 fragment 里声明
`uniform sampler2D uTexture2;`，否则构造时抛异常。

### 多画面布局

`Tiles` 提供 `grid(count, columns)`、`columns(count)`、`pictureInPicture()`、`focus(count)`（1 大 + N 缩略）、
`sidebar(count)`（主图 + 侧栏缩略）、`full()` 以及 `custom(tiles)`。所有布局返回归一化 `Tile` 列表，
与 `compositor.tiles` 一一对应；切换布局时只需替换 `tiles`，合成器会按新的几何重排各路源。

---

## 帧调度与性能怎么量

```kotlin
compositor.policy = RenderPolicy(maxFps = 30)
compositor.animating = true   // 只有依赖 uTime 的 shader 才需要；CPU 自产纹理源不用管
```

**渲染模式恒为 `RENDERMODE_CONTINUOUSLY`，不可配置。** 这不是省电上的妥协，而是正确性要求：
`WHEN_DIRTY` 下每次 `requestRender()` 只换**一次** `onDrawFrame` 回调，一旦这次回调被
fps 节流或内容门控跳过，**没有下一次补偿**，该帧就永久丢失——表现就是「一直卡帧」。
连续回调保证节拍永不断链，省电交给门控：内容没变时回调只做几次比较（最多再加一次全屏 blit）。

**「不画」不等于「不 swap」——这是 30fps 卡帧的真凶（第五个坑）。**
`GLSurfaceView` 在连续模式下不管 renderer 画没画，每次迭代都会 `eglSwapBuffers`；
而双缓冲的后备缓冲里装的是**两帧以前**的画面。于是「隔帧画」的 30fps 场景实际上是：

```
回调 k    合成 N     → 上屏 N
回调 k+1  不画，swap → 上屏 N-1（旧缓冲）
回调 k+2  合成 N+1   → 上屏 N+1
回调 k+3  不画，swap → 上屏 N（旧缓冲）
```

真机录屏逐帧差实测正是 `动 动 停 停` 四帧一循环（`4.7 4.7 0 0`，同一页面切到 60fps
立刻变成每帧都变、完全不卡）。肉眼看就是「一顿一顿、时不时插前面的某一帧」。

所以门控要返回三种模式（`RenderPolicy.presentMode`）：`DRAW` / `REPEAT` / `IDLE`。
**节流跳过的那次回调必须 `REPEAT`**：不重新合成，只把上一帧的输出再全屏 blit 一次
（约 0.3ms，比重跑「每路源 + 每格特效链 + 全局后处理」便宜一个数量级），把后备缓冲也刷成
当前画面。贴一次即可，两块缓冲一致之后继续跳过就安全了——静态页依然是 0 帧空转。
修复后同一场景实测逐帧差变成 `动 停 动 停`（每组恰好 2 帧），不再出现 3 帧以上的停顿。

**门控判据：出场节拍与内容代次彻底解耦。** 纯函数
`RenderPolicy.presentMode(pending, dirty, animating, selfProducing, externalPresent, intervalOk, needsRepaint)`
先决定要不要**重新合成**（内部就是 `shouldDraw`，语义与旧版一致）：

- `dirty`（布局 / 参数变化）→ 立刻画；
- **`externalPresent`（布局里有视频 / 摄像头源）→ 每个回调都画**，出场恒为屏幕刷新率。
  内容由源按自己的节奏换，屏幕按固定 16.7ms 换，两者互不牵制；30fps 内容自然显示两次。
  这条产生的「显示层重复帧」只上屏、**不进编码器**；
- 纯程序化布局：`animating`（uTime shader）/ `selfProducing`（CPU 自产纹理，
  `FrameSource.selfAnimating`）→ 内容每帧都变，按 `intervalOk` 按 `maxFps` 节流
  （节流跳过的回调走 `REPEAT`）；
- 以上都没有 → **绝不重画**，两块缓冲一致后 `IDLE`（静态页零空转）。

**不要让「新帧到达」去等 vsync 槽位。** 这是踩过的第四条错路：源在 33.3ms 周期上产帧、
槽位在 16.7ms 网格上开启，两个周期只有不可通约的一点点差，相位会缓慢漂移；某帧一旦落在
「刚画完的下一个回调」，就要多等一格，出场间隔于是在 33ms / 50ms 之间来回跳（1 帧、2 帧的
节拍突变），肉眼就是卡顿——**视频页面尤其明显**。正确做法是内容跟着源、出场跟着屏幕：
每个回调都画，内容延迟恒定为一个 vsync，屏幕上的帧时长自然均匀。

**限速用 vsync 槽位计数，不用时间阈值。** GL 回调被 `eglSwapBuffers` 锁相在 vsync 上
（~60/s）：时间阈值法（`now - last >= interval`）在 GL 线程迟到大 ~1ms 时会把本该
+33ms 的下一帧滑到 +50ms，实测间隔序列每秒混入 3~5 个 41~50ms 尖峰——画面就是
「同一格停三拍再跳两格」的顿挫感。槽位计数按
`N = round(帧间隔 / 实测回调周期)`（EMA 估计）每 N 个回调出一次帧，节奏锁死在 vsync
整数格上，单次迟到由下一格立刻追平。

**录制喂帧与显示出场是两条独立判据。** 显示为了平滑会重复贴同一帧，编码器绝不能收到它：
只在 `pending || animating || selfProducing || 无外部源` 时才 `onFrame`，并且 PTS 把
真实经过时间**吸附到目标帧间隔的整数格**上（既均匀又忠实；源比目标快时按 0.75×间隔
降采样）。实测 6s 录制：181 帧、PTS 全部 33.3ms、无重复帧、无倒退。

真正的 `updateTexImage()` 放在门控**之后**，帧一直留在 `BufferQueue` 里，
积压多帧时一次追到最新。帧到达与 GL 回调是两个独立时钟，早先「先消费再门控」
会让被跳过的回调吞掉新帧、绘制回调重复旧帧。

**验收口径必须看内容，不能看帧数。** 只数「每秒画了几帧」会把重复帧粉饰成满帧：
真机上曾出现 `draw=30/s` 却每一帧画面都和上一帧完全相同。正确做法有两层：

1. 画完读回合成结果的一整行像素算指纹，与上一帧比对，`dup` 必须为 0；
2. **最终裁判是「录屏 + 逐帧像素差」**（`adb shell screenrecord` → 抽成灰度帧 →
   相邻帧 MAD）。它看的是**屏幕真正显示了什么**，而不是 renderer 以为自己画了什么。
   上面那个 30fps 卡帧就是靠它才定位到：renderer 每秒确实合成 30 帧、间隔 33ms 稳定，
   但屏幕上呈现的是 `动 动 停 停` —— 问题根本不在合成节拍，而在 swap 出来的是哪块缓冲。
   判断标准：`maxFps` 为屏幕刷新率一半时，相邻帧差应当是严格的 `动 停 动 停`，
   任何连续 3 帧以上的「停」都是真实卡顿。

`GpuStats`（约每 0.5s 回调一次，已切主线程）里的数字都是实测值，不是估算：

| 字段 | 含义 |
| --- | --- |
| `fps` | 只统计**真正渲染**的帧，被策略跳过的帧不计入 |
| `cpuFrameMs` | GL 线程该帧从进入到**整帧工作做完**的墙钟时间（含 GLES 提交与指标窗口开销，**不含** GPU 执行）。别在帧中途提前算，会漏掉最慢的那一段 |
| `gpuFrameMs` | `GL_EXT_disjoint_timer_query` 实测的 GPU 执行时间；`-1` = 设备不支持或本轮 disjoint 作废，HUD 应显示 `n/a`，不要拿 CPU 时间冒充 |
| `cpuPercent` / `pssKb` / `graphicsPssKb` / `nativeHeapKb` | 进程 CPU 占用（多核可 >100%）、`Debug.MemoryInfo` 的 PSS 分项、native heap |
| `powerMw` | 整机瞬时功率（电流 µA × 电压 mV），`-1` = 设备不给读数；**只适合同机 A/B 对比**，不是本库单独的功耗 |
| `drawCalls` / `gpuPasses` | 这一帧实际提交的 draw call 与 pass 数 |
| `textureBytes` / `resolutionScale` | 库自己持有的 FBO + 纹理字节（不含 BufferQueue）、本帧实际生效的离屏缩放 |
| `latencyP50Ms` / `latencyP95Ms` / `jitterP95Ms` | C++ 帧时钟**滑动窗口(240 帧)**的延迟分布与帧到达间隔抖动；不是单次 drain 的瞬时值 |
| `droppedFrames` | 指标窗口内生产者入队时队列已满的累计次数，背压信号 |
| `encodedFrames` | 最近一次录制会话送进编码器的帧数 |

**系统级采样绝对不能落在 GL 线程。** `Debug.getMemoryInfo()`、
`registerReceiver(null, ACTION_BATTERY_CHANGED)`、读 `/proc/self/stat` 都是同步 binder / 文件 IO，
真机单次约 **50ms**。即使只在 0.5s 指标窗口调一次，只要那次调用在 `onDrawFrame` 里，
就会每半秒卡掉 1~2 个 vsync，画面规律性地顿一下，连解码线程的帧投递都会被一起拖慢。
库的 `SystemMeters` 跑在独立的 `gfx-meters` 线程：`requestSample()` 只投递任务立即返回，
`latest()` 读上一次结果（零系统调用），`lastCostMs` 给出采样真实耗时。
指标本来就是 0.5s 粒度，晚一个窗口不影响口径。

> 排查口诀：**「每 0.5 秒规律顿一下、但 fps/cpu 指标看着正常」先查指标窗口里有没有同步系统调用**，
> 别去看视频源或帧调度。早期版本还把 `cpuFrameMs` 算在采样之前，那 50ms 完全不进指标，
> HUD 一路显示正常，只有肉眼能看出来。

**HUD 的三个前提**（否则会自激触发 `onSurfaceChanged`，表现为「空闲还在反复重绘」）：

1. HUD 文本必须**固定高度**：`maxLines = 1, softWrap = false`，并给状态行预留固定高度。
   文本在 1 行 / 2 行之间抖动会带动下方 `GLSurfaceView` 高度抖动，每次 `onSurfaceChanged`
   都会触发一次重绘，看起来像"停不下来"。提示行也一样，别写成「有错才出现」的条件分支，
   否则错误出现/消失同样会改高度。
2. 固定高度只挡住**纵向**换行，挡不住**横向**溢出：`softWrap = false` 时过长的一行会被
   直接裁掉尾部。指标数字一变长（`pss 128.4 MB`、`power 264 mW`），`p95 … ms`、`drop 0`、
   `mW` 就没了，验收数字会读错。要么按可用宽度自适应字号（`TextMeasurer` 量单行宽度后
   收敛字号，示例工程里的 `HudLine` 就是这么做的），要么自己把数值拆成两行并给两行都留固定高度。
3. 完全空闲时 GL 线程不会回调 `onStats`，HUD 会停在上一次快照。自己加一个一次性延迟判定，
   超过 1s 没新指标就显示「空闲 · 0 fps」，别把 0 帧显示成上一次的高 fps；**不要**用常驻轮询，
   那本身又是一次次重组。

其余生命周期（页面进后台暂停 GL 线程、surface 销毁释放 GL 对象、上下文重建重传纹理）
三个组件都已接管，不用手动调。

### 动态分辨率与显存预算

`RenderPolicy` 多两个字段，离屏 FBO 按 `resolutionScale` 建，最后一步线性上采样到屏幕：

```kotlin
compositor.policy = RenderPolicy(
    maxFps = 30,
    resolutionScale = 0.75f,        // 手动档位，0.25..1.0
    maxTextureBytes = 8L * 1024 * 1024,  // 0 = 不限制；非 0 时自动往下选档
)
```

- 自动档位从 `1.0 / 0.75 / 0.5 / 0.35` 里选**第一个**满足预算的；`resolutionScale` 是上限，
  预算宽裕也不会把你手动设的低分辨率抬高。
- 实际生效的档位在 `GpuStats.resolutionScale`，显存收益看 `textureBytes`，别只看策略值。
- 选档用 `w*h*16` 字节做最坏估算（主 ping-pong + 每路独立链各两张 RGBA），
  纯函数 `RenderPolicy.chooseScale(w, h, maxScale, budget)` 可以直接单测 / 离线对照。

---

## 采集与录制

### Camera2 实时采集

`CameraFrameSource` 把摄像头预览直接写进 `SurfaceTexture`（OES 零拷贝），和视频文件回放
走同一条合成链。**权限由你的 app 声明并动态申请**，库不替你申请：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

```kotlin
val camera = remember {
    CameraFrameSource(context, width = 640, height = 480) { msg -> /* 错误提示 */ }
}
LaunchedEffect(granted) { if (granted) compositor.register("cam", camera) }
DisposableEffect(Unit) { onDispose { camera.close() } }

compositor.tiles = listOf(
    TileSpec("cam", Tiles.columns(2)[0]),
    TileSpec("logo", Tiles.columns(2)[1]),
)
```

- 没有权限不会崩，`onFailure` 收到提示，这一路只是不出画面。
- 预览方向按 `SENSOR_ORIENTATION` 与屏幕旋转自动补进 uv 变换；特殊朝向可覆盖 `camera.rotationDeg`。
- `camera.framesArrived()` 是采集侧累计帧数。画面黑时先查它：**0 = 采集链没建立，>0 = 采集正常**，
  再去查布局和贴图。摄像头刚打开的第一两秒可能还没出首帧。
- GL 上下文重建会换 Surface，内部自动关旧 session 再开。

### 把合成结果录成 MP4

`VideoRecorder` 把最终合成纹理送进 `MediaCodec` 的 input surface，复用 GL 线程当前的
EGLContext、只临时切换 draw surface，全程零 CPU 回读。

```kotlin
val out = File(context.cacheDir, "composite.mp4")
val recorder = VideoRecorder(out, width = 528, height = 656, fps = 30)
check(recorder.start())
// attachFrameSink（不要直接赋值 frameSink）：清零录制时钟（起点 + 上一帧 PTS）。
// 编码器只在「内容代次真的变了」时才收到帧（source.update() 拿到新内容 / uTime 动画 /
// CPU 自产纹理）；仅仅因为布局或策略变化重画出来的画面内容与上一帧相同，不会送进去。
// 这正是「渲染出一帧，然后一直插入」的根因修复——旧实现按 animating 无条件放行，
// 源没新帧时每 33ms 把同一张旧纹理送一次。
compositor.attachFrameSink(recorder)

// 停止
recorder.stop()
compositor.postGl {
    recorder.releaseGl()          // 必须在 GL 线程
    compositor.attachFrameSink(null)
}
// 等 muxer 落盘后，再用 MediaPlayer.prepare() 校验 / 回放
recorder.close()
```

- 编码宽高建议取 GL View 的一半并**对齐 16**；部分编码器对非对齐尺寸会静默失败。
- `stop()` 只发 EOS，MP4 由 drain 线程收尾，别立刻对同一个文件 `setDataSource`。
- `releaseGl()` 必须用 `compositor.postGl { }` 投递；`close()` 任意线程可调。
- 回放时**每次新建** `ExternalTextureSource`：复用同一个源时 `onGlCreated()` 会因
  `created = true` 直接返回，MediaPlayer 会一直播着上一次打开的旧文件 fd。
- `GpuStats.encodedFrames` 是最近一次录制会话送进编码器的帧数。

---

## 视频源接入：不绑定解码器

库核心**不依赖任何第三方解码器**，也不包含 FFmpeg / GStreamer / ExoPlayer。想 `git clone`
下来直接 `./gradlew :app:installDebug` 就能看效果，这是刻意守住的边界。

视频画面统一通过 `FrameSource` 接口接入，库只认**纹理**，不认**容器格式**：

| 实现 | 适用 | demo 里的用法 |
| --- | --- | --- |
| `ExternalTextureSource` | 任何能给出 `Surface` 的播放器 | `MediaPlayer` + 系统 `MediaCodec` 解 H.264/MP4，落成 `GL_TEXTURE_EXTERNAL_OES` |
| `CameraFrameSource` | Camera2 实时预览 | 采集页的实时摄像头画面 |
| `DynamicTexture2DSource` | 自己上传的 RGBA 数据 | 程序化纹理、软解帧、AI 生成帧 |

三者进 `GpuCompositor` 后走同一条路：多画面拼接 → 每路独立 shader 链 → 全局后处理 → 录屏。

需要 FFmpeg 独有能力时（mkv/flv/ts 容器、rtmp 拉流、非常规编码），**自己实现一个 `FrameSource`**
接进来即可——把解出的帧送进一个 `SurfaceTexture`，或直接上传 2D 纹理。库不绑定具体解码实现，
也不替你做许可选择；这只影响你自己那个实现，不影响库本身的依赖图。


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
| 摄像头那一格全黑 | 先看 `camera.framesArrived()`：0 = 权限没给 / Camera2 没打开，看 `onFailure`；>0 = 采集正常，检查 `register` 与 `TileSpec.source`。刚打开前 1~2 秒没首帧是正常的 |
| 摄像头画面横着 / 倒置 | `camera.rotationDeg` 默认按 sensor 与屏幕旋转算；特殊设备手动覆盖 90 / 180 / 270 |
| 录制没文件、或 `encodedFrames` 为 0 | 编码宽高要 16 对齐；`releaseGl()` 要在 GL 线程；`stop()` 后等 drain 落盘再读文件 |
| 回放一直在播上一次的旧视频 | 每次开启回放都新建 `ExternalTextureSource` + `MediaPlayer`，别复用 `created = true` 的旧源 |
| HUD 显示 `gpu n/a` / `power n/a` | 设备不支持 `GL_EXT_disjoint_timer_query` 或不提供电流读数；这是如实降级，不是初始化失败 |
| HUD 数字尾部被裁掉（`p95 53.2 r`、`power 25 m`） | 单行 `softWrap = false` 只防纵向换行，横向会硬裁。按宽度自适应字号，或拆两行并给两行都留固定高度；别直接缩短指标字符串，数值位数一变还会重现 |
| 空闲时仍反复重绘、显示 1~2 fps | HUD 行数或高度变化引起 `onSurfaceChanged` 自激，见上文「HUD 的三个前提」 |
| 画面每 0.5 秒规律顿一下，但 fps / cpu 指标看着正常 | 系统级采样（PSS、电量、`/proc`）在 GL 线程同步执行，单次可达 50ms。挪到独立线程，并确认 `cpuFrameMs` 是在整帧结束后才取的。自己接 `GpuCompositor`、在指标回调里顺手读系统状态时尤其容易踩 |
| Android 15+ 弹「应用兼容性：不符合 16 KB 对齐要求」 | 检查 APK 里每个 `.so` 的 `PT_LOAD p_align` 是否为 `0x4000`；本库已对齐，通常是接入方自己的 native 库仍是 4 KB。可调试应用才弹这个警告，但不代表 release 包能在 16 KB 内核上正常加载 |
| 视频画面一顿一顿、像「闪回前几帧」 | 先分清是渲染层还是内容层：渲染层看 `fps` 是否贴近源帧率、`jitterP95Ms` 是否异常（它量的是**源**的帧到达抖动）；内容层看测试图案的循环接缝是否闭合。库侧已由帧到达驱动，自定义 `FrameSource` 要覆写 `hasPendingFrame()`，别在 `update()` 之外偷偷消费帧。短视频在 60Hz 屏上解码时偶发 67ms 双间隔属正常节拍差 |
| 自写源接进来后画面不动 | 布局里有 OES 源时合成器是帧驱动：没有新帧就不画。确认 `hasPendingFrame()` 有覆写、`frameSink` 已接到生产者的帧到达事件 |

---

## 示例工程

`app/` 模块有五个可运行页面：扇形牌堆、场景图、性能对比、GPU 合成、采集·编码·回放。
最后一页需要动态申请 `CAMERA` 权限，走的是「Camera2 → 双画面合成 → H.264/MP4 → MediaPlayer 回灌」闭环。
五个页面全部只依赖系统 API（MediaPlayer / MediaCodec / Camera2），不要求预编任何第三方库。

```bash
./gradlew :app:assembleDebug :gfx:testDebugUnitTest :carddeck:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.arcana.carddeck/.MainActivity
```

---

## 边界

库负责「画面从哪来、怎么合成、到哪去」：`Surface`（视频 / 摄像头）进、FBO 拼接与
shader 链、`MediaCodec` input surface 出 MP4，整条 GPU 链零 CPU 回读；指标全部实测可量。

它**不提供**解封装器、解码器、播放器 UI、音轨混流、音画同步、seek、文字渲染或跨平台后端。
视频源通过 `FrameSource` 接口接入（可选 `Surface` / `Camera2` / 自上传纹理三种实现），
解码与容器格式完全由调用方决定——demo 走系统 `MediaCodec`，你要接 ExoPlayer 或自研 FFmpeg
也只是多写一个 `FrameSource`，不动库本体。要的是一条能验证的 GPU 渲染链，
不是一个包办一切的多媒体框架。
