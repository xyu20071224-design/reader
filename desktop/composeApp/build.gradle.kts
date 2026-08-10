import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val currentOsName = System.getProperty("os.name").lowercase()
val currentArch = System.getProperty("os.arch").lowercase()
val javafxClassifier = when {
    currentOsName.contains("win") -> "win"
    currentOsName.contains("mac") && currentArch.contains("aarch64") -> "mac-aarch64"
    currentOsName.contains("mac") -> "mac"
    else -> "linux"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("org.jsoup:jsoup:1.18.3")
            implementation("org.json:json:20240303")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }

        androidMain.dependencies {
            implementation(compose.uiTooling)
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
            implementation("androidx.webkit:webkit:1.14.0")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("com.tom-roush:pdfbox-android:2.0.27.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        }

        val desktopMain by getting {
            resources.srcDir(rootProject.file("assets"))
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
                implementation("org.xerial:sqlite-jdbc:3.47.1.0")
                implementation("org.apache.pdfbox:pdfbox:2.0.27")
                implementation("org.openjfx:javafx-base:21.0.5:$javafxClassifier")
                implementation("org.openjfx:javafx-controls:21.0.5:$javafxClassifier")
                implementation("org.openjfx:javafx-graphics:21.0.5:$javafxClassifier")
                implementation("org.openjfx:javafx-media:21.0.5:$javafxClassifier")
                implementation("org.openjfx:javafx-swing:21.0.5:$javafxClassifier")
                implementation("org.openjfx:javafx-web:21.0.5:$javafxClassifier")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.linguareader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linguareader.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        assets.srcDirs("assets")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "false")
    systemProperty("prism.order", "sw")
    systemProperty("prism.text", "t2k")
}

compose.desktop {
    application {
        mainClass = "com.linguareader.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "LinguaReader"
            packageVersion = "1.3.0"
            description = "LinguaReader 语境阅读 Windows 桌面版"
            vendor = "LinguaReader"
        }
    }
}
