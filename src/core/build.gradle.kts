plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Models.kt 依赖 org.json；Android 运行时自带 org.json，故仅编译期引入，
    // 避免把 org.json:json 打进 APK 造成重复类。
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
}
