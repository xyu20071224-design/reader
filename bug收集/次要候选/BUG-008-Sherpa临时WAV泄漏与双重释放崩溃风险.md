# BUG-008 Sherpa 合成器临时 WAV 泄漏 + MediaPlayer 双重释放崩溃风险

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-16
- 涉及文件：`src/app/src/main/java/com/linguareader/app/tts/SherpaTtsSynthesizer.kt`

## 问题一：临时 WAV 文件泄漏

`play()` 中生成的临时文件只在三条路径删除：`onCompletion`、`onPrepared` 的过期分支、
`onError`（第 207–226 行）。但 `stop()`（第 84–91 行）：

```kotlin
override fun stop() {
    generation++
    runCatching {
        player?.stop()
        player?.release()
    }
    player = null
}
```

手动 stop 不会触发 `onCompletion`，正在播放（或已 prepared 尚未播放）的句子的 wav
文件不会被删除，`cacheDir` 里会不断累积 `sherpa-*.wav` 临时文件。长时间听书 + 频繁
暂停/切句会持续膨胀缓存目录。

修复：`stop()` 时记录当前 player 关联的文件并删除；或 `play()` 时把 file 挂在
player 标签上（`setOnPreparedListener` 闭包捕获），stop 路径统一清理。

## 问题二：MediaPlayer 双重释放的崩溃风险

`stop()` 已经 `player.release()` 并把 `player = null`，但 `prepareAsync` 在途时
`onPrepared` 可能仍在 release 之后回调，其过期分支（第 211–214 行）：

```kotlin
} else {
    it.release()      // 未包 runCatching；若已 release 会抛 IllegalStateException
    file.delete()
}
```

对已 release 的 MediaPlayer 再次调用 `release()` 会抛 `IllegalStateException`，
此回调运行在主线程，有崩溃风险。竞态窗口较小（本地小文件 prepare 很快），但存在。

修复：给过期分支加 `runCatching`；并在 `stop()` 里先
`player?.setOnPreparedListener(null)` 再 release，彻底断开回调。

## 回归验证

- 长时间听书并频繁暂停/切句，观察 `cacheDir` 下 `sherpa-*.wav` 数量是否稳定。
- 高强度快速切句 + 暂停压测，观察是否有 MediaPlayer 相关崩溃日志。
