plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// 发布坐标。依赖方只认 com.arcana:gfx，模块内部怎么拆都不影响写法。
group = "com.arcana"
version = "0.5.0"

// 多路画面合成层：FrameSource（视频 / 相机 / 程序化纹理统一成一路源）→
// GpuCompositor（FBO 拼接 + 逐路 shader 链 + 全局后处理）→ 录制 / 显示。
//
// 这个模块**不引用 com.arcana.gl 也不引用 com.arcana.carddeck**：库不能假设宿主
// 里有同名类，GlUtil / MsaaConfigChooser / TextureBank 都在本包内自带一份。
// 这是「可以被任意项目当第三方库依赖」的前提 —— 只要坐标一行，不撞任何包名。
android {
    namespace = "com.arcana.gfx"
    // 本机 SDK 里可用的 NDK；固定版本避免 AGP 默认版本漂移导致 CMake 重新配置。
    ndkVersion = "27.0.12077973"
    // 刻意停在 36 而不是 37.1：
    // ① AAR 的 minCompileSdk 就是编译它的 compileSdk —— 定得越高，消费方越被迫升级；
    // ② JitPack 的构建镜像装 platforms;android-36 比 37.1 稳得多；
    // ③ 这个模块一行 API 37 的新特性都没用（GLES/EGL/MediaCodec/Camera2 都是老 API）。
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // 发布产物只带真机 ABI，控制 AAR 体积。
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        debug {
            // 开发期补上 x86_64，让模拟器也能跑（不进 release AAR）。
            ndk {
                abiFilters += "x86_64"
            }
        }
    }

    // C++ 帧时钟（SPSC 队列）+ GPU 计时（GL_EXT_disjoint_timer_query）。
    // JNI 是静态注册，符号固定为 Java_com_arcana_gfx_*，见 consumer-rules.pro。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // CompositeStage 的签名里出现 Modifier，用 api 让消费方不必再声明 Compose。
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // CompositeStage 跟随宿主的生命周期自动 pause/resume。
    // 刻意不跟项目里的 2.11.0：lifecycle 2.11 要求消费方 compileSdk >= 37，
    // 会让「只想接个合成层」的人被迫升 SDK。2.8.7 够用（只用到 LocalLifecycleOwner
    // 与 LifecycleEventObserver），并把本 AAR 的 minCompileSdk 压在 36。
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "gfx"

            pom {
                name.set("Arcana GFX")
                description.set(
                    "Multi-source GPU compositing for Android: FBO tiling, per-tile shader " +
                        "chains, frame-clock driven render policy and MediaCodec recording.",
                )
                url.set("https://github.com/arcana/carddeck")
            }
        }
    }
    repositories {
        mavenLocal()
        maven {
            name = "localBuild"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
