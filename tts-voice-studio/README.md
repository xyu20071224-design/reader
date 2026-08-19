# TTS 音色试听台 (TTS Voice Studio)

> 一个**独立、自包含**的本地插件：一个网页界面 + 一个纯标准库代理服务，用来**启动/停止、浏览、试听**两套本地 TTS 服务，并一键复制要填进「听书 App」的配置。

**零第三方依赖** —— 服务端只用 Python 标准库（`http.server`、`urllib.request`、`subprocess` 等），前端是单个 `index.html`（原生 JS，无构建步骤、无框架）。

---

## 它能做什么

| 能力 | 说明 |
|------|------|
| 🚀 启动 / 停止后端 | 各后端「一键启动/停止」，以及顶部「全部启动 / 全部停止」；脱离本插件进程在后台运行，关页面不中断 |
| 🎧 试听 | 每个音色「试听中文 / 试听英文」，`/api/preview` 代理拿 MP3 自动播放；播放互斥（同时只响一个） |
| 🎲 随机试听 | 每个区块右上角「随机试听」，从当前运行的音色里抽一个播放 |
| 📋 复制配置 | 「复制配置」= 可直接粘贴进 App 的多行文本；「完整说明」= 服务器地址 + 模型名留空 + 音色的完整说明段 |
| 🔍 筛选 / 搜索 | Kokoro 按「中文女/男、美英、英英」分组，双区块支持搜索（防抖） |
| 📊 状态监控 | 每 4 秒轮询后端状态、进程 PID；GPU 显存/运算占用（`nvidia-smi` 检测，失败优雅隐藏） |
| 📄 日志查看 | 弹层实时查看两后端日志（`studio_logs/`），支持刷新与复制 |
| 🕘 试听历史 | 记录本次会话的试听，点一下即可重听 |
| ♿ 无障碍 | 卡片可键盘 Tab 聚焦、chip 可键盘激活、`aria-label`/`aria-live` 标注、`prefers-reduced-motion` 适配 |

## 被管理的两套后端（本插件只通过 HTTP 调用 + 启动命令管理，不修改它们）

| 服务 | 端口 | 说明 | 引擎 |
|------|------|------|------|
| **Kokoro**（轻量，CPU） | 8000 | 103 个音色，中/英文，秒级就绪 | `C:\工作文件夹\reader\tts-server\server.py` |
| **IndexTTS 2.5**（高质量，GPU） | 8001 | 克隆音色 `voice_01~voice_12`（无 voice_10）+ emo 音色，启动加载约 15~35 秒 | `...\index-tts-2.5.0\indextts_server.py` |

> 两个后端都是 OpenAI 兼容服务：`GET /voices` 与 `POST /v1/audio/speech`。
> 本插件用启动命令拉起它们、用 HTTP 接口读取音色与试听。

### 音色命名约定

- **Kokoro**：`zf_*` 中文女 / `zm_*` 中文男 / `af_*`·`am_*` 美英 / `bf_*`·`bm_*` 英英；`voice="default"` 会自动中英切换。
- **IndexTTS**：音色用去掉 `.wav` 的短名（如 `voice_07`）或 WAV 绝对路径，或 `default`。

---

## 快速开始

1. 双击 `start_studio.bat`（会用 `C:\工作文件夹\reader\tts-server\.venv\Scripts\python.exe` 运行 `studio.py`）。
2. 浏览器打开 <http://127.0.0.1:8002>（局域网其他设备访问 <http://192.168.1.9:8002>）。
3. 在页面上点「全部启动」或单个后端的「启动」：
   - Kokoro 几秒就绪；
   - IndexTTS 需 15~35 秒加载模型（页面会自动刷新出音色列表，也可点「查看日志」观察进度）。

> 启动的服务在后台运行，**关闭本页/关闭本插件不会停止它们**（子进程以 `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP` 拉起）；再次打开页面会自动识别已运行状态，可在此「停止」。

---

## HTTP 接口（`studio.py` 提供）

### 既有契约（保持稳定）

- `GET /` / `GET /index.html` —— 单页网页。
- `GET /api/voices` —— 聚合两套音色清单 + 状态。两端离线时也返回结构完整对象（`state="stopped"`、`online=false`、`voices=[]`）。
  每端含 `state`（`running`/`starting`/`stopped`）、`pid`、`label`、`desc`、`port`、`voices`；IndexTTS 额外含 `default`。顶层还含 `lan_ip`、`poll_seconds`、`sample_zh`、`sample_en`、`gpu`。
- `POST /api/preview`，请求体 `{"engine","voice","text"}` —— 转发到对应后端 `/v1/audio/speech`，原样回传 MP3。
- `POST /api/control`，请求体 `{"engine":"kokoro"|"indextts","action":"start"|"stop"}` —— 启动/停止对应后端，返回最新状态。

### 新增接口（供增强 UI 使用）

- `POST /api/control` 的 `engine` 扩展为可传 `"all"` —— 一次性 start/stop 两端，返回 `{"results":{...}}`。
- `GET /api/logs?engine=kokoro|indextts` —— 返回后端日志尾部（`{engine,label,log,tail_bytes}`）。
- `GET /api/history` —— 返回本次会话试听历史 `{history:[{time,engine,voice,lang}]}`。

---

## 健壮性设计

- **并发防抖**：前端控制操作在途时锁定；后端 `start_backend`/`stop_backend` 幂等。
- **端口释放轮询**：Windows `taskkill /F` 后套接字释放有延迟，`kill_port` 会轮询端口真正释放（最多 6 秒）。
- **启动失败纠正**：后台 housekeeping 线程每 5 秒清理已退出子进程句柄，发现「发起启动但进程已退出」时记录日志；状态始终以端口监听为准（中途崩溃会自动被下一次轮询识别为 `stopped`）。
- **GPU 容错**：`nvidia-smi` 失败 / 无 GPU 时上报 `unavailable`，前端优雅隐藏 GPU 胶囊。

## 后端日志

启动的后端日志写入 `studio_logs\kokoro.log` 与 `studio_logs\indextts.log`（相对本目录），页面上「查看日志」即可实时读尾部。排查启动失败时优先看这里。

---

## 手机访问需放行防火墙

若要用手机通过局域网访问 8002，请以管理员身份运行：

```
netsh advfirewall firewall add rule name="TTS Studio 8002" dir=in action=allow protocol=TCP localport=8002
```

---

## 自定义

- **局域网 IP 变化**：编辑 `studio.py` 顶部的 `LAN_IP`（或依赖 `/api/voices` 的自动探测结果）。
- **后端启动命令 / 端口变化**：编辑 `studio.py` 的 `BACKENDS` 配置（含 `cwd`、`command`、`env`、`startup_timeout`）。
- **样例句**：`SAMPLE_ZH` / `SAMPLE_EN`。
- **轮询间隔**：`POLL_SECONDS`。

## 目录结构

```
tts-voice-studio/
├── studio.py          # 标准库 HTTP 服务 + 后端管理（唯一后端）
├── index.html         # 单页前端（原生 JS，内联 CSS）
├── start_studio.bat   # Windows 启动脚本（保持可用）
├── README.md          # 本文件
└── studio_logs/       # 后端启动日志（运行时生成）
```
