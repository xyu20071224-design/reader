# BUG-017 语音工作室 `stop_backend` 无属主校验杀进程，且 `taskkill` 缺 `/T` 残留 worker

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-voice-studio/studio.py`（`kill_port` 169–186 行、`stop_backend` 269–283 行）

## 现象

1. **误杀无关进程**：若后端未运行而端口被其他程序占用（例如其他软件恰好监听 8000/8001），
   用户点「停止」会 `taskkill /F` 杀掉那个无关进程；而真正登记在 `_children` 里的子进程
   （若有）只收到温和的 `terminate()`。
2. **worker 残留**：`taskkill /PID <pid> /F` 不带 `/T`，只杀父进程。IndexTTS 这类会
   派生 GPU worker / 子进程的后端，父进程死后 worker 仍存活——GPU 显存不释放、端口可能
   继续被占用，studio 上报「已停止」与事实不符。
3. 状态误报：`stop_backend` 在 `kill_port` 返回端口未释放（`ok=False`）时，
   仍可能对外返回 `"state": "stopped"`。

## 根因

`kill_port` 完全以「端口 → PID」为唯一依据，不做任何属主校验：

```python
def kill_port(port, wait=6.0):
    pid = port_pid(port)                     # 只查 netstat
    if pid:
        _run(["taskkill", "/PID", str(pid), "/F"], timeout=15)   # 无 /T、无属主校验
    ...
```

而 `stop_backend` 先按端口杀、再对登记句柄做温和终止，顺序和判定都不保证
「杀的是自己启动的进程」：

```python
ok = kill_port(b["port"])                    # 276 行
if child is not None and child.poll() is None:
    child.terminate()                        # 279 行
state = "stopped" if ok else backend_state(key)
```

## 修复建议

1. 优先按 `_children[key]` 句柄终止：Windows 下用 `taskkill /T /PID <child.pid> /F`
   （带进程树），或先 `terminate()` 超时后升级 `kill()`。
2. 端口 PID 仅作兜底，且在杀掉前校验其身份（命令行包含本项目的 server.py / 后端路径，
   或父进程为 studio），不匹配则只告警不误杀。
3. `ok=False` 时按 `backend_state(key)` 真实状态返回，不要强行返回 `stopped`。

## 回归验证

- 起一个占住 8000 端口的无关进程（如 `python -m http.server 8000`），点「停止」，
  断言无关进程存活、日志给出告警。
- 对会派生 worker 的后端执行 stop，断言进程树全部退出、端口释放。
