# BUG-015 云 TTS 下按「上一句」跨章节时无限等待旧章节预生成，播放可卡住数分钟

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：
  - `src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`（`previous()` 186–210 行）
  - `src/app/src/main/java/com/linguareader/app/tts/CloudTtsSynthesizer.kt`（`waitForFileOrSynthesize()` 239–260 行）

## 现象

使用云引擎（Azure / 火山 / OpenAI 兼容自建）时，在章节 N 开头（`sentenceIndex == 0`）
按「上一句」回到章节 N-1，目标句（N-1 的**最后一句**）长时间无声。若章节 N 的预生成
还剩大量句子（自建服务器慢、长章节），用户要等旧章节**全部预生成完**才听到声音，
等待可达数分钟；期间 UI 只显示「正在准备朗读…」，无任何进度或错误提示。

## 根因

1. `TtsPlaybackEngine.previous()` 的跨章分支（193–206 行）直接加载上一章后调用
   `speakCurrent()`，**绕过了 `loadAndSpeakCurrent()`**：

   ```kotlin
   } else if (chapterIndex > 0) {
       chapterIndex--
       ...
       scope.launch {
           val previous = chapterLoader(currentBook, targetChapter)
           ...
           chapter = previous
           sentenceIndex = (previous.sentenceCount - 1).coerceAtLeast(0)
           speakCurrent()          // ← 不经过 loadAndSpeakCurrent
       }
   }
   ```

   因此既不调用 `prepareChapter`（上一章没有被云预生成），也不重置引擎的
   `preparedChapterKey`，更不会取消/替换合成器里**旧章节仍在运行的 `prepareJob`**。

2. `CloudTtsSynthesizer.speak()` → `waitForFileOrSynthesize()`（239–260 行）：
   第一个等待循环有 25 秒截止；截止后进入第二个循环：

   ```kotlin
   // 251-256 行
   while (!file.exists() && prepareJob?.isActive == true && !shutdown && !chapterFailed) {
       delay(100)   // ← 无超时
   }
   ```

   此时 `prepareJob` 是**旧章节**的预生成任务（合成器只在 `prepareChapter` 里替换它，
   而本路径从未调用 `prepareChapter`），目标句文件永远不会由它产生。于是循环一直等到
   旧章节全部句子合成完毕，才走出循环走 259 行的兜底即时合成——上一章的第一句才开播。

3. `chapterFailed` 标志也属于旧章节的预生成，等待期间若旧章预生成失败，
   `waitForFileOrSynthesize` 直接返回 false → `onError` → 引擎静默跳到下一句，听感更差。

## 修复建议

1. `previous()` 跨章分支改为复用 `loadAndSpeakCurrent()` 的流程（加载后校验
   `chapterIndex != targetChapter || book !== currentBook`，由它负责
   `prepareChapter`、`preparedChapterKey` 更新与状态刷新），或在分支内显式调用
   `prepareChapter` 并重置 `preparedChapterKey`。
2. 给 `waitForFileOrSynthesize()` 第二个等待循环加超时兜底（例如再等 30 秒后直接
   `backend.synthesize`），保证任何情况下单句等待有上界。

## 回归验证

- 单测：云 FakeSynthesizer 的 `prepareChapter` 挂起不结束，引擎在 `sentenceIndex == 0`
  时调用 `previous()`，推进虚拟时间断言：不等待旧章节 prepare 完成即可 speak 目标句，
  且等待总时长有上界。
- 真机：Azure/自建模式下，章节中间开始播放后立刻回到章节开头按「上一句」，
  上一章末句应在数秒内出声。
