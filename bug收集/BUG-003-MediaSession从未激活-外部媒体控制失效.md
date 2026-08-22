# BUG-003 MediaSession 从未激活，蓝牙/耳机/系统媒体控制全部失效

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackService.kt`

## 现象

听书时：

- 耳机线控/蓝牙耳机按键无法播放、暂停、切句；
- 系统媒体控制（Android Auto、媒体通知的控制器、部分手表控制）不响应；
- 锁屏媒体卡片上的播放控制无效。

## 根因

`android.media.session.MediaSession` 只有在 `isActive = true` 时才会把
`MediaSession.Callback` 分发给外部控制器。全文件只出现了三次赋值，且全部是 `false`：

```kotlin
// onCreate (第 108 行)
mediaSession = MediaSession(this, "LinguaReaderTts").apply {
    setCallback(object : MediaSession.Callback() {
        override fun onPlay() = resume()
        override fun onPause() = pause()
        override fun onSkipToNext() = nextSentence()
        override fun onSkipToPrevious() = previousSentence()
        override fun onStop() = stopPlayback()
    })
    isActive = false
}

// onDestroy (第 161 行) 与 stopPlayback (第 212 行)
mediaSession?.isActive = false
```

已 grep 全工程确认：**没有任何地方置 `true`**。因此第 101–107 行注册的全部回调永远不会
被派发（应用内按钮走的是 PendingIntent，不受影响，所以自测时不易发现）。

## 修复建议

在会话真正进入前台播放的位置激活会话，例如：

```kotlin
private fun ensureForeground() {
    ...
    ServiceCompat.startForeground(...)
    mediaSession?.isActive = true        // 新增
    isForeground = true
}

private fun resume() {
    ensureForeground()
    engine.resume()
}
```

同时在 `stopPlayback()` / `onDestroy()` 保持现有的 `isActive = false`。

## 回归验证

- 真机：开始听书后按耳机按键，验证可播放/暂停/上一句/下一句。
- 系统媒体控件（下拉通知的媒体卡片、Android Auto 模拟器）可控制听书。
