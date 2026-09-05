pluginManagement {
    repositories {
        // Mirrors first: some networks block the upstream repos (403/reset).
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "LinguaReader"
include(":app")
// 跨端共享的纯 Kotlin/JVM 库（桌面迁移 M1 起，见根目录 迁移方案-桌面Windows版.md）
include(":shared")
