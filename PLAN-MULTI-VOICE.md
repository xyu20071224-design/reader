# 多角色听书：说话人识别 × 音色分配 实现计划

> 目标：让听书能按「谁在说话」给不同角色分配不同音色（旁白/角色 A/角色 B…）。
> 本文档把两大块——**说话人文本识别**与**音色分配算法**——合成一条完整实现链路，
> 标注每一处的现有代码复用点与新增模块。

---

## 1. 目标与范围

| 阶段 | 能力 | 识别方式 | 引擎 |
|---|---|---|---|
| **M1（MVP）** | 旁白 vs 对白，两音色 | 规则层（引文检测 + 归属启发） | Kokoro / Azure（现成 voice 参数） |
| **M2** | N 角色逐句归属 | 规则层 + LLM 打标（带缓存与降级） | 同上 |
| **M3** | 角色 → 音色自动分配 | 分配算法（硬约束+软评分+共现区分） | 同上 + 音色库画像 |
| **M4** | 用户可调、可试听 | 手动覆盖 + 持久化 | 全引擎 |
| **M5（计划中）** | 系统引擎多角色 | 复用 M1–M3 全链路 | 系统引擎（音色库由用户标注构建，Google TTS 为推荐基准） |

设计原则：**每一阶段都可独立上线**；M1 先出可用闭环，后续增量叠加。

### 实施状态（2026-08-20，M1/M1.5/M2/M3/M4 全部完成）

| 阶段 | 状态 | 落地位置 |
|---|---|---|
| **M1** | ✅ 已实现 | `TtsSynthesizer.speak(..., voice)` 透传；`SpeakerRuleTagger`；`TtsChapter.speakers`；`CloudTtsSettings.narratorVoice/dialogueVoice` + 听书设置两个输入框 |
| **M1.5** | ✅ 已实现 | IndexTTS 2.5 跑在 8001（OpenAI 兼容，`tts-server/indextts/indextts_server.py` 为仓库权威副本）；`GET /voices` 增加 `voice_profiles` 画像 + `voices/voices.json` 清单；`scripts/make_clone_voice.py` 生成/登记克隆音色（强制 `--consent`）；`scripts/tts_compare.py` + `artifacts/tts-compare/report.md` 完成中英实测；App 侧 `ServerVoice` 元数据贯通到 M3 硬过滤；`tts-voice-studio`（8002）一键启停/试听两端；LICENSE 已核对 |
| **M2** | ✅ 已实现 | `AiChatClient`（DeepSeek 共用调用层）；`CharacterProfile` + `BookContextProfile.characterProfiles`；`GlossaryEntry` 角色字段 + `mergeProfile`（手动优先）；`SpeakerRuleTagger.index()` 槽位；`SpeakerLlmTagger`（提示词/校验/对齐/分窗/降级）；`SpeakerTagRepository`（`ai/speaker-tags/<bookId>/<chapter>.json` 缓存与增量）；`TtsPlaybackEngine.applySpeakerTags` + `TtsTextExtractor.applySpeakers` 热替换；`TtsPlaybackService` 后台打标（D2 门控） |
| **M3** | ✅ 已实现 | `VoiceInfo`/`VoiceLibrary`/`VoiceNaming`（元数据 + 名字先验）；`VoiceLibraryLoader` + `ServerVoiceStore`（Azure `voices/list`、自建服务器 `/voices`、火山已配音色）；`VoiceAssigner`（硬过滤 + 软评分 + 共现贪心 + 交换 + 受限复用 + 旁白兜底）；`SpeakerCooccurrence`（复用 M2 章节缓存统计相邻说话人）；`BookVoiceMap` + `VoiceMapRepository`（`files/voice_maps/<bookId>.json`，增量/锁定/换引擎重算）；`TtsPlaybackService.resolveVoice` 与预生成共用同一解析器 |
| **M4** | ✅ 已实现 | `CloudTtsSettings.multiVoiceEnabled`（默认关）；`MultiVoiceSettings.kt`（`MultiVoiceSection`：开关 + Piper/系统语音置灰说明、书目选择、状态提示、中英旁白下拉、角色列表 + 音色下拉 + 试听、锁定标记）；`MultiVoiceSupport`（服务与 UI 共用装配、状态文案、样句）；`VoiceAudition`（按音色合成一句并播放）；选定即写 `userLocked` 并触发服务重载 |

M2 实施中的两处**有意偏离本文档**（更保守、已写进单测）：

1. §4.2 原写「不在角色表内或 `confidence` 低于阈值 → narrator」。实现改为**退回规则层标签**：规则层对未归属引文给出 `dialogue`（不是角色名，同样不会凭空造人），因此退回它可以保住 M1 的双音色听感，而不是把对白读成旁白。
2. 缓存目录沿用现有 AI 目录规范，为 `files/ai/speaker-tags/<bookId>/<chapter>.json`（而非 `speaker_cache/`），与 `ai/book-context`、`ai/glossary` 并列。

另外 M2 增加了两条计划外的成本/稳定性保护：无引文的段落窗口不发请求；每章的打标状态在服务内只解析一次（播放引擎每句都会重新加载章节，否则会逐句重复读缓存甚至重复请求）。

M3 与本文档 §5 的差异与补充：

1. **旁白先选**：算法把 §5.2 的第 ④ 步（旁白音色）提到最前面并把它计入「已占用」，这样角色天然避开旁白音色，无需事后再算距离。
2. **用户已配音色视为保留位**：设置里的 M1 旁白/对白音色（`narratorVoice`/`dialogueVoice`）作为 `reserved` 传入，自动分配不会再把它们分给角色；解析顺序为「手动旁白音色 → 角色/旁白映射 → 对白音色 → 引擎默认」。
3. **共现统计不新增存储**：`adjacentSpeakers` 直接由 M2 的章节打标缓存现算（跳过旁白与未归属对白、折叠连续同一说话人），已读章节越多统计越准。
4. **换引擎时的锁定项**：`userLocked` 始终保留；若锁定的音色在新引擎音色库里不存在，则该角色会被重新分配（不存在的音色无法合成），锁定标记留待用户改回。
5. **未做（留待 M1.5/后续）**：§3.4 的第 2 项「采样分析（f0 提取校准性别/音区）」尚未实现，`VoiceInfo.quality` 目前是占位值，只用于打分排序的稳定性；IndexTTS 克隆音色一旦在服务器 `/voices` 中出现即作为普通音色库成员参与分配，无需改算法。
6. **预生成一致性**：云引擎整章预生成与逐句播放共用同一个 `resolveVoice`，否则预生成会把音频缓存到播放端永远不会请求的音色目录下（重复计费）。

M4 与本文档 §8 的差异与补充：

1. **开关改为显式设置项**：新增 `multiVoiceEnabled`（默认关）。此前 M1/M2/M3 用「对白音色非空」作为隐式开关，现在开关关闭即完全不打标、不分配（省钱、可预期）；Piper/系统语音下开关置灰并给出 D2 说明。
2. **解析优先级调整**：多角色开启时以 `BookVoiceMap` 为准（M4 面板改的就是它），M1 的 `narratorVoice`/`dialogueVoice` 退化为「映射里没有的语言/未归属对白」的兜底，避免面板里改了旁白却被旧的手填音色盖住。
3. **入口两处**：阅读页「听书设置」自带当前书（直接显示角色列表）；书架「AI 中心 → 听书语音」提供书目下拉（沿用术语表页的选择器样式）。
4. **试听**：`VoiceAudition` 用当前引擎按指定音色合成一句样句并播放（中文音色说中文样句，英文音色说英文样句），播放前先停掉上一条，避免叠音。
5. **未做（可选 M4+）**：阅读页长按句子改「此人说话/旁白」并增量重打标，仍是可选项，未实现。

---

## 2. 总体架构与数据流

```
EPUB 章节 HTML
  │
  ▼
TtsTextExtractor（现有：leafBlocks → blocks → sentencesByBlock → 偏移）
  │                        （不动，仅在其结果上扩展）
  ▼
SpeakerTagger（新增）
  ├─ 规则层 SpeakerRuleTagger：引号归一化 → 引文跨度 → 归属启发
  └─ LLM 层   SpeakerLlmTagger：整章 JSON 打标（复用 DeepSeek 调用框架）
  │           输出：与 sentences 平行的 speakers[]（角色名 或 "narrator"）
  ▼
TtsChapter.speakers（扩展数据类，与 sentences 平行）
  │
  ▼
VoiceAssigner（新增）：角色画像 × 音色库画像 → 角色→音色映射
  │           输出：BookVoiceMap（书级，持久化）
  ▼
播放：TtsPlaybackEngine.speakNow()
  → speak(text, rate, utteranceId, voice)      ← 接口小改
  → 各引擎透传 voice（Kokoro/Azure 现成；Piper/系统忽略或映射）
```

关键点：**识别结果挂在 `TtsChapter` 的平行数组上**，播放引擎、队列、进度、
高亮（`sentenceLocation`）全部零改动；改动集中在「数据扩展 + 合成接口签名 + 新模块」。

---

## 3. 数据模型（新增/扩展）

### 3.1 `TtsChapter` 扩展（TtsTextExtractor.kt）
```kotlin
data class TtsChapter(
    val chapterIndex: Int,
    val title: String,
    val blocks: List<String>,
    val speakers: List<String> = emptyList()   // 新增：与 sentences 平行
) {
    // 约定：speakers 为空 ⇒ 全部 narrator（兼容旧缓存/无 LLM）
    fun speakerAt(sentenceIndex: Int): String =
        speakers.getOrNull(sentenceIndex)?.takeIf { it.isNotBlank() } ?: "narrator"
}
```
注意：`sentenceLocation`/`sentenceIndexAt` 等现有方法保持原语义。

### 3.2 角色画像 `CharacterProfile`（新增，ai/ 包）
```kotlin
data class CharacterProfile(
    val name: String,             // 规范名，如 "Gandalf"
    val aliases: List<String> = emptyList(),   // Mithrandir → Gandalf
    val gender: String? = null,   // male/female/unknown
    val ageGroup: String? = null, // child/young/adult/elderly/unknown
    val style: List<String> = emptyList(),     // calm, deep, lively...
    val importance: String = "minor",          // major/medium/minor
    val language: String = "en",               // 硬约束用
    val confidence: Float = 0f,
    val origin: String = "auto"                // auto/local/manual（沿用术语表约定）
)
```
存储：并入现有 `BookGlossary`（`kind = "character"` 的条目扩展字段），
或独立 JSON 文件 `speaker_profiles/<bookId>.json`。**推荐并入术语表**，
复用既有「手动优先于自动」的合并逻辑（`BookGlossaryRepository.importFromProfile`）。

### 3.3 音色映射 `BookVoiceMap`（新增，书级持久化）
```kotlin
data class BookVoiceMap(
    val bookId: String,
    val narrator: Map<String, String> = emptyMap(),  // lang -> voiceId（中英各一）
    val characterVoice: Map<String, String> = emptyMap(),  // 角色名 -> voiceId
    val userLocked: Set<String> = emptySet(),        // 用户锁定项不参与自动分配
    val engine: String = ""                          // 生成时的引擎（Kokoro/Azure…）
)
```
存储：`files/voice_maps/<bookId>.json`（仿 `BookGlossaryRepository` 的
Mutex + 原子写 + `origin=manual` 覆盖约定）。

### 3.4 音色库画像 `VoiceLibrary`（新增）
```kotlin
data class VoiceInfo(
    val id: String,                // "zf_001" / "zh-CN-XiaoxiaoNeural"…
    val language: String,          // zh/en
    val gender: String?,           // male/female（Kokoro 名字先验 / Azure 元数据）
    val style: List<String>,       // 元数据或 LLM 补充描述
    val quality: Float = 0.5f,     // 采样试听评分（可选）
    val source: String             // kokoro/azure/piper/clone
)
```
来源（按成本递增）：
1. **元数据**：Azure `/voices`（性别+风格描述）；Kokoro `/voices` + 名字先验（`zf_`女/`zm_`男/`af_`英文女/`bf_`英文男）。
2. **采样分析**：对候选音色各合成一句样音 → 基频 f0 提取 → 校准性别/音区（一次性离线任务）。

---

## 4. 说话人识别模块（SpeakerTagger）

### 4.1 规则层 `SpeakerRuleTagger`（M1，也作兜底）
输入：`TtsChapter.blocks`（extractor 已归一化空白，天然对齐）。
步骤：
1. **引号归一化**：`“ ” ‘ ’ " '` 统一为 `"` 对；处理英美单/双引号嵌套与跨段引文（段首开、段尾闭 → 合并跨度）。
2. **引文跨度**：在每个 block 内找出 `"..."` 区间 → 区间的句子=对白，区间外=旁白。
3. **归属启发**：
   - 言语动词窗口：`said/asked/replied/whispered/shouted/muttered/cried/answered…`
   - 三种句式：`X said, "…"`（前挂）、`"…", said X`（后挂）、`"…", said X, "…"`（中插，两个引文都归 X）
   - 专名识别：前/后窗口内的大写词（结合术语表 `kind=character` 名单优先匹配）
   - **换段惯例**：连续对白段，无归属时默认「新段=换人」（交替/最近说话人启发）
4. 输出：`speakers[]`；无法归属的对白 → `narrator`。

边界处理（写进单测）：
- 间接引语（`he said that…`）→ narrator；
- 心理活动/斜体/歌谣（LOTR 大量）→ narrator；
- 无引号剧本格式（`NAME: 台词`）→ 单独解析器（M2 后再做）。

### 4.2 LLM 层 `SpeakerLlmTagger`（M2）
复用现有 DeepSeek 调用框架（`AiModels`/`ContextAnalyzer` 同款）。
**【已确认决策 D1】**：打标与「AI 语境档案」共用同一套 DeepSeek 调用基础设施与提示词管理，
且**语境档案书级生成时顺带产出角色画像表**（书级一次，含性别/年龄/风格/重要性）；
章节说话人归属则**按章独立请求**（章节级，文本量大不能并入书级档案）。
两者共用：网络层、Key 管理、失败降级、结果缓存。
输入：章节文本 + 引文索引（规则层产物）+ 角色表（术语表 character 条目）。
输出 JSON（逐段逐引文归属）：
```json
{"paragraphs": [
  {"p": 3, "speaker": "narrator"},
  {"p": 4, "quotes": [{"q": 0, "speaker": "Gandalf", "confidence": 0.97},
                      {"q": 1, "speaker": "Frodo",   "confidence": 0.88}]}
]}
```
处理规则：
- **校验**：`speaker` 必须 ∈ 角色表（别名先归一）∪ {narrator}；不在表内或 `confidence < 阈值` → narrator；
- **缓存**：书级 `speaker_cache/<bookId>/<chapter>.json`；增量——只对未打标章节请求；
- **降级**：无 Key / 失败 → 规则层结果（M1 产物）；
- **对齐**：按「段索引 + 引文序号」映射到 `sentencesByBlock`（**不做文本匹配**，避免归一化差异漂移）；规则层直接在 blocks 上跑，天然对齐。

### 4.3 与 TtsTextExtractor 的对接
- `SpeakerRuleTagger` 在 `TtsTextExtractor.chapter()` 产出 `blocks` 之后、构造 `TtsChapter` 之前执行，填充 `speakers`；
- LLM 结果经「段/引文索引 → 句子序号」换算后同样填 `speakers`；
- `TtsTextExtractor` 的 session 缓存 key 不变（`bookId:chapter`），但缓存对象升级为带 `speakers` 的版本（旧缓存失效一次即可）。

---

## 5. 音色分配模块（VoiceAssigner）

### 5.1 输入
- `CharacterProfile` 列表（术语表，自动+手动合并后）；
- `VoiceLibrary` 当前引擎可用音色（如 Kokoro `zf_/zm_/af_/bf_` 全集或 Azure `/voices`）；
- 章节共现统计（跨章累加）：`adjacentSpeakers: Map<Pair<String,String>, Int>`；
- `BookVoiceMap.userLocked`（用户锁定）。

### 5.2 分配算法（伪代码）
```
function assign(profiles, library, cooccur, locked):
  # ① 硬过滤
  for p in profiles:
      p.candidates = library.filter {
          v.language == p.language            # 语言
          && (p.gender==null || v.gender==null || v.gender==p.gender)  # 性别不冲突
          && v.available                      # 已下载/已部署
      }
  # ② 软评分：贴合分（0..1）
  for p in profiles:
      for v in p.candidates:
          score[p][v] = w1*genderMatch + w2*ageMatch + w3*styleSim + w4*importancePrize
  # ③ 贪心分配（按 importance 降序）
  assigned = {}
  for p in sort(profiles, by importance desc, then cooccur-degree desc):
      best = argmax_v( score[p][v]
                       - λ * Σ_{q ∈ assigned ∩ neighbors(p)} sim(v, assigned[q]) )
      if best == null:
          best = localSearch(p, assigned, cooccur)   # 交换/退让一次
      if best == null:
          best = reuseNonCooccurring(assigned)       # 复用（仅限不共现对）
      if best == null:
          best = narratorVoice                       # 最终兜底
      assigned[p] = best
  # ④ narrator：单独选「最中性、最清晰」且与其他角色距离大的音色
  # ⑤ 锁定：locked 角色的映射直接保留，①~③ 跳过它们
  return assigned
```
关键参数：
- `λ`：区分度惩罚权重（可调；共现越强、惩罚越大）；
- 音色间相似度 `sim(v1,v2)`：按 (gender, f0 区间, style 集合 Jaccard) 计算；
- 音色不足时：**复用只允许发生在「不共现」角色对**；主角/旁白绝不降级复用。

### 5.3 一致性
- `BookVoiceMap` 书级持久化，跨章节不重算；
- 新增章节出现新角色 → **增量分配**（只给新角色分配，不动旧映射）；
- 用户手动改过的角色（`userLocked`）永不被自动分配覆盖；
- 同系列书（可选 `seriesKey`）共享角色映射。

---

## 6. 合成接口与播放引擎改动

### 6.1 `TtsSynthesizer`（TtsSynthesizer.kt）
```kotlin
interface TtsSynthesizer {
    fun speak(text: String, rate: Float, utteranceId: String, voice: String? = null)
    ...
}
```
各实现：
- **CloudTtsSynthesizer / OpenAiCompatTtsBackend**：`voice` 非空则覆盖 `serverVoice` 进请求体（Kokoro 服务器已支持任意 voice ID，Azure 同理）。**这是多角色主引擎。**
- **SherpaTtsSynthesizer（Piper）**：忽略 voice（仅中英自动切换）；VoiceAssigner 对 Piper 只允许 2 音色或禁用多角色。
- **SystemTtsSynthesizer**：voice 非空则 `setVoice(voice)`（沿用现有 `voicesByName` 逻辑，找不到回退默认）。

### 6.2 `TtsPlaybackEngine`（TtsPlaybackEngine.kt）
- `speakNow()` 增加一步：`val voice = voiceMap?.get(speakerOf(sentenceIndex))`，
  传入 `synthesizer.speak(text, rate, utteranceId, voice)`；
- `utteranceId` 不变（`book:chapter:sentence:attempt`）→ 回调去重、进度、高亮逻辑零改动；
- 引擎本身不需要知道「谁在说话」，只透传 voice 字符串。

### 6.3 听书服务（TtsPlaybackService.kt）
- 启动/切章时加载 `BookVoiceMap`（`VoiceMapRepository.load(bookId, engine)`）；
- 引擎切换（reconfigure）时重载 VoiceMap（音色库变了要重分配，但 `userLocked` 保留）。

---

## 7. 术语表整合（复用现有机制）

- `ContextAnalyzer`（AI 语境档案）生成时**顺手输出角色画像**（性别/年龄/风格/重要性）：
  新增 `CharacterProfile` 列表并入 `BookContextProfile`（现有 `characters` 是 `ContextTerm`，可扩展或并列）；
- `BookGlossaryRepository.importFromProfile` 扩展：导入角色画像（沿用 `origin=manual 优先`）；
- 手动编辑：书架 → 本书术语表 → 角色条目新增「性别/风格/音色」编辑 + 试听；
- `SpeakerLlmTagger` 的「角色表」直接读 `BookGlossary`（`kind=character`）。

---

## 8. UI（听书设置 / 书级入口）

1. **多角色开关**：听书设置 →「多角色音色」（默认关）。
   【已确认决策 D2】：**仅 AZURE / 火山 / 自建服务器（OpenAI 兼容，含 Kokoro 与 IndexTTS）可用**；
   Piper 与系统语音模式下开关**置灰**并说明「本地 Piper 只有 2 个内置音色，系统语音音色不可控，均不支持多角色」。
2. **角色列表**：每行 = 角色名 + 当前音色 + 试听按钮（用该角色一句样句合成试听）。
3. **音色选择**：下拉候选 = VoiceAssigner 推荐 + 全库；选定即写 `userLocked`。
4. **旁白音色**：中英各一个下拉。
5. **状态提示**：LLM 打标中 / 无 Key 已用规则模式 / 音色不足已复用（Toast/角标）。
6. **（可选 M4+）** 长按阅读页句子 →「此人说话/旁白」→ 触发该句归属修正 + 增量重打标。

---

## 9. 测试计划

### JVM 单测（test/，沿用现有 JUnit4 风格）
| 模块 | 用例 |
|---|---|
| SpeakerRuleTagger | 引号归一化（美/英/弯/嵌套/跨段）；前挂/后挂/中插归属；换段惯例；间接引语→旁白；无归属→narrator；心理/歌谣→旁白 |
| SpeakerLlmTagger | JSON 解析（正常/缺字段/非法角色名→narrator）；别名归一；置信度阈值；缓存读写；增量只补新章节 |
| VoiceAssigner | 硬过滤（语言/性别/不可用）；软评分排序；共现贪心（相邻角色音色拉开）；锁定项跳过；复用仅限不共现；增量分配不动旧映射 |
| VoiceMap/CharacterProfile | JSON 往返；manual 优先合并；userLocked 持久化 |
| TtsChapter | `speakerAt()` 默认 narrator；speakers 与 sentences 平行（长度一致） |

### 仪器测试（androidTest）
- 规则层端到端冒烟：种入含引号对白的 EPUB → 听书 → 断言 TTS 请求带角色 voice（需要可注入后端）。

### 真机/服务器验证（重点）
- **Kokoro 服务器**：`POST /v1/audio/speech` 带不同 `voice`（`zf_001`/`zm_009`/`af_maple`）→ 日志确认 `X-Voice` 变化 + 手机侧逐句 AudioTrack 正常；
- 端到端：M1 双音色（旁白=`af_maple`、对白=`af_sol`）听一段英文书，听感区分度人工确认；
- M2 后：用测试书《The Lantern Library》/ LOTR 章节人工抽验归属准确率（目标 LLM ≥ 90%，规则 ≥ 60%）。

---

## 10. 里程碑与工作量（估算）

| 里程碑 | 内容 | 工作量 |
|---|---|---|
| **M1** | `speak(voice)` 接口 + Kokoro 透传验证；SpeakerRuleTagger（引文+前/后挂+换段）；双音色端到端 | 1–2 天 |
| **M1.5** | IndexTTS 2.5 服务器部署（8001）+ 中英文实测对比 + 单角色克隆冒烟（D3） | 0.5–1 天 |
| **M2** | LLM 打标（并入语境档案框架：书级画像 + 按章归属 + 校验 + 缓存 + 降级）；术语表角色表接入 | 1–2 天 |
| **M3** | VoiceLibrary（元数据 + 名字先验 + 克隆音色画像）；VoiceAssigner（硬过滤+软评分+共现贪心+锁定）；BookVoiceMap 持久化 | 1–2 天 |
| **M4** | UI（开关 + 角色列表 + 试听 + 旁白音色；Piper/系统置灰按 D2）；增量分配；长按改说话人（可选） | 1–2 天 |
| **合计** | | **约 6–9 个工作日**（M1 之后每步独立可用） |

---

## 11. 风险与决策点

**已确认决策：**
- **D1**：LLM 打标并入「AI 语境档案」调用框架——书级角色画像随语境档案一次生成，章节归属按章独立请求。
- **D2**：多角色音色对 Piper / 系统语音引擎**禁用**（音色数量不足/不可控），仅云引擎与自建服务器可用。
- **D3**：**上 IndexTTS 2.5** 作为多角色克隆音色引擎（见第 12 节）。

**剩余风险与决策点：**

1. **英文归属质量是最大变量**：M1 规则层对 LOTR 这类「思维/歌谣/跨段引文」密集的书只有 ~60% 准确率；M2 LLM 是关键（目标 ≥90%）。
2. **逐句换音色延迟**：Kokoro 每句合成 1–3s（CPU），角色切换无额外开销；Azure 网络延迟 ~1s；可接受。
3. **音色数量约束**：Kokoro 100+ 音色够 10+ 角色；Piper 只 2 个 → 已按 D2 禁用；系统 TTS 音色数量依赖引擎（OPPO 引擎已坏，Google TTS 待验证）。
4. **用户期望管理**：自动分配提供「推荐默认」，但必须允许手动覆盖——自动再准，用户对角色声音有自己的想象。
5. **一致性 > 单次正确**：映射持久化 + 锁定，避免「第三章角色换了音色」的听感崩坏。
6. **英文主引擎实测（2026-08-20 已完成客观部分）**：`scripts/tts_compare.py` 同批句子实测（RTX 5070 Ti）——
   Kokoro 英文 0.45 s/句、中文 0.77 s/句；IndexTTS 2.5 英文 2.58 s/句、中文 3.17 s/句（每字 0.057 / 0.119 s）。
   即 Kokoro 约快 5–6 倍且 CPU 即可跑，IndexTTS 单句 1.5–4.7 秒仍可用于在线逐句合成（全书缓存对其保持禁用）。
   **人工试听结论（2026-08-20，已确认）**：中英文默认引擎都选 **IndexTTS 2.5**——英文用参考音色
   `first_3s_1.wav`（样音 `indextts_first_3s_1_en_0.mp3`），中文用 `voice_03.wav`（样音
   `indextts_voice_03_zh_4.mp3`）；Kokoro 转为「快速/无 GPU 兜底」引擎。风险 #6 关闭。

---

## 12. IndexTTS 2.5 接入（已确认 D3）

**硬件前提（已核实）**：本机 RTX 5070 Ti / 16GB 显存 / 驱动 610.74 —— 满足 IndexTTS 2.5 要求（8GB+ 显存）。

### 12.1 部署形态
- `tts-server/` 目录**并列新增** IndexTTS 服务（OpenAI 兼容包装，`POST /v1/audio/speech`），端口 `8001`；
- 现有 Kokoro 继续跑 `8000` 不动（英文兜底/对比基线）；
- 应用侧「自建服务器」地址可切换 `8000`/`8001`（设置里加「引擎：Kokoro / IndexTTS」选择，或直接改地址）。

### 12.2 音色库接入（架构不变，只换来源）
- `VoiceLibrary.source = "clone"` 的新音色成员：每个克隆音色 = 一个 `voiceId`（如 `clone_gandalf`）；
- 角色 → 参考音频目录：`tts-server/voices/<voiceId>.wav`（3–10 秒参考音频，零样本克隆）；
- 播放时请求体 `voice=clone_gandalf` → IndexTTS server 按 voiceId 载入参考音频克隆合成；
- **VoiceAssigner 零改动**：克隆音色只是音色库成员，硬过滤/软评分/共现区分照常（克隆音色画像由采样 f0 分析 + LLM 描述生成）。

### 12.3 使用红线（沿用此前讨论）
- 参考音频用**自备/有授权**素材（用户自己的声音、自制角色音频）；
- 不内置、不推荐、不引导克隆真人/演员声音；提供「通知-删除」机制 + AI 合成标识；
- **LICENSE 已核对（2026-08-20）**：IndexTTS2 采用《bilibili 模型使用许可协议》——全球、非独占、不可转让、免费使用；
  仅当「月活 > 1 亿或上一自然年营收 > 1 亿人民币」才需另行申请商业许可（本项目规模无需）；
  须保留原始版权声明与许可副本、发布衍生品需声明与原权利人无关、不得用其输出改进其他商用 AI 模型、
  禁止高风险场景、输出内容合规与侵权责任自负；适用中国法律，上海仲裁。

### 12.5 实施结果（2026-08-20）

- 服务：`indextts_server.py` 增加 `INDEX_VOICES_DIR`（默认 `voices/`）与 `voices/voices.json` 画像清单，
  `GET /voices` 同时返回兼容的 `voices`（字符串数组）与新增的 `voice_profiles`（id/label/language/gender/style）；
  `voice` 参数解析顺序为「绝对路径 → 克隆音色目录 → 安装目录 examples/ → 默认参考音频」。仓库权威副本在 `tts-server/indextts/`。
- App：`ServerVoice` 承接服务器元数据 → `ServerVoiceStore` 缓存 → `VoiceLibraryLoader` 与命名先验合并，
  于是克隆音色能参与 M3 的语言/性别硬过滤；`VoiceNaming` 新增 `clone_<角色>_<lang>_<m|f>` 识别并忽略音频扩展名。
- 工具：`scripts/make_clone_voice.py`（剪辑 + 登记，强制 `--consent` 声明素材来源）、`scripts/tts_compare.py`（中英实测报告）。
- 红线落地：仓库不含任何克隆参考音频（`tts-server/voices/` 音频已 gitignore）；多角色面板常驻「AI 合成 + 仅用自备/授权素材」提示；
  历史测试素材 `artifacts/first_3s.wav`（取自商业音乐轨）标记为**不得用于发布**。

### 12.4 里程碑插入
- M1 后插入 **M1.5：IndexTTS 服务器部署 + 中文/英文实测对比**（半天–1 天）：
  - 中文章节：IndexTTS 2.5 效果确认（预期明显优于 Kokoro）；
  - 英文章节：与 Kokoro `af_maple`/`bf_*` 对比，决定英文主引擎；
  - 单角色克隆冒烟：`clone_gandalf` 端到端出声。

---

## 13. 系统引擎多角色（M5）：用户标注音色库 + Google TTS 优先（设计，未实施）

> 背景：D2 原判定「系统语音没有可控音色库」（`MultiVoiceSupport.engineSupportsMultiVoice`
> 只认三类云引擎）。修订为：**系统引擎的音色库由用户手动标注构建**——分配器
> （`VoiceAssigner`）只消费 `VoiceLibrary`，而 `VoiceInfo` 的元数据全部可空容忍，
> 用户标到哪算哪。sherpa/Piper 本地引擎不在本期范围（Piper 多说话人 `sid=0`
> 是另一笔技术债，另案）。

### 13.1 设计基准与红线

- **Google TTS 为行为基准与推荐引擎**：`getVoices()` 稳定、元数据齐全、逐句
  `setVoice` 服从度高。但**零硬编码依赖**——所有 Google 特有行为只用于「探测 +
  引导」，代码路径必须对任意系统引擎成立。
- **不自动切换用户的系统 TTS 引擎**：检测到更优引擎只提示，切换动作由用户在
  系统设置里完成。
- **不做性别自动猜测**：系统音色名（如 `cmn-cn-x-ccc-local`）无可靠命名先验，
  猜错比留空危害大（硬过滤会据此排除/选中）。性别一律由用户标注。
- **离线红线沿用**：网络型 voice 继续被过滤（`SystemTtsVoices.readVoices` 已做），
  标注列表只出现离线可用音色。

### 13.2 数据模型

```kotlin
/** 用户对一条系统音色的标注；voiceName = android.speech.tts.Voice#getName()。 */
data class SystemVoiceAnnotation(
    val voiceName: String,
    val gender: String = "",      // "male" / "female" / ""（未知）
    val enabled: Boolean = true   // 不参与分配但保留标注
)
```

- `ageGroup` / `style` 本期不标（系统音色没有风格标签来源），`VoiceInfo` 对应字段
  留空即可，分配器按「未知=中性分」处理。
- **language 不标注**：从 `SystemVoiceInfo.locale` 自动归一化。`SystemVoiceInfo`
  已有 `isChinese`/`isEnglish` 集合（含 `cmn`/`chn`/`usa` 等 ISO 639-3 与厂商
  非标码，见 `tts/.../SystemTtsVoice.kt`），新增派生属性：

```kotlin
val assignerLanguage: String get() = when {
    isChinese -> "zh"
    isEnglish -> "en"
    else -> ""                 // 其它语言=多语言容忍，参与分配但不做语言硬过滤
}
```

### 13.3 存储与库构建（完全复刻 ServerVoiceStore 模式）

- 新增 `SystemVoiceStore`（仿 `ServerVoiceStore`）：SharedPreferences
  `"system_voice_annotations"`，key = `"voices@" + enginePackage`——换系统引擎后
  旧标注互不干扰、也不误用。存两样东西：
  1. **音色快照**：`List<SystemVoiceInfo>`（name/locale/isNetwork 过滤后），
     解决 `SystemTtsVoices.load` 回调式异步与 `VoiceLibraryLoader.load` 同步签名
     的矛盾；
  2. **标注表**：`List<SystemVoiceAnnotation>`。
- `VoiceLibraryLoader.refresh()` 增加 SYSTEM 分支：用 `suspendCoroutine` 包装一次
  `SystemTtsVoices.load` 探测，结果连同已有标注写入 `SystemVoiceStore`（调用方
  `MultiVoiceSupport.library` 本就是 suspend，零接口改动）。
- `VoiceLibraryLoader.load()` SYSTEM 分支改为：

```kotlin
TtsEngineMode.SYSTEM -> VoiceLibrary(systemVoices(context, settings), engine)

// 快照 × 标注 → VoiceInfo(source = "system")；quality 沿用默认 0.5
```

### 13.4 门控修订（D2 → D2'）

```kotlin
fun engineSupportsMultiVoice(settings: CloudTtsSettings,
                             systemUsableVoices: Int = 0): Boolean =
    settings.mode == TtsEngineMode.AZURE ||
        settings.mode == TtsEngineMode.VOLC ||
        settings.mode == TtsEngineMode.OPENAI_COMPAT ||
        (settings.mode == TtsEngineMode.SYSTEM && systemUsableVoices >= 2)
```

- 「可用」= `enabled` 且 gender 已知且 language 可归一化（zh/en 或空）。**≥2 才放行**：
  只有 1 个音色时分配无从谈起，此时面板维持置灰并引导去标注。
- 默认参数 `= 0` 保证云引擎路径与全部现有调用点/单测零改动。
- `MultiVoiceStatusKind` 复用 `NO_LIBRARY` 表达「还没标够」，UI 文案区分
  「引擎不支持」与「去标注」两种引导。

### 13.5 引擎探测与引导

新增纯查询 helper（建议落 `SystemTtsVoice.kt` 内，如 `SystemTtsEngines`）：

| 探测 | API | 用途 |
| --- | --- | --- |
| 当前引擎包名 | `Settings.Secure.getString(resolver, TTS_DEFAULT_SYNTH)`（公开常量；实施时发现 `TextToSpeech.getCurrentEngine()` 不存在于 SDK，`getDefaultEngine()` 返回的是系统默认而非用户所选，均不可用） | 标注存储 key、面板显示当前引擎名 |
| Google TTS 是否已装 | PackageManager 查 `com.google.android.tts` | 三态引导 |

面板三态（SYSTEM 模式下）：

1. 当前就是 Google TTS → 正常流程，状态行标注「推荐引擎」；
2. 已安装但未启用 → 提示文案 + 跳系统 TTS 设置的 intent（不自动切）；
3. 未安装 → 安装引导文案（说明需自行下载语音数据）。三段文案走
   `strings.xml`（zh 默认 + values-en，key 形如 `multi_voice_system_*`）。

### 13.6 UI（改 MultiVoiceSection，不动框架）

- 现状「Piper/系统语音下开关置灰 + D2 说明」改为：**Piper 维持置灰**；SYSTEM
  改为条件置灰 + 「标注系统音色」入口按钮。
- 标注对话框：每行 = 音色 `displayName()` + 性别单选（男/女/未知）+ 启用开关，
  底部保存。保存后即时重算门控，≥2 即解灰开关。
- 角色列表/试听/锁定逻辑零改动：`VoiceAudition` 走当前引擎合成，系统引擎已支持
  per-utterance voice 参数。

### 13.7 播放侧

**零接口改动**。`SystemTtsSynthesizer.speak(text, rate, utteranceId, voice)` 已支持
逐句 voice 覆盖，回退链（`setVoice` 失败 → `setLanguage` → 引擎默认）已存在。
已知风险不变：部分 OEM 引擎对 `setVoice` 静默忽略——缓解手段是 13.6 的逐音色
试听（用户可自行确认每个音色真的出不同声）+ 13.8 真机矩阵。

### 13.8 测试计划

JVM 单测（先写，纯逻辑全部可测）：

- `SystemVoiceAnnotationStoreTest`（Robolectric）：读写 round-trip、按引擎包名隔离、坏 JSON 容错；
- `assignerLanguage` 归一化：`zh/cmn/yue/chn→zh`、`en/eng/usa→en`、`fr→""`；
- 库构建 merge：标注覆盖缺省、未知性别留空、disabled 音色不入库；
- `MultiVoiceSupportTest` 扩展：SYSTEM + <2 可用 = false / ≥2 = true / 云引擎不受默认参数影响；
- `status()` 映射：SYSTEM 未标够 → `NO_LIBRARY`。

真机验证矩阵（缺一不可，结论进 `VALIDATION.md`）：

| 引擎 | 验证点 |
| --- | --- |
| Google TTS（基准） | 逐句 setVoice 切换生效、角色声可辨、无串声 |
| 一台国产 ROM 自带引擎 | 回退链不静音、`getVoices` 延迟重试路径 |
| ColorOS（已知 `chn` 非标码） | 中文音色可被归一化为 zh 并参与分配 |

### 13.9 里程碑拆分

| 步骤 | 内容 | 估时 |
| --- | --- | --- |
| M5a | 数据层（annotation store + 快照缓存）+ 库构建 + 门控 + 全部 JVM 单测 | ✅ 2026-08-21 |
| M5b | 标注对话框 + 试听接线 + 开关条件置灰 | ✅ 2026-08-21（`VoiceAudition` 增加 SYSTEM 直播路径；验收修正：`onFinished` 统一走字段注册、复用 `TtsLanguage.of` 去重 `isHan`） |
| M5c | 引擎探测 + 三态引导文案（strings.xml zh/en 双份） | ✅ 2026-08-21（`ACTION_TTS_SETTINGS` 不在公开 SDK，用字面值；javap 查证） |
| M5d | 真机验证矩阵 + VALIDATION.md + 记忆文件回写 | 部分：VALIDATION.md 已记录，单测 325 通过、并存包构建通过；**真机矩阵待设备连接**（Google TTS 基准 / 国产 ROM / ColorOS 三项 + 两条交互遗留） |

M5b 验收遗留（不阻塞，M5d 真机时顺带确认）：标注对话框关闭时若试听仍在播，音频会继续到句末（无可见停止入口）；对话框打开期间用户切换系统引擎的极端场景未处理。

### 13.10 明确不做

- 性别/风格的自动推断（名字先验对系统音色不可靠）；
- Piper 多说话人 `sid` 映射放开（另案，可与本节并行）；
- 自动切换或代下载任何系统 TTS 引擎；
- f0 采样分析（§3.4 遗留项，继续搁置）。
