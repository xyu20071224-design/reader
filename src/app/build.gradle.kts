plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.linguareader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linguareader.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 11
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            // 加 -PverifyBuild 时安装成并存的验证包（com.linguareader.app.verify），
            // 这样真机上已有的正式调试包（可能是别的调试签名）与其数据都不用动。
            if (project.hasProperty("verifyBuild")) {
                applicationIdSuffix = ".verify"
                versionNameSuffix = "-verify"
            }
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // 源码只有一份，CI 只跑 debug 变体；release 再跑一遍纯属浪费额度。
        checkReleaseBuilds = false
        // error 级问题直接失败；warning 不升级为 error——本仓库还没做过整轮 lint
        // 清理，一上来全量升级会红几百条，反而没人看。清完再把 warningsAsErrors 打开。
        abortOnError = true
        warningsAsErrors = false
        // 报告落盘：CI 失败时作为 artifact 上传，本地可直接开 HTML 看。
        htmlReport = true
        xmlReport = true
        textReport = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.jsoup:jsoup:1.18.3")
    testImplementation("org.json:json:20240303")
    testImplementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
