# LinguaReader Windows 桌面版（Compose Multiplatform + JavaFX WebView）

这是 LinguaReader 从 Android 迁移到 Windows 桌面的跨平台工程：同一个 `composeApp` 模块同时产出 Android APK 与桌面（JVM）应用。桌面阅读渲染使用 **JavaFX WebView**；首版**不包含**听书（TTS）与定时提醒（仅 Android 目标保留原能力）。

## 目录结构

```text
desktop/
  assets/dictionary/ecdict.sqlite       # 内置离线词典（Android assets / 桌面 classpath 共用一份）
  composeApp/
    build.gradle.kts
    src/commonMain/                     # 平台无关：数据层、解析器、Compose UI、ReaderScripts
    src/androidMain/                    # Android actual：WebView、TTS、通知、文件选择器
    src/desktopMain/                    # 桌面 actual：JavaFX WebView、AWT 文件对话框、JDBC SQLite、PDFBox
    src/desktopTest/                    # 桌面 JVM 冒烟测试与 WebView 桥接测试
```

## 构建

需要 JDK 17 与 Android SDK 35（Android 目标需要 SDK；只跑桌面也需要，因为工程是双目标）。

macOS / Linux：

```bash
./gradlew :composeApp:run                      # 运行桌面版
./gradlew :composeApp:desktopTest              # 桌面测试（导入+查词+WebView 桥接）
./gradlew :composeApp:assembleDebug            # Android 调试 APK
./gradlew :composeApp:createDistributable      # 本机可分发应用
```

Windows（在 Windows 机器上执行）：

```bat
gradlew.bat :composeApp:run
gradlew.bat :composeApp:packageMsi             REM 或 packageExe，生成 Windows 安装包
```

## 桌面端实现要点

- **WebView 桥接**：每本书通过 `ChapterHttpServer`（`desktopMain/.../reader/ChapterHttpServer.kt`）在 127.0.0.1 随机端口提供章节与资源；JS 端注入 `window.ReaderBridge`，用 `fetch('/__bridge')` 把分页、点词、翻页等事件回传 Kotlin。`ReaderScripts`（分页 CSS/JS）与 Android 版完全复用。
- **安全**：服务器只映射书籍解压根目录并拒绝路径穿越；HTML 注入 CSP（仅本机来源 + data:），远程资源被阻止；导入层原有解压配额与脚本清理逻辑原样保留。
- **数据层**：`Context/Uri/SharedPreferences/SQLiteDatabase` 全部抽成 common 接口；桌面 SQLite 走 JDBC（`org.xerial:sqlite-jdbc`），存储目录为 Windows `%APPDATA%\LinguaReader`（macOS/Linux 为 `~/.lingua-reader`）。
- **PDF**：Android 用 pdfbox-android，桌面用 Apache PDFBox 2.0.27，章节启发式逻辑共用。
- **文件选择/导出**：Android 用系统文件选择器；桌面用 AWT `FileDialog`。

## 首版范围

包含：EPUB / TXT / FB2 / 文字版 PDF 导入、书架、分页阅读、字号/行距/主题/字体、点词查词（ECDICT 离线词典、词形还原、短语识别、义项排序）、生词本、CSV 导出、间隔复习（语境高亮/停顿点提示/角标）。

不包含（桌面版）：听书/朗读（TTS）、定时轻提醒。Android 目标仍保留这两项。

## 从 Android 迁移本地数据

桌面版使用与 Android 相同的 `books/` + `metadata.json` + `vocabulary.json` 布局，但 `metadata.json` 里的 `extractedDir` 是绝对路径，直接复制到 Windows 后需要做一次路径重写（按书 ID 重建 `extractedDir`）。该迁移工具尚未实现，属于后续增量。

## 验证

- `desktopTest`：导入 `测试电子书-TheLanternLibrary.epub` 并断言章节生成与离线词典查询；JavaFX WebView 真实加载章节 HTML，`ReaderBridge.onReady` 事件回传成功。
- 双目标编译：`:composeApp:compileKotlinDesktop` 与 `:composeApp:compileDebugKotlinAndroid` 均通过。
