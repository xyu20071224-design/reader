# BUG-001 云 TTS 失败后回退系统引擎失效，无限重建云引擎（重复计费）

- 严重程度：🔴 严重
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackEngine.kt`
- 相关测试：`src/app/src/test/java/com/linguareader/app/tts/TtsPlaybackEngineTest.kt`（`cloudPrepareFailureFallsBackToSystemEngine`，该用例掩盖了本 bug）

## 现象

用户配置了云 TTS（Azure / 火山 / OpenAI 兼容）且密钥失效、网络不通或服务端出错时，
听书不会回退到系统语音，而是**不断重复创建云引擎、重复发起章节预合成请求**，
既不播放也停不下来，云端按量计费场景下会持续产生费用。

## 根因

`TtsPlaybackEngine` 注入了两个工厂：

```kotlin
private val synthesizerFactory: (TtsSynthesizerListener) -> TtsSynthesizer,          // 第 45 行
private val fallbackSynthesizerFactory: (TtsSynthesizerListener) -> TtsSynthesizer,  // 第 46 行
```

但全文件唯一的引擎创建点只有一处，且永远走主工厂：

```kotlin
private fun ensureSynthesizer(onReady: () -> Unit) {
    ...
    val created = synthesizerFactory(synthesizerListener)   // 第 378 行
    ...
}
```

云章节预合成失败后 `fallbackToSystemTts()` 把 `synthesizer` 置空并调用
`ensureSynthesizer { loadAndSpeakCurrent() }`（第 519–536 行），于是再次执行主工厂
`TtsSynthesizerFactory.create`。只要设置里仍是云引擎，它就返回**同一个失败路径的云引擎**：

1. 新云引擎 `isReady = backend.isConfigured()` 为 true → 立即 `onReady`；
2. `loadAndSpeakCurrent` 再次 `prepareChapter` → 再次失败；
3. `onComplete(false)` → 再次 `fallbackToSystemTts()` → 回到第 1 步。

**无限循环**。`fallbackSynthesizerFactory` 从头到尾没有被调用过，是死代码。

## 为什么现有单测没发现

`TtsPlaybackEngineTest.kt:280–287` 中，测试自己传入的 factory 在第二次调用时手动
切换成 FakeTtsSynthesizer：

```kotlin
val factory: (TtsSynthesizerListener) -> TtsSynthesizer = { l ->
    if (first) { first = false; FakeCloudTtsSynthesizer(l, prepareResult = false) ... }
    else FakeTtsSynthesizer(l) ...
}
```

即测试替引擎完成了"第二次走系统引擎"的动作，而生产代码没有这个行为。
测试第 301–303 行注释还承认了回退时首句被重复朗读（与 BUG-005 同源）。

## 修复建议

1. `fallbackToSystemTts()` 中直接使用回退工厂，例如：

   ```kotlin
   private fun fallbackToSystemTts() {
       val current = synthesizer ?: return
       if (isSystemEngine(current)) return
       synthesizer?.stop()
       synthesizer?.shutdown()
       synthesizer = fallbackSynthesizerFactory(synthesizerListener)   // 关键修复
       pendingReady.clear()
       ...
       if (synthesizer?.isReady == true) loadAndSpeakCurrent()
       else pendingReady += { loadAndSpeakCurrent() }
   }
   ```

2. 或者给 `ensureSynthesizer` 增加工厂参数，回退路径传入 `fallbackSynthesizerFactory`。
3. 修复后改写 `cloudPrepareFailureFallsBackToSystemEngine`：
   主工厂永远返回失败的云引擎，回退工厂返回 FakeTtsSynthesizer，
   断言云引擎只被创建一次、系统引擎被真实使用、`engineLabel` 为「系统语音（云 TTS 失败）」。
