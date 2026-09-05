plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    // :shared 是纯 JVM 库（Android 与未来的桌面壳共同依赖）
    id("org.jetbrains.kotlin.jvm") version "2.1.10" apply false
    // 桌面 UI：Compose Multiplatform Desktop（M2 起，纯 JVM 模块可直接套用）
    id("org.jetbrains.compose") version "1.9.3" apply false
}
