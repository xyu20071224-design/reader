## 2026-08-06 F-122 短语识别逻辑修复（增量验证）

- 根因：短语匹配只看“点击词是否在词条中”，词组后部宾语/补足语（如 `good day` 的 `day`、`out of time` 的 `time`）或点击偏移漂移到相邻词时，仍会短语优先，导致点到单词却显示词组释义。
- 修复：短语优先仅由“首个实义词或紧随动词的小品词”触发；点击词与短语词条按词形还原对齐（支持 `have got to` 等变形词条）；同位置多候选优先核心命中；点击词定位以表面词为准防偏移漂移。
- 验证：`testDebugUnitTest` 通过（59 个，新增 3 个 F-122 回归用例）；`lintDebug` 通过；`assembleDebug` 通过；`connectedDebugAndroidTest` 通过（Android 15，32/32，含新增 2 个相邻词回退/变形词头仪器用例）。
## 2026-08-06 F-138 自定义复习节奏 + F-137 提醒方式更新（增量验证）

- F-138 自定义节奏：设置面板新增“自定义”入口，在示意遗忘曲线上取点（约 70%–95% 记忆保留率）换算间隔倍率（×0.5–×2.0，30 分钟下限），并可调整首次复习（5 分钟 / 30 分钟 / 2 小时 / 次日）、每日主动提示上限（1/2/4 次）与单次最多复习词数（3/5/10）；以 `review_mode_custom` JSON 持久化，旧数据无需迁移。
- F-137 提醒方式独立化：语境浮现、停顿点提示、工具栏角标、定时轻提醒可自由组合，另有“仅手动”一键全关；`review_reminders` JSON 持久化，升级时按当前节奏预设的经典组合兜底；选择预设会恢复其经典提醒组合，之后仍可单独调整。
- `testDebugUnitTest`：通过（56 个），新增 ReviewPace 曲线换算/JSON 往返/倍率缩放与 ReviewReminders 组合用例。
- `lintDebug`：通过，0 错误。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：30/30 通过；ReviewUiTest 覆盖提醒开关组合、预设选择、自定义曲线编辑与保存。

## 2026-08-06 增量验证（P4 多格式 + P5 复习提醒 + P6 全量收尾 + P7 启动问候/更新提示）## 2026-08-06 增量验证（P4 多格式 + P5 复习提醒 + P6 全量收尾 + P7 启动问候/更新提示）

- `testDebugUnitTest`：通过。新增 PdfImporterTest（Robolectric 模拟 Android 环境，共 6 个用例）：书签分章、无书签标题启发式分章、无标题按页块分章、扫描版/无文字层拒绝、标题行识别、单次提取按页切分。
- `lintDebug`：通过，0 错误；新增警告仅来自 PDFBox 依赖内的 Bouncy Castle `TrustAllX509TrustManager` 提示（离线使用不受影响）。
- `assembleDebug`：通过。APK 由约 44.4MB 增至 53.2MB（+8.7MB），新增 pdfbox-android 2.0.27.0 与 Bouncy Castle 1.72。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：26/26 通过。P4 覆盖 EPUB/TXT/FB2/PDF 导入（PDF 含真机 PDFBox 文本提取、书签分章、扫描版拒绝、文件名回退）、离线词典查词、生词持久化与复习状态、书架冒烟；P5 新增复习设置面板/停顿点提示 Compose 测试（3）、新词首次延迟（1）、通知链路（授权发送/每日上限/拒绝不崩溃，2）。本次回归发现并修复 TXT/FB2/PDF 书名回退误用临时文件名的问题（新增 ImportSupport.displayName/baseName）。
- `testDebugUnitTest`（P5 新增）：ReviewModeTest 5 个（预设参数、倍率缩放、掌握等级、遗忘重启）、ReviewReminderPolicyTest 3 个（仅勤学+授权+限额内提醒、每日上限、日期键）、ReaderScriptsTest 高亮脚本 2 个。
- `ReaderAcceptanceTest`（P6 新增，端到端）：打开种入的 EPUB → 翻页 → 配置变更（旋转）后保持当前页；点击页码输入 3 跳转成功；点击正文常用词打开查词面板。
- `LaunchPromptUiTest`（P7 新增，2 个）：问候弹窗（标题/文案/开始阅读）与更新说明弹窗（版本标题/条目/知道了）渲染正确。
- P7 实测（adb）：清数据模拟旧版本升级后首次启动显示“版本更新 1.1.0”说明，点“知道了”后重启仅显示时间问候，更新说明不再出现。
- `PdfImporterInstrumentedTest`：3 个用例全部通过（带书签 PDF 导入、无文字层拒绝、元数据缺失时用文件名做书名）。

## 2026-08-10 F-150 听书（增量验证）

- 新增听书：整章/全书连续朗读（系统 TTS，中英句级切分），朗读时当前句在阅读页高亮并自动翻到所在页；点击句子从此句开始听，手动切章/翻页后播放位置跟随。
- 播放控制：暂停/继续、上一句/下一句、0.5×–2.0× 语速（滑块 6 档）、停止；前台媒体服务支持后台播放，通知栏提供上一句/播放暂停/下一句/停止。
- 进度记忆：`metadata.json` 新增 `ttsChapterIndex`/`ttsSentenceIndex`，旧数据缺失按 0 兼容；停止/暂停/切章时保存。
- 架构：播放层仅依赖 `TtsSynthesizer` 接口，`TtsSynthesizerFactory` 为云端 TTS 预留替换点；当前实现为 Android 系统 TTS，不申请联网权限。
- `testDebugUnitTest`：通过（77 个），新增中英句切分（缩写/引号/省略号/中文无空格边界）、章节文本提取与句定位、听书进度 JSON 兼容、阅读器脚本高亮/点击跟读桥接用例。
- `lintDebug`：通过，0 错误（仅原有 `allowBackup` 弃用提示）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过；ReaderAcceptanceTest 翻页/跳页/点词回归通过（模拟器预热后全量通过）。

## 2026-08-10 F-151 外置 TTS（增量验证）

- 新增三引擎听书设置：系统语音（默认）/ Azure 云 TTS（世纪互联 `chinanorth3`，音色列表实时拉取）/ 自建 OpenAI 兼容服务器（`/v1/audio/speech`，可接 Fish Speech S2、GPT-SoVITS 适配服务）。
- 整章首次预生成（并发 3 路）并缓存到 `files/tts_cache/`，首句就绪即播、其余后台生成；语速本地变速不重复合成；失败自动回退系统语音；Key/Token 经 Android Keystore AES-GCM 加密存储；删除书籍时清理对应缓存。
- `testDebugUnitTest`：通过（100 个），新增 Azure 音色 JSON 解析/默认音色选择/SSML 转义、OpenAI 兼容请求体与音色回退用例。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过（预热后全量；冷启动首轮偶发 2 条 ReaderAcceptanceTest 超时，重跑即绿，与云 TTS 改动无关）。
- 说明：云 TTS 合成依赖真实服务器，仪器测试仅覆盖现有离线链路；Azure/自建服务器联调需真实 Key/服务器后人工验证。

## 2026-08-10 F-151 火山引擎（豆包语音）增量验证

- 新增第四引擎“火山引擎（豆包语音）”：V3 HTTP SSE 单向流式接口 `https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse`；鉴权支持新版控制台 API Key（`X-Api-Key`，推荐）与旧版 App ID + Access Token（`X-Api-App-Id` + `X-Api-Access-Key`）；Resource ID 默认 `seed-tts-2.0`（也可选 `seed-tts-1.0` / `seed-tts-1.0-concurr` 使用 `BV*_streaming` 音色）；中文/英文音色分别配置，支持声音复刻 speaker ID；设置页“测试连接”中英文各合成一次。
- `testDebugUnitTest`：通过（113 个），新增火山引擎用例 10 个：请求体（文本/音色/采样率/mp3 参数）、中英文语音路由、配置校验（新版 API Key 或旧版 AppID+Token）、SSE 多帧音频 base64 解码、结束帧（code 20000000）停止、错误帧/HTTP 错误失败、新版/旧版鉴权头与请求 ID。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过（首轮 2 条 ReaderAcceptanceTest 冷启动偶发超时，预热后单独重跑 3/3 通过，与火山改动无关）。
- 说明：火山引擎联调需用户提供真实 API Key（或 App ID + Access Token）后人工验证；仪器测试仍只覆盖离线链路。

## 2026-08-12 F-150 真机问题修复（增量验证）

- 修复手动翻页后旧句高亮残留：`onPageChanged` 时立即清除 `lr-tts-overlay`（[ReaderScreen.kt](src/app/src/main/java/com/linguareader/app/ReaderScreen.kt)），下一句朗读时重新定位高亮。
- 高亮不再自动滚到对应页：移除 `lrHighlightSentence` 中的滚动逻辑与 `ReaderBridge.onTtsPage` 桥接（[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt)、[EpubPage.kt](src/app/src/main/java/com/linguareader/app/reader/EpubPage.kt)），页面始终停留在用户当前位置。
- 新增“起点”按钮（[ListeningBar.kt](src/app/src/main/java/com/linguareader/app/ListeningBar.kt)）：进入起点模式后点击正文任意单词/句子即从该句开始朗读（只设起点、读到手动停止）。
- `testDebugUnitTest`：通过（113 个），ReaderScriptsTest 更新为断言“高亮不再自动滚页/不再调用 onTtsPage”。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：全量 32/32 通过（首轮与预热轮偶发 1–2 条 ReaderAcceptanceTest 冷启动超时，单跑 1/1 通过，与本次改动无关）。

## 2026-08-12 F-150 起点交互重做（增量验证）

- 打开听书不再自动播放：`TtsPlaybackService` 新增待选起点状态（`ACTION_STANDBY`，[TtsPlaybackService.kt](src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackService.kt)），播放条提示点击正文选择起点；首次正文点击经 `startFromBlockOffset` 从该句开始。
- 播放条“起点”按钮只消费其后第一次正文点击：JS 端 `lrSetChoosingStart` 标志在首次点击即被消费（[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt)），正常播放中点击正文恢复点词查词，不再误触重定位。
- 顶部“听书”按钮在播放中仅暂停，不再进入选择状态；待选/暂停状态再次点击才重新进入选择。
- `testDebugUnitTest`：通过（113 个），ReaderScriptsTest 断言“选择起点标志首次点击即消费”。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。

# Validation report

Validated on 2026-07-30 with an Android 15 / API 35 ARM64 Pixel 6 emulator.

## Automated checks

- `testDebugUnitTest`: passed
  - book metadata and reading-position JSON round trip
  - tokenization preserves abbreviations, possessives and hyphenated words
  - phrase-window generation and context POS ordering
  - reader JavaScript contains word/sentence extraction and sentence offsets
  - reader preferences are safely encoded
  - saved-word JSON round trip, CSV escaping and review scheduling
- `connectedDebugAndroidTest`: 8/8 passed
  - EPUB metadata/spine parsing and script sanitization
  - bundled ECDICT lookup with irregular inflection (`carried` → `carry`)
  - lemmatized phrases (`looked forward to` and `took off`)
  - abbreviation lookup retaining internal periods (`U.S.`)
  - saved-word persistence and review-state update
  - bookshelf Compose UI smoke test
  - vocabulary screen navigation and controls
- `lintDebug`: passed with 0 errors
  - remaining notices are dependency-update and optional KTX-style suggestions
- `assembleDebug`: passed

## Manual emulator checks

Using [`测试电子书-TheLanternLibrary.epub`](测试电子书-TheLanternLibrary.epub):

1. Imported through Android's system file picker.
2. Verified title, author, three chapters and generated bookshelf card.
3. Opened chapter one and confirmed four-page pagination.
4. Used the right edge to move from page 1 to page 2.
5. Opened the table of contents and reading appearance controls.
6. Restarted the app and confirmed the stored book and reading position.
7. Tapped `carried`; the app selected `carry`, inferred a verb context and moved
   `vt.` / `vi.` Chinese senses ahead of the noun sense.
8. Tapped `forward` in `They looked forward to working together.`; the app matched
   the complete phrase `look forward to` and returned its Chinese phrase meaning.
   Tapping the function word `to` now shows the word meaning plus a
   “相关短语：look forward to” entry point instead of replacing the word lookup.
9. Added `carry` to the vocabulary list and confirmed its pronunciation button,
   three context-ranked senses, source sentence, book and chapter.
10. Opened review mode, revealed the answer and verified both “again” and
    “remembered” grading actions.
11. Confirmed the app manifest does not request network access.

## Artifact

- APK: [GitHub Releases](https://github.com/xyu20071224-design/reader/releases)（本地副本：`artifacts/LinguaReader-1.2.0-debug.apk`）
- Version: 1.2.0 (version code 5)
- Size: approximately 53 MB
- SHA-256: `60815E4E4A941CD24DD656DABBA0F043F77E8C0DECAA0BBEE84F63A11833BE03`（2026-08-07 更新说明精简后重建刷新）

The APK is debug-signed for direct installation and evaluation. A Play Store
release still requires a production signing key and release configuration.
