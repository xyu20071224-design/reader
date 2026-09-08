# TTS 音色试听台 · MiMo 版

> 一个**独立、自包含**的本地网页工作台：浏览 / 试听小米 **MiMo-V2.5-TTS** 的预置音色，临时设计音色、临时克隆音色，并一键复制要填进「听书 App」的配置。

**零第三方依赖** —— 服务端只用 Python 标准库（`http.server` / `urllib.request`），前端是单个 `index.html`（原生 JS，无构建步骤、无框架）。

> **2026-09-08 变更**：本工具已**移除两套本地模型后端**（Kokoro :8000、IndexTTS 2.5 :8001）的启动/停止/日志/GPU 监控与全部相关接口，只保留 MiMo 云引擎。本地服务端 `tts-server/` 本身保留，仍可单独手动运行，只是不再由本试听台管理。

---

## 它能做什么

| 能力 | 说明 |
|------|------|
| 🎧 试听预置音色 | 9 个预置音色（中 5 / 英 4），点「试听」用中/英样例合成并自动播放，播放互斥 |
| ✍️ 设计音色 | 用一句自然语言描述（voicedesign）临时生成音色并试听 |
| 🧬 克隆音色 | 上传一段 mp3 / wav 样本（≤10 MB，voiceclone）临时复刻并试听 |
| 🎛 风格指令 | 自然语言风格 prompt，对预置音色生效，改动即时参与下次合成 |
| 📋 复制配置 | 生成可直接粘进 App 的多行配置（Base URL / 模型 / 中英音色 / 风格指令） |
| 🔐 密钥不落盘 | API Key 只存浏览器 localStorage，服务端不写文件、不写日志 |

## 快速开始

```bash
# Linux / macOS
./start_studio.sh
# 或
python3 studio.py
```

```bat
:: Windows
start_studio.bat
```

浏览器打开 <http://127.0.0.1:8002>，填 API Key → 保存 → 点任意音色的「试听」。

环境变量：`STUDIO_HOST`（默认 `127.0.0.1`，设 `0.0.0.0` 才暴露到局域网）、`STUDIO_PORT`（默认 `8002`）。

## HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` / `/index.html` | 单页网页 |
| GET | `/api/voices` | `base_url` / `models` / `defaults` / `samples` / `presets` |
| POST | `/api/preview` | `{api_key, text, style, voice}` → 直接回 wav 字节；`voice.kind` 取 `preset`（`id`）/ `design`（`description`）/ `clone`（`sample_base64`、`sample_name`） |

## 音色命名约定

- **预置音色 id 直传**：`mimo_default`（大陆集群 = 冰糖）、`冰糖`、`茉莉`、`苏打`、`白桦`、`Mia`、`Chloe`、`Milo`、`Dean`。
  中文音色**必须发中文名**，发拼音/英文会返回 `Unknown voice`（真机实测 2026-08-26）。
- **设计音色**：`model = mimo-v2.5-tts-voicedesign`，`messages[0]`（user）= 音色描述，不带 `audio.voice`。
- **克隆音色**：`model = mimo-v2.5-tts-voiceclone`，`audio.voice = data:<mime>;base64,…`。

## 与 DSH 插件的关系

本目录是**独立网页工具**（不依赖 DSH）。日常在 DSH Web GUI 里用的是持久化插件 `@local/dsh-voice-console`
（源码在仓库 `tools/dsh-voice-console/`，侧栏底部「音色控制台」按钮）：它同样只做 MiMo，另带设计/克隆音色的**本地持久化**
（`~/.dsh/voice-console/`）。两者共用同一套请求形态与音色目录，改动时请同步。
