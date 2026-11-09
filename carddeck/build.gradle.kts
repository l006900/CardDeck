plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// 发布坐标。拆分模块时 artifactId 会变（如 arcana-gfx-core），但 group 与
// 依赖方的写法保持不变，消费方无需改 build 文件。
group = "com.arcana"
version = "0.5.0"

android {
    namespace = "com.arcana.carddeck.lib"
    // 本机 SDK 里可用的 NDK；固定版本避免 AGP 默认版本漂移导致 CMake 重新配置。
    ndkVersion = "27.0.12077973"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

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

    // C++ 帧时钟：固定容量无锁 SPSC 队列 + 延迟分位统计。
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

    // maven-publish 需要显式声明发布哪个变体，并附带源码 jar。
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 公开 API 里出现 Compose 类型（Modifier 等），用 api 让消费方无需再声明。
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    // 手写 GLSurfaceView + 生命周期，无需 material3。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "carddeck"

            pom {
                name.set("CardDeck")
                description.set(
                    "Compose-native GPU rendering for Android: scene graph, multi-source " +
                        "compositing (FBO tiling, per-tile shader chains), MediaCodec recording, " +
                        "and a frame-clock driven render policy. No third-party decoder required.",
                )
                url.set("https://github.com/arcana/carddeck")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    // 默认发到 build/repo；同时支持 publishReleasePublicationToMavenLocal 供本机消费方验证。
    repositories {
        mavenLocal()
        maven {
            name = "localBuild"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
