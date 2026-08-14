# PLAN：TTS 事件流架构 + 词级高亮改造

> 借鉴 Readest（readest/readest，源码参考 `readest-src/`）TTS 设计中的两条思想：
>
> **3. 合成与消费解耦** —— 统一 mark/事件流模型 + 能力声明（`TTSCapabilities`），UI 与控制器按能力降级，绝不按引擎身份特判。
> **4. 高亮位置由引擎回传** —— 词级时间戳来自引擎事件（不自行切词对齐）；引擎不回传就降级句级高亮。

## 0. 现状盘点（已核实代码）

| 现有文件 | 职责 | 与目标的差距 |
|---|---|---|
| `TtsSynthesizer.kt` | 引擎接口：`speak(text, rate, utteranceId)` + `onStart/onDone/onError` 回调；SystemTts / Cloud / Piper 三个实现 | 事件只有"句子开始/结束"粒度；无 mark 概念、无词级事件、**无能力声明** |
| `CloudTtsSynthesizer.kt` | 整章预生成 MP3 缓存 + MediaPlayer 播放；本地变速不重合成 | 词级时间戳（Azure word boundary、火山 SSE 时间戳）被丢弃；`ChapterTtsPreparer` 用 `as?` 探测 |
| `TtsPlaybackService.kt` | 前台服务 + 句子队列推进 + MediaSession | 控制流按引擎身份特判（`as? CloudTtsSynthesizer`）；事件处理分散在 3 个 handler 里 |
| `TtsTextExtractor.kt` | `TtsChapter.sentenceLocation(i)` → `(blockIndex, offset, length)` DOM 精确定位 | ✅ 已是 Readest `TTSMark` 的等价物，直接复用 |
| `ReaderScripts.kt` / `ReaderScreen.kt` | JS overlay `lr-tts-overlay` 画句级高亮；`highlightCurrentTts` 按 block/offset/length 定位 | 只有一层高亮；定位能力已支持任意区间 → 词级只差"句子内偏移" |

## 1. 阶段一：事件模型 + 能力声明（对应 Readest 第 3 点）

### 1.1 数据模型（新文件 `tts/TtsEngineModel.kt`）

```kotlin
/** 引擎能力声明（对应 Readest 的 TTSCapabilities） */
data class TtsCapabilities(
    val wordBoundaries: Boolean,   // 能回传词级时间戳 → 词级高亮
    val chapterPreparer: Boolean,  // 能整章预生成（替代 as? ChapterTtsPreparer）
    val gapControl: Boolean,       // 句间停顿可调
    val liveRateChange: Boolean    // 变速不重合成/不重计费
)

/** 一句话的完整定位信息（对应 Readest 的 TTSMark；由 sentenceLocation 填充） */
data class TtsMark(
    val sentenceIndex: Int,
    val text: String,
    val blockIndex: Int,
    val offset: Int,      // 块内字符偏移
    val length: Int
)

/** 统一事件流（对应 Readest 的 TTSMessageEvent） */
sealed interface TtsEvent {
    data class Boundary(val mark: TtsMark) : TtsEvent                  // 该句开始发声 → 切换当前句高亮
    data class WordBoundary(val mark: TtsMark, val startChar: Int, val endChar: Int) : TtsEvent
    data class End(val mark: TtsMark) : TtsEvent                       // 该句读完 → 推进队列
    data class Error(val mark: TtsMark, val message: String?) : TtsEvent
}
```

- `TtsSynthesizer` 增加 `val capabilities: TtsCapabilities`；`TtsSynthesizerListener` 增加 `onWordBoundary(utteranceId, startChar, endChar)`（最小侵入，旧回调保留）。
- `TtsPlaybackService` 内部把回调统一折叠成 `handleTtsEvent(TtsEvent)` 单一入口，替代现在分散的 `handleUtteranceStart/Done/Error`——之后加词级事件不再改控制流。

### 1.2 去身份特判

- `synthesizer as? ChapterTtsPreparer` → 走 `capabilities.chapterPreparer`（prepare 入口）。
- `synthesizer as? CloudTtsSynthesizer` → `engineLabel` 上移为接口字段。
- 目标：服务里零 instanceof/`as?` 引擎判断（对齐 Readest 的铁律 "gate on capabilities, never on client identity"）。

### 1.3 为可测性抽出纯 Kotlin 状态机（本阶段的真正落地）

`TtsPlaybackService` 是 Android Service，无法低成本单测。把"句子推进 + 事件分发 + 高亮状态"抽成纯 Kotlin 类：

```kotlin
class TtsPlaybackEngine(
    private val synthesizer: TtsSynthesizer,
    private val chapters: (book, chapterIndex) -> TtsChapter,
    private val onState: (TtsPlaybackState) -> Unit
) { fun play/pause/next/previous/stop(); internal fun handleEvent(e: TtsEvent) }
```

Service 退化为壳（前台服务/MediaSession/通知照旧）。抽完之后即可用 **FakeTtsSynthesizer**（脚本化发射事件）做全路径单测——这正是 Readest `tts-fake-audio.ts` 的做法。

## 2. 阶段二：词级高亮（对应 Readest 第 4 点）

原则：**词偏移只来自引擎回传，绝不自造对齐；不回传就降级句级**。

### 2.1 系统引擎（`SystemTtsSynthesizer`）

- API 26+：`speak` 改传 `Spannable`（句子文本），`UtteranceProgressListener.onRangeStart(utteranceId, start, end)` 回调词范围。
- `onRangeStart` → `onWordBoundary` → 事件流 → `TtsPlaybackState` 增加 `wordStart/wordEnd`（句子内偏移）。
- DOM 映射：高亮区间 = `(blockIndex, mark.offset + wordStart, wordEnd - wordStart)`；`ReaderScripts` 增加 `highlightRange`，复用现有 overlay 定位代码。
- **能力探测 + 降级**：国产引擎（小米/讯飞/华为）对 `onRangeStart` 支持度不一。启动时用一句隐藏测试句试讲一次，收到 `onRangeStart` 才置 `wordBoundaries = true`；收不到自动降级句级高亮，并在设置里提供手动开关。
- 已知坑：CJK 无空格，回调粒度由引擎决定（按词/按字均可）——"引擎回传什么就用什么"，不做规范化对齐；偏移需与 `SentenceSplitter` 的空白规范化一致（现有约束）。

### 2.2 云引擎：Azure 先行

- Azure 合成响应已含 word boundary 事件（100ns tick + 词文本）。`AzureSpeechClient` 合成时收集 boundary 序列，随 MP3 一并存入缓存侧车文件（`.meta`，按句子）。
- 词字符定位：boundary 事件带词文本，在句子文本内按顺序单调 `indexOf` 定位 → 仍是"引擎回传什么就用什么"（文本来自引擎事件本身），不算自造对齐。
- 火山 SSE：若响应带时间戳则同法处理；否则该引擎 `wordBoundaries = false`。
- **Piper：明确不做词级**。Piper 不输出词时间戳，Silero 对齐属于"自造对齐"，与 Readest 对 Media Overlays 的决策一致——记为决策，不做。
- 缓存影响：词级事件只动高亮，不动音频文件；现有 `tts_cache` 结构无需变更。

### 2.3 渲染（`ReaderScreen.kt` + `ReaderScripts.kt`）

- 双层高亮：当前句弱高亮 + 当前词强高亮（Readest 同款：词级边界存在时抑制句级高亮的"整句跳动"，句级保留底色）。
- 翻页仍由 `Boundary`（句级 mark）驱动，词事件只动页内高亮——与 Readest 一致，避免词级事件把翻页逻辑打散。

## 3. 里程碑与估时

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M1 | `TtsEngineModel.kt` 事件模型 + 能力声明；服务去特判；`handleTtsEvent` 单一入口 | 1–2 天 |
| M2 | 抽出 `TtsPlaybackEngine` 纯 Kotlin 状态机 + FakeTtsSynthesizer 全路径单测（`src/test/`） | 1–2 天 |
| M3 | 系统引擎 `onRangeStart` 词级高亮 + 能力探测 + 降级 + 真机验证 | 1–2 天 |
| M4 | Azure word boundary 侧车 + 词级高亮（火山视 API 能力跟进） | 1–2 天 |

## 4. 验收与回归清单

- **单测**：状态机全路径（播放/暂停/跳句/章节切换/云失败回退系统引擎）由假引擎驱动。
- **真机矩阵**：Google TTS（支持 `onRangeStart`）、小米/讯飞/华为（预期部分不支持 → 验证自动降级与手动开关）。
- **回归**：整章/全书连续朗读、点击句子从此句起播、句级高亮不回归、云引擎失败回退、速率调节、后台播放 + MediaSession。

## 5. 明确不做（对齐 Readest 决策）

- 不自造音频-文本对齐（Piper 词级时间戳、任何本地对齐模型）。
- 不强制词级高亮可用：引擎能力不够就句级，UI 按 `capabilities` 降级。
- 不改云引擎音频缓存格式（词级只加高亮，不动文件）。
