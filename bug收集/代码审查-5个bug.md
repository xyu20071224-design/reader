# 代码审查报告：发现的 5 个 Bug

审查时间：2026-08（v1.4.0 工作树）
审查范围：AI 翻译链路（ai/）、TTS 播放链路（tts/），并抽查数据层与阅读器。

---

## Bug 1：Azure 动态词典标记的源文本未做 XML 转义

- **文件**：`src/app/src/main/java/com/linguareader/app/ai/AzureSentenceTranslator.kt`
- **位置**：第 78–91 行（`markupSentence`）
- **严重程度**：中

```kotlin
builder.replace(
    match.start,
    match.endExclusive,
    "<mstrans:dictionary translation=\"$escaped\">${match.text}</mstrans:dictionary>"
)
```

只有 `translation` 属性做了 `xmlEscape`，而 `match.text`（句子原文片段）被原样塞进标记内。

**失败场景**：当句子包含 `&`、`<`、`>`（英文书中很常见，如 "Tom & Jerry"、"R&D"）时，会生成非法标记，Azure 会拒绝请求或翻译错乱。现有单测 `AzureSentenceTranslatorTest` 只覆盖了干净文本，未覆盖特殊字符。

**修复方向**：对 `match.text` 也做 `xmlEscape`（与 `translation` 一致）。

---

## Bug 2：`handleUtteranceStart` 缺少 `playing` 守卫，暂停后状态卡在"播放中"

- **文件**：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`
- **位置**：第 550–556 行
- **严重程度**：中

```kotlin
private fun handleUtteranceStart(utteranceId: String) {
    if (utteranceId == utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt)) {
        consecutiveErrors = 0
        updateState { it.copy(isPlaying = true) }   // ← 无条件置 true
    }
}
```

`handleUtteranceDone` / `handleUtteranceError` 都检查了 `!playing`，唯独 `onStart` 没有。

**失败场景**：用户暂停的瞬间引擎回调 `onStart`（云 TTS 的 `MediaPlayer` 异步启动让这个窗口很大），引擎内部 `playing == false` 但 UI 状态 `isPlaying == true`——听书条/通知卡在"播放中"，实际无声，且暂停后句子仍会播完。

**修复方向**：加 `|| !playing` 守卫，与 Done/Error 处理保持一致。

---

## Bug 3：云 TTS 暂停后音频仍会响起（`stop()` 与 `play()` 的投递竞态）

- **文件**：`src/app/src/main/java/com/linguareader/app/tts/CloudTtsSynthesizer.kt`
- **位置**：`stop()` 第 153–155 行；`speak()` 第 149 行；`play()` 第 262–302 行；`releasePlayer` 第 308–317 行
- **严重程度**：中

`speak()` 在 IO 协程里等文件就绪后 `mainHandler.post { play(...) }`；`stop()` 同样 `mainHandler.post { releaseCurrentPlayer() }`。

**失败场景**：当暂停先于文件就绪发生时，主线程消息队列顺序是 `[release, play]`——`play` 在用户已暂停后启动 `MediaPlayer`，音频照样响起；配合 Bug 2 的 `onStart` 无守卫，UI 还会跳回"播放中"。另外 `releasePlayer` 把 completion 监听置空，引擎收不到 `onDone`，队列停在原句不再推进。

**修复方向**：`play()` 执行前检查引擎会话代号（generation/代次）是否仍有效；`stop()` 使已投递的 `play` 失效（如递增代次后在 `play` 内校验）。

---

## Bug 4：`loadAndSpeakCurrent` 协程恢复时未校验书/章是否已切换（脏章节竞态）

- **文件**：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`
- **位置**：第 384–402 行
- **严重程度**：中

```kotlin
scope.launch {
    val loadedChapter = chapterLoader(currentBook, chapterIndex)  // IO
    chapter = loadedChapter          // ← 恢复时不检查 book/chapterIndex 是否变了
    ...
    speakCurrent()
}
```

**失败场景**：章节加载是 IO 操作；恢复时若用户已切章/换书，旧协程仍会把过期章节赋给 `chapter` 并 `speakCurrent()`，导致新会话里朗读上一本书/上一章的句子。

**修复方向**：同文件 `previous()`（第 199–206 行）已有守卫 `if (chapterIndex != targetChapter || book !== currentBook) return@launch`，在 `loadAndSpeakCurrent` 中补同样的校验。

---

## Bug 5：DeepSeek 返回 JSON null 时 `optString` 得到字符串 "null" 并直接展示

- **文件**：`src/app/src/main/java/com/linguareader/app/ai/DeepSeekTranslator.kt`
- **位置**：第 45–64 行（`translate`）、第 66–93 行（`translateSentence`）
- **严重程度**：低–中

org.json 的 `optString` 对 JSON `null` 返回字面量 `"null"`。提示词只写"无则省略"，但模型经常不省略而是给 `null`。

**失败场景**：

- `answer.optString("meaning").trim()` → `"null"`，`isBlank()` 判不空 → 语境释义直接显示 "null"；
- `explanation`、`phrase` 同理，`.takeIf { it.isNotBlank() }` 拦不住 "null"。

**修复方向**：统一加 `.takeUnless { it == "null" }` 或封装一个 `optStringOrBlank` 工具函数。

---

## 其他发现（候补，未计入 5 个）

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| A | `isHan` 的 `0x20000..0x2FA1F` 区间是死代码 | `AzureTtsBackend.kt:36-42`、`SherpaTtsSynthesizer.kt:147-154`、`SystemTtsSynthesizer.kt:145-151` | Kotlin `Char` 是 16 位码元，永远不可能 ≥ 0x20000；CJK 扩展 B+ 汉字被当作英文路由 |
| B | 已掌握单词永不出复习队列 | `VocabularyRepository.kt:44-60`（`ReviewScheduler.reviewed`） | `reviewLevel` 到 7（"已掌握"）后仍调度 `now + 30天`，复习后因 `coerceAtMost` 停在 7 再调度 30 天，无限循环；同时被统计为"已掌握"又出现在到期队列 |
| C | `generation` / `player` 跨线程非 volatile 字段 | `SherpaTtsSynthesizer.kt:39,64,85,199` | IO 协程读 `generation`、主线程写，无 happens-before 保证（对比 `ready` 是 `@Volatile`） |
| D | `stop()` 不删除临时 WAV 文件 | `SherpaTtsSynthesizer.kt:84-91` | 临时文件只在 MediaPlayer 监听器里删除，`stop()` 释放播放器后监听器不再触发，`sherpa-*.wav` 泄漏在 cacheDir |
| E | Azure SSML `xml:lang` 硬编码 `en-US` | `AzureSpeechClient.kt:85` | 中文句走中文音色却声明英文语言标签，可能被拒或发音错误 |

---

## 附：审查经过

- 通读 `ai/` 全部 10 个文件（DeepSeek/Azure/本地术语表/设置存储/术语库/语境仓库）；
- 通读 `tts/` 核心链路（引擎状态机、前台服务、系统/云/Piper 合成器、句子切分、文本抽取、火山/Azure/OpenAI 兼容后端）；
- 抽查数据层（词汇仓库、复习调度、词典仓库、导入器）与阅读器 JS 桥（`ReaderScripts.kt`、`EpubPage.kt`、`ReaderScreen.kt`）。
