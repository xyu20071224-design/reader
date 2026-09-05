plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // org.json 在 Android 运行时由平台提供，若以 implementation 进入 :app 会与
    // android.jar 撞重复类（dex 报错），所以只 compileOnly、不打进任何 APK。
    // coroutines 同理：:app 经 lifecycle 已在运行时提供 1.7+，桌面壳自己声明 runtime。
    // 两端拿到的版本都与现状一致，:shared 不引入任何新的运行时版本钉死。
    compileOnly("org.json:json:20240303")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // M2 刀3：ai 包的 ChapterTextExtractor 用 jsoup 清理 HTML——纯 JVM 库，
    // Android 侧由 :app 已有的 jsoup 提供运行时，桌面壳后续自行声明 runtime。
    compileOnly("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("org.json:json:20240303")
    // 测试期才需要（DictionaryRepositoryTest 用 runBlocking 跑 suspend lookup）：
    // 运行时仍保持 compileOnly 不进 APK，与上面两条「不新增运行时依赖」规则不冲突。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jsoup:jsoup:1.18.3")
    // 注意：不要在这里加 kotlin-stdlib —— KGP 会自动添加与插件同版本的 stdlib。
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
