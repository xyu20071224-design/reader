# AGENTS.md — LinguaReader / 语境阅读

面向中文母语学习者的**英文阅读器**。核心闭环：导入图书 → 点击语境查词 → 收藏例句 → 间隔复习 → CSV 导出，外加多引擎「听书」。**离线优先**：默认不联网，所有联网能力都是可选增强且必须有离线降级路径。

## ⚠️ 三个最容易踩的前提

1. **Gradle 根在 `src/`，不是仓库根。** 所有 gradle 命令都要在 `D:\reader\src` 下执行。仓库根没有 `gradlew`。
2. **开发机是 Windows / PowerShell。** 用 `.\gradlew.bat`；`cd` 不跨命令保留，用工具的 `workdir` 参数。
3. **`readest-src/`、`Readest/` 不是本项目代码。** 它们是另一个开源阅读器 Readest 的源码与安装目录，仅作参考（已在 `.gitignore`）。改动本项目时绝不要动它们，也不要把它们的结论当作本项目事实。

## 常用命令

```powershell
# 工作目录：D:\reader\src
.\gradlew.bat assembleDebug            # 构建调试 APK
.\gradlew.bat installDebug             # 安装到已连接设备/模拟器
.\gradlew.bat testDebugUnitTest        # JVM 单元测试（Robolectric，快，改逻辑必跑）
.\gradlew.bat connectedDebugAndroidTest # 仪器测试（需设备）

# 真机上已装正式调试包时，装一个并存的验证包（applicationId 加 .verify 后缀）
.\gradlew.bat assembleDebug -PverifyBuild
```

自建 TTS 服务端见 `.agents/memory/tts-server-stack.md`。

## 构建事实（`src/app/build.gradle.kts`、`src/build.gradle.kts`）

| 项 | 值 |
| --- | --- |
| applicationId / namespace | `com.linguareader.app` |
| compileSdk / targetSdk / minSdk | 35 / 35 / **23** |
| versionCode / versionName | 12 / 1.6.0 |
| JDK / jvmTarget | 17 |
| AGP / Kotlin / Gradle | 8.9.1 / 2.1.10 / 8.11.1 |
| Compose BOM | 2025.05.01 |

关键依赖：`jsoup`（HTML 解析）、`pdfbox-android`（PDF 文字层）、`androidx.webkit`。

- **仓库配置阿里云镜像优先**（`src/settings.gradle.kts`），因为上游仓库在部分网络下 403/reset。加依赖时别把镜像顺序改掉。
- `src/gradle.properties` 开了 `android.overridePathCheck=true`（路径含中文时 AGP 会报错）。
- **仓库搬过家（两次）**：`C:\工作文件夹\reader` → `C:\work\reader` →（2026-09-03）`D:\reader`。搬迁已核对：工作树与 HEAD 零差异、单测在新位置全绿；`src/local.properties` 与 `.agents/` 记忆里的路径已同步订正，历史快照里的 `C:\work\reader` 只出现在 git 历史里。`tts-voice-studio/studio.py` 与 `scripts/cut_first_3s.py` 里仍硬编码**最旧**中文路径，直接跑会失败（详见 `.agents/memory/local-tools-and-assets.md`）。上面那条 `overridePathCheck` 也是旧中文路径留下的。
- `minSdk = 23`：写代码别用未做兼容处理的高版本 API。

## 目录布局

| 路径 | 内容 |
| --- | --- |
| `src/` | **Android 主工程的 Gradle 根**（`settings.gradle.kts` / `gradlew`） |
| `src/app/src/main/java/com/linguareader/app/` | 应用代码，包名根 |
| `.../app/` | 顶层 Compose 屏幕与外壳：`MainActivity`、`AppViewModel`、`ReaderScreen`、`BookshelfScreen`、`VocabularyScreen`、`ReviewUi`、`ListeningBar`、`ListeningSettingsSheet`、`MultiVoiceSettings`、`AiDrawerSheet`、`AppSnackbar`、`ThemeColors` |
| `.../app/reader/` | WebView 阅读渲染：`ReaderScreen` 的引擎侧（`ReaderScripts` 注入 JS、`EpubPage`、`ReaderController`） |
| `.../app/data/` | 导入器（EPUB/TXT/FB2/PDF）、词典、语境分析、书库、生词本、复习与提醒 |
| `.../app/tts/` | 听书全部实现（26 个文件）：播放状态机、合成器、3 类引擎后端（系统 / 自建 OpenAI 兼容 / MiMo；Piper/Azure/火山已于 2026-08-29 移除）、多角色音色 |
| `.../app/ai/` | 可选联网 AI：语境档案、整句翻译、术语表、说话人 LLM 标注 |
| `.../app/translation/` | 中文译本对照（F-128，纯离线）：三级 DP 对齐、句/段/词级查询、对齐档案读写 |
| `src/app/src/test/` | JVM 单测（62 个文件：tts 19 / ai 16 / data 10 / 外壳 7 / translation 5 / update 3 / reader 2） |
| `src/app/src/androidTest/` | 仪器测试（13 个文件） |
| `src/app/src/main/assets/` | `dictionary/ecdict.sqlite`（离线词典） |
| `src/app/src/main/res/values{,-en}/strings.xml` | 中文（默认）+ 英文文案，两侧各 559 个 string + 11 个 plurals |
| `tts-server/` | 自建 OpenAI 兼容 TTS 服务端（Python）+ IndexTTS 克隆音色 + frp 内网穿透配置 |
| `tts-voice-studio/` | 本地音色调试工作台（Python + 单页 HTML） |
| `bug收集/` | 缺陷文档库（BUG-001~039 分析/分级/验证方案；001~026 于 2026-08 自 legacy 线收录，027~039 为 2026-09-01 第五轮审查）；修 bug 前先来这里查有没有前人分析，**并留意 README 顶部那条「状态列怎么读」的警告**（001~026 的「已修复」记的是 legacy 线状态，未必等于 main） |
| `.github/workflows/ci.yml` | GitHub Actions 单测 CI（push/PR 自动跑 `testDebugUnitTest`） |
| `scripts/`、`src/scripts/` | 克隆音色制作、音频对比、词典构建、示例 EPUB 生成 |
| `artifacts/`、`验证截图/` | 本地验证产物（APK / logcat / 截图），`artifacts/` 被 gitignore |

## 项目约定

- **提交信息**：`feat:` / `fix:` / `refactor:` / `test:` / `chore:` + 中文描述（照现有 git log 的风格）。
- **文案资源化进行中**：新增用户可见文案走 `strings.xml`（zh 默认 + `values-en`，两边同时加，key 用 `模块_用途` 形式如 `notice_book_imported`）。老代码里仍有硬编码字符串，改到哪块顺手迁哪块。
- **用户反馈走全局 Snackbar**（`AppSnackbar.kt`）。任何「保存/导入/删除成功或失败」都要给反馈——历史上就吃过「AI 保存无反馈」的亏。
- **纯逻辑要能单测**：状态机/算法抽成不依赖 Android 的类（先例：`tts/TtsPlaybackEngine.kt` 被专门抽出来做纯 Kotlin 状态机）。新增算法先写 JVM 单测。
- **隐私边界是产品承诺**：新增任何出网调用，必须（a）默认关闭、（b）由用户显式开关控制、（c）失败/未配置时有离线降级。注意实际实现是「AI 总开关 `powerEnabled` 默认 true，但各子开关默认 false 且无 Key」，所以出厂状态零出网 —— 别误以为总开关本身是那道闸。详见 `.agents/memory/ai-context-translation.md`。
- **明文 HTTP 全局放行**（`res/xml/network_security_config.xml`），因为自建 TTS 服务器常在局域网跑 HTTP。这是有意决定，注释写在文件里。
- **不要提交大文件**：APK、logcat、`.onnx` 模型、参考音频、ffmpeg 都在 `.gitignore` 里，保持这样。
- **密钥不入库、不入文档**：API Key 由用户在应用内填写。任何文档/记忆文件只写字段名。

## Git / GitHub 工作流

远端 `origin` = `github.com/xyu20071224-design/reader`，`main` 是唯一权威线。分布式格局一句话：**本地随便折腾（试验分支、worktree、reset 都行），但任何不想丢的工作必须落在 `main` 或已 push 的分支上**——`git status` 干净且 `git log origin/main..main` 为空，才可安心关机。

- **合并即推送**：feature 分支合并回 `main` 后立刻 `git push origin main`。曾发生过本地 main 领先远端二十多个提交一周没推的情况，等于没有备份。
- **feature 分支也要推**：`git push -u origin feat/<主题>`。单人项目推分支不是为了评审，是异地备份 + 在网页上翻 diff。
- **动手前先 `git fetch`；push 被拒说明远端分叉，停下来查清再动**。曾发生过两条线平行开发同一批功能（译本对照、TTS 修复、tts-server），main 真正分叉、四十多个文件两边都改的事故——多机/多会话并行开发时尤其危险。**绝不 force-push main**，确需覆盖时必须先把远端现状备份成分支；`main` 已开 GitHub 分支保护（禁 force-push、禁删除），应急覆盖前需先用仓库所有者令牌经 API 临时解除。
- **CI（GitHub Actions）**：`.github/workflows/ci.yml` 在 push（main / feat / ci / legacy 分支）与所有 PR 上自动跑 `testDebugUnitTest`（Gradle 根在 `src/`，workflow 已设 working-directory）。红了先修再合并；远端状态以 Actions 页为准。
- **同一时间尽量只开一个会话操作本仓库**。2026-08-21 与 08-23 两次并行会话撞车：strings.xml 被 concurrent 改动丢 14 个 key、CI 搭建被另一会话抢先完成。确需并行：每个会话开工前 `git fetch`，分工不重叠文件。
- **legacy 备份分支**：`legacy/remote-main-20260820`（本地+远端长期保留）封存另一账号 2026-08-19/20 的平行开发——`:core` 模块化、ui/ 包重组、facade 层、TermLexiconLearner/TranslationMemorySearch 双实现。其缺陷修复已于 2026-08-23 审阅移植完毕（见 `bug收集/` 与 VALIDATION.md 当日条目）；剩余是架构方向决策，未表决前不要从该分支合并代码。
- **分支命名统一 `feat/<主题>`**（与提交前缀一致，不混用 `feature/`）；合并后即删：`git branch -d <name>`，推过远端的加 `git push origin --delete <name>`。
- **提交身份保持统一**：`git config user.name / user.email` 全仓库一致。历史上出现过两个身份各推一条线，加剧了分叉排查难度。
- **同机并行用 `git worktree add`**，试验目录删掉后记得 `git worktree prune` 清残留。
- **版本发布**：tag 用 `v<x.y.z>`（与 `versionName` 一致）并 `git push origin v<x.y.z>`；正式版可在 GitHub 建 Release 附 APK——Release 资产不进 git 历史，不受大文件规则约束。
- **大文件红线对远端同样生效**：截图、logcat、APK、模型、测试电子书不入库；远端历史上混入过整目录工件，清理时分批 commit，别再犯。

## 验证纪律

- 改纯逻辑 → `testDebugUnitTest` 必跑（本地或 CI 皆可，CI 在 push 时自动兜底）。
- 改 WebView 渲染、手势、TTS 播放、通知栏媒体控制 → **必须真机或模拟器实测**，这些行为单测覆盖不到。历史真机验证设备：PKB110 / Android 16。
- **分页跟随已于 2026-09-01 解禁**（M2 第 2 刀），但解禁的前提别丢：当年章末死循环（2026-08-23 真机事故）的成因是「翻页 → 阅读器位置回报 → 引擎被拽回该页首块」这条回路，现在**回报路径整条删除**（契约反转成「页面跟朗读」），回路不可能闭合。护栏有三道：`followRangeIntoView` 只在句子不在当前页时翻、300ms 合并、用户接管窗口内不翻；跟随翻页带 `origin='tts'`，Kotlin 侧据此不清高亮。**谁要把「阅读位置回报给 TTS」加回来，必须先重新设计抑制机制**，否则死循环会原样复活。
- **PKB110（ColorOS）已知怪癖**：通知权限 `pm grant` 命令成功但检查仍 false、应用通知被 importance=NONE 压制——通知相关仪器测试 assumeTrue 跳过属预期，不是回归。
- 验证结论写进 `VALIDATION.md`，截图放 `验证截图/`。
- sideload 同一 `versionCode` 覆盖安装有坑，需要并存包时用 `-PverifyBuild`。

## Agent 工作区

项目记忆与规则在 `.agents/`：

- `.agents/memory/MEMORY.md` — **索引，先看这里**，再按主题深入 18 个记忆文件（含跨模块的 `architecture-map.md`）。
- `.agents/rules/` — 工作规则：`memory-maintenance.md`（何时、怎么把新知识写回记忆库）、`code-and-verification.md`（实现范围、平台约束、文案、测试与验证矩阵、联网硬约束、提交规范）。

动手前先读 `MEMORY.md`；发现记忆与代码不符，以代码为准并顺手修正记忆文件。
