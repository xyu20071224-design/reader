# 远程使用家里的 IndexTTS / Kokoro（frp 内网穿透）

让手机在**任何网络**下都能连家里的 TTS 服务（IndexTTS 8001 / Kokoro 8000）。

架构：

```
手机 (4G/Wi-Fi)
   │  http://VPS_IP:8001/v1/audio/speech  (+ Authorization: Bearer <INDEX_TOKEN>)
   ▼
VPS（有公网 IP，跑 frps）
   │  frp 隧道（加密）
   ▼
家里电脑（跑 frpc + IndexTTS 8001 / Kokoro 8000）
```

> 注意：远程解决的是「人在外面也能用 IndexTTS 听书」（在线逐句合成）。
> 合成速度仍取决于家里机器的 GPU，全书缓存对 IndexTTS 依然不可用（App 已自动隐藏该按钮）。

---

## 一、准备

1. **frp**：<https://github.com/fatedier/frp/releases>（v0.52+，配置是 TOML 格式）
   - VPS 上装 **frps**（服务端，Linux 选 `frp_*_linux_amd64.tar.gz`）
   - 家里电脑装 **frpc**（客户端，Windows 选 `frp_*_windows_amd64.zip`）
2. **强随机串**：生成两个 —— 一个给 frp 本身（`auth.token`），一个给 TTS 服务（`INDEX_TOKEN`/`TTS_TOKEN`）：
   ```powershell
   python -c "import secrets; print(secrets.token_urlsafe(24))"
   ```

## 二、VPS 端（frps）

1. 上传 `frps.toml`，把 `auth.token` 和面板密码换成强随机串
2. 运行：`./frps -c frps.toml`
3. 防火墙（ufw / 云厂商安全组）放行：
   - `7000/tcp`（frp 控制口）
   - `8001/tcp`（IndexTTS；若映射了 Kokoro 再加 `8000/tcp`）
4. 建议用 systemd 常驻（示例）：

   ```ini
   # /etc/systemd/system/frps.service
   [Unit]
   Description=frps
   After=network.target

   [Service]
   ExecStart=/opt/frp/frps -c /opt/frp/frps.toml
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```

## 三、家里电脑（frpc + TTS 服务）

1. 上传 `frpc.toml`，把 `serverAddr` 换成 VPS 公网 IP，`auth.token` 与 frps 一致
2. 运行：`frpc -c frpc.toml`（可做成开机自启；frp 官方文档有 Windows 服务方式）
3. **启动 IndexTTS 时带上 Token**（不设 Token 也能跑，但暴露公网**必须**设）：

   ```powershell
   # 临时生效（当前窗口）：
   $env:INDEX_TOKEN = "上一步生成的串"
   uv run python indextts_server.py

   # 或写进 start 脚本永久生效：
   # set INDEX_TOKEN=xxx   ← 在 .bat 里
   ```

   Kokoro 同理：`$env:TTS_TOKEN = "..."` 后 `python server.py`

4. 验证：VPS 上 `curl http://127.0.0.1:8001/v1/models` 应返回 indextts-2.5

## 四、App 端

听书设置 → 朗读引擎 → **自建服务器（OpenAI 兼容）**：

| 字段 | 值 |
|---|---|
| 服务器地址 | `http://VPS_公网IP:8001` |
| Token | 与 `INDEX_TOKEN` 相同 |
| 模型名 | 留空 |
| 音色 | 例如 `voice_07`（examples 下的短名）或 WAV 绝对路径 |

- 连接后 App 自动 `GET /v1/models` 探测：识别到 IndexTTS → **全书缓存按钮自动隐藏**（探测失败/超时则默认显示，保持兼容）
- 局域网场景不变：地址填 `http://192.168.x.x:8001` 即可，Token 可留空（服务端未设 Token 时不需要）

## 五、安全清单（务必过一遍）

- [ ] `INDEX_TOKEN` / `TTS_TOKEN` 已设置（服务端启动打印会显示「鉴权: 已启用」）
- [ ] frp `auth.token` 是强随机串
- [ ] VPS 防火墙只开放 7000/8000/8001，管理面板端口不对公网开放（frps.toml 里已绑 127.0.0.1）
- [ ] `/v1/models` 保持开放（无鉴权）：App 探测引擎能力要用；它只泄露模型 id，无实际风险。若介意，可改为固定返回（App 侧探测逻辑不受影响）
- [ ] Token 泄露后：换新 Token 重启服务即可，无需动 frp

## 常见问题

- **手机能连通 VPS 但合成 401**：Token 不匹配。检查 App 填的 Token 与 `INDEX_TOKEN` 是否一致（注意首尾空格）。
- **`frpc` 启动报 `login to server failed`**：VPS 防火墙没放行 7000，或 `auth.token` 与 frps 不一致。
- **公网合成超时**：手机到 VPS 网络差时单句合成 1–13 秒 + 网络往返，App 读超时 120 秒足够；若仍超时，先 `curl` 验证 VPS 本机可达性。
- **合成了但手机没声音**：确认 App 里音色名存在（`voice_07` 等短名），可先用语音工作室的试听功能验证。
