# BUG-024 自建 TTS 服务未校验 `voice` 类型，非字符串请求返回 500 而非 400

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-server/server.py`（`audio_speech` 200–216 行；`resolve_voice_and_lang` 136–141 行）

## 现象

`POST /v1/audio/speech` 请求体若包含非字符串 `voice`，例如：

```json
{"input": "hello", "voice": 123}
```

服务端抛 `AttributeError: 'int' object has no attribute 'strip'`，返回 Flask
默认 500 页面，而不是结构化 400 JSON。畸形客户端请求会污染日志并让 App
端显示「HTTP 500」而不是参数错误。

## 根因

`audio_speech` 把原始 JSON 值直接传入：

```python
# server.py:210
voice, lang = resolve_voice_and_lang(text, data.get("voice"))
```

而 `resolve_voice_and_lang` 假定参数是字符串：

```python
# server.py:138
req = (requested_voice or "").strip()
```

该调用位于 `try/except` 合成捕获之前，异常不会被转成 JSON 400/500。

## 修复建议

在路由入口校验类型：

```python
raw_voice = data.get("voice")
if raw_voice is not None and not isinstance(raw_voice, str):
    return jsonify({"error": "voice 必须是字符串"}), 400
```

`input` 也建议校验为字符串，而不是 `str(...)` 强制转换任意对象。

## 回归验证

- 分别 POST `"voice": 123`、`"voice": ["zf_001"]`，断言返回 400 且
  `Content-Type` 为 JSON。
- 正常 `"voice": "zf_001"` 与缺省 voice 行为保持不变。
