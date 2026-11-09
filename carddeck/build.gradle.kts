plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

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
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
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
