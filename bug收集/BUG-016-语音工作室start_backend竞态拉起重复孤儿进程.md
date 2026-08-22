# BUG-016 语音工作室 `start_backend` 检查与启动无锁，并发请求可拉起重复/孤儿后端进程

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-voice-studio/studio.py`（`start_backend` 234–266 行）

## 现象

两个并发的 `POST /api/control {"engine":"kokoro","action":"start"}`（例如前端轮询重试、
双窗口、或局域网内两台设备同时点启动）可能**先后拉起两个后端进程**：一个占住端口正常
服务，另一个绑定端口失败退出；或者极端时序下双双进入模型加载，浪费数秒到数十秒
CPU/GPU。后写者覆盖 `_children[key]`，第一个进程的句柄丢失，成为 studio 无法跟踪的
孤儿（只能靠端口兜底杀）。

README 声称的「并发防抖」只存在于 `index.html` 前端 JS，服务端没有等价保护。

## 根因

`start_backend` 是典型的 check-then-act，且检查与登记不在同一临界区：

```python
def start_backend(key):
    b = BACKENDS[key]
    if is_listening(b["port"]):          # 237 行：检查 1
        return {...}
    child = _children.get(key)           # 239 行：检查 2（锁外读取）
    if child is not None and child.poll() is None:
        return {...}
    ...
    proc = _launch_with_flags(...)       # 253 行：Popen
    ...
    with _children_lock:                 # 262 行：直到这里才加锁
        _children[key] = proc
```

两个线程都通过 237/239 的检查后，各自 Popen；`_children` 只保留最后写入的句柄。
`_children_lock` 存在但没有覆盖检查段，起不到幂等作用。

## 修复建议

把整个「检查 → Popen → 登记」放进同一把锁（或先在 `_children[key]` 写入
"starting" 占位再检查），例如：

```python
with _start_lock:
    if is_listening(b["port"]) or (_children.get(key) and _children[key].poll() is None):
        return {...}
    proc = _launch_with_flags(...)
    _children[key] = proc
```

注意 Popen 本身不耗时（模型加载在子进程内），锁内执行无阻塞问题。

## 回归验证

- 用两个线程同时调用 `start_backend("kokoro")`（或对 8002 端口并发发两个 start 请求），
  断言只产生一个后端进程、`_children["kokoro"]` 句柄与存活进程一致。
