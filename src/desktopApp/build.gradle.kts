plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    // Compose Desktop UI（M2 起）：currentOs 只拉 Windows 运行时，别换成 universal。
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // :shared 里的 org.json / coroutines 是 compileOnly（Android 侧由平台与 lifecycle 提供），
    // 桌面没有 android.jar，所以运行时得由本模块补齐 —— 版本与 :app 在用的一致。
    // 刀5 起 DesktopAppContext 自己也编译期用 org.json（叶子模块，无撞类风险），升为 implementation。
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // 桌面听书：JVM 无内置 MP3 解码，mp3spi（JavaZoom）提供 SPI，javax.sound 直接吃 MP3。
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

    // 桌面阅读器（M4，路线 B）：jcefmaven 引导 CEF/Chromium，运行时按需下载浏览器内核。
    implementation("me.friwi:jcefmaven:146.0.10")

    // 词典/译本记忆的只读驱动（M1 验证目标）
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
}

application {
    // M2 起默认入口是桌面 UI 主程序；探针用 -PmainClass=... 切换，
    // 如 -PmainClass=com.linguareader.desktop.DesktopContextProbeKt（数据平台面冒烟）。
    mainClass.set(
        providers.gradleProperty("mainClass")
            .orElse("com.linguareader.desktop.LinguaReaderAppKt")
    )
}
