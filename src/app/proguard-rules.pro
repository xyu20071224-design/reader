# ===== LinguaReader release R8 规则 =====
# release 开启 isMinifyEnabled + isShrinkResources 后，下面每条都对应一个具体的
# 「反射 / 动态引用」点，删任何一条前先读注释。

# --- WebView JS 桥（reader/EpubPage.kt 的 ReaderBridge）---
# JS 侧按方法名调用 ReaderBridge.onWord / onPageChanged / onScrollProgress / ...，
# 方法名一旦被混淆成 a/b/c，点词查词、翻页回报、滚动进度会「静默失效」——
# 不崩溃、不报错，只是点了没反应，灰度里极难发现。
# 类名本身可以随便混淆：注册用的是 addJavascriptInterface(..., "ReaderBridge") 那个字符串。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- pdfbox-android（data/PdfImporter.kt）---
# PDFBox 是桌面 Java 移植，引用了 Android 上不存在的 AWT / ImageIO 等类。
# AGP 8 的 R8 遇到 missing class 是直接报错终止（不是警告），必须显式放行。
# 本项目只用它做 PDF 文字层提取，走不到这些类。
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn org.apache.jempbox.**
-dontwarn com.gemalto.jp2.**

# --- 以下情形已被默认规则覆盖，无需重复声明 ---
# * enum valueOf（data/ReviewMode.kt:167）：proguard-android-optimize.txt 自带
#   -keepclassmembers enum * { values(); valueOf(java.lang.String); }
# * 四大组件（MainActivity / TtsPlaybackService / ReviewReminderReceiver / FileProvider）：
#   AGP 依据 AndroidManifest.xml 自动生成 keep 规则。
# * 本项目 JSON 全用 org.json 手写解析，无 Gson / Moshi / kotlinx.serialization 的字段名反射。
