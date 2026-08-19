# BUG-002 TTS 引擎初始化失败后播放永久卡死，无法恢复

- 严重程度：🔴 严重
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`

## 现象

当 TTS 引擎初始化失败时（例如：

- Piper/sherpa-onnx 模型加载抛异常（`SherpaTtsSynthesizer.kt:54–56` → `onInitFailed(-1)`）；
- 系统 TTS 不可用，`TextToSpeech` 构造函数回调非 SUCCESS（`SystemTtsSynthesizer` → `onInitFailed(status)`）；

）听书只显示"已暂停"。之后无论怎么点播放、或停止后重新开始听书，都**永远无法再出声**，
只能重启 App。

## 根因

引擎对初始化失败的响应只有暂停（第 85 行）：

```kotlin
override fun onInitFailed(status: Int) { scope.launch { pause() } }
```

但失败的 synthesizer 实例仍然留在 `synthesizer` 字段里。用户再点播放：

```kotlin
fun resume() {
    ...
    ensureSynthesizer { loadAndSpeakCurrent() }   // 第 166 行
}

private fun ensureSynthesizer(onReady: () -> Unit) {
    val existing = synthesizer
    if (existing != null) {
        if (existing.isReady) onReady() else pendingReady += onReady   // 第 375 行
        return
    }
    ...
}
```

坏引擎的 `isReady` 永远是 false，回调被永久停在 `pendingReady`，而这个引擎再也不会触发
`onReady()`。停止后重新 `startPlayback`（第 99–127 行）也走同一个 `ensureSynthesizer`，
复用同一个坏引擎，同样卡死。唯一能重建引擎的路径是 `reconfigure()`（需改设置触发）或
服务销毁。

## 修复建议

1. 初始化失败且当前不是系统引擎时，直接走回退逻辑（与 BUG-001 修复联动）：

   ```kotlin
   override fun onInitFailed(status: Int) {
       scope.launch {
           val current = synthesizer
           if (current != null && !isSystemEngine(current)) {
               fallbackToSystemTts()
           } else {
               // 系统引擎也失败：释放并置空，避免 pendingReady 永远挂起
               synthesizer?.shutdown()
               synthesizer = null
               pendingReady.clear()
               pause()
           }
       }
   }
   ```

2. 至少在 `startPlayback`/`resume` 时判断 `!isReady` 且 `pendingReady` 长时间未消化则重建引擎。
3. 建议在 UI 上把 init 失败暴露给用户（状态里已有 `engineLabel`，可加错误态），而不是静默暂停。

## 回归验证

- 单测：构造一个 `isReady = false` 且从不回调 `onReady` 的 Fake，触发 `onInitFailed` 后
  调用 `resume()`，断言不会永远停在 pending 状态（应回退或释放重建）。
- 真机：禁用系统 TTS 引擎（或删除 Piper 模型文件）后开始听书，再点播放，验证可恢复或明确报错。
