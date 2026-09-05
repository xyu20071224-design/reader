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
src/                          ← Gradle 根（【本来就在这】，M1 已核实，无需上移）
├── settings.gradle.kts       （rootProject.name = "LinguaReader"；include :app / :shared / :desktopApp）
├── build.gradle.kts
├── gradle.properties         ← android.overridePathCheck 等原样留在根
├── gradlew / gradlew.bat / gradle/wrapper
├── shared/                   ← 【M1 已建】纯 kotlin("jvm") 库（KMP 与 Compose Multiplatform 推到 M4 再评估）
│   └── src/{main,test}/java/com/linguareader/shared/<域>/
├── app/                      ← Android：保持【纯 Android 模块】，不碰 Compose Multiplatform 插件
└── desktopApp/               ← 桌面（M2 建）：main() + 窗口/托盘/JCEF 宿主 + 平台装配
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
| 算法（DP 对齐、SPS、语境分析、词形还原、句切分、`TtsPlaybackEngine`、规则标注器、更新解析、复习调度、CSV） | `:shared`（纯 JVM 库，`src/main`） | 零 Android 依赖，实测 54/100 文件无 `import android.*`；**但被 `R.string` 绑住的模型除外，见 §3.5.1** |
| 数据层（书库/生词本/进度/设置仓库、EPUB/TXT/FB2 解析） | `:shared` + 自造 `AppContext` 接口 | 只认一个平台面（§4）；M1 未动 |
| UI 组件（书架/生词本/复习/设置的 Composable） | 二期再迁入 `:shared` | material3 跨端可用，但要等 KMP/Compose Multiplatform 引入（M4 前）；实测 import 全在 `foundation/material3/ui/runtime/material` 五个包内 |
| 阅读器正文渲染 | **不共享**（两端各一套实现，共用 `ReaderScripts` 文本） | Android WebView vs 桌面 JCEF，宿主语义不可混（决策 1 = 路线 B） |
| 听书 Service / 托盘 | 不共享 | 平台生命周期完全不同 |
| `strings.xml`（zh/en 双语，559+11 × 2） | Android 保留；桌面届时用 KMP `MR` 资源体系，**内容一次性搬迁** | 别指望 Android 资源系统跑在桌面上 |

### 3.4 为什么弃用「旁挂」而选「单 root」

| | 旁挂（初稿） | 单 root（采用） |
| --- | --- | --- |
| 改一个共享文件的语义漂移 | 复制 → **必然漂移**，Android 修 bug 桌面不知道 | 单一真相 ✓ |
| Gradle 配置 | 两套 root 各自重复声明镜像仓库/JDK/编码（`settings.gradle.kts` 的阿里云镜像优先这条纪律要维护两遍） | 一处 ✓ |
| CI | 两条 workflow 两套缓存 | 一条 workflow 同时跑 `:app` 与 `:shared` 的 JVM 测试 ✓ |
| 风险 | 低，但代价是长期双份 | 需要一次 Gradle 结构调整（§3.5），有明确验证闸门 ✓ |

### 3.5 结构调整的落地步骤（M1 实际执行记录）

> **初稿订正**：原计划「把根上移到 `src/`」是**伪命题** —— `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` / wrapper 本来就在 `src/`，`:app` 也本来就是它的子模块；实际只需在既有根上 `include(":shared")`。§3.6 随此作废。

1. ✅ **建模块**：`src/shared/` 为 **`kotlin("jvm")` 纯库（不是 KMP）**；`include(":shared")`、根 `build.gradle.kts` 加 `org.jetbrains.kotlin.jvm` 2.1.10 `apply false`、`:app` 加 `api(project(":shared"))`。不选 KMP 的理由：M1 抽取物零 Android 依赖，一个 target 就够，不必提前背 Compose Multiplatform + native 产物的成本（实测本地 Gradle 缓存连 KMP 插件 marker 都没有）。
2. ✅ **抽第一刀（最干净的闭环）**：`app/update/` 的 4 个纯文件 → `com.linguareader.shared.update`（`git mv` 保历史），随迁 2 个测试文件共 8 用例；`AppUpdateRepository` / `AppUpdateSettings` / `ApkInstaller` 因依赖 `Context` / `BuildConfig` / `FileProvider` 留在 `:app`。
3. ✅ **回归全绿**：`:shared:test` 8 通过；`:app:testDebugUnitTest` **530 通过 / 0 失败 / 1 跳过**（530 + 8 = 基线 538，**一条未丢**）；`:app:assembleDebug` 出包 54.44 MB。**测试总数守恒**是「移动 ≠ 改语义」最硬的证据，后续每刀都按此核对。
4. ✅ **`:desktopApp` 已建**（比原计划提前半步）：纯 JVM 壳 + `application` 插件；M1 只装一个词典只读冒烟探针 `DictionaryProbe`（跑法 `.\toolchain\build.ps1 :desktopApp:run`，实测 entries 770,611 行、词形还原/短语命中正常），M2 起承接 Compose 桌面 UI。
5. ✅ **词典 SQL 双引擎对账**：`:app` 新增 `DictionarySqlParityTest`（Robolectric，同一份 58 MB 词典、`SQLiteDatabase` 与 `sqlite-jdbc` 并读、逐字照抄两条生产 SQL + LIKE/GROUP BY/`||`/大小写，**4 测全绿**）。借此**证伪并订正了 §4 的「裸 BINARY」断言**（真相是 schema 级 `COLLATE NOCASE`），并实测发现 `?mode=ro` URI 形式 sqlite-jdbc 不解析（只读走 `open_mode` 属性）。详见 §4 词典行与 `VALIDATION.md` 2026-09-05 条目。
6. ⬜ **按包继续抽 `:shared`**：一次只移一个包，Android 编译 + 既有 JVM 单测立即回归。移动 = 移文件 + 改包名，**不改语义**。全程不开并行会话碰 `src/`；开工前 `git fetch`。

### 3.5.1 M1 撞上的第一个真问题：`R.string` 渗进了本该共享的模型

原计划在 M1 一并抽出 `data` 包与 `TtsPlaybackEngine`，曾**主动止步** —— 跨端模型被 Android 资源系统绑住：`PartOfSpeech`（`ContextAnalyzer.kt`）、`ReaderTheme`/`ReaderFont`（`Models.kt`）、`ReviewMode`/`ReviewPace`（`ReviewMode.kt`）把 `@StringRes val labelRes: Int` 编进了枚举，`:shared` 拿不到 `:app` 的 `R`。

**✅ 已解（2026-09-05，用户拍板决策 4 = 资源 id 间接层）**：

- 新增 `com.linguareader.shared.res.SharedString` 枚举（词性 5 + 主题 7 + 字体 5，共 17 个符号），共享模型携带**符号**而非资源 id；
- Android 侧 `com.linguareader.app.res.AndroidStrings.kt` 提供穷举 `when` 的 `SharedString.resolve(): Int` → `R.string.*`。`:shared` 加符号、这里不补就编译失败，杜绝运行时资源空洞；桌面侧（M2+）另建对端映射；
- `Models.kt` + `ContextAnalyzer.kt`（含 `Book`/`Chapter`/`SavedWord`/`WordLookup`/`ReaderPreferences`/`ReaderTheme`/`ReaderFont`/`PartOfSpeech`/`ContextAnalyzer`，**被 105 个文件引用的基础层**）已迁入 `com.linguareader.shared.data`，随迁 `ModelsTest`+`ContextAnalyzerTest` 共 20 条用例；
- 旧包路径 `com.linguareader.app.data.*` 用 `SharedDataCompat.kt` 的 **typealias 兜底**，既有 45 个 import 文件零改动（`ContextAnalyzer` object 用同名 val 兜底）；UI 消费点仅 `ReaderScreen.kt` 3 处改为 `labelRes.resolve()`（另 3 处 labelRes 消费是 `:app` 本地类型：`ReviewUi`/`ReaderScreen:1123`/`ShelfAppearanceSheet`，未动）；`ModelsTest` 的 `labelRes != 0` 断言相应改为枚举非退化断言。
- **实测踩到一条跨模块硬规则**：类型进 `:shared` 后，`:app` 不再对其属性做智能转换（`ReaderScreen.kt` 的 `entry.matchedPhrase` 编译报错），捕获局部量即可——写进了 `src/shared/README.md`，后续每一刀都会再撞上。
- **剩余拦路石已全部解掉（2026-09-06 M2 刀1/刀2）**：`AppContext`/`PreferencesStore` 抽象落地（prefs 最小面，Android 侧 `SharedPreferencesStore` 适配），`ReviewMode`/`ReviewPace`/`ReviewReminders`/`ReviewScheduler` 随迁入 ：shared（测试守恒 503+39=542）；`DictionaryDatabase` 接口 + `DictionarySql` 常量落地，`DictionaryRepository` 查词主流程入 ：shared（`:app` 同名 facade，测试 503+44=547）。`translation/`、`ai/` 包大体零 Android 依赖，是较顺的后续目标。

**回归**：`:app:testDebugUnitTest` 514 + `:shared:test` 28 = **542**，与上一刀 534+8 完全守恒；`:app:assembleDebug` 通过。

### 3.5.2 依赖纪律：`:shared` 为什么全用 `compileOnly`

`org.json` 与 `kotlinx-coroutines-core` 在 `:shared` 里写成 **`compileOnly`**：Android 侧 `org.json` 由 `android.jar` 平台提供、coroutines 由 lifecycle 传递带入；若用 `implementation`，`org.json` 会进 APK 并与 `android.jar` **撞重复类（dex 报错）**。桌面壳各自声明自己那份 runtime 版本。好处是 `:shared` 的 POM 不带传递依赖，不把版本钉死在 Android 正在用的那份上。

### 3.5.3 门禁补齐（防止「测试悄悄掉出门禁」）

`testDebugUnitTest` 不编译 `:shared`，搬走的 8 个用例等于**从 CI 门禁里静默消失**。已在 `.github/workflows/ci.yml` 补两处：

- 单测步骤改为 `./gradlew testDebugUnitTest :shared:test`；
- 新增 **`:shared` 纯净性检查**：`grep -rE '^import (android|androidx)\.' shared/src/main` 命中即红。「`:shared` 零 Android 依赖」是它存在的全部理由，这种红线必须机械执法，不能只写在文档里靠自觉。

本地跑法：`.\toolchain\build.ps1 :shared:test`（现有脚本零改动，加任务名即可）。

### 3.6 （作废）~~`toolchain/build.ps1` 必须同步改~~

初稿担心根上移后脚本第 17 行硬编码的 `src` 路径要改。实测根未动，**该脚本零改动**，`:shared` 在同一根下直接构建通过（`.\toolchain\build.ps1 :shared:build`）。曾另立过 `toolchain/build-desktop.ps1`，确认多余，已删除。

### 3.7 版本与发布策略

- Android 与桌面**各自 `versionName`**，不共用一个号（发布节奏本来就不同）。
- 桌面版在 SPEC 里作为独立产品轨道登记（F-16x 段），不塞进现有 F-1xx 的"已实现"清单——两端功能集从此会分叉，必须显式记账。
- `readest-src/`、`Readest/`、`silkweaver/` 一律不动，也不作为任何决策依据。

## 4. 逐模块替换清单（接口 + 两端装配）

> 措辞订正（M1 后）：`:shared` 现在是纯 JVM 库、单一 target，所以这里的机制是「**接口/参数下沉到 `:shared`，Android 与桌面各自传入自己的实现**」，不是 Kotlin `expect`/`actual`（那要等 M4 上 KMP 才用得上）。下表语义不变。

| 能力 | Android 现状 | 桌面 actual | 备注 |
| --- | --- | --- | --- |
| 应用上下文 | `Context` 参数贯穿 39 文件 | 自造 `AppContext` interface：`filesDir`、`cacheDir`、`prefs(name)`、`base64`、`platform` | **第一阶段先做这个**，所有数据/网络文件只认这一个面。**M2 刀1 已落地起步**：`AppContext`+`PreferencesStore`（prefs 最小面，Android 适配器 `SharedPreferencesStore`）解掉了 ReviewMode 簇的 SharedPreferences；`filesDir` 等成员按刀的实际消费逐个上收，不预置 |
| 设置存储 | `SharedPreferences` | Properties/JSON 文件（照 data-persistence 记忆：项目本无 DataStore，实现简单） | 数据迁移见 §7 |
| 文件访问 | SAF Uri（`ContentResolver` 仅 6 文件） | `java.nio.file` + `FileDialogProvider`（Compose Multiplatform 1.12+ 自带原生文件对话框） | 拖拽导入（Windows 拖 .epub 进窗口）是桌面加分项 |
| 离线词典 | `SQLiteDatabase.openDatabase(READONLY, NO_LOCALIZED_COLLATORS)`（`DictionaryRepository.openDatabase()`：先把 58 MB assets 拷到 `filesDir/dictionary/ecdict-v2.sqlite` 再打开） | `org.xerial:sqlite-jdbc:3.46.1.3`（只读用连接属性 `open_mode=1`） | **M1 已实测对账**（`:app` 的 `DictionarySqlParityTest`：同一份文件双引擎打开、逐字照抄 `DictionaryRepository` 两条 SQL、含 `\n` 字面量与 LIKE/GROUP BY/`||`，4 测全绿；`:desktopApp` 探针独立跑通）。两处订正：**① 初稿说"NO_LOCALIZED_COLLATORS = 裸 BINARY 比较"错了**——`src/scripts/build_dictionary.py` 在 word/form/lemma 三列声明了 `COLLATE NOCASE`，大小写不敏感是 schema 自带行为，两端等价性反而更强（两引擎同样尊重 schema）；**② `jdbc:sqlite:<盘符路径>?mode=ro` 的 URI 形式驱动不解析**（整串当文件名，Windows 报错），只读要走 `open_mode` 属性。`openDatabase` 的"拷 assets"段是 Android 特有，桌面直接指向安装目录文件。**M2 刀2 已接口下沉**：`DictionaryDatabase` 接口 + `DictionarySql` 常量（唯一真相）进 ：shared，`:app` 留同名 facade（assets 落盘 + SQLiteDatabase 实现），查词主流程与对账测试共用常量 |
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

### 5.1 决策清单（4 项均已拍板）

| # | 选项 | 结论 |
| --- | --- | --- |
| 1 | 渲染：**A 纯 Compose 自绘** / **B 内嵌 JCEF** | ✅ **B**。点词取句契约（归一化坐标、`TTS_BLOCK_SELECTOR` 与 `TtsTextExtractor` 等价性）是本项目最脆弱的跨模块资产，保留 WebView 语义 = 1472 行 JS 和既有回归经验原样生效。A 降为二期评估。 |
| 2 | 工程形态：单仓多模块 / 新仓库 / 桌面替代 Android | ✅ **单仓共用一个 Gradle root（`src/`），`:shared` + `:app` + `:desktopApp` 三模块，Android 版保留**（2026-09-04 从"旁挂"修订而来，理由见 §3.4） |
| 3 | 系统 TTS 后端：做 / 不做 | ✅ **默认不做**，保留自建 + MiMo 两个云后端，离线降级路径改为"纯阅读 + 查词"（系统引擎缺失不影响任何其它功能） |
| 4 | `R.string` 渗入共享模型：剥出 labelRes（映射放 UI 层）/ **资源 id 间接层** / 暂不抽 data | ✅ **资源 id 间接层**（2026-09-05 拍板并落地，见 §3.5.1：`SharedString` 符号 + 各端穷举 `resolve` 映射 + typealias 兼容旧 import）。`ReviewMode` 簇的 `SharedPreferences` 约束已由 M2 刀1 的 `AppContext`/`PreferencesStore` 抽象解掉（2026-09-06） |

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
| 抽 `:shared` 时无意改语义拖垮 Android | 高 | ✅ **M1 已建立机制并跑通一次**：每次移动必须 `:app:assembleDebug` + `:app:testDebugUnitTest` + `:shared:test` 全绿，并核对**测试总数守恒**（M1 实测 530+8=538 与基线一致）；一次只移一个包；CI 已补 `:shared:test` 与纯净性 grep |
| **`R.string` 绑住共享模型**（M1 新发现） | 中 | 5 个枚举（`PartOfSpeech`/`ReaderTheme`/`ReaderFont`/`ReviewMode`/`ReviewPace`）带 `labelRes`，挡住 `data` 包抽取。需拍板决策 4（§5.1）；未决前 M1 只能抽本就零资源的文件 |
| 工作区脏（本会话外还有 14 项在途） | 低 | 本会话只提交自己碰的文件；剩余仍待用户收口 |
| 两套 `pdfbox-android`/JVM PDFBox 行为差异 | 低 | 用同一批 PDF 样本双端 diff 提取文本 |
| 桌面键盘/鼠标交互是新设计，"手势肌肉记忆"回退 | 中 | 快捷键表尽早贴 README；翻页/选词交互单独做一轮走查 |
| 双端功能漂移（Android 修 bug 桌面没修） | 中 | core 单测共享；bug收集/ 的文档注明涉及端；桌面版明确"功能快照"节奏，不承诺同步发布 |

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
