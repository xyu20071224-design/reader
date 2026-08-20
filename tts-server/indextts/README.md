# IndexTTS 2.5 服务（OpenAI 兼容包装，端口 8001）

> 多角色听书 **M1.5**（PLAN-MULTI-VOICE §12）：把 IndexTTS 2.5 作为克隆音色引擎接进
> 「听书设置 → 自建服务器（OpenAI 兼容）」。Kokoro 继续跑 8000 作为快速/兜底引擎。

## 文件

| 文件 | 说明 |
|---|---|
| `indextts_server.py` | 本仓库维护的服务源码（Flask + IndexTTS2）：`GET /voices`、`GET /v1/models`、`POST /v1/audio/speech` |
| `voices.json.example` | 克隆音色画像清单示例，复制到 `INDEX_VOICES_DIR/voices.json` |

`indextts_server.py` 需要 IndexTTS 2.5 的运行环境（`indextts` 包、`checkpoints/`、GPU torch），
因此**部署时把它放进 IndexTTS 安装目录**（本机为 `C:\Users\Clannad\Downloads\index-tts-2.5.0\index-tts-2.5.0`），
两份保持一致；仓库这份是版本管理与审阅用的权威副本。

## 启动

```powershell
# 在 IndexTTS 安装目录下（依赖已装好）
$env:INDEX_VOICES_DIR = "C:\work\reader\tts-server\voices"   # 克隆音色目录
$env:INDEX_TOKEN      = ""                                       # 暴露公网时必须设置
.\.venv\Scripts\python.exe .\indextts_server.py
```

也可以用试听台 `tts-voice-studio`（8002）一键启停 Kokoro 与 IndexTTS，并直接试听音色。
首次启动会加载模型，约 15–35 秒；日志出现 `[init] 模型加载完成` 即就绪。

## 接口

- `GET /voices`

```json
{
  "voices": ["clone_gandalf_en_m.wav", "voice_03.wav"],
  "voice_profiles": [
    {"id": "clone_gandalf_en_m.wav", "label": "Gandalf", "language": "en", "gender": "male", "style": ["deep"]},
    {"id": "voice_03.wav", "label": "", "language": "", "gender": "", "style": []}
  ],
  "default": "voice_03.wav"
}
```

  `voices` 是字符串数组（试听台等旧客户端依赖），`voice_profiles` 是新增的音色画像：
  App 的 M3 音色分配用 `language`/`gender` 做硬过滤，缺省时退回文件名先验
  （`clone_<角色>_<lang>_<m|f>`、Kokoro 的 `zf_/zm_/af_/am_/bf_/bm_`）。

- `POST /v1/audio/speech`：`{"input": "...", "voice": "clone_gandalf_en_m.wav"}` → MP3。
  `voice` 可用文件名（带/不带扩展名）、绝对路径或 `default`；解析顺序为
  绝对路径 → `INDEX_VOICES_DIR` → 安装目录 `examples/` → 默认参考音频。
- `GET /v1/models` → `indextts-2.5`（App 靠它识别慢引擎并隐藏「全书缓存」按钮）。

## 添加克隆音色

```powershell
# 从自备录音剪 8 秒参考音频并写入清单（必须显式确认素材来源）
python scripts\make_clone_voice.py --source my_recording.m4a --name Gandalf `
    --lang en --gender male --start 12 --duration 8 --style deep --consent
```

产物：`tts-server/voices/clone_gandalf_en_m.wav` + `voices.json` 条目；重启服务或让 App 再拉一次
`/voices` 即可在「多角色音色」面板里选到它。

## 实测对比（2026-08-20，本机 RTX 5070 Ti）

`python scripts/tts_compare.py` 的结果（同一批中英文句子，报告见 `artifacts/tts-compare/report.md`）：

| 引擎 | 语种 | 平均每句耗时 | 每字耗时 |
|---|---|---|---|
| Kokoro（CPU） | en | 0.45 s | 0.010 s |
| Kokoro（CPU） | zh | 0.77 s | 0.029 s |
| IndexTTS 2.5（GPU 克隆） | en | 2.58 s | 0.057 s |
| IndexTTS 2.5（GPU 克隆） | zh | 3.17 s | 0.119 s |

**结论（2026-08-20 人工试听确认）**：中英文默认引擎都用 IndexTTS 2.5 ——
英文参考音色 `first_3s_1.wav`、中文 `voice_03.wav`；Kokoro 保留为「快速 / 无 GPU 兜底」
（纯 CPU、每句 0.45–0.77 s，约快 5–6 倍）。IndexTTS 单句 1.5–4.7 秒可用于在线逐句合成，
但**全书缓存对它保持禁用**（App 按 `/v1/models` 自动隐藏按钮）。

对应的 App 配置（听书设置 → 自建服务器）：

| 字段 | 值 |
|---|---|
| 服务器地址 | `http://<本机IP>:8001` |
| 模型名 | 留空或任意（IndexTTS 忽略） |
| 音色/voice（通用兜底） | `voice_03.wav` |
| 英文音色 | `first_3s_1.wav` |
| 中文音色 | `voice_03.wav` |

> `first_3s_1.wav` 的参考音频取自商业音乐轨，**仅限本机自用**；对外发布前必须换成自备或
> 已授权录音（用 `scripts/make_clone_voice.py --consent` 生成）。

## 使用红线（§12.3，务必遵守）

1. 参考音频只能是**自备或已获授权**的素材（本人录音、自制角色音频）。**不要**克隆真人/演员/主播的声音；
   `make_clone_voice.py` 强制 `--consent` 参数即为此确认。
2. 仓库不内置任何克隆参考音频：`tts-server/voices/` 只保留目录与清单示例，音频文件已被 gitignore。
   历史遗留的测试素材（`artifacts/first_3s.wav`，取自商业音乐轨）**不得作为发布用音色**，建议删除。
3. 合成结果属 AI 生成内容：App 多角色面板已常驻提示；对外分发音频请标注「AI 合成」并提供删除渠道。
4. 模型许可：IndexTTS2 采用《bilibili 模型使用许可协议》——免费、非独占、不可转让；
   **月活 > 1 亿或上一自然年营收 > 1 亿人民币**才需另行申请商业许可（本项目规模无需）；
   须保留原始版权声明与许可副本，不得用其输出改进其他商用 AI 模型，禁止高风险场景，
   输出内容合规责任自负（详见安装目录 `LICENSE` / `LICENSE_ZH.txt`）。
