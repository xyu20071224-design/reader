# BUG-023 语音工作室 `/api/voices` 返回写死的局域网 IP，换网络后手机连不上

- 严重程度：🟠 中等
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：`tts-voice-studio/studio.py`（`LAN_IP` 第 47 行；`build_voices_payload` 442–443 行；`detect_lan_ip` 604–616 行）

## 现象

前端 `load()` 每次轮询都用 `/api/voices` 返回的 `lan_ip` 覆盖页面显示的
服务器地址（`index.html:880–884`），并用于「复制配置」。但服务端返回的
始终是顶部写死的 `LAN_IP = "192.168.1.9"`。

电脑 Wi-Fi 变化（例如从 192.168.1.9 变为 192.168.1.11）后，页面仍显示旧
地址，复制给 App 的也是旧地址，手机无法连接。启动 banner 却会打印正确的
探测结果，形成「页面地址 ≠ 真实地址」的矛盾。

## 根因

代码已经实现了 `detect_lan_ip()`，但只被 `banner()` 用于打印，没有写入
`build_voices_payload()`：

```python
# studio.py:442-443
return {
    "lan_ip": LAN_IP,          # ← 写死值
    ...
}
```

```python
# studio.py:727（banner 中的正确探测）
print("  局域网访问: http://%s:%d  (探测: %s)" % (LAN_IP, PORT, detect_lan_ip()))
```

## 修复建议

```python
return {
    "lan_ip": detect_lan_ip(),   # 失败时该函数会回退到 LAN_IP
    ...
}
```

## 回归验证

- 修改 `LAN_IP` 或 mock `detect_lan_ip` 返回另一个地址，调用
  `build_voices_payload()`，断言顶层 `lan_ip` 与探测结果一致。
- 页面轮询后显示的 `http://<IP>:8000/8001` 应与启动 banner 的探测结果一致。
