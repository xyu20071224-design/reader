# 本地 TTS 服务（Kokoro-ONNX，OpenAI 兼容）

给 Lingua Reader「听书设置 → 朗读引擎 → **自建服务器（OpenAI 兼容）**」用的本地离线语音合成服务。

- **完全离线**：模型（Kokoro v1.1-zh，约 330MB）与 espeak-ng 音素化数据都在本机，合成过程不联网。
- **中英文自动切换**：每句话自动判断是中文还是英文，分别用中文/英文音色朗读。
- **返回 MP3**：与 App 的 `response_format: mp3` 对齐，直接可播。
- **单模型**：一个模型同时覆盖中文（`zf_*` 女声 / `zm_*` 男声）和英文（`af_maple` / `af_sol` / `bf_vale`）。

## 目录结构

```
tts-server/
├── server.py          # 服务主程序
├── start.bat          # 双击启动
├── requirements.txt   # Python 依赖
├── models/
│   ├── kokoro-v1.1-zh.onnx   # 模型（已下载）
│   └── voices-v1.1-zh.bin    # 音色（已下载）
└── .venv/             # 独立虚拟环境（已建好）
```

## 启动

双击 `start.bat`（或在本目录运行 `.venv\Scripts\python.exe server.py`）。

启动成功后会打印类似：

```
  本机候选地址（手机与电脑需在同一 Wi-Fi）：
      http://192.168.1.9:8000      ← 手机填这个
      http://172.23.240.1:8000     ← WSL/Docker 虚拟网卡，忽略
      http://198.18.0.1:8000       ← VPN 虚拟网卡，忽略
```

> 选 `192.168.x.x` / `10.x` 这类**真实局域网地址**；`172.23.240.1`、`198.18.0.1` 是虚拟网卡/VPN 地址，手机连不上。若列表里没有 `192.168.x.x`，说明电脑没连 Wi-Fi 或连的是热点/有线，需换网络。

## 手机 App 里这样填

打开 App → 听书设置 → 朗读引擎 → 选「**自建服务器（OpenAI 兼容）**」：

| 字段 | 填什么 |
| --- | --- |
| 服务器地址 | `http://192.168.1.9:8000`（换成上面打印的真实 IP） |
| 模型名 | 留空即可，或填 `tts-1`（服务端不校验） |
| API Token（可选） | 留空 |
| 音色/voice（可选） | 留空（=自动按中英文切换），或填具体音色名 |

点「测试连接」，看到「连接成功」即可保存；然后点播放开始听书。

### 音色/voice 的填法

- **留空 / 填 `default`**：自动判断语言 → 中文用 `KOKORO_ZH_VOICE`，英文用 `KOKORO_EN_VOICE`（推荐，中英混读最自然）。
- **填具体音色名**：所有句子固定用该音色。中文可填 `zf_001`~`zf_099`、`zm_009`~`zm_100`；英文可填 `af_maple`、`af_sol`、`bf_vale`。
- 完整音色列表：浏览器打开 `http://<IP>:8000/voices` 查看。

## 更换默认音色 / 端口

编辑 `start.bat`，把对应行前面的 `rem` 去掉并改值：

```bat
rem set KOKORO_ZH_VOICE=zf_001
rem set KOKORO_EN_VOICE=af_maple
rem set PORT=8000
```

中文音色可选 `zf_001`~`zf_099`（女声）、`zm_009`~`zm_100`（男声）；英文音色只有 `af_maple`、`af_sol`、`bf_vale`。

## 让手机能访问（重要，只需做一次）

Windows 防火墙默认会拦截外部设备访问 8000 端口，需要用**管理员**运行一次：

1. 按 `Win` 键，搜索「PowerShell」或「命令提示符」→ 右键 → **以管理员身份运行**。
2. 粘贴执行：

```powershell
netsh advfirewall firewall add rule name="Kokoro TTS 8000" dir=in action=allow protocol=TCP localport=8000
```

3. 提示「确定。」即可。之后手机与电脑连同一 Wi-Fi，App 里填 `http://<电脑IP>:8000` 就能连上。

## 常见问题

- **测试连接失败 / 超时**：手机和电脑是否同一 Wi-Fi？IP 是否填的 `192.168.x.x`？防火墙是否已放行？（见上一节）
- **合成失败自动回退系统语音**：服务端没启动、IP 变了、或电脑休眠/断网。看服务端窗口的报错。
- **中文读成英文口音 / 反之**：单句里中英混杂时按「多数字符」判断；可在 App 里对纯英文句固定用英文音色。留空 voice 的自动判断已覆盖绝大多数情况。
- **换音色后音质不满意**：中文音色有 100 个编号可试，改 `KOKORO_ZH_VOICE` 逐个听。
- **端口被占用**：改 `PORT` 环境变量为其他端口（同时防火墙命令里的 `localport` 也要改）。

## 技术说明

- 服务协议：`POST /v1/audio/speech`，请求体 `{"model","input","voice","response_format"}`，返回原始 MP3 字节——与 Lingua Reader 的 `OpenAiCompatTtsBackend` 完全一致。
- 合成引擎：`kokoro-onnx`（onnxruntime CPU 推理），模型 `kokoro-v1.1-zh`。
- espeak-ng 数据会被自动复制到 `%LOCALAPPDATA%\kokoro-tts\espeak-ng-data`（纯 ASCII 路径），以规避 Windows 上 espeak-ng 无法读取含中文路径的问题。
