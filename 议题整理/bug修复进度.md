# 11 条 bug 修复进度（本轮只做 bug）

> goal：`goal-d0396d2a`（修复 `当前待解决清单.md` §二A 的 11 条 bug；**不实现任何功能需求**）。
> 纪律：先复现 → 最小改 → 测试判据取 XML → 需真机则真机（未连则如实标阻塞）→ 失败回退。

## 一、逐条对照表（全 11 条）

| # | ID | 问题 | 状态 | 证据位置 | 未完成原因 |
| --- | --- | --- | --- | --- | --- |
| 1 | `Q1-t09` | 词级高亮与释义不匹配 | ✅ **已修** | 提交 `524f454`（`WordAligner.kt` 单字位置惩罚 0.35→0.70 + 先红后绿用例） | — |
| 2 | `Q2-c08` | 生词本非独立页面 | ✅ **已修**（真机待验） | 提交 `1efc523`（`BookshelfScreen.kt` `if (!showVocabulary)` + `BackHandler`） | 手势类须真机；设备未连 |
| 3 | `Q1-t03` | 一句译多句只显第一句 | ✅ 已修（上游 V3） | `TranslationAligner.kt:181-187/722`；用例 `sentenceLevelMergeJoinsSplitTranslation` | — |
| 4 | `Q1-t01` | 段落对上句子没对上 | ✅ 已缓解（上游 V4） | `TranslationMemoryIndex.kt:89-105`（兜底返回整段）+ `ReaderScreen.kt:1528-1537`（粒度标识） | — |
| 5 | `Q1-t04` | B 句返回 A 句意思 | ✅ 已缓解（上游 V4/V5） | `TranslationAligner.kt:189-204` 低置信不落盘；金标准重放契约样本 **66/66** | — |
| 6 | `Q2-c04a` | 听书降级不显示级别 | ✅ 本就已实现 | `ad7cb05`：`TtsPlaybackState.degradedReason` → `ListeningBar.kt:97/128/348-351` | — |
| 7 | `Q1-t02` | 划线只画原型部分 | ✅ 本就已修 | `5c36f2c` CVC 双写 `ReaderScripts.kt:929-936` + `ReaderScriptsTest.kt:600-603` | — |
| 8 | `Q1-b02` | 全文 AI 翻译失败 | ⏳ **待一次失败证据** | 根因 `c1097ac`（09-01）已修；`AiBookTranslator.kt:132-135` | 你 09-12 仍失败 → 疑另一原因。需 UI 上「AI 译本生成失败：**<原因>**」文本或 logcat |
| 9 | `Q2-c05` | 词组加生词后听书条只显示单词 | 🚫 **阻塞** | 发音走一次性系统 TTS（`MainActivity.kt:141/263`），不经听书条/通知 | 需你**截图**定位是哪个 UI |
| 10 | `Q2-c04c` | `her's` 断句 | 🚫 **阻塞** | 两次探针共 **14 个变体**全不复现（ASCII/排版撇号 U+2019、制表/换行、283 字长句走 300 上限路径） | 需**能复现的确切原文** |
| 11 | `Q1-t13` | 发音首音消失 | 🚫 **阻塞** | 见下方代码级发现 | 需**录音**确认；或授权我做加固后真机验 |

## 二、本轮新增只读发现

### `Q2-c04c`：探针扩到 14 变体，仍不可复现

```
IN : It was her’s.                          OUT(1): [It was her’s.]          ← U+2019
IN : her’s book                             OUT(1): [her’s book]
IN : He said her’s was gone. Then it rained. OUT(2): [He said her’s was gone., Then it rained.]
IN : her's<TAB>book                         OUT(1): [her's book]
IN : her's<LF>book                          OUT(1): [her's book]
IN : a her's b her's c                      OUT(1): [a her's b her's c]
IN : <283 字长句，含 her's，走 TTS 300 上限> OUT(1): 未切
```
切分入口唯一（`TtsChapter.kt:20`、`SpeakerRuleTagger.kt:116`、`SentenceSplitter` 本身）；`'` 只出现在 `closing` 集（句末标点后的收尾引号），**不是切分触发条件**。

### `Q1-t13`：代码级领先候选（尚未改，等确认或授权）

1. **单字发音路径无初始化门控**（最可能）：`MainActivity.kt:246-266` 的 `rememberEnglishSpeaker` 在构造后**立即** `engine = created`，而 `TextToSpeech` 的初始化在 `onInit` 回调里才完成（且 `language = Locale.US` 也只在回调里设）。用户首次点发音若落在初始化完成前，`engine?.speak(...)` 会被系统静默丢弃/截掉首音 —— 吻合「**有时**首个音消失」且多见于首次。
2. 全仓**无音频焦点处理**（`grep AudioFocus` 无命中）→ 候选①的「焦点请求与 start 竞态」在当前代码里不成立（不存在该请求）。
3. 云引擎路径是**每句新建 `MediaPlayer`**（`CloudTtsSynthesizer.kt:398-425`），无 `seekTo` → 候选③不成立；但 `playbackParams` 在 `start()` **之前**设置（`:416-424`），这在部分设备上会削掉开头。

**未改动原因**：本条属 TTS 播放（AGENTS 规定必须真机实测），且设备未连、成因未由录音确认 → 按纪律不臆断修改。

## 三、统计

- 改代码并提交：**2**（`Q1-t09`、`Q2-c08`）
- 核实上游已修/已缓解：**5**（`Q1-t01`、`Q1-t03`、`Q1-t04`、`Q2-c04a`、`Q1-t02`）
- 待一次证据：**1**（`Q1-b02`）
- 阻塞待素材/设备：**3**（`Q2-c05` 截图、`Q2-c04c` 原文、`Q1-t13` 录音）

## 四、未触碰功能需求（声明）

`Q2-c06`、`Q2-c07`、`Q1-t11`、`Q2-c04b`、`Q2-01`、`Q2-c02`、`Q2-c03` —— **一行未改**。
