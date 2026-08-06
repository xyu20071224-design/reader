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

Using `artifacts/TheLanternLibrary.epub`:

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

- APK: `artifacts/LinguaReader-debug.apk`
- Version: 1.1.0 (version code 4)
- Size: approximately 53 MB
- SHA-256: `055456f84251acf55b2919d7327ce83e71f6425c59700d37fbd3f1c30422a8dc`（2026-08-06 F-122 修复后刷新）

The APK is debug-signed for direct installation and evaluation. A Play Store
release still requires a production signing key and release configuration.
