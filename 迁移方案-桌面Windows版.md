# 迁移方案 — LinguaReader 桌面（Windows）版评估与计划

> 状态：**方案 v1，未动工**（2026-09-04 评估）。
> 结论先行：**推荐做，走 Compose Multiplatform 单仓多 target**。代码约 65% 可平移（含全部核心算法），真正需要新写的只有桌面外壳、文件访问、音频播放、词典 sqlite 驱动四类"平台叶子"；Android 版与全部既有 JVM 单测保持不动，桌面版独立迭代。
>
> **已拍板（2026-09-04，用户确认）**：
> - **目标形态** = 原生桌面应用（Compose Multiplatform 单仓多 target），**Android 版保留、不退役**。
> - **工程布局** = **共用一个 Gradle root（`src/`）**：`:shared`（KMP）+ `:app`（Android 薄壳）+ `:desktopApp`（JVM 薄壳），而非分仓/旁挂（§3，2026-09-04 修订）。
> - **渲染路线** = **B，内嵌 JCEF/Chromium**（复用 ReaderBridge 协议与全部注入 JS；接受安装包 +~200MB、仅 x64）。路线 A 降为二期可选评估，不再是待决项。
> - 系统 TTS 后端 = **默认不做**（保留自建 + MiMo 云后端，离线降级为纯阅读+查词）。
>
> 下一步即 §9 的 **M0 → M1**，无阻塞决策。

---

## 1. 代码可迁移性评估（基于源码实测，非估计）

**底数**：`src/app/src/main` 共 100 个 Kotlin 文件、25,562 行；`src/app/src/test` 共 66 个测试文件，其中 47 个是不依赖 Robolectric 的纯 JVM 测试——**这 47 个文件的存在本身就是"核心逻辑与 Android 解耦"的既有证据**。

**Android API 触点普查**（按 import 聚合）：

| Android 包 | 涉及文件数 | 性质 |
| --- | --- | --- |
| `androidx.compose.*` | 全 UI 层 | **可平移**（Compose Multiplatform 与 AndroidX Compose 同一血统，material3 跨端可用） |
| `android.content` | 39 | Context 到处传 → 换成自造 AppContext 接口 |
| `android.net`（Uri） | 14 | SAF → java.nio.file（见 §4） |
| `android.os`（Build/Handler/Looper） | 8 | 少量，换 `Dispatchers` / 删 |
| `android.app`（Service/Notification/PendingIntent） | 5 | 前台服务/通知 → 系统托盘（见 §5） |
| `android.speech.tts` | 4 | 系统 TTS 引擎 → Windows SAPI/WinRT（可选项） |
| `android.media`（MediaPlayer/MediaSession） | 3 | → openal-soft 播放器（见 §5） |
| `android.webkit`（WebView） | 2 | `ReaderScripts.kt`(1472 行注入 JS) + `ReaderScreen.kt` 宿主 → 见 §6 决策 |
| `android.database.sqlite` | 2 | `DictionaryRepository` + `TranslationMemoryRepository` 的 `SQLiteDatabase.openDatabase` → sqlite-jdbc（SQL 本身全通用） |
| `android.security.keystore` | 1 | → Windows DPAPI / 降级明文 + 文档说明（见 §5） |
| 其余（`util.Base64`/`graphics.BitmapFactory`/`provider`/`pm`/`annotation`） | 各 1-3 | 一行级替换（`java.util.Base64` 等） |

**依赖普查**：`jsoup`（纯 JVM ✓）、`org.json`（纯 JVM ✓，主源码 49 处 import）、`androidx.webkit`（仅 ReaderScreen 用）、**`pdfbox-android`（Android 专用 fork，桌面必须换标准 JVM PDFBox）**、`core-ktx`/`activity`/`lifecycle`（外壳级）。

**关键结论：54/100 个主源码文件完全没有 `import android.*`**，TTS 三层中的 Engine（纯 Kotlin 状态机）、AI 包的绝大部分、translation 包的 DP 对齐算法、data 包的解析/书库/生词本逻辑都在其中。

## 2. 迁移成本地图

```
可原样复用（≈65%）
  core 算法：DP 对齐、SPS 词表/降义、语境分析、词形还原、句切分、
            TtsPlaybackEngine 状态机、规则标注器、GitHub 更新解析、
            EPUB/TXT/FB2 解析（jsoup+zip，纯 JVM）、CSV 导出、复习调度计算
  UI 层：约 80% Compose 代码（改 expect/actual 与平台细节）
  测试：47 个纯 JVM 单测直接跑在桌面 CI

需要换后端、接口不变（≈20%）
  词典/译本记忆 sqlite 驱动、PDF 解析（pdfbox-android → JVM PDFBox）、
  HTTP（HttpURLConnection 其实原样能用）、文件访问（SAF → java.nio）、
  设置持久化（SharedPreferences → java.util.Properties 或 json）

需要新写/重设计（≈15%）
  桌面外壳：窗口、托盘、菜单、快捷键、DPI 感知
  音频播放：解码+播放管线（MediaPlayer → openal）
  渲染宿主：WebView → Chromium 或自绘（§6 决策）
  系统 TTS 后端（可选项）
```

## 3. 目标工程形态 —— 共享核心 + 两端薄壳（单 Gradle root）

> **2026-09-04 修订**：本节替换初稿的「Android 原地不动 + 旁挂 `desktopApp/`」方案。读完 `src/settings.gradle.kts`、`src/app/build.gradle.kts`、`toolchain/build.ps1` 后判定旁挂是次优解，理由见 §3.4。

### 3.1 目标布局

```
src/                          ← Gradle 根上移一层到 src/，成为【唯一的根】
├── settings.gradle.kts       （rootProject.name = "LinguaReader"；include :app / :shared / :desktopApp）
├── build.gradle.kts
├── gradle.properties         ← android.overridePathCheck 等原样留在根
├── gradlew / gradlew.bat / gradle/wrapper
├── shared/                   ← 新模块，KMP（androidTarget + jvm("desktop")）
│   └── src/{commonMain, androidMain, desktopMain, commonTest}
├── app/                      ← Android：保持【纯 Android library 模块】，不碰 Compose Multiplatform 插件
└── desktopApp/               ← 桌面：main() + 窗口/托盘/JCEF 宿主 + 平台 actual 装配
```

仓库根（`src/` 之外）只留**工具与文档**：`tts-server/`、`tts-voice-studio/`、`scripts/`、`toolchain/`、`bug收集/`、`.agents/`、各方案文档。不再往根上挂 Gradle 工程。

### 3.2 为什么不让 `:app` 直接变成 KMP 模块

`:app` 是 `com.android.application`，`src/main/assets/` 里有 **58 MB 的 `ecdict.sqlite`**、`res/values{,-en}/strings.xml` 各 559 条 string + 11 条 plurals，还有 Manifest 与资源系统。让一个 application 模块同时挂 KMP 插件是给自己挖坑。正确切法：

- **`:app` 保持纯 Android library/application 不动**，只新增对 `:shared` 的依赖；
- **`:desktopApp` 保持纯 JVM**，只依赖 `:shared` 的 desktop 变体；
- 跨端代码只活在 `:shared` 一个地方，**不存在双份真相**。

### 3.3 共享什么、不共享什么（边界要写死）

| 层 | 归属 | 理由 |
| --- | --- | --- |
| 算法（DP 对齐、SPS、语境分析、词形还原、句切分、`TtsPlaybackEngine`、规则标注器、更新解析、复习调度、CSV） | `:shared` commonMain | 零 Android 依赖，实测 54/100 文件无 `import android.*` |
| 数据层（书库/生词本/进度/设置仓库、EPUB/TXT/FB2 解析） | `:shared` commonMain + expect 接口 | 只认 `AppContext` 一个面（§4） |
| UI 组件（书架/生词本/复习/设置的 Composable） | `:shared` commonMain | material3 跨端可用；实测 import 全在 `foundation/material3/ui/runtime/material` 五个包内 |
| 阅读器正文渲染 | **不共享**（各端 `expect`，两套 `actual`） | Android WebView vs 桌面 JCEF，宿主语义不可混（决策 1 = 路线 B） |
| 听书 Service / 托盘 | 不共享 | 平台生命周期完全不同 |
| `strings.xml`（zh/en 双语，559+11 × 2） | Android 保留；桌面用 KMP `MR` 资源体系，**内容一次性搬迁** | 别指望 Android 资源系统跑在桌面上 |

### 3.4 为什么弃用「旁挂」而选「单 root」

| | 旁挂（初稿） | 单 root（采用） |
| --- | --- | --- |
| 改一个共享文件的语义漂移 | 复制 → **必然漂移**，Android 修 bug 桌面不知道 | 单一真相 ✓ |
| Gradle 配置 | 两套 root 各自重复声明镜像仓库/JDK/编码（`settings.gradle.kts` 的阿里云镜像优先这条纪律要维护两遍） | 一处 ✓ |
| CI | 两条 workflow 两套缓存 | 一条 workflow 同时跑 `:app` 与 `:shared` 的 JVM 测试 ✓ |
| 风险 | 低，但代价是长期双份 | 需要一次 Gradle 结构调整（§3.5），有明确验证闸门 ✓ |

### 3.5 结构调整的落地步骤（风险集中在这一步，逐条验）

1. **先加后移**：新建 `src/shared/`、`src/desktopApp/` 与三个 `include`，此阶段 `:shared` 空壳。**闸门：`.\gradlew.bat help` 通过。**
2. **根上移**：把 `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` / wrapper 提到 `src/`，`app/` 降为子模块。**闸门：`assembleDebug` + `testDebugUnitTest` 全绿，APK 与基线同 size 量级。**
3. **按包抽 `:shared`**：一次只移一个包，Android 编译 + 既有 JVM 单测（`src/app/src/test` 的 66 文件，其中 47 个纯 JVM）立即回归。移动 = 移文件 + 改包名，**不改语义**。
4. 全程不开并行会话碰 `src/`（AGENTS.md 的会话纪律）；开工前 `git fetch`。

### 3.6 副作用：`toolchain/build.ps1` 必须同步改

第 17 行硬编码 `Set-Location (Join-Path (Split-Path $root -Parent) "src")`。§3.5 第 2 步之后 gradle 根变成 `src/`，此脚本要改成指向 `src`；`-PverifyBuild`、JDK 路径（`jdk\jdk-17.0.20.1+1`）不动。**这一步别忘，否则桌面 CI 与本地构建双断。**

### 3.7 版本与发布策略

- Android 与桌面**各自 `versionName`**，不共用一个号（发布节奏本来就不同）。
- 桌面版在 SPEC 里作为独立产品轨道登记（F-16x 段），不塞进现有 F-1xx 的"已实现"清单——两端功能集从此会分叉，必须显式记账。
- `readest-src/`、`Readest/`、`silkweaver/` 一律不动，也不作为任何决策依据。

## 4. 逐模块替换清单（expect/actual 映射）

| 能力 | Android 现状 | 桌面 actual | 备注 |
| --- | --- | --- | --- |
| 应用上下文 | `Context` 参数贯穿 39 文件 | 自造 `AppContext` interface：`filesDir`、`cacheDir`、`prefs(name)`、`base64`、`platform` | **第一阶段先做这个**，所有数据/网络文件只认这一个面 |
| 设置存储 | `SharedPreferences` | Properties/JSON 文件（照 data-persistence 记忆：项目本无 DataStore，实现简单） | 数据迁移见 §7 |
| 文件访问 | SAF Uri（`ContentResolver` 仅 6 文件） | `java.nio.file` + `FileDialogProvider`（Compose Multiplatform 1.12+ 自带原生文件对话框） | 拖拽导入（Windows 拖 .epub 进窗口）是桌面加分项 |
| 离线词典 | `SQLiteDatabase.openDatabase(READONLY, NO_LOCALIZED_COLLATORS)` | `net.zettabridge:sqlite-jdbc`（只读 URI `?mode=ro`） | SQL 与 `ecdict.sqlite` 资源原样复用；**注意 NO_LOCALIZED_COLLATORS 意味着现行为即裸 BINARY 比较**，桌面端大小写语义反而等价；唯一要验的是 unicode 正则（`DictionaryRepository` 的 `[\\p{L}']` 仅 Android 侧使用，桌面按代码点等价处理） |
| PDF | `pdfbox-android` | `org.apache.pdfbox:pdfbox`（标准 JVM 版） | `PdfBookImporter` 的三级分章降级逻辑不变 |
| HTTP | `HttpURLConnection`（8 文件） | **原样复用** | 纯 JVM 可用；TLS 1.2+ 桌面天然满足 |
| JSON | `org.json` | `org.json:json` 依赖 | 纯 JVM 可用 |
| 音频 | `MediaPlayer` | openal（`com.sealedwind:sadko` 或底层 LWJGL openal-soft）+ MP3 解码（跳 JVM 自带的纯 Java 解码，用 `mp3spi` 或 `JLayer`） | 见 §5 风险 |
| 系统 TTS 后端 | `android.speech.tts` | Windows SAPI via JNA（或砍掉此引擎，桌面只留自建/MiMo） | 可选项，默认不做（§5.1 决策 3） |
| 凭证加密 | Android Keystore（`CredentialKeystore`） | DPAPI（CryptProtectData，JNA）；失败降级：明文 + 设置页明确警告 | 桌面单用户威胁模型本就不同，记录进文档 |
| 前台服务/通知 | `TtsPlaybackService` 前台服务 + MediaSession；`ReviewReminder` AlarmManager | 托盘常驻 + 桌面通知（`Apprise`/原生 toast API via JNA，或砍通知） | `TtsPlaybackEngine`（纯状态机）不动；只换 Service 宿主 |
| 阅读器渲染 | Android WebView + 1472 行注入 JS | **§6 专项决策** | 全计划最大单一变量 |
| 应用内更新 | `ApkInstaller`（FileProvider + REQUEST_INSTALL_PACKAGES） | 删；GitHub 检查 → 下载 zip → 提示替换目录 | `GitHubReleaseParser`/`UpdatePolicy` 复用 |
| 权限/Manifest/BootReceiver | — | 整体消失（桌面优势） | ColorOS 开机广播那摊事桌面没有 |

## 5. 桌面新写部分

1. **窗口外壳**：`ComposeWindow` + 最小化到托盘、Ctrl+F 查找（桌面版缺全局查找是明确的功能回退，必须补）、Ctrl+滚轮字号、滚轮/键盘翻页、多窗口留作后续。
2. **`TtsPlayer` 抽象**：从 `TtsSynthesizer` 链路中分出"取字节流 → 解码 → 播放 → 回调时长/完成"，Android actual 保留 MediaPlayer，桌面 actual 走 openal。**风险：JVM 无内置 AAC 解码**，自建服务器与 MiMo 返回什么格式要实测（`CloudTtsSynthesizer` 当前请求格式字段需对齐桌面解码器）；先跑 `tts-server/` 的 Kokoro/IndexTTS 服务实测 WAV/MP3，大概率足够。
3. **`SystemTtsVoice`（系统引擎后端）**：默认**不实现**——桌面用户配自建服务器或 MiMo 的门槛远低于 Android 用户找系统语音。列为可选增强（设置里灰置"暂不支持"即可），不阻塞主线。
4. **打包与更新**：`jpackage`（app-image，内嵌 JRE）+ GitHub Actions 出 zip；不做安装器、不做代码签名（个人项目成本考虑，写进 README 的已知限制）。

### 5.1 三个决策（2026-09-04 已全部拍板）

| # | 选项 | 结论 |
| --- | --- | --- |
| 1 | 渲染：**A 纯 Compose 自绘** / **B 内嵌 JCEF** | ✅ **B**。点词取句契约（归一化坐标、`TTS_BLOCK_SELECTOR` 与 `TtsTextExtractor` 等价性）是本项目最脆弱的跨模块资产，保留 WebView 语义 = 1472 行 JS 和既有回归经验原样生效。A 降为二期评估。 |
| 2 | 工程形态：单仓多模块 / 新仓库 / 桌面替代 Android | ✅ **单仓共用一个 Gradle root（`src/`），`:shared` + `:app` + `:desktopApp` 三模块，Android 版保留**（2026-09-04 从"旁挂"修订而来，理由见 §3.4） |
| 3 | 系统 TTS 后端：做 / 不做 | ✅ **默认不做**，保留自建 + MiMo 两个云后端，离线降级路径改为"纯阅读 + 查词"（系统引擎缺失不影响任何其它功能） |

## 6. 渲染决策展开（路线 B 已选定）

- **路线 B（主线）**：JCEF（`me.friwi:jcefmaven`，自动下载并引导 CEF）承载 `ReaderScreen`；`ReaderBridge` 的 `addJavascriptInterface` 桥接需经 `CefMessageRouter` 自写 JS↔Java 管道。注入 JS 的坐标系差异（多列布局、探针自校准）在 Chromium 内核一致的前提下大部分经验直接适用，但 **JS 里任何 Android-WebView 特有行为（`window_lr*` 挂载时机、`evaluateJavascript` 回调语义）要重新验一遍**。
- **路线 A（二期备选，当前不排期）**：EPUB→XHTML 解析 + Compose AnnotatedString 自绘。改动集中在分页/高亮/点词三层（对应 reader-pipeline 记忆），工期翻倍起步；若未来 JCEF 体积/稳定性问题不可接受再启。
- 无论哪条路线，**`ReaderScripts.kt` 与 Kotlin 侧选择器的等价性纪律**（known-pitfalls §2）原样继承。

## 7. 数据共存与迁移（用户已有 Android 数据怎么办）

- 存储根改为可配置，默认 `%LOCALAPPDATA%\LinguaReader`；布局照 `data-persistence` 记忆的文件级对账体系（books/progress/vocabulary/settings 等），**格式沿用既有 JSON/文件契约，不加新 schema**。
- 桌面版提供"从 Android 备份导入"：用户在手机上导出一包（应用内"导出书库"按钮 → 复用现有文件），桌面解包 + 文件级对账（`DataConsistency` 已单测）。
- **不做实时云同步**（离线优先承诺），写进方案边界。
- `progress.json` 里 WebView 位置（章节内进度是归一化的吗？）需要核对：若是字符偏移/页索引，路线 B 等价、路线 A 需重定义。

## 8. 风险表

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| JCEF 体积/崩溃恢复 | 中 | 决策 1 已权衡；启动失败降级为"书架+生词本+复习+听书可用，阅读页提示" |
| JVM MP3/AAC 解码踩坑 | 中 | §5.2；先实测云引擎返回格式，必要时只支持 WAV/MP3 |
| 抽 `coreShared` 时无意改语义拖垮 Android | 高 | 每次移动必须：Android assembleDebug + 47 个 JVM 单测 + CI 全绿；**一次只移一个包** |
| 两套 `pdfbox-android`/JVM PDFBox 行为差异 | 低 | 用同一批 PDF 样本双端 diff 提取文本 |
| 桌面键盘/鼠标交互是新设计，"手势肌肉记忆"回退 | 中 | 快捷键表尽早贴 README；翻页/选词交互单独做一轮走查 |
| 双端功能漂移（Android 修 bug 桌面没修） | 中 | core 单测共享；bug收集/ 的文档注明涉及端；桌面版明确"功能快照"节奏，不承诺同步发布 |
| 工作区当前脏（14 个未提交条目） | 低 | M0 先收口 |

## 9. 里程碑（建议 5 个阶段，M0 先行）

- **M0（0.5 天）**：脏工作区收口提交；`git fetch` 确认远端；本方案评审拍板决策 1/2/3；在 `FEATURE_SPEC.md` 登记"桌面版"为独立产品轨道。
- **M1 结构调整 + 抽地基（2-3 天）**：按 §3.5 分四小步走——① 建 `:shared` / `:desktopApp` 空壳；② gradle 根上移到 `src/`（闸门：`assembleDebug`+`testDebugUnitTest` 全绿）并同步改 `toolchain/build.ps1`；③ 抽 `AppContext` 接口 + data 包无 android 依赖文件 + `TtsPlaybackEngine` 进 `commonMain`；④ 桌面侧跑通"查词 CLI 冒烟测试"（`DictionaryRepository` 换 sqlite-jdbc 读同一份 ecdict.sqlite）。验收：Android 编译 + 全部 JVM 单测绿。
- **M2 非阅读核心（3-5 天）**：书架、生词本、复习卡、设置页 4 个 Compose 屏上桌面（它们不碰 WebView）；导入改原生文件对话框。**验收：能在 Windows 上完成"导入 TXT/EPUB → 查词收藏 → 复习"闭环——产品核心价值首次成立。**
- **M3 听书（3-5 天）**：`TtsPlayer` 抽象 + openal 桌面 actual + 自建/MiMo 云后端实测；句级高亮回显复用 `TtsPlaybackEngine` 既有单测语义；系统 TTS 按决策 3。
- **M4 阅读器（5-8 天，路线 B）**：JCEF 壳 + `ReaderBridge` 管道 + 手势/分页/点词高亮逐条回归（对照 reader-pipeline 与 known-pitfalls 的 22 条坑建桌面验收清单）；PDF 导入接 JVM PDFBox。
- **M5 收口**：托盘/快捷键/备份导入/`jpackage` zip /CI desktop job/更新检查（去 APK 安装）/文档（README 桌面章节、`docs-and-history` 登记、记忆库新增 `desktop-migration.md`）。

**总计约 14–22 个有效工作日**，可按 M2 结束做一次"值不值"复盘再决定 M3+。

## 10. 测试与验证矩阵（桌面版）

| 层 | 桌面做法 |
| --- | --- |
| 单测 | 共享 core 的 47 个 JVM 文件原样跑进 desktop CI job；新 actual（文件访问、sqlite 驱动、音频时长解析）补纯 JVM 单测；JCEF/窗口相关不可单测 |
| 真机验证 | "真机"换成 Windows 10/11 双机实测清单：高 DPI、深色模式、托盘常驻、休眠唤醒、无网络冷启动（离线优先承诺） |
| 验收基线 | M2 的 TXT/EPUB 最小闭环清单先于任何 UI 细节；阅读器回归对照 `bug收集/` 历史缺陷逐条验 |

## 11. 明确不做（边界）

云同步、iOS/Linux 对齐（架构预留 target 但不排期）、移动 UI 的触控手势复刻、安装器与代码签名、系统 TTS 引擎（除非用户明确要求）、`silkweaver`/`Readest` 等无关目录的一切改动。
