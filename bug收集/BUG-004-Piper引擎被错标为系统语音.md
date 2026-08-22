# BUG-004 Piper 离线引擎在 UI 上被错标为「系统语音」

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackService.kt`

## 现象

用户在设置中选择「本地 Piper 语音」并开始听书后，听书条/通知上显示的引擎名称是
「系统语音」，而不是「本地 Piper 语音」。

## 根因

引擎就绪后，`TtsPlaybackEngine.handleSynthesizerReady`（第 538–548 行）会用
`engineLabelForSynthesizer` 的返回值覆盖启动时设置的正确标签。而 Service 注入的实现
（第 76–78 行）只识别云合成器：

```kotlin
engineLabelForSynthesizer = { s ->
    (s as? CloudTtsSynthesizer)?.engineLabel ?: "系统语音"
},
```

`SherpaTtsSynthesizer`（PIPER 模式的实现）不是 `CloudTtsSynthesizer`，于是落到兜底
`"系统语音"`。而 `startPlayback` 时通过 `engineLabelForSettings()`（第 112 行）设置的
`"本地 Piper 语音"` 被覆盖。

时间线：

1. 启动：`engineLabel = engineLabelForSettings()` →「本地 Piper 语音」；
2. Sherpa 后台加载模型成功 → `listener.onReady()`（`SherpaTtsSynthesizer.kt:53`）；
3. `handleSynthesizerReady` → `engineLabel = engineLabelForSynthesizer(sherpa)` →「系统语音」。

## 修复建议

```kotlin
engineLabelForSynthesizer = { s ->
    when (s) {
        is CloudTtsSynthesizer -> s.engineLabel
        is SherpaTtsSynthesizer -> "本地 Piper 语音"
        else -> "系统语音"
    }
},
```

建议同时给 `SherpaTtsSynthesizer` 加一个 `engineLabel` 属性（与云合成器对齐），避免
Service 反向依赖具体实现类。

## 回归验证

- 真机：选 Piper 引擎开始听书，听书条与通知显示「本地 Piper 语音」。
- 切回云引擎/系统引擎，验证标签分别正确。
