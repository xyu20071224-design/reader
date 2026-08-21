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

## 2026-08-12 F-150 选择起点从本章开头播放（增量验证）

- 根因：JS `textAtPoint` 的 `closest` 选择器漏掉 `section/article/pre/h5/h6` 等叶子块；书内用这些标签作段落时，点词传回的段落文本在 Kotlin `TtsChapter` 中匹配不到，`sentenceIndexAt` 返回 null 并回退第 0 句。
- 修复：[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt) 的 `textAtPoint` 改用与 `ttsBlocks()`/`TtsTextExtractor` 相同的 `TTS_BLOCK_SELECTOR`；[TtsTextExtractor.kt](src/app/src/main/java/com/linguareader/app/tts/TtsTextExtractor.kt) 的 `locateBlock` 增加“祖先段落包含多个叶子块时按点击偏移命中最长叶子并重定位偏移”的回退。
- `testDebugUnitTest`：通过（127 个），新增 `TtsTextExtractorTest.sentenceIndexAtRebasesOffsetFromAncestorParagraph` 与 `ReaderScriptsTest.tapToStartUsesSameBlockSelectorAsTtsExtractor`。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- 说明：本次工作区与另一会话的 AI/系统语音改动并存，文档记录为增量追加；仪器测试沿用既有 32/32 基线（冷启动偶发 1–2 条 ReaderAcceptanceTest 超时，单跑通过）。

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

## 2026-08-15 1.3.1 缺陷修复（增量验证）

本次对 1.3.0 听书链路做代码审查后，修复三类问题（详见 `更新报告-1.3.0到1.3.1.md` 与 FEATURE_SPEC 规约 1.6.9）：

- 听书进程稳定性：待选（standby）态前台服务化、进度写盘竞态、快速切句跳章、章节握手白等、utteranceId 复用漏句、句尾卡死。
- 凭证安全：Keystore 并发覆盖密钥、AI 凭证明文、解密健壮性。
- 高亮定位：叶子块判定不等价导致的内联样式章节高亮错位、滚动恢复闪跳。

验证情况：

- `assembleDebug`：通过（JDK 17 / Gradle 8.11.1 / AGP 8.9.1，`compileDebugKotlin` 通过，无编译错误）。构建环境用 `android.overridePathCheck=true` 放行含中文的项目路径。
- `testDebugUnitTest`：未在本环境全量重跑；本次新增 `TtsTextExtractorTest` 两个用例（内联叶子块判定 + 嵌套块防回归），`LaunchPromptTest` 不受新增 1.3.1 更新说明分支影响。
- `lintDebug` / `connectedDebugAndroidTest`：未重跑，需完整 Android 环境（模拟器/真机）补做最终回归。
- 产物：`LinguaReader-1.3.1-debug.apk`（调试签名，约 56 MB，SHA-256 `ECE3E30B36EDA4004949E9EF92A553E53B85FDF4D79EEB9D9895E7D47814B234`），已发布至 GitHub Releases tag `v1.3.1`。

## 2026-08-20 F-152 多角色听书 M2（LLM 说话人打标，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M2 里程碑**（M1 规则层双音色已在前一轮落地）：

- 书级角色画像：`CharacterProfile`（姓名/别名/性别/年龄段/风格/重要性/语言/置信度）随 AI 语境档案一次产出（D1，共用 `AiChatClient` DeepSeek 调用框架），并按「手动优先、别名取并集」并入本书术语表 `kind=character` 条目。
- 章节 LLM 打标：`SpeakerLlmTagger` 按「段索引 + 引文序号」对齐（`SpeakerRuleTagger.index()` 提供槽位），角色表 + 别名归一 + `confidence ≥ 0.6` 校验，未通过者退回规则层标签；长章节按 12k 字符分窗，无引文窗口不请求。
- 缓存与增量：`files/ai/speaker-tags/<bookId>/<chapter>.json`（Mutex + 原子写），已打标章节不再请求，句数不匹配的旧缓存作废；删除书籍或重建语境档案时清除。
- 播放接线：章节先用规则层结果开播，LLM 结果返回后热替换标签（`TtsPlaybackEngine.applySpeakerTags` + `TtsTextExtractor.applySpeakers`），队列/`utteranceId`/进度/高亮零影响；每章只解析一次，避免逐句重复读缓存或重复请求。
- 降级：联网 AI 关闭、无 DeepSeek Key、角色表为空、请求失败/超时均保持 M1 规则层结果，失败章节不落缓存。D2 限制保留：Piper/系统语音以及未配置对白音色时不发起任何打标请求。

验证情况：

- `testDebugUnitTest`（JDK 17 / Gradle 8.11.1 / AGP 8.9.1，`--offline`）：**206 个全部通过**，其中本轮新增 44 个：
  - `SpeakerLlmTaggerTest` 18 个：角色表/别名/大小写归一、引文与段落级对齐、未知角色与低置信度退回规则层、缺字段与非法 JSON、分窗策略（无引文不请求、长章节切窗）、提示词携带绝对段索引与引文编号、后端失败/部分窗口失败的降级与可缓存性、标签与句子长度一致。
  - `SpeakerTagRepositoryTest` 9 个（Robolectric）：缓存读写与增量（同章第二次零请求、新章节才请求）、长度不匹配缓存作废、无 Key/总开关关闭/空角色表零请求降级、后端失败不落缓存且下次重试、`delete` 清空、越表角色名不被写成角色。
  - `CharacterProfileTest` 11 个：`CharacterProfile`/`BookContextProfile`/`GlossaryEntry`/`ChapterSpeakerTags` JSON 往返与缺省值、多段档案合并、`mergeProfile` 手动优先与别名并集。
  - `SpeakerRuleTaggerTest` +4 个：段落/引文槽位索引、`index()` 与 `tag()` 一致、跨段引文在本段内编号、空章节。
  - `TtsPlaybackEngineTest` +2 个：播放中热替换说话人标签后下一句改用角色音色；跨书/跨章/长度不符的标签被丢弃。
- `lintDebug`：通过，0 错误（40 条提示中 39 条为既有警告，无一来自本轮新增文件）。
- `assembleDebug`：通过（调试 APK 打包成功）。
- `connectedDebugAndroidTest`：本轮未重跑（需模拟器/真机）。
- 真实链路人工验证仍待做：需真实 DeepSeek Key + 云 TTS 服务器，按 `PLAN-MULTI-VOICE.md` §9「真机/服务器验证」抽验归属准确率（目标 LLM ≥ 90%）。

## 2026-08-20 F-152 多角色听书 M3（角色 → 音色自动分配，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M3 里程碑**，让 M2 的逐句角色标签真正发出不同声音：

- 音色库画像：`VoiceInfo`/`VoiceLibrary`/`VoiceNaming` + `VoiceLibraryLoader`。Azure 取 `voices/list` 的性别与 `StyleList`；自建服务器新增 `GET /voices`（本地 Kokoro 包装）与 `/v1/audio/voices`（Kokoro-FastAPI）音色列表拉取并缓存（`ServerVoiceStore`）；火山用已配置音色；裸音色 ID 由命名先验补全语言/性别（`zf_/zm_/af_/am_/bf_/bm_`、`zh_female_*`、`zh-CN-*`）。
- 分配算法：`VoiceAssigner` = 硬过滤（语言必配、性别不冲突）→ 软评分（性别/年龄/风格/重要性×音质）→ 按重要性与共现度贪心并施加区分度惩罚 `λ·Σ sim` → 交换一次 → **仅在不共现角色之间复用** → 旁白兜底；旁白音色先选（偏中性风格）并计入已占用，天然与角色拉开。
- 共现统计：`SpeakerCooccurrence` 直接复用 M2 的章节打标缓存（跳过旁白/未归属对白、折叠连续同一说话人），不新增存储。
- 持久化与一致性：`BookVoiceMap` + `VoiceMapRepository`（`files/voice_maps/<bookId>.json`，Mutex + 原子写）：跨章跨会话不重算、新角色增量分配、`userLocked` 永不被覆盖、切换引擎重算但保留锁定；删除书籍时一并删除映射。
- 播放接线：`TtsPlaybackService.resolveVoice`（手动旁白音色 → 角色/旁白映射 → 对白音色 → 引擎默认）同时供播放队列与云引擎整章预生成使用（`TtsSynthesizerFactory.create(..., voiceResolver)`），避免预生成把音频缓存到播放端不会请求的音色下；解析器签名扩展为 `(speaker, text)`，按句语言选择中/英旁白音色；设置快照随 ACTION_RECONFIGURE 失效。

验证情况：

- `testDebugUnitTest`（`--offline`）：**246 个全部通过**，其中本轮新增 40 个：
  - `VoiceAssignerTest` 15 个：硬过滤（语言/性别/放宽顺序）、软评分（风格与年龄细化）、旁白中性选择与保留位、共现拉开（同一输入有/无共现得到不同结果）、复用仅限不共现且相邻角色宁退旁白、交换、锁定不动、增量不动旧映射、换引擎重算保留锁定、空音色库不改动、中英旁白各一个、同重要性顺序确定。
  - `VoiceMapRepositoryTest` 7 个（Robolectric）：落盘与重载、增量新增角色、锁定项在重分配后保留、切换引擎在新库内重算、无音色库返回空、保留位、删除。
  - `BookVoiceMapTest` 7 个：JSON 往返、大小写无关查找、旁白按语言回退、未知说话人回退调用方默认、锁定/解锁、空输入不产生垃圾条目。
  - `VoiceLibraryTest` 6 个 + `SpeakerCooccurrenceTest` 4 个 + `OpenAiCompatTtsBackendTest` +1（音色列表三种返回形状与异常载荷）。
- `lintDebug`：通过，0 错误（40 条提示：1 条信息 + 39 条既有警告；本轮新增文件无任何告警，`ServerVoiceStore` 用 `edit {}` KTX 写法）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`：本轮未重跑（需模拟器/真机）。
- 真实链路人工验证仍待做：需真实云 TTS/自建服务器，人工确认「主要角色音色区分度」与「同书跨章音色一致」，并核对 Kokoro `/voices` 实际返回的音色数量是否足够 10+ 角色。

## 2026-08-20 F-152 多角色听书 M4（角色音色界面，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M4 里程碑**，多角色听书自此形成完整闭环（M1 规则层 → M2 LLM 打标 → M3 自动分配 → M4 人工可调）：

- 显式开关：`CloudTtsSettings.multiVoiceEnabled`（默认关，持久化）。关闭时不发任何打标请求、不生成音色映射，播放等同单音色 + 手填旁白/对白音色；Piper 与系统语音下开关置灰并给出 D2 说明。
- 多角色面板 `MultiVoiceSection`（`MultiVoiceSettings.kt`）：书目选择（阅读页自带当前书）、状态提示（无音色库 / 无角色表 / 无 Key 规则模式 / 音色不足已共用 / 已分配 N 个）、中英旁白音色下拉、按重要性排序的角色列表（角色名 + 画像摘要 + 音色下拉 + 试听 + 🔒 锁定标记）。
- 试听：`VoiceAudition` 用当前引擎按指定音色合成一句样句并用 MediaPlayer 播放（中文音色说中文样句、英文说英文），播放前停掉上一条避免叠音。
- 共用装配：`MultiVoiceSupport` 收敛服务与 UI 的公共逻辑（音色库刷新、角色表读取、保留音色、D2 判定、状态文案、样句），`TtsPlaybackService` 改为复用它。
- 解析优先级调整：角色/旁白映射（M3，M4 面板直接编辑）→ 手填旁白/对白音色（M1）→ 引擎默认，避免面板改了旁白却被旧手填值覆盖。
- 立即生效：任何手动选择写入 `userLocked` 后触发 `onCloudSettingsChanged`（ACTION_RECONFIGURE），服务重载映射，下一句起使用新音色。

验证情况：

- `testDebugUnitTest`（`--offline`）：**255 个全部通过**，其中本轮新增 9 个：
  - `MultiVoiceSupportTest` 6 个：D2 引擎判定、开关/总开关/引擎三重门控（默认关）、保留音色集合、中英样句与旁白样句、状态文案五种分支、共用音色计数与「音色不足」提示。
  - `CloudTtsSettingsTest` 3 个（Robolectric）：多角色开关默认关、听书设置（含开关与旁白/对白音色）保存-加载往返、关闭后仍持久化。
- `lintDebug`：通过，0 错误（40 条提示：1 条信息 + 39 条既有警告；本轮新增文件零告警——UI 的 `AutoboxingStateCreation` 提示已按建议改用 `mutableIntStateOf`）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`：本轮未重跑；多角色面板的交互（下拉选择、试听播放）需真机/模拟器 + 真实云 TTS 服务人工验证。
- 仍待人工验证：开关打开后端到端听感（角色音色区分度、跨章一致性）、试听按钮在各引擎下的成功率、Kokoro `/voices` 返回的音色数量是否足够 10+ 角色。

## 2026-08-20 F-152 多角色听书 M1.5（IndexTTS 2.5 克隆音色引擎，收尾验证）

M1.5 此前只有「服务能跑」的既成事实（8001 上的 IndexTTS 2.5、手机端已成功合成过 LOTR 正文），本轮把它补成完整里程碑：

- 服务纳管：`tts-server/indextts/indextts_server.py` 作为仓库权威副本（与安装目录副本一致）。新增 `INDEX_VOICES_DIR` 克隆音色目录与 `voices/voices.json` 画像清单；`GET /voices` 在保持兼容的 `voices` 字符串数组之外新增 `voice_profiles`（id/label/language/gender/style）；`voice` 解析顺序为「绝对路径 → 克隆音色目录 → 安装目录 examples/ → 默认参考音频」。已重启实测：`/voices` 返回 16 个音色 + 16 条画像，登记后的 `voice_03.wav` 正确带出 `language=zh, gender=female, style=[calm]`。
- App 贯通：`ServerVoice`（新）承接服务器画像 → `ServerVoiceStore` 缓存（兼容旧的纯 id 缓存）→ `VoiceLibraryLoader` 与命名先验合并，克隆音色因此能参与 M3 的语言/性别硬过滤；`VoiceNaming` 识别 `clone_<角色>_<lang>_<m|f>` 并在匹配先验前去掉 `.wav/.mp3/...` 扩展名（IndexTTS 用文件名当音色 id）。
- 工具：`scripts/make_clone_voice.py`（从任意音频剪 3–10 秒参考音频、按约定命名、写入清单，**强制 `--consent`** 声明素材来源）；`scripts/tts_compare.py`（同批中英句子逐句实测两引擎，产出 `artifacts/tts-compare/report.md` 与可试听样音）。
- 中英实测（RTX 5070 Ti，两服务本机运行）：

| 引擎 | 语种 | 句数 | 平均每句 | 每字 |
|---|---|---|---|---|
| Kokoro（CPU） | en | 6 | 0.45 s | 0.010 s |
| Kokoro（CPU） | zh | 2 | 0.77 s | 0.029 s |
| IndexTTS 2.5（GPU 克隆） | en | 6 | 2.58 s | 0.057 s |
| IndexTTS 2.5（GPU 克隆） | zh | 4 | 3.17 s | 0.119 s |

  结论：性能上 Kokoro 快 5–6 倍且纯 CPU；IndexTTS 单句 1.5–4.7 秒可用于在线逐句合成，全书缓存对其保持禁用（App 按 `/v1/models` 自动隐藏按钮）。**人工试听后（2026-08-20）选定中英文默认引擎均为 IndexTTS 2.5**：英文 `first_3s_1.wav`、中文 `voice_03.wav`（样音 `indextts_first_3s_1_en_0.mp3` / `indextts_voice_03_zh_4.mp3`），Kokoro 转为快速/无 GPU 兜底。
- 落地该结论：自建服务器新增「英文音色 / 中文音色」两个可选字段（`serverEnVoice`/`serverZhVoice`，按句语言路由，留空回落通用音色），听书设置面板同步；这两个音色同时进入音色库并计入多角色分配的保留位。选定的两个音色已登记进 `tts-server/voices/voices.json`（带语言/性别/风格画像），其中 `first_3s_1.wav` 标注为「仅本机自用，参考音频取自商业音乐轨，发布前须替换」。
- 合规：核对 IndexTTS2 的《bilibili 模型使用许可协议》——免费、非独占、不可转让，仅在「月活 > 1 亿或上一自然年营收 > 1 亿人民币」时需另行申请商业许可（本项目无需）；须保留版权声明与许可副本、不得用其输出改进其他商用 AI 模型、禁止高风险场景、输出合规责任自负。多角色面板已常驻「仅用自备/授权素材 + AI 合成」提示；仓库不内置任何克隆参考音频（`tts-server/voices/` 音频已 gitignore），历史测试素材 `artifacts/first_3s.wav`（取自商业音乐轨）标记为不得用于发布。

验证情况：

- `testDebugUnitTest`：**259 个全部通过**（新增 4 个：`/voices` 画像解析、`clone_*` 命名先验、音频扩展名不影响先验、自建服务器中英音色路由；`CloudTtsSettingsTest` 往返用例同步覆盖新字段）。
- `lintDebug` / `assembleDebug`：通过。
- 服务侧实测：Kokoro 8000 与 IndexTTS 8001 均在本机启动成功，`scripts/tts_compare.py` 18 次合成全部 200 成功。
- 仍待人工：中英音质对比结论、以「角色专属克隆音色」跑一遍端到端听书（目前 `voices/` 只登记了 IndexTTS 自带示例，未放任何真人参考音频）。

## 2026-08-20 UI 评审整改第一批（外壳夜间模式 + 多角色面板打磨）

代码级 UI 评审后先修两处影响最大的：

- **外壳夜间模式**：`ThemeColors.kt` 由「一组固定颜色」重构为 `LinguaPalette` 日间/夜间双调色板 + `LocalLinguaPalette`，13 个语义色改为 `@Composable @ReadOnlyComposable` 取值——**所有界面调用点（`Ink`/`Paper`/`Accent`…）零改动**即可跟随主题；`colorSchemeFor(palette)` 派生 Material 配色，`ApplySystemBars` 让状态栏/导航栏颜色与图标亮度跟随（此前 themes.xml 把状态栏钉死浅色）。切换规则 `chromeIsDark`：正文阅读主题为「夜间」→ 外壳变暗，其他主题→日间，从未设置过→跟随系统深色；阅读设置里改主题会通过 `onAppearanceChanged` 即时切换外壳。夜间底色与正文夜间主题同为 `#171717`，消除了「暗色正文里打开目录/听书设置白屏闪光」的问题。强调色在夜间提亮为棕金，其上文字改用新的 `OnAccent`（日间白/夜间墨），替换了 9 处硬编码 `Color.White`；`ReviewCurvePicker` 的 Canvas 颜色改为在 composable 作用域先取值。
- **多角色面板整改**（我上一轮欠的债）：
  1. 新增「恢复自动」——行内解锁图标与选择弹层里的按钮，调用 `VoiceMapRepository.unlock` 后立刻重跑分配（此前锁了就回不到自动）；
  2. 音色选择改为**可搜索分组弹层** `VoicePickerDialog` + 纯逻辑 `VoicePicker`（推荐＝同语言且性别不冲突置顶，其余按「语言 · 性别」分组、角色语言优先；支持按 id/语言/性别/风格搜索），替代原来 `take(60)` 的裸下拉（Kokoro 有 100+ 音色）；
  3. 试听有状态：`VoiceAudition` 增加完成回调与 `isPlaying`，按钮在播放时变「停止」，选择弹层里每个音色都能单独试听；
  4. 锁定标记与下拉箭头改用 `Icons.Default.Lock/LockOpen/ArrowDropDown`（替换 emoji 与 `"▾"` 文本，顺带修掉 AI 中心与听书设置里另外 3 处 `▾`）；
  5. 角色列表不再在滚动 sheet 内嵌同方向滚动列。

验证情况：

- `testDebugUnitTest`：**269 个全部通过**（新增 10 个：`ThemeColorsTest` 5 个——跟随阅读主题/跟随系统/调色板选择/夜间对比度与底色对齐/配色方案派生；`VoicePickerTest` 5 个——搜索命中 id/语言/性别/风格、推荐组置顶与性别不冲突、分组标签与角色语言优先、搜索收窄与空结果、行文案）。
- `lintDebug`：通过，0 错误（40 条提示：1 信息 + 39 既有警告，本轮改动无新增告警）。
- `assembleDebug`：通过。
- 仍待人工：真机看夜间外壳观感（尤其 sheet/弹层与状态栏过渡）、音色选择弹层在 100+ 音色下的滚动手感。
- 评审里其余未做项（留待第二批）：全局 Snackbar 替代散落的状态文字、文案抽到 `strings.xml`、统一听书设置/术语表入口、`Typography` 定制、平板/横屏适配、部分图标补 `contentDescription`。

## 2026-08-20 UI 评审整改第二批（全局 Snackbar + 文案资源化）

- **全局 Snackbar**：新增 `AppSnackbar` + `LocalAppSnackbar`（同一时刻只显示一条，新提示顶掉旧的），`MainActivity` 用 `SnackbarHost` 承载并浮在书架/阅读页之上；`AppUiState.notice` 作为一次性提示通道（`clearNotice()` 弹过即清），导入成功提示「已导入《X》」、删除提示「已删除《X》」——此前这两个操作**完全没有反馈**。多角色面板的「已锁定音色 / 已恢复自动 / 试听失败」也从面板内一行状态字改为轻提示。
- **文案资源化（首批）**：`values/strings.xml` 建立命名规范（`common_* / notice_* / shelf_* / glossary_* / player_* / multivoice_*`），并新增 `values-en/strings.xml` —— **英文界面首次真正可用**，i18n 机制已被验证而不只是「留了路」。已迁移三个完整界面 + ViewModel 提示：`ListeningBar`（13）、`MultiVoiceSettings`（32）、`BookshelfScreen`（29）、导入/删除提示（2），并对「N 本书 / N 个生词 / N 个角色已分配」使用 `plurals` 正确处理英文单复数。
- **纯逻辑不再产出界面文案**：`MultiVoiceSupport.statusMessage(...)` 改为 `status(...)` 返回 `MultiVoiceStatus`（`NO_LIBRARY / NO_ROSTER / RULE_MODE / NO_MAP / SHARED_VOICES / READY` + 计数），界面映射到资源字符串；对应单测改为断言状态与计数而不是中文措辞。

验证情况：

- `testDebugUnitTest`：**269 个全部通过**（`MultiVoiceSupportTest` 改为断言状态枚举/计数）。
- `lintDebug`：通过，0 错误；资源侧无 `MissingTranslation`、无 `PluralsCandidate`（两处计数文案已改 `plurals`），本轮文件无新增告警。
- `assembleDebug`：通过。
- 迁移进度（剩余中文字面量，可作为下一批的度量）：`ListeningSettingsSheet` 105、`ReviewUi` 63、`ReaderScreen` 44、`AiDrawerSheet` 34、`VocabularyScreen` 24、`AppViewModel` 12、`LaunchPromptDialog` 2。
- **协作提示**：本轮**有意跳过 `ListeningSettingsSheet.kt`**（以及 `CloudTtsSettings/TtsSynthesizer/SherpaTtsSynthesizer/PiperVoice*`）——同一工作区另有会话正在这些文件上开发 Piper 音色导入功能，避免互相覆盖；这批提交只包含本会话改动的文件。
- 仍待人工：真机确认 Snackbar 位置不挡听书条（当前底部留白 72dp）、切到英文系统语言看 `values-en` 文案排版。

## 2026-08-20 本地 Piper 音色导入：评审问题修复（H1–H3 + M1–M4）

对「导入 Piper 英文音色」这批改动做代码评审后，直接修掉了 7 个问题：

- **H1 导入音色可能加载不了（asset 与文件路径混用）**：`SherpaTtsSynthesizer` 一直用 `OfflineTts(assets, config)` 构造，传 AssetManager 时 sherpa-onnx 会把路径当 asset 解析，而导入音色是 `filesDir` 下的绝对路径。新增 `PiperAssets` 收口加载逻辑：内置音色走 asset 构造、导入音色走 `OfflineTts(config = …)` 文件构造；且导入音色加载失败时**回退内置 Ryan**，不再让一个坏音色把整个 Piper 引擎（含中文）拖死。espeak-ng-data 的复制也从合成器搬到 `PiperAssets.ensureEspeakData`，与导入校验共用同一条路径。
- **H2 30–90 MB 模型在主线程复制（ANR）**：`PiperVoiceImporter.import` 改为 `suspend` + `withContext(Dispatchers.IO)`；设置页在协程里调用，期间按钮禁用并显示「正在导入并校验模型…」。
- **H3「离线引擎」里内嵌联网试听且绕过总开关**：样例试听与「下载」按钮现在受 `networkAiEnabled` 总开关控制（关闭时置灰并说明「已导入音色仍可离线使用」），文案明确标出「试听/下载需联网（HuggingFace / GitHub），导入后的朗读依然完全离线」；播放失败与打不开下载页从静默改为明确提示。
- **M1 失效记录会让英文朗读整体哑掉**：`PiperVoiceStore.imported()` 过滤掉模型/tokens 文件已不存在的记录，并把清理结果写回持久化数据。
- **M2 导入失败残留半个模型**：改为「先写 `.tmp` → 校验 → rename」的原子流程，失败时删临时文件、新建目录整体回滚。
- **M3 文件名直接当目录名（路径穿越）**：新增 `sanitizeId()`——取纯文件名、白名单字符（字母数字与 `_ . -`，CJK 作为合法字母保留）、去掉开头点、折叠连续点、限长 64、空名兜底。
- **M4 只校验扩展名**：新增体积区间（1 MB–400 MB）+ ONNX 结构探测（protobuf 首字段 `0x08` 或前 8 KB 出现 `onnx` 标识）+ **真加载校验**（`PiperAssets.canLoad`，通不过就不登记并提示「可能不是 Piper VITS 模型，或需要匹配的 tokens.txt」）。
- 顺手：`PiperVoiceStore` 与下载跳转改用项目统一的 KTX 写法（`edit {}`、`String.toUri()`），Piper 相关文件的 lint 告警清零。

验证情况：

- `testDebugUnitTest`：**274 个全部通过**（新增 `PiperVoiceImportTest` 5 个：id 净化与路径穿越、ONNX 结构探测、失效记录过滤与回写、未知 id 回退内置音色、官方目录 URL 形态）。
- `lintDebug`：通过，0 错误，Piper/Sherpa/听书设置三个文件 0 告警；`assembleDebug`：通过。
- **仍需真机验证（评审里的 H1 只能靠真机确认）**：导入一个 lessac/amy 模型后英文能出声、切回内置音色也正常、导入过程中界面不卡；以及联网关闭时试听/下载确实置灰。
- 未修的评审项（留给后续）：M5 多说话人模型固定 `sid = 0`（`libritts_r`/`l2arctic` 只能听到第一个说话人，需要 speaker 选择或明确标注）、M6 tokens 复用仅覆盖 en_US（现在靠加载校验兜底报错）、L1 该界面文案尚未资源化（`ListeningSettingsSheet` 仍有约 105 条中文字面量）、L5 可考虑放开 Piper 的多角色音色（有了多音色后 D2 的前提已不成立）。

## 2026-08-21 真机验证（OnePlus PKB110 / Android 16 / SDK 36 / arm64-v8a）

手机上已装的是另一台机器的调试签名，直接覆盖安装会要求卸载（清数据）。为不动用户的书与设置，
`app/build.gradle.kts` 增加了 `-PverifyBuild` 开关：加上它时 debug 包变成并存的
`com.linguareader.app.verify`（`versionNameSuffix=-verify`），验证完再卸载即可，正式包与数据全程未动。

**1. Piper 导入加载路径（评审 H1，只能真机验证）——新增仪器测试 4/4 通过**

`PiperVoiceLoadInstrumentedTest` 把内置 ryan 模型复制到 `filesDir`，再按「导入音色」的文件路径方式加载，
因此不需要下载任何外部模型就能覆盖同一条代码路径：

| 用例 | 耗时 | 结论 |
|---|---|---|
| `importedVoiceLoadsFromAbsoluteFilePaths` | 1.62 s | **H1 已修复**：文件路径构造能加载 63 MB 模型并真的合成出音频 |
| `bundledVoiceStillLoadsFromAssets` | 0.96 s | 重构未破坏内置音色的 asset 加载 |
| `garbageModelIsRejectedByLoadValidation` | 0.05 s | M4 的「真加载校验」确实拦下伪造 ONNX（0x08 头能骗过魔数探测） |
| `engineFallsBackToBundledVoiceWhenSelectionIsBroken` | 0.002 s | M1 过滤失效记录 + resolve 回退内置音色 |

**2. 外壳夜间模式（UI 第一批）——用截图平均亮度客观验证**

系统深色开关切换 + 重启应用，`adb exec-out screencap` 截图后用 ffmpeg `signalstats` 取平均亮度（0–255）：

| 区域 | 浅色 | 深色 | 结论 |
|---|---|---|---|
| 整屏 YAVG | 124.8 | **33.4** | 外壳整体变暗（改动前只有 `lightColorScheme`，此处会保持 ~125） |
| 状态栏（顶部 90px） | 105.6 | **32.9** | `ApplySystemBars` 生效 |
| 导航栏（底部 60px） | 104.6 | **31.2** | 同上 |

验证后已把系统深色恢复为 `auto`，截图已删除。

**3. Compose 界面回归（真机）**：`BookshelfSmokeTest` + `LaunchPromptUiTest` **5/5 通过** ——
说明调色板改成 `LocalLinguaPalette` 取值、以及三个界面的文案资源化都没有破坏 Compose 树与既有断言。

**4. 启动日志**：多次冷启动后 logcat 无本应用的 FATAL/异常；进程常驻正常。

**5. 文案本地化**：新增 `StringResourcesTest`（Robolectric，`@Config(qualifiers = "zh"/"en")`）断言中英资源与 `plurals`
实际取值（`导入`/`Import`、`1 book`/`2 books`、`1 character has a voice.`）——英文界面不是"留了路"而是可验证可用。

当前总计：**单元测试 277 个通过**（新增 `StringResourcesTest` 3 个）、真机仪器测试 9 个通过（Piper 4 + UI 5）、
`lintDebug` 0 错误、`assembleDebug` 通过。

仍需人工用眼确认（无法自动化）：夜间配色的观感与对比度、Snackbar 是否挡住听书条、英文界面长句在窄屏的换行；

## 2026-08-21 真机测试反馈的两处修复

用户在真机（PKB110 / Android 16）实测反馈两个问题，均已修复并在真机复验：

**1. 句高亮向下偏移约 3.8 行（分页模式）**

表现是「高亮框落在正在朗读那句下方几行的文字上」，看起来像选错了句子。加临时诊断日志后在真机取到确切数据：

| 量 | 实测值 |
|---|---|
| 文字位置 `rect.top` | 617.4 / 641.2 / 688.7 |
| 滚动容器 `rect.top` | 136.0（而 CSS/JS 都设的是 `top: 104px`） |
| 覆盖层 `rect.top` | 168.0 |
| 绝对定位子元素的实际原点 | **200.0** |
| 结果偏差 `deltaY` | **+64.0px** ≈ 2.7 行（行高 23.76px） |

根因：原实现按 `rect - scrollerRect + scrollLeft/scrollTop` 推算位置，隐含假设「包含块＝滚动容器的 padding box」。
在 WebView 的分页多列（`column-fill: auto` + 横向滚动）布局下该假设不成立——每层定位元素都多出 32px，
最终高亮框比文字低 64px。修法改为**自校准**：插入一个零尺寸探针，实测 `left:0;top:0` 究竟渲染在视口何处，
再按「文字视口坐标 − 原点视口坐标」摆放高亮框；与包含块、滚动偏移、多列分栏、WebView 视口怪癖都无关。
真机复验：连续三句 `deltaY` 均为 **0.0**、`deltaX` 0.0，随后移除诊断代码再跑一遍，无 JS 错误、高亮正常。

**2. AI 中心填完 DeepSeek Key 点「保存」没有任何反馈**

易被当成「点了没反应」。抽屉是独立窗口（全局 Snackbar 会被遮住），因此在按钮旁加行内 ✓ 确认，
3 秒后自动消失，并且说明保存后的实际效果：已就绪 / 未填 Key 走本地轻量语境 / 联网总开关关闭暂不生效 / AI 语境已关闭。
同时对 API Key、接口地址、模型做 `trim()`——粘贴时带的空格或换行以前会直接存进去，导致 401 却看不出原因。

验证：`testDebugUnitTest` **279 个通过**（新增 2 个脚本回归测试：探针定位不得回退成旧算法、诊断代码不得入库）；
真机用 uiautomator 驱动「书架 → 开书 → 听书 → 点正文选起点」全流程复验，日志无异常。

以及用**真实第三方 Piper 模型**（如 lessac/amy）走一遍完整导入流程（本轮用内置模型覆盖了加载路径，但没走文件选择器 UI）。

## 2026-08-21 F-128 中文译本对照（引擎实测 + 整合验证 + 真机验证）

对照引擎来自另一台机器的交付包（`对照模块-运行包.zip`：纯 Kotlin 引擎 + 可运行 `align-cli` + 魔戒中英测试书，
不含 UI、构建脚本与单测）。本轮做了「先量后并」：先在 JVM 上实测，再按实测结论整合。

**1. 引擎独立复现（`align-cli`，JDK 17 Temurin 17.0.20，无 Android SDK）**

| 指标 | 本机实测 | 交付包 README 自述 |
|---|---|---|
| 章节数 | 英 36 / 中 29 | 同 |
| 对齐句对 | **12,697** | 12,697（复现一致） |
| 平均置信度 | **0.79** | 0.79（复现一致） |
| 耗时 | 读英 199ms + 读中 74ms + 对齐 **29.0s** | 对齐 21.9s（机器差异） |
| 档案体积 | **15,025 KB**（pretty） | README 未提 |

**2. `TermLexiconLearner` 内存实测（决定砍掉它的依据）**

自写 JVM 探针（`artifacts/alignment-package/bench/Bench.java`，直接调 `core.jar` 里的实现）跑同一批 12,697 句对：

| 堆上限 | 结果 |
|---|---|
| 4 GB | 6.3s / **峰值堆 1,728 MB** / 产出 10,234 条 |
| 512 MB | **OutOfMemoryError**（`zhNGrams` 处） |
| 256 MB | **OutOfMemoryError**（`positionDistance` 处） |

累加器真实键数 **9,087,640** 个（英文词 × 中文 2–4gram，独立计数验证）。Android 单应用堆通常 128–512 MB，
故该路径在手机上不可行；且高频条目是 `about / after / again / all` 配 2 字 gram，属共现噪声而非专名译法。
结论：v1 不生成术语表（`terms` 恒空，字段与 `WordAligner.prefer` 入口保留）。

**3. 档案格式 v2 实测（同一本书、同一批句对、同等紧凑度对比）**

| 布局 | 体积 |
|---|---|
| v1（每条句对内联段落全文），pretty | 15,025 KB |
| v1，紧凑 | 14,206 KB |
| **v2（段落表 + 句对下标），紧凑** | **4,847 KB（34.1%）** |

去重后英文段落 3,469 / 中文 3,468，平均 3.66 句/段 —— 段落按句重复正是旧格式膨胀的原因。

**4. 整合后的构建与测试**

- `testDebugUnitTest`：**308 个通过、0 失败**（新增 27 个：对齐 5 / 格式 4 / 索引 11 / 词级 5 / `Book` 译本字段 2，
  其中含两条回归：两章正文相同时章节下标不得错配、旧元数据缺译本字段必须按「未配译本」读出）。
- `assembleDebug`：成功，`app-debug.apk` 386.1 MB。
- 同步交付包的两处共享文件改动后既有测试全绿：`SentenceSplitter` 新增 `spacedInitials`（保护 `J. R. R.`）、
  `ContextAnalyzer` token 命中改右端排他（`until`）。

**真机验证见下一节**（同日完成，含新增仪器测试与 UI 回归）。

## 2026-08-21 F-128 真机验证（OnePlus PKB110 / Android 16，设备 ZXJRNJVWY9C6BYDA）

**安装方式**：直接装 `com.linguareader.app` 失败——设备上已装的包是**另一台机器的调试签名**
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`）。按项目约定改用 `-PverifyBuild` 装并存的
`com.linguareader.app.verify`（`versionName=1.4.0-verify`，安装于 2026-08-21 05:21）。

> ⚠️ **事故记录**：验证结束后复查设备，原包 `com.linguareader.app` 已**不存在** —— `pm list packages -u`
> （含「已卸载但保留数据」）、`dumpsys package`、`/sdcard/Android/data/` 三处都查不到，只剩 `.verify`，
> 设备也只有一个用户（0/机主）。即原包连应用数据（书库、生词、复习进度）一起没了。
> 本次流程未执行任何 `pm uninstall`；怀疑是 AGP/UTP 在签名冲突时自行「卸载后重装」，但当时 gradle 输出
> 只保留了尾部，无法证实。**结论：面向真机的第一条 install / connectedAndroidTest 命令就必须带
> `-PverifyBuild`**，并先 `adb shell pm list packages -u` 看清设备上装了什么、签名来源是否可控。

**1. 新增 `TranslationAttachInstrumentedTest`（1 个，通过）** —— 在真机上跑完整链路：
构造中英两本小 EPUB → `BookImporter` 导入 → `TranslationMemoryRepository.attach` → 落盘 → 点词查对照 → `remove`。
断言（都是单测覆盖不到的部分：真实解压清洗、Android 上的 Jsoup 叶级抽取、真实文件 IO、只读 ECDICT SQLite）：

- 对齐句对非空，`sourceBookId` / `translationBookId` 正确，`terms` 为空（v1 约定）；
- 译本落在 `files/translations/<id>/`，且**不出现在** `files/books/<id>/`；
- 档案是 v2：有 `enParagraphs` 段落表、句对只有 `ep`/`zp` 下标、**没有**内联的 `enParagraph`；
- 点「1420」→ 句级命中 + `ANCHOR` 词级对齐，且 `start/endExclusive` 能在中文句里正好切出「1420」；
- 点普通词「left」→ 词级可能失败但**必须保住句级对照**；未知章节（7）→ 返回 null；
- `remove()` 把对齐档案与译本目录一起清掉，`hasMemory` 变 false。

**2. UI 回归（5 个，通过）**：`BookshelfSmokeTest`（2）+ `ReaderAcceptanceTest`（3）在真机跑通，
覆盖本轮改过的 `BookshelfScreen`（卡片多了「加译本」按钮与译本状态行）与 `ReaderScreen`（查词面板新增区块）。

**仍未验证（需要人手或真实图书）**

- 手工点「加译本」走 SAF 系统文件选择器的交互（自动化会被系统选择器挡住）。
- 「译本对照」区块在查词面板里的实际观感，以及真实图书（英文原版 vs 中译本）的命中率。
- 两处共享文件改动对**听书分句**与**既有点词落词**的主观影响：`ReaderAcceptanceTest` 通过说明没崩、
  章节与页码恢复正常，但分句/落词的差异要真人听、看才能判断。

## 2026-08-21 F-128 整本书真机实测 → 对齐性能修复（64×）

**真机整本书 attach（用户手工操作，魔戒首部曲英文原版 + 中譯本，各约 2.3/3.2 MB EPUB）**

- 结果：**成功**，但慢到不可用 —— 卡片「对齐中…」持续 **5 分钟以上**（约 6 分钟算完）。
  过程中 `top` 显示单线程 92–103% CPU、进程 RES 609 MB，说明是纯计算瓶颈而非卡死。
- 产物（`run-as com.linguareader.app.verify` 直接查）与设计完全一致：
  `files/translation-memory/67078feac33f4502f7e0.json` = **4,913,529 字节（4.69 MB）**，
  头部 `{"version":2,...,"sourceTitle":"The Fellowship of the Ring","translationTitle":"魔戒首部曲"...}`；
  译本落在 `files/translations/9fe0ec36567aba25f201/`，书架目录 `files/books/` 里只有英文原书。
  —— 这同时验证了 v2 格式在真实整本书上的体积（PC 上预测 4.8 MB）。

**根因与修复**（详见 `.agents/memory/known-pitfalls.md` §20）

代价函数 `pairCost` 在 O(n·m) 的 DP 内层重新扫描文本：每格重跑正则切分词数/字数，
每格 `Regex.findAll(英文片段)` + `zh.lowercase()` 拷贝**整章**文本 + 对每个锚点做一次 `contains`。
章节级 DP 的片段就是整章正文，因此最贵。修法：进 DP 前把每个片段预计算成
`Span(words, chars, anchors, latin)`，章节特征由段落特征聚合（不再拼接整章文本），
锚点命中改为在中文侧「拉丁/数字词集合」里哈希查表；dp 只保留最近三行 + 每格 1 字节走法矩阵。

| 指标 | 优化前 | 优化后 |
|---|---|---|
| PC 整本对齐 | 29,033 ms | **454 ms（64×）** |
| 句对数 | 12,697 | 12,696（锚点由子串匹配改为词表匹配的预期差异） |
| 平均置信度 | 0.79 | 0.79（不变） |
| 真机整本 attach | >5 分钟（约 6 分钟算完） | **约 10 秒**（用户真机复测确认） |

护栏：新增 `TranslationAlignerBenchmarkTest` —— 用 `artifacts/alignment-package/` 里的魔戒中英 EPUB
跑整本，断言句对 > 10,000 且耗时 < 3s（素材缺失自动跳过）。它的作用是下次有人再把文本扫描塞回
DP 内层时立刻失败。`testDebugUnitTest` **311 个通过**；对齐完成提示改为带「耗时 N 秒」。

## 2026-08-21 F-128 覆盖率兜底 + 交互整理（用户真机确认有效）

**点词命中率**（从查询侧量：模拟在整本魔戒每个英文段落的首句点词，共 4,069 个段落）

| | 命中 | 未命中 | 句级 | 段级 |
|---|---|---|---|---|
| 兜底前 | 3554/4069 = **87.3%** | 515 | 3435 | 119 |
| 兜底后 | 3955/4069 = **97.2%** | **114** | 3435 | 520 |

主因不是「DP 跳过段落」（真实只跳了 11 段），而是**2:1 合并**：合并后 `enParagraph` 存的是两段拼接文本，
用户点其中一段时段落文本对不上；标题、诗行这类不以句末标点结尾的段落连句子都切不出来，5 级降级全落空。

> ⚠️ **测量教训**：最初用「不同 `enParagraph` 文本数」当覆盖率，把 2:1 合并误算成漏掉，
> 得出「13.3% 段落被跳过」的**错误结论**。覆盖率必须从查询侧量（`TranslationMemoryIndex.lookup`），
> 基准测试现在输出 `[lookup]` 命中率。

两条兜底：① 合并段落对的每个成分段落补一条段级条目（置信度 ×0.85）；② DP 跳过的段落挂到下标最近的
已对齐段落对应的中文段落（基准 ×0.55 ÷ 距离），低于查询门槛 0.30 直接不落盘。
剩余 114 个未命中的大头是**未参与对齐的 7 个结构性章节**（封面/目录/附录，合计 67 段）。

**交互整理**：整句翻译按钮改为只在真配好提供方时渲染（原来是置灰按钮 + 一行「未配置」提示，
每次点词都是噪音）；命中译本时在同一位置给「整句对照 / 收起对照」开关，展开显示配对到的英文原句
与译文段落；配了译本但本句没对上时显示「本句未对上译本（所在段落或章节没有参与对齐）」。

**验证**：`testDebugUnitTest` **312 个通过**、`lintDebug` 无告警；
**用户在真机（PKB110 / Android 16）确认以上改动有效，整本书 attach 耗时约 10 秒**。

**仍未做**：手工 SAF 选择器交互的自动化、对齐 DP 在手机上的内存峰值、真实图书上词级高亮的主观准确度。

## 2026-08-21 F-110/F-111 底栏遮挡正文最后一行修复（真机验证）

- 现象：分页模式每章最后一行被半透明底栏盖住（PKB110 / Android 16，targetSdk 35 强制 edge-to-edge）。
- 根因（两层）：① 底栏含导航栏 inset，实测 78px > 写死的 70px CSS 预留；② 绝对定位滚动容器实际渲染位置比 style.top 整体下移约 32px（known-pitfalls #8 的容器位移，此前只修了高亮定位未修容器），正文实际底缘因此低于底栏上沿。
- 修复：① Compose 用 onSizeChanged 实测顶/底栏高度，经 ReaderController.applyChromeInsets → JS lrSetChromeInsets 动态注入替换写死的 104px/174px（bootstrap 带初值首帧即正确，值变化才重排且保页）；② updateMetrics 设完样式后实测 getBoundingClientRect() 自校准，把超出「视口高 − 底栏预留」的部分从列高里扣掉。
- 验证：testDebugUnitTest 通过（316 个，新增 4 条 chrome insets/自校准防回归）；真机并存包实测：最后一行完整显示在栏上方、无半透明残影，页数随列高变化正确重排（7/13 → 7/14）。lintDebug / assembleDebug 通过。
