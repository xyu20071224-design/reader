plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    // :shared 是纯 JVM 库（Android 与未来的桌面壳共同依赖）
    id("org.jetbrains.kotlin.jvm") version "2.1.10" apply false
}
