# BUG-025 Kokoro 模型懒加载单例的初始化检查与赋值未加锁

- 严重程度：🟡 轻微
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-server/server.py`（`get_kokoro` 99–110 行；`synthesize_mp3` 154–160 行）

## 现象

代码注释声称「进程内单例，合成用一把锁串行化」，但 `_lock` 只保护
`kokoro.create(...)` 合成段；`_kokoro is None` 的检查、模型构建和全局赋值
都在锁外。Flask 以 `threaded=True` 启动（`server.py:275`），`/voices` 与
`/v1/audio/speech` 均可并发调用 `get_kokoro()`。

当前 `main()` 会在 `app.run()` 之前预热模型，正常 `python server.py`
启动路径掩盖了该问题；但通过 WSGI/`flask run` 启动、测试代码直接调用
`app`，或未来改为延迟加载时，两个线程可能同时进入初始化分支：

```python
def get_kokoro():
    global _kokoro, _voices
    if _kokoro is None:                 # ← 无锁 check
        ...
        _kokoro = Kokoro(...)           # ← 无锁 init/assign
        _voices = set(_kokoro.get_voices())
    return _kokoro
```

结果可能是重复加载数百 MB 模型，或两个实例交错访问 espeak-ng 全局状态。

## 修复建议

使用独立的初始化锁做 double-check，或复用 `_lock`：

```python
_init_lock = threading.Lock()

def get_kokoro():
    global _kokoro, _voices
    if _kokoro is None:
        with _init_lock:
            if _kokoro is None:
                ...
                _kokoro = Kokoro(...)
                _voices = set(_kokoro.get_voices())
    return _kokoro
```

初始化后再把合成锁 `_lock` 用于 `create` 调用。

## 回归验证

- 单测：用 `threading.Barrier` 让两个线程同时首次调用 `get_kokoro()`，
  断言 `Kokoro.__init__` 只执行一次、两个线程拿到同一实例。
- 现有串行启动/合成测试保持通过。
