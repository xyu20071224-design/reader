# BUG-026 试听音频的 blob URL 未在停止/错误/重听中断时释放

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-voice-studio/index.html`（`playPreview` 704–719 行；`replay` 809–829 行）

## 现象

每次试听/重听都会调用 `URL.createObjectURL(blob)` 创建新的 blob URL。
只有部分路径会 `revokeObjectURL`：

```javascript
// 卡片试听：仅自然播放结束才释放
a.onended = function () { resetBtn(); URL.revokeObjectURL(url); };
a.onerror = function () { resetBtn(); toast("音频播放失败"); };  // ← 不 revoke
a.play().catch(function () { resetBtn(); ... });                  // ← 不 revoke

// 历史重听：仅自然结束才释放
a.onended = function () { URL.revokeObjectURL(url); };
```

而 `stopPlayback()` 只 `pause()` 并置空 `playingAudio`，不会 revoke：

```javascript
function stopPlayback() {
  if (playingAudio) { try { playingAudio.pause(); } catch (e) {} playingAudio = null; }
  resetBtn();
}
```

停止当前试听、切到下一段试听、随机试听、历史重听中断、`onerror` 或
`play()` 被拦截等路径都会泄漏一个 blob URL；长时间频繁试听后浏览器内存
占用持续上升。此外历史重听结束后 `playingAudio` 没有置空，播放状态残留。

## 修复建议

1. 增加全局 `currentAudioUrl`，创建新音频前先 revoke 旧 URL。
2. `stopPlayback()` 统一 revoke 当前 URL。
3. `onerror` 与 `play().catch` 中同样 revoke。
4. `replay` 的 `onended` 中同时 `playingAudio = null`。

## 回归验证

- 代码审查/自动化：任一音频元素结束或 `stopPlayback()` 后，对应 blob URL
  必须且只能 revoke 一次。
- 手工验证：连续试听多段并中途打断，浏览器任务管理器内存不持续线性增长。
