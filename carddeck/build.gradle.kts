plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// 发布坐标。依赖方只认 group + artifactId，模块怎么拆对它透明。
group = "com.arcana"
version = "0.6.0"

android {
    namespace = "com.arcana.carddeck.lib"
    // 与 :gfx 对齐停在 36：minCompileSdk 越低，接库的人越省事（理由见 gfx/build.gradle.kts）。
    compileSdk = 36

    defaultConfig {
        minSdk = 24
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
    // 合成层（com.arcana.gfx）单独发布为 com.arcana:gfx，这里 api 传递：
    // 依赖 :carddeck 的项目照样能直接用 CompositeSurfaceHost / GpuCompositor。
    api(project(":gfx"))
    // 公开 API 里出现 Compose 类型（Modifier 等），用 api 让消费方无需再声明。
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    // 手写 GLSurfaceView + 生命周期，无需 material3。
    // 同 :gfx：停在 2.8.x，否则 lifecycle 2.11 会把 minCompileSdk 抬到 37。
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
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
