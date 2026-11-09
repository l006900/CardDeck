pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
        maven("https://repo1.maven.org/maven2/")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/nexus/content/repositories/google")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
        maven("https://developer.huawei.com/repo/")
    }
}

rootProject.name = "CardDeck"
include(":app")
include(":carddeck")
// 多路合成层（com.arcana.gfx），可独立发布为 com.arcana:gfx。
// 与 :carddeck 分开是为了让「只要合成能力、不要牌桌控件」的项目能一行依赖接进来，
// 且不会和宿主自己的 com.arcana.gl 撞名。
include(":gfx")
 