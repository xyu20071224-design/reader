# 11 条 bug 修复进度（本轮只做 bug）

> goal：`goal-d0396d2a`（修复 `当前待解决清单.md` §二A 的 11 条 bug；**不实现任何功能需求**）。
> 纪律：先复现 → 最小改 → 测试判据取 XML → 需真机则真机（未连则如实标阻塞）→ 失败回退。

## 进度表

| # | ID | 问题 | 状态 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | `Q1-t09` | 词级高亮与释义不匹配（BUG-039） | ✅ **本轮已修** | 提交 `524f454`。**先红后绿**：新用例 `worst positioned single character candidate is rejected by the threshold` 修前红（`confidence=0.69375` 放行位置最差候选）→ 修法：单字候选位置惩罚 0.35→0.70（多字不动）→ 修后绿。`WordAlignerTest` 8 例 0 失败；`:shared` 307 例 0 失败 |
| 2 | `Q2-c08` | 生词本非独立页面 | ✅ **已修**（真机待验） | 提交 `1efc523`：`if (!showVocabulary)` 包住书架专属动作 + `BackHandler` 回书架。`:app` 427 例 0 失败。⚠️ 手势类须真机，设备未连 → 待验 |
| 3 | `Q1-t03` | 一句译多句只显示第一句 | ✅ **已修（上游 V3）** | 句级 `allowMerge = true` + `mergeGate`（`TranslationAligner.kt:181-187`、`sentenceMergeAllowed:722`）；测试 `sentenceLevelMergeJoinsSplitTranslation` 绿；`TranslationAlignerTest` 18 例 0 失败 |
| 4 | `Q1-t01` | 段落对上但句子没对上 | ✅ **已缓解（上游 V4）** | 段级兜底改为**显示整段译文**并带粒度标识（`ReaderScreen.kt:1528-1537` 段级文案「未定位到句」；第四轮审查 1-4 亦确认）。金标准重放实测：句级句对=0 的段落现在返回 `PARAGRAPH/整段`，不再给空/半句 |
| 5 | `Q1-t04` | B 句返回 A 句意思 | ✅ **已缓解（上游 V4/V5）** | 低置信句对**不落盘**（`TranslationAligner.kt:189-204`，V4）+ 句对长度比硬门槛（V5 `fb323b1`）。金标准重放：**契约样本 66/66 保持、变化 0**；`bad/skip` 28 保持、未定位 1、垃圾 5 |
| 6 | `Q2-c04a` | 听书降级不显示级别 | ✅ **本就已实现** | `ad7cb05`：`TtsPlaybackState.degradedReason` → `ListeningBar.kt:97/128/348-351`；引擎 `:653/:717` 置位 |
| 7 | `Q1-t02` | 划线只画原型部分 | ✅ **本就已修** | `5c36f2c` CVC 双写（`ReaderScripts.kt:929-936`）+ `ReaderScriptsTest.kt:600-603` |
| 8 | `Q1-b02` | 全文 AI 翻译失败 | ⏳ **待一次失败证据** | 根因已由 `c1097ac`（09-01）修（注释 `AiBookTranslator.kt:132-135` 自述即本症状）。你 09-12 仍报失败 → 疑另一原因。**取证**：失败时 UI 文案 `notice_translation_ai_failed`「AI 译本生成失败：**%1$s**」的原因文本，或 logcat |
| 9 | `Q2-c05` | 词组加生词后听书条只显示单词 | 🚫 **阻塞：需截图** | 发音走一次性系统 TTS（`MainActivity.kt:141/263`），不经听书条、无通知 → 当前代码找不到该路径 |
| 10 | `Q2-c04c` | `her's` 断句 | 🚫 **阻塞：需复现原文** | 7 变体不复现（`SplitProbe.java`） |
| 11 | `Q1-t13` | 发音首音消失 | 🚫 **阻塞：需录音** | 无法只读复现 |

## 统计

- 本轮改代码并提交：**2**（`Q1-t09` `524f454`、`Q2-c08` `1efc523`）
- 核实为上游已修/已缓解：**5**（`Q1-t01`、`Q1-t03`、`Q1-t04`、`Q2-c04a`、`Q1-t02`）
- 待一次证据即可推进：**1**（`Q1-b02`）
- 阻塞待素材：**3**（`Q2-c05` 截图 / `Q2-c04c` 原文 / `Q1-t13` 录音）

## 未触碰功能需求（声明）

`Q2-c06`、`Q2-c07`、`Q1-t11`、`Q2-c04b`、`Q2-01`、`Q2-c02`、`Q2-c03` —— **一行未改**。
