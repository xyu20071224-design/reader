# BUG-005 云 TTS 合成等待窗口内暂停无效，音频照播且状态被翻回「播放中」

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：
  - `src/app/src/main/java/com/linguareader/app/tts/CloudTtsSynthesizer.kt`
  - `src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`

## 现象

云引擎下，如果用户在某一句**还没合成完**（首句等待章节预合成、或单句在线合成中）时按暂停：

1. 过了几秒，这句音频照样播出来；
2. 听书条与状态流从「已暂停」被翻回「播放中」，通知栏图标也显示播放。

## 根因

**其一：在途 speak 协程无法被 stop() 取消。**
`CloudTtsSynthesizer.speak()`（第 143–150 行）在协程中等待文件就绪后：

```kotlin
scope.launch {
    val ready = waitForFileOrSynthesize(file, text, voice)
    if (!ready || shutdown) { ...; return@launch }
    mainHandler.post { play(file, rate, utteranceId) }   // 第 149 行
}
```

暂停时 `stop()`（第 153–155 行）只 post 了 `releaseCurrentPlayer()`；此刻 `currentPlayer`
还是 null，无事可释放。等文件就绪后 `play()` 照常创建新 MediaPlayer 并播放——
`play()` 内部也没有检查任何"已停止"标志（`shutdown` 标志只在 shutdown() 时置位）。

**其二：引擎的 onStart 处理不检查播放状态。**
`TtsPlaybackEngine.handleUtteranceStart`（第 550–556 行）：

```kotlin
private fun handleUtteranceStart(utteranceId: String) {
    if (utteranceId == utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt)) {
        consecutiveErrors = 0
        updateState { it.copy(isPlaying = true) }   // 无 playing 判断
    }
}
```

对比 `handleUtteranceDone`（第 559 行）有 `|| !playing` 保护，onStart 没有。暂停后
utteranceId 的三要素（chapter/sentence/attempt）都没变，匹配成立 → 状态被翻回
`isPlaying = true`。UI 依赖 `_state.value.isPlaying`，于是显示"播放中"，而引擎内部
`playing` 字段仍是 false——内部状态与 UI 状态分叉。

## 修复建议

1. 引擎侧（防御性，最小修复）：

   ```kotlin
   private fun handleUtteranceStart(utteranceId: String) {
       if (!playing) return
       if (utteranceId == utteranceIdFor(chapterIndex, sentenceIndex, speakAttempt)) {
           consecutiveErrors = 0
           updateState { it.copy(isPlaying = true) }
       }
   }
   ```

   注意：这能防止状态翻转，但音频本身仍会播（见下），必须配合合成器侧修复。

2. 合成器侧：给 `CloudTtsSynthesizer` 加一个与 `shutdown` 同类的暂停/停止标志，
   `stop()` 置位、`speak()` 开头清位；`speak` 协程在 post `play()` 前检查标志：

   ```kotlin
   @Volatile private var stopped = false
   override fun speak(...) { stopped = false; ... }
   override fun stop() {
       stopped = true
       mainHandler.post { releaseCurrentPlayer() }
   }
   // speak 协程内：
   if (stopped) return@launch
   mainHandler.post { if (!stopped) play(file, rate, utteranceId) }
   ```

3. 若想进一步避免"暂停后恢复要重新等待合成"，可保留在途合成（只缓存不播放），
   恢复时直接播放缓存文件。

## 回归验证

- 单测：FakeCloud 合成器延迟完成期间调用 `engine.pause()`，推进虚拟时间，
  断言不再有 `onStart`/新播放发生，`state.isPlaying` 保持 false。
- 真机：云引擎 + 慢速自建服务器，刚切到新章节（首句预合成中）立即暂停，
  等 3–5 秒确认无声音、听书条保持暂停态。
