# BUG-014 章节握手 deferred 清理不对称：等待状态被虚假占用，暂停期间页面跟随失效

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`

## 现象

在"等待章节加载握手"的窗口内（`lastLoadedChapter != chapterIndex`，已发出
`onChapterRequest` 但读者尚未回报）切换章节（目录选章）或暂停时：

- `chapterReadyDeferred` 字段**不被清理**，遗留一个无人等待的 deferred；
- 此后 `waitingForChapter()`（第 575 行）恒为 true，`onReaderPositionChanged`
  （第 345–360 行）被直接忽略——手动翻页的"跟读同步"在暂停期间失效；
- 遗留的 deferred 还可能被后续 `onReaderChapterLoaded`（第 316–324 行）以
  **新章节号**补完成（`loadedChapter == chapterIndex` 为新章节时），该补完成
  没有任何消费方，纯属悬挂状态。

恢复路径存在（下一次 `speakCurrent` 握手会用新 deferred 覆盖字段），因此该 bug
表现为"暂停/切章窗口内的状态失真"，而非永久卡死——与 BUG-002（引擎初始化失败
永久卡死）是不同的问题。

## 根因

`speakCurrent()`（第 456–494 行）的等待协程有 4 条退出路径，只有 3 条清理了字段：

```kotlin
scope.launch {
    val loaded = withTimeoutOrNull(chapterReadyTimeoutMs) { deferred.await() }
    if (chapterReadyDeferred !== deferred) return@launch   // ① 新握手替换 → 无需清理 ✓
    if (chapter !== currentChapter) return@launch          // ② 章节被切换 → 直接返回 ✗ 未清理
    chapterReadyDeferred = null                            // ③ 正常路径 ✓
    ...
}
```

路径 ② 的触发场景：`onReaderChapterSelected`（第 326–343 行）把 `chapter = null`，
但**没有**像 `stop()`（第 271 行）那样 `complete(-1)` 并置空 `chapterReadyDeferred`。
等待协程随后醒来发现 `chapter !== currentChapter` 即返回，字段保持悬挂。

```kotlin
fun onReaderChapterSelected(bookId: String, selectedChapter: Int) {
    if (book?.id != bookId) return
    chapterIndex = ...
    sentenceIndex = 0
    preparedChapterKey = null
    lastLoadedChapter = null
    chapter = null                     // ← 未处理 chapterReadyDeferred
    ...
}
```

## 修复建议

1. `onReaderChapterSelected` 里像 `stop()` 一样收尾旧握手：

   ```kotlin
   chapterReadyDeferred?.complete(-1)
   chapterReadyDeferred = null
   ```

2. 路径 ② 兜底清理（防御性）：`if (chapter !== currentChapter) { chapterReadyDeferred = null; return@launch }`。

## 回归验证

- 单测：用 TestDispatcher 构造"握手等待中 → 调用 `onReaderChapterSelected`"，
  断言 `waitingForChapter()`（经 `onReaderPositionChanged` 行为）不再被虚假占用；
- 真机：听书章节切换瞬间暂停，随后手动翻页，验证跟读同步恢复。
