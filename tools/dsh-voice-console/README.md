# @local/dsh-voice-console — MiMo 音色控制台（DSH 持久化插件）

DSH Web GUI 里的**持久化**插件：侧栏底部多一个「音色控制台」按钮，点开是帧级浮层面板，
用来浏览 / 试听小米 **MiMo-V2.5-TTS** 的预置音色，临时设计音色、临时克隆音色，
**内录系统输出直接当克隆样本**，并一键复制可粘进「听书 App」的配置文本。

它不是动态 Cordis 插件（那类插件进程重启即消失）：源码在这里，插件行写在 web profile 的
`cordis.patch.yml` 里，重启后依然在。

## 目录与安装形态

| 位置 | 内容 |
|------|------|
| `tools/dsh-voice-console/package.json` | 插件包声明：`main` → host 半，`exports["./client"]` → client 半，`dsh.client.platform = web` |
| `tools/dsh-voice-console/lib/index.js` | Host 半：`/voice-console/api` 路由 + MiMo 代理 + 设计/克隆音色的本地存储 + 内录（`parec` / `ffmpeg`） |
| `tools/dsh-voice-console/lib/client.js` | Client 半：`sidebar.footer.action` 按钮 + `shell.overlay` 面板（手写 `window.__ModuleLoader__` bundle，无构建步骤） |
| `tools/dsh-voice-console/test/client-smoke.mjs` | 无浏览器冒烟测试：用 React stub 跑通两次 Slot 注册 + 面板渲染 |
| `~/.dsh/profiles/web/node_modules/@local/dsh-voice-console` | 指向本目录的符号链接（Node 从 profile 锚点解析插件名） |
| `~/.dsh/profiles/web/cordis.patch.yml` | 插件行：`- insert: [{ id: voice-console, name: '@local/dsh-voice-console' }]` |

安装（已由 agent 完成，换机时照做）：

```bash
PROFILE="$HOME/.dsh/profiles/web"
mkdir -p "$PROFILE/node_modules/@local"
ln -sfn /home/xinyan/work/reader/tools/dsh-voice-console "$PROFILE/node_modules/@local/dsh-voice-console"
# 再把上面那行 insert 写进 $PROFILE/cordis.patch.yml
```

> 这两处都在会话工作区之外，默认 `workspace-write` 沙箱会拒绝写入，需要一次显式提权。
> profile 的 `patchReload: live` 让插件行的增删**不用重启** dsh 就生效；浏览器刷新一次即可拿到新的
> `window.__DSH_BOOT__`（客户端插件名单是在渲染 index.html 时注入的）。
>
> ⚠️ 本机没装 `pnpm`，`dsh plugin --profile web …` 目前不可用；手工链接即可。将来若用 pnpm 重装 profile，
> 它可能清掉这个符号链接——重装后按上面两行重新链接，并确认 `cordis.patch.yml` 的插件行还在。

## 改代码后的生效方式

| 改了什么 | 生效方式 |
|----------|----------|
| `cordis.patch.yml` 的插件行 | 立即（live patch reload），刷新页面看 UI |
| `lib/client.js`（浏览器半） | 需要重启 dsh 重新快照 bundle，再刷新页面；没有 `pnpm run dev:web` 时浏览器不会自动热更 |
| `lib/index.js`（Host 半） | profile 的 `cordis.patch.yml` 已启用 `hmr` 行（root 限定到本插件目录），保存即重载；没生效就重启 dsh |

## Host 接口（`/voice-console/api`，全部要求请求头 `x-voice-console: 1`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/state` | 预置音色目录 + 模型名 + 默认音色 + 自定义音色 + 存储根目录 |
| POST | `/synthesize` | `{apiKey, text, style, voice:{kind,id}}` → `{ok, mime, audioBase64}`；`kind` 取 `preset`/`design`/`clone` |
| POST | `/record/start` | `{source, seconds}` → `{ok, session}`；`source` 取 `system`/`mic`/具体 pactl 源名，`seconds` 夹在 3–300 |
| POST | `/record/stop` | 停止并后处理 → `{ok, sample}`；**幂等**——兜底计时器先停时返回上一份样本（带 `alreadyStopped: true`） |
| GET | `/record/status` | `{ok, session}`，`{active:false}` 表示当前没在录 |
| GET | `/samples/<id>` | 试听 / 下载录音样本（wav 字节） |
| DELETE | `/samples/<id>` | 删除录音样本 |
| GET | `/voices/sample?id=…` | 下载克隆音色的样本字节（面板「下载样本」按钮用） |
| POST | `/voices` | 新建：`kind=design`（`name`,`description`,`language`,`gender`）；`kind=clone` 用 `filename`+`dataBase64` 上传，或只给 `sampleId` 直接取内录样本 |
| DELETE | `/voices?id=…` | 删除自定义音色（克隆音色连同样本文件） |

自定义音色落盘在 `~/.dsh/voice-console/`：`voices.json` + `clones/<id>.<ext>`；录音样本在 `recordings/<rec-id>.wav`。
**API Key 不落盘**：浏览器把它存在 `localStorage`，每次请求随 body 传给 Host，Host 只做转发。

## 请求形态（与 App 的 `MiMoTtsBackend` 一致）

```
POST https://api.xiaomimimo.com/v1/chat/completions
headers: { "content-type": "application/json", "api-key": <key> }   # 不是 Authorization: Bearer
body: {
  model: "mimo-v2.5-tts" | "mimo-v2.5-tts-voicedesign" | "mimo-v2.5-tts-voiceclone",
  messages: [ {role:"user", content:<风格指令或音色描述>}?, {role:"assistant", content:<正文>} ],
  audio: { format: "wav", voice: <预置 id | 克隆样本 data URI> }
}
→ choices[0].message.audio.data（base64 wav）
```

预置音色 id 直传，中文音色必须发中文名（`冰糖` / `茉莉` / `苏打` / `白桦`；`mimo_default` 为默认）。
设计音色不带 `audio.voice`，user 消息即描述；克隆音色 `audio.voice = data:audio/*;base64,…`（≤10 MB）。

## 内录（系统输出 → 克隆样本）

面板的「内录」区把 `scripts/record_voice_sample.sh` 那套搬进 GUI，不用开终端：

- **原理**：`parec` 抓 `sink.monitor`（默认输出）或麦克风的数字信号 → `ffmpeg` 去首尾静音（−45 dB / 0.3 s）
  → 带通降噪（80 Hz–12 kHz）→ 响度归一（−16 LUFS）→ 24 kHz 单声道 WAV。参数与脚本一致。
- **源**：下拉里 `system` / `mic` 是 Host 用 `pactl` 解析的默认值，其余是 `pactl list short sources` 的原始设备名。
  内录抓的是数字信号，不经扬声器/麦克风，没有环境噪声——这是拿音色质量最好的路子。
- **时长**：3–300 秒，默认 30。Host 持有兜底计时器，面板关掉（或浏览器崩了）也不会漏掉一个 `parec`；
  浏览器侧比兜底早 400 ms 收尾以保证样本 id 回到页面，即使迟到，`/record/stop` 也会把上一份样本交回来。
- **产物**：`~/.dsh/voice-console/recordings/<rec-id>.wav`。可「试听样本」，或填名称/语言/性别后
  「存为克隆音色」——走 `POST /voices` 带 `sampleId`，不经过上传，样本直接复制进 `clones/`。
- **样本质量**：10–60 秒、单人、纯语音最好；MiMo 上限 10 MB，24 kHz 单声道约 48 KB/s（≈3.5 分钟）。
  整段都在静音阈值以下会被判为「后处理结果为空」，把音量调大再录。
- **合规**：只录你有权使用的声音（自己的，或已获本人同意的）——与 `scripts/record_voice_sample.sh` 的 `--consent` 同一约束。

### 样本怎么进 App

控制台与 App 是**两套独立存储，没有直通**：控制台样本在电脑 `~/.dsh/voice-console/{recordings,clones}/`，
App 的样本在手机 `filesDir/mimo-voices/`。因此「复制配置」对克隆音色**无效**——它只带引擎 / Base URL /
模型 / 音色 id，不带样本字节，把克隆 id 填进 App 会报「克隆音色样本缺失」。正确路径：

1. 面板里点「下载样本」（内录样本行、克隆音色卡片上都有）；
2. 把 wav 传到手机（USB / 网盘 / `adb push`）；
3. App：听书设置 → 多角色 → MiMo 音色区 →「复刻音色」→ 填名称 + 选该文件（mp3/wav，≤10 MB）。

音色包（`.lrpack`，M4）是另一条通道——样本随包分发、装完即用，但控制台目前不生成包。

## 安全边界

- 路由绑在 loopback（DSH Web 默认只监听 `127.0.0.1`），并强制 `x-voice-console: 1` 头：
  该头不在 CORS 安全列表里，跨站页面想发请求会先触发预检，而本服务不会给跨站预检放行。
- 密钥只在浏览器与 Host 内存里，不写文件、不进日志、不入库。
- 面板不注册任何全局副作用；音频每次播放前先停旧音频并 `revokeObjectURL`。

## 自测

```bash
node tools/dsh-voice-console/test/client-smoke.mjs   # 期望 {"ok":true,...}
curl -s -H 'x-voice-console: 1' http://127.0.0.1:3080/voice-console/api/state | head -c 200
```

内录冒烟（需要有声音正在从默认输出播放；也可以先 `pactl load-module module-null-sink sink_name=vctest`，
再用 `paplay --device=vctest <wav>` 造测试音，`source` 填 `vctest.monitor`，测完 `pactl unload-module`）：

```bash
curl -s -H 'x-voice-console: 1' -H 'content-type: application/json' \
  -X POST -d '{"source":"system","seconds":5}' http://127.0.0.1:3080/voice-console/api/record/start
curl -s -H 'x-voice-console: 1' -X POST http://127.0.0.1:3080/voice-console/api/record/stop
```

真机试听需要有效 MiMo API Key（在面板里填并保存）。
