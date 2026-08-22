# BUG-018 TTS 服务与语音工作室绑定 0.0.0.0 且控制面/试听代理无鉴权

- 严重程度：🟠 中等（安全）
- 状态：已修复
- 修复日期：2026-08-19
- 发现日期：2026-08-17
- 涉及文件：
  - `tts-server/server.py`（51 行 `HOST = os.environ.get("HOST", "0.0.0.0")`）
  - `tts-voice-studio/studio.py`（42 行 `HOST = "0.0.0.0"`；`handle_preview` 488–535 行、
    `handle_control` 538–563 行）

## 现象

服务与工作室默认监听所有网卡，且所有 `/api/*` 接口**无任何鉴权、token 或 Origin
校验**。同一 Wi-Fi 下任意设备都可以：

1. `POST http://<主机IP>:8002/api/control` 反复启动/停止本机 TTS 后端子进程
   （`engine=all` 可一次控制全部后端）；
2. `POST /api/preview` 代理任意文本（≤2000 字符）到后端合成，占用本机 CPU/GPU；
3. 按 `tts-voice-studio/README.md`（36、58 行）说明，IndexTTS 的 `voice` 参数支持
   WAV **绝对路径**——经无鉴权代理转发后可被用于探测/读取本机文件路径；
4. `tts-server` 的 `/v1/audio/speech`（200–219 行）同样无输入长度上限与速率限制，
   局域网内可提交超长文本占用 CPU 且阻塞全局合成锁。

## 根因

两个服务都是面向本地开发的内网工具，但默认绑定 `0.0.0.0`（方便手机连电脑），
同时把「启动进程」和「合成音频」这类高危操作暴露为无鉴权 HTTP。

## 修复建议

1. 默认绑定 `127.0.0.1`，需要局域网访问时显式设置并提示风险；
2. 给控制面（studio `/api/control`、`/api/preview`）加共享密钥/随机 token
   （如首次启动打印 token，前端请求头携带），至少校验 `Origin`；
3. `handle_preview` 对 `voice` 做白名单校验（拒绝路径分隔符、绝对路径、扩展名）；
4. `tts-server /v1/audio/speech` 增加输入长度上限与简单速率限制。

## 回归验证

- 另一台设备直接 POST `/api/control`（不带 token）应被拒绝（修复后）；
- `voice` 传 `C:/some/file.wav` 或 `../../etc/passwd` 应被拒绝而非转发。
