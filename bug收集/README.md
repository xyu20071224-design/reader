# Bug 收集

本文件夹集中记录「语境阅读」App 听书（TTS）链路中发现的缺陷，供排期修复与回归验证使用。

- 审查范围（第二轮已扩展）：
  - `src/app/src/main/java/com/linguareader/app/tts/`（TtsPlaybackEngine / TtsPlaybackService / CloudTtsSynthesizer / SystemTtsSynthesizer / SherpaTtsSynthesizer / SentenceSplitter / TtsTextExtractor）
  - `src/app/src/main/java/com/linguareader/app/ai/`（DeepSeek / Azure 整句翻译 / 本地语境 / 术语表）
  - `tts-server/server.py`、`tts-voice-studio/studio.py`
- 审查范围（第三轮扩展，2026-08-17）：
  - `src/app/src/main/java/com/linguareader/app/data/ContextAnalyzer.kt`
  - `src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt`（阅读器 JS 桥）
  - `tts-voice-studio/studio.py` 进程管理（start/stop/housekeeping）与 HTTP 控制面
  - `tts-server/server.py` 监听与鉴权
- 审查范围（第四轮扩展，2026-08-17）：
  - `tts-voice-studio/index.html`（随机试听、播放/停止、blob URL 生命周期）
  - `tts-voice-studio/studio.py`（`/api/voices` 的 `lan_ip` 探测与返回）
  - `tts-server/server.py`（`voice` 参数校验、`get_kokoro` 懒加载初始化并发）
- 审查基准：commit `9d03d04`（抽取 TtsPlaybackEngine 纯 Kotlin 播放状态机，M2）之后的当前工作区状态
- 发现日期：2026-08-16（第一/二轮）、2026-08-17（第三轮、第四轮）

---

## 修复排期（同族分批）

> 排期原则：先解决核心播放链路/严重缺陷，再处理 AI/阅读器，最后服务端与工作室前端；
> 同族问题集中修改，避免反复触碰同一段代码。
> 批次内按「严重 → 中等 → 轻微/观察项」排序，并把相互依赖的修复放同一批。

### 批次 1：TTS 引擎核心链路（P0）

#### 1.1 云 TTS 失败 / 回退 / 初始化族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-001](./BUG-001-云TTS失败回退失效-无限重建云引擎.md) | 云 TTS 失败后回退系统引擎失效，无限重建云引擎（重复计费） | 🔴 严重 | 已修复 | `fallbackToSystemTts()` 实际使用 `fallbackSynthesizerFactory`；改写被测试工厂掩盖的 `cloudPrepareFailureFallsBackToSystemEngine` |
| [BUG-002](./BUG-002-TTS引擎初始化失败后播放永久卡死.md) | TTS 引擎初始化失败后播放永久卡死，无法恢复 | 🔴 严重 | 已修复 | `onInitFailed` 后走回退或释放重建，避免坏引擎永远挂在 `pendingReady` |
| [OBS-03](./次要候选/观察项-未定级.md) | `fallbackActive` 字段是死代码 | ⚪ 未定级 | 已修复 | 与 BUG-001/002 同批处理：删除或正确赋值并用于状态展示 |

#### 1.2 云 TTS 暂停 / 计费时序族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-005](./BUG-005-暂停后云TTS在途合成仍出声并翻回播放状态.md) | 云 TTS 合成等待窗口内暂停无效，音频照播且状态被翻回「播放中」 | 🟠 中等 | 已修复 | `CloudTtsSynthesizer` 增加 `stopped` 标志；`handleUtteranceStart` 增加 `!playing` 保护 |
| [OBS-04](./次要候选/观察项-未定级.md) | 全书缓存进行中点击句子朗读，可能同句重复合成计费 | ⚪ 未定级 | 已修复 | `waitForFileOrSynthesize` 同时感知 `bookPrepareJob`，或把直接合成路径纳入 `inflightFiles` 登记 |

#### 1.3 播放导航 / 异步竞态族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-006](./次要候选/BUG-006-连按下一句导致同一句重复朗读.md) | `loadAndSpeakCurrent` 缺少过期保护，连按「下一句」重复朗读 | 🟡 轻微 | 已修复 | 增加请求代次/过期校验 |
| [BUG-007](./次要候选/BUG-007-上一句在章节边界遇到空白句会向前跳.md) | 章节边界按「上一句」且上一章末尾为空白句时，会跳回当前章开头 | 🟡 轻微 | 已修复 | `previous()` 跨章跳过空白句并保持方向一致 |
| [BUG-015](./BUG-015-云TTS跨章上一句无限等待旧章节预生成.md) | 云 TTS 下按「上一句」跨章节时无限等待旧章节预生成，播放卡住数分钟 | 🟠 中等 | 已修复 | `previous()` 跨章复用 `loadAndSpeakCurrent()` 流程；`waitForFileOrSynthesize` 加超时兜底 |
| [BUG-009](./次要候选/BUG-009-停止与异步启动的竞态导致播放复活.md) | 停止操作与异步章节查询竞态，已停止的播放会被复活 | 🟡 轻微 | 已修复 | Service 增加播放 generation，异步协程恢复后校验代次 |
| [BUG-014](./次要候选/BUG-014-章节握手deferred清理不对称.md) | 章节握手 deferred 清理不对称，暂停期间页面跟随失效 | 🟡 轻微 | 已修复 | `chapterReadyDeferred` 在切章/暂停时对称清理 |

#### 1.4 引擎标记 / 媒体控制 / 资源族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-003](./BUG-003-MediaSession从未激活-外部媒体控制失效.md) | MediaSession 从未激活，蓝牙/耳机/系统媒体控制全部失效 | 🟠 中等 | 已修复 | 播放进入前台时置 `isActive = true` |
| [BUG-004](./BUG-004-Piper引擎被错标为系统语音.md) | Piper 离线引擎在 UI 上被错标为「系统语音」 | 🟡 轻微 | 已修复 | `engineLabelForSynthesizer` 识别 `SherpaTtsSynthesizer` |
| [BUG-008](./次要候选/BUG-008-Sherpa临时WAV泄漏与双重释放崩溃风险.md) | Sherpa 合成器临时 WAV 泄漏 + MediaPlayer 双重释放崩溃风险 | 🟡 轻微 | 已修复 | `stop()` 清理临时文件；断开 `onPrepared` 回调并保护重复 release |

---

### 批次 2：AI 翻译 / 语境 / 阅读器（P1）

#### 2.1 句子切分 / 术语 / 动态词典族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-010](./BUG-010-人名缩写被拆成碎句.md) | 人名缩写（J. K. Rowling）被 SentenceSplitter 拆成碎句朗读 | 🟠 中等 | 已修复 | 把空格分隔的缩写（`J. K. Rowling`）纳入 `protectedPeriods` |
| [BUG-011](./BUG-011-Azure整句翻译动态词典标记未做XML转义.md) | Azure 整句翻译动态词典标记的元素内容未做 XML 转义 | 🟠 中等 | 已修复 | 元素内容同样 `xmlEscape`；单测通过 |
| [BUG-020](./次要候选/BUG-020-术语匹配每句仅首次出现漏标.md) | 术语匹配每个句子只返回第一次出现，重复术语在整句翻译中漏标 | 🟡 轻微 | 已修复 | `matchesIn` 命中后继续收集后续非重叠匹配 |

#### 2.2 翻译重试 / 设置页族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-012](./BUG-012-Azure设置页测试连接按钮未实现.md) | Azure TTS 设置页「测试连接」按钮未实现，与「获取可用音色」行为相同 | 🟡 轻微 | 已修复 | 新增 `testAzure()` 真实发起一次合成 |
| [BUG-013](./次要候选/BUG-013-DeepSeek-JSON模式重试逻辑颠倒.md) | DeepSeek JSON 模式重试逻辑颠倒：解析失败后反而关闭 JSON 约束 | 🟡 轻微 | 已修复 | 解析失败时保持 json_mode 重试，而不是关闭约束 |
| [OBS-05](./次要候选/观察项-未定级.md) | 整句翻译失败静默吞掉，无任何提示 | ⚪ 未定级 | 已修复 | 失败时展示 `Throwable.message`，不静默吞掉 |

#### 2.3 词边界 / 高亮 / 索引族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-019](./次要候选/BUG-019-targetIndex闭区间包含排他边界off-by-one.md) | `targetIndex` 用闭区间包含排他边界 `endExclusive`（off-by-one） | 🟡 轻微 | 已修复 | 改为 `in it.start until it.endExclusive` |
| [BUG-021](./次要候选/BUG-021-生词高亮无词边界误标子串.md) | 生词高亮无词边界，短词被误标进不相关单词 | 🟡 轻微 | 已修复 | JS 侧复用 Kotlin 端词边界规则 |
| [OBS-01](./次要候选/观察项-未定级.md) | 本地语境把句首大写普通词混入专名/术语档案 | ⚪ 未定级 | 已修复 | 区分句首大写与句中大写，或加功能词黑名单 |

---

### 批次 3：TTS 服务端 / 语音工作室（P1/P2）

#### 3.1 服务端健壮性族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-024](./次要候选/BUG-024-TTS服务voice参数非字符串返回500.md) | 自建 TTS 服务未校验 `voice` 类型，非字符串参数返回 500 | 🟡 轻微 | 已修复 | 路由入口校验 `voice`/`input` 类型，返回结构化 400 |
| [BUG-025](./次要候选/BUG-025-Kokoro模型懒加载单例初始化竞态.md) | Kokoro 模型懒加载单例初始化未加锁，并发首次访问有竞态 | 🟡 轻微 | 已修复 | 初始化 double-check 加锁 |
| [OBS-02](./次要候选/观察项-未定级.md) | `detect_lang` 对纯数字/标点文本误判为中文 | ⚪ 未定级 | 已修复 | 增加空字符/纯符号兜底判断 |

#### 3.2 语音工作室进程管理族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-016](./BUG-016-语音工作室start_backend竞态拉起重复孤儿进程.md) | 语音工作室 `start_backend` 检查与启动无锁，并发请求拉起重复/孤儿后端进程 | 🟠 中等 | 已修复 | 加锁/幂等创建后端进程 |
| [BUG-017](./BUG-017-语音工作室stop_backend误杀无关进程与worker残留.md) | 语音工作室 `stop_backend` 无属主校验杀进程，`taskkill` 缺 `/T` 残留 worker | 🟠 中等 | 已修复 | 校验进程属主；`taskkill` 加 `/T` |

#### 3.3 服务网络 / 安全族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-018](./BUG-018-TTS服务与工作室无鉴权暴露控制面.md) | TTS 服务与工作室绑定 0.0.0.0 且控制面/试听代理无鉴权 | 🟠 中等（安全） | 已修复 | 增加鉴权或默认不绑 0.0.0.0 |
| [BUG-023](./BUG-023-语音工作室API返回写死局域网IP.md) | 语音工作室 `/api/voices` 返回写死的 `LAN_IP`，换网络后手机连不上 | 🟠 中等 | 已修复 | 动态探测局域网 IP，不再写死 |

#### 3.4 试听前端族

| 编号 | 标题 | 严重程度 | 状态 | 关键改动 |
| --- | --- | --- | --- | --- |
| [BUG-022](./BUG-022-语音工作室随机试听必崩.md) | 语音工作室「随机试听」传入 null 按钮，点击必然 TypeError 无法播放 | 🟠 中等 | 已修复 | 播放前判空并正确处理按钮 |
| [BUG-026](./次要候选/BUG-026-试听blobURL未释放.md) | 试听音频 blob URL 未在停止/错误/中断时释放，长时间使用内存泄漏 | 🟡 轻微 | 已修复 | 统一管理 blob URL，停止/中断/错误时 revoke |

---

### 批次 4：全量回归与文档收尾 ✅（2026-08-19 已完成）

- 全量 Android 单元测试：`testDebugUnitTest` ✅ BUILD SUCCESSFUL
- 真机（PKB110 / Android 16）全量仪器测试：`connectedDebugAndroidTest`
  ✅ 35 tests，0 failed，1 skip（ReviewReminder 通知权限无法在 OEM 上自动授予）
- `tts-server/server.py`、`tts-voice-studio/studio.py`：`py_compile` + AST 检查通过
- `tts-voice-studio/index.html`：内嵌 JS `node --check` 通过
- `tts-voice-studio/studio.py`：macOS 冒烟验证通过（动态 IP / 属主校验 / 幂等）
- 所有缺陷状态已更新为「已修复」；同族关联改动已记录于「同源/同族关系说明」

---

## 编号速查表

| 编号 | 标题 | 严重程度 | 状态 | 所属批次 |
| --- | --- | --- | --- | --- |
| BUG-001 | 云 TTS 失败后回退系统引擎失效，无限重建云引擎（重复计费） | 🔴 严重 | 已修复 | 批次 1.1 |
| BUG-002 | TTS 引擎初始化失败后播放永久卡死，无法恢复 | 🔴 严重 | 已修复 | 批次 1.1 |
| BUG-003 | MediaSession 从未激活，蓝牙/耳机/系统媒体控制全部失效 | 🟠 中等 | 已修复 | 批次 1.4 |
| BUG-004 | Piper 离线引擎在 UI 上被错标为「系统语音」 | 🟡 轻微 | 已修复 | 批次 1.4 |
| BUG-005 | 云 TTS 合成等待窗口内暂停无效，音频照播且状态被翻回「播放中」 | 🟠 中等 | 已修复 | 批次 1.2 |
| BUG-006 | `loadAndSpeakCurrent` 缺少过期保护，连按「下一句」重复朗读 | 🟡 轻微 | 已修复 | 批次 1.3 |
| BUG-007 | 章节边界按「上一句」且上一章末尾为空白句时，会跳回当前章开头 | 🟡 轻微 | 已修复 | 批次 1.3 |
| BUG-008 | Sherpa 合成器临时 WAV 泄漏 + MediaPlayer 双重释放崩溃风险 | 🟡 轻微 | 已修复 | 批次 1.4 |
| BUG-009 | 停止操作与异步章节查询竞态，已停止的播放会被复活 | 🟡 轻微 | 已修复 | 批次 1.3 |
| BUG-010 | 人名缩写（J. K. Rowling）被 SentenceSplitter 拆成碎句朗读 | 🟠 中等 | 已修复 | 批次 2.1 |
| BUG-011 | Azure 整句翻译动态词典标记的元素内容未做 XML 转义 | 🟠 中等 | 已修复 | 批次 2.1 |
| BUG-012 | Azure TTS 设置页「测试连接」按钮未实现 | 🟡 轻微 | 已修复 | 批次 2.2 |
| BUG-013 | DeepSeek JSON 模式重试逻辑颠倒：解析失败后反而关闭 JSON 约束 | 🟡 轻微 | 已修复 | 批次 2.2 |
| BUG-014 | 章节握手 deferred 清理不对称，暂停期间页面跟随失效 | 🟡 轻微 | 已修复 | 批次 1.3 |
| BUG-015 | 云 TTS 下按「上一句」跨章节时无限等待旧章节预生成 | 🟠 中等 | 已修复 | 批次 1.3 |
| BUG-016 | 语音工作室 `start_backend` 检查与启动无锁，并发请求拉起重复/孤儿后端进程 | 🟠 中等 | 已修复 | 批次 3.2 |
| BUG-017 | 语音工作室 `stop_backend` 无属主校验杀进程，`taskkill` 缺 `/T` 残留 worker | 🟠 中等 | 已修复 | 批次 3.2 |
| BUG-018 | TTS 服务与工作室绑定 0.0.0.0 且控制面/试听代理无鉴权 | 🟠 中等（安全） | 已修复 | 批次 3.3 |
| BUG-019 | `targetIndex` 用闭区间包含排他边界 `endExclusive`（off-by-one） | 🟡 轻微 | 已修复 | 批次 2.3 |
| BUG-020 | 术语匹配每个句子只返回第一次出现，重复术语在整句翻译中漏标 | 🟡 轻微 | 已修复 | 批次 2.1 |
| BUG-021 | 生词高亮无词边界，短词被误标进不相关单词 | 🟡 轻微 | 已修复 | 批次 2.3 |
| BUG-022 | 语音工作室「随机试听」传入 null 按钮，点击必然 TypeError 无法播放 | 🟠 中等 | 已修复 | 批次 3.4 |
| BUG-023 | 语音工作室 `/api/voices` 返回写死的 `LAN_IP`，换网络后手机连不上 | 🟠 中等 | 已修复 | 批次 3.3 |
| BUG-024 | 自建 TTS 服务未校验 `voice` 类型，非字符串参数返回 500 | 🟡 轻微 | 已修复 | 批次 3.1 |
| BUG-025 | Kokoro 模型懒加载单例初始化未加锁，并发首次访问有竞态 | 🟡 轻微 | 已修复 | 批次 3.1 |
| BUG-026 | 试听音频 blob URL 未在停止/错误/中断时释放，长时间使用内存泄漏 | 🟡 轻微 | 已修复 | 批次 3.4 |
| OBS-01 | 本地语境把句首大写普通词混入专名/术语档案 | ⚪ 未定级 | 已修复 | 批次 2.3 |
| OBS-02 | `detect_lang` 对纯数字/标点文本误判为中文 | ⚪ 未定级 | 已修复 | 批次 3.1 |
| OBS-03 | `fallbackActive` 字段是死代码 | ⚪ 未定级 | 已修复 | 批次 1.1 |
| OBS-04 | 全书缓存进行中点击句子朗读，可能同句重复合成计费 | ⚪ 未定级 | 已修复 | 批次 1.2 |
| OBS-05 | 整句翻译失败静默吞掉，无任何提示 | ⚪ 未定级 | 已修复 | 批次 2.2 |

> 说明：BUG-006（连按「下一句」重复朗读）在第一轮已记录；第二轮独立复查时也定位到
> 同一问题，两轮结论一致，无需新增条目。BUG-010/011 已用脚本按原逻辑逐行复现，
> 证据见各文件「证据」节。
>
> 第三轮（2026-08-17）复查时再次定位到以下问题，与既有条目一致、**未重复建档**：
> MediaSession 从未激活（=BUG-003）、Piper 被错标为系统语音（=BUG-004）、
> 云 TTS 暂停竞态（=BUG-005）、Sherpa 临时 WAV 泄漏/双重释放（=BUG-008）、
> `detect_lang` 对纯数字/标点文本误判为中文（=观察项 OBS）。第三轮新增
> BUG-015~021，覆盖：云 TTS 跨章「上一句」无限等待、语音工作室进程管理竞态与
> 无鉴权控制面、`targetIndex` off-by-one、术语/生词的边界一致性缺陷。

## 同源/同族关系说明

为避免修复时反复触碰同一段代码，以下问题按族归入同一批次，但**各自根因和修复点不同，
不算重复 bug**：

- **云 TTS 回退/初始化/暂停/计费族**：BUG-001、BUG-002、BUG-005、BUG-015、OBS-03、OBS-04
  - 分布在 `TtsPlaybackEngine.kt` / `CloudTtsSynthesizer.kt`，建议一次性重构该区域状态机。
- **上一句跨章节族**：BUG-007、BUG-015
  - 都集中在 `TtsPlaybackEngine.previous()` 的跨章分支，修复时一并处理。
- **语音工作室进程管理族**：BUG-016、BUG-017
  - 都集中在 `tts-voice-studio/studio.py` 的 start/stop/housekeeping。
- **Azure 动态词典族**：BUG-011、BUG-020
  - 都集中在 Azure 整句翻译的 `markupSentence` / `matchesIn` 上游管线。
- **词边界/术语/高亮族**：BUG-019、BUG-020、BUG-021、OBS-01
  - 都涉及“匹配位置/边界”语义，Kotlin 与 JS 两侧需统一规则。
- **试听前端族**：BUG-022、BUG-026
  - 都集中在 `tts-voice-studio/index.html` 的音频播放生命周期。

## 严重程度说明

- 🔴 严重：核心链路瘫痪 / 无法恢复 / 产生持续消耗（重复计费）
- 🟠 中等：功能失效或明显错误行为，但有可绕行路径
- 🟡 轻微：边缘场景、竞态窗口小、仅影响体验或资源

## 验证方法

- 每个文档的「验证方式」小节给出最小复现步骤，修复后应逐条回归。
- 引擎层 bug（BUG-001/002/005/006/007）可参考
  `src/app/src/test/java/com/linguareader/app/tts/TtsPlaybackEngineTest.kt`
  用 FakeTtsSynthesizer + TestDispatcher 补充单测；注意现有用例
  `cloudPrepareFailureFallsBackToSystemEngine` 通过「测试工厂自行翻转行为」掩盖了
  BUG-001，修复引擎后应改写该用例（fallback 工厂必须真实参与）。

### 本地运行单元测试

仓库已内置 Robolectric 本地依赖与初始化脚本，避免 Robolectric 尝试写
`~/.robolectric-download-lock` 导致权限失败。运行单测时带上：

```bash
cd src
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=/Users/clannad/Desktop/reader/.gradle-home \
./gradlew --init-script /Users/clannad/Desktop/reader/.gradle-home/robolectric-init.gradle \
  :app:testDebugUnitTest
```

只跑某个测试类：

```bash
./gradlew --init-script /Users/clannad/Desktop/reader/.gradle-home/robolectric-init.gradle \
  :app:testDebugUnitTest --tests "com.linguareader.app.ai.AzureSentenceTranslatorTest"
```

> 说明：`robolectric-init.gradle` 会把 `user.home` 指向仓库内 `.robolectric-home`，
> 并把 `robolectric.dependency.dir` 指向已下载的
> `android-all-instrumented-15-robolectric-12650502-i7`，因此无需联网下载。

### 运行 Android 仪器测试（首选真机，模拟器备选）

仪器测试运行在真实 Android 设备或模拟器上，用于验证 MediaSession、系统 TTS、
WebView 交互等 JVM 单测覆盖不到的场景。测试源码位于
`src/app/src/androidTest/`。

> **首选环境：真机**。当前已验证设备：
>
> - 型号：PKB110
> - Android 版本：16（API 36）
> - 连接状态：`adb devices` 显示 `ZXJRNJVWY9C6BYDA device`
> - 已验证：全量 `connectedDebugAndroidTest` 通过（`LaunchPromptUiTest` 3 个用例全部通过；
>   `ReviewReminderInstrumentedTest` 因该 OEM 不允许 shell 自动授予通知权限而 skip）

**真机连接方式（首选）：**

1. 手机开启「开发者选项」和「USB 调试」。
2. 用数据线连接电脑，首次连接时在手机上允许 USB 调试。
3. 确认设备已被 adb 识别：

   ```bash
   adb devices
   ```

   应看到真机序列号 + `device` 状态，例如：

   ```
   ZXJRNJVWY9C6BYDA   device
   ```

**模拟器连接方式（备选）：**

- 启动已配置好的 AVD（Android Studio Device Manager 或命令行 `emulator -avd <name>`）。
- 同样用 `adb devices` 确认出现 `emulator-5554   device`。

**SDK / 调试签名配置：**

- `src/local.properties` 已配置：

  ```properties
  sdk.dir=/opt/homebrew/share/android-commandlinetools
  ```

- 项目本地调试签名目录：`/Users/clannad/Desktop/reader/.android`
  （`ANDROID_USER_HOME` 指向它，避免尝试写 `~/.android` 被权限拦截）。

**运行全量仪器测试：**

```bash
cd /Users/clannad/Desktop/reader/src

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=/Users/clannad/Desktop/reader/.gradle-home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_USER_HOME=/Users/clannad/Desktop/reader/.android \
./gradlew :app:connectedDebugAndroidTest
```

**只跑某个仪器测试类：**

```bash
cd /Users/clannad/Desktop/reader/src

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRADLE_USER_HOME=/Users/clannad/Desktop/reader/.gradle-home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
ANDROID_USER_HOME=/Users/clannad/Desktop/reader/.android \
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.linguareader.app.tts.SystemTtsVoicesInstrumentedTest
```

> 说明：
>
> - 每次执行前先 `adb devices` 确认目标设备处于 `device` 状态。
> - 如果同时连接了多个设备，`connectedDebugAndroidTest` 默认会在所有设备上运行；
>   只想跑一台时，请先断开其他设备或只保留目标设备。
> - 若首次运行因「签名不匹配」安装失败，可先手动安装一次当前 debug APK：
>
>   ```bash
>   adb install -r \
>     /Users/clannad/Desktop/reader/src/app/build/outputs/apk/debug/app-debug.apk
>   ```
