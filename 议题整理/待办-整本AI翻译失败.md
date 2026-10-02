# 待办 · 整本 AI 翻译总是失败（`Q1-b02`）

> **状态：已修复（代码级，路线 A，2026-10-02，工作区未提交）** —— §0 是修复记录；§1–§6 保留 2026-09-25 的挂起分析与决策点，作历史证据。
> **出处**：issue #1 正文第 2 条「对于长文本的全文 ai 翻译不可用，总是返回失败」；用户 2026-09-12 明确答复「仍失败」。
> **权威状态**：`canonical_status.py` / `canonical_status.json` 已于 2026-10-02 回填为 `已修复`（全 34 条 = 已修复 22 / 部分实现 6 / 未修复 2 / 已消除 4）。⚠️ `01-台账.md`、`02-已修复排除清单.md`、`03-bug与功能需求.md`、`最终分组结果.md`、`代码核查-2026-09-25.md` 是 09-12/13/25 的**日期快照**，按项目惯例**不追改**（两次既往回填 `d74ed96` / `69098e7` 也只改 py + json）。
> **复核报告**：`议题整理/代码核查-2026-09-25.md` §2（B 档）与 §6。

## 0. 修复记录（2026-10-02，路线 A）

**方案**：超长段按**句子边界**切成 ≤ 每批上限的译块，回写时按原段合并。

- **拆批**：`AiBookTranslator.groupIntoBatches` —— 段落 `length > maxCharsPerBatch` 时调 `SentenceSplitter.split(p, maxSentenceLength = 上限)` 分句并贪心合并成译块（单句超限由 SentenceSplitter 在词/字符边界硬切兜底），再按译块贪心装批，**每批 ≤ 上限**；旧实现让单个超长段独占一批、净额可远超上限（即 §2.1）。
- **数据模型**：`TranslationBatch` 新增 `sourceParagraphIndices`（译块 → 原段，默认 = `paragraphIndices`）；`paragraphIndices` 语义改为「章内译块号」，未拆分的章与原段落下标逐位一致（旧检查点、手动 IO 在普通书上零变化）。
- **回写合并**：新增 `AiBookTranslator.mergeBatchTranslations`，同原段译块按序用空串拼接；某批失败（`null`）→ 该段**整段回退英文原文**，保持段落级占位与英文侧 1:1。
- **接线**：`AiTranslationRepository.translateBook` / `completeFromCheckpoints`；`AppViewModel` 未翻译段计数改按「(章, 原段)」去重。

**改动文件**：
- `src/shared/src/main/java/com/linguareader/shared/ai/AiBookTranslator.kt` —— `groupIntoBatches` / `splitOversizedParagraph` / `mergeBatchTranslations` / `TranslationBatch.sourceParagraphIndices`
- `src/app/src/main/java/com/linguareader/app/ai/AiTranslationRepository.kt` —— 两个调用点
- `src/app/src/main/java/com/linguareader/app/AppViewModel.kt` —— 未翻译段计数
- `src/shared/src/main/java/com/linguareader/shared/ai/ManualTranslationIo.kt` —— 编号措辞
- 测试：`src/shared/src/test/java/com/linguareader/shared/ai/AiBookTranslatorTest.kt`（旧契约重写 + 8 例）、`src/app/src/test/java/com/linguareader/app/ai/AiTranslationRepositoryTest.kt`（estimate `3 → 4`）

**验证（原始 XML 判据）**：`:shared:test` tests=399 / failures=0 / errors=0 / skipped=1；`:app:testDebugUnitTest` tests=443 / failures=1 / errors=0 / skipped=0，唯一失败 `TranslationGoldenReplayTest` 为既有红（已在 HEAD `e40ace9` 隔离 worktree 复现同样「金标准回归失败 11 条」）。

**残留**：真机 + 真 Key 端到端未跑（JVM 层已覆盖机制）；含超长段的旧书重跑会按既有校验失效重翻受影响章节；手动 IO `FORMAT_VERSION` 刻意不 bump（见 §4 结论）。

## 1. 现象

长文本的整本 AI 翻译「总是返回失败」。这不是推测：`议题整理/G3-裁决记录.md` 记录了 2026-09-12 用户对该条的选择是「**仍失败**」。

## 2. 已查明的机制（2026-09-25 代码复核）

### 2.1 单个超长段落成批无上限（有量化证据）

`src/shared/src/main/java/com/linguareader/shared/ai/AiBookTranslator.kt:76-83` 的 `groupIntoBatches` 是整本翻译**唯一**的批次规划：

- 6000 字符上限只约束「**新段**能否加入」（`if (indices.isNotEmpty() && chars + paragraph.length > maxCharsPerBatch)`）；
- **单个超长段既不会被切分，也不会因超限触发换批**，只能独占一批，净额可远超上限；
- `14e2828`（2026-09-13）实测：**6000 上限下产出过 258890 字符的单批**。

### 2.2 超长段在真实导入路径上可达

`src/shared/src/main/java/com/linguareader/shared/importer/TextImporter.kt:117-121`：TXT 只按**空行**分段、单换行并成空格 → **无空行的长文本，整章即一个巨大段**。

### 2.3 当前行为被测试锁成「设计」

`AiBookTranslatorTest.kt:39` 把「超长段独立成批（不切分）」写成期望值 → 这不是疏漏，是**尚未裁决**的设计选择，改它要同时改这条契约测试。

### 2.4 用户看到的失败出口

`src/app/src/main/java/com/linguareader/app/ai/AiTranslationRepository.kt:273-291`：单批失败以原文占位续跑，**连续 3 批失败**抛中止（连败断路器，`349644e`）；`AppViewModel.kt:1180-1190` 把它变成用户可见的「生成已中止」。

## 3. 已做过的都不算根治

| 提交 / 功能 | 作用 | 局限 |
| --- | --- | --- |
| `349644e` | 连败断路器：系统性失败不再把剩余章节全烧成英文占位 | 只是止损，不解决超长批本身 |
| `ba46859` | 书架可重新生成补齐译本 | 同上 |
| `b1d352c` | 整本管线加固「真机验收」 | 验收用伪服务商模拟失败，**只验了中止路径**，没证明长书能跑通 |
| `1c83006` / `acd73b7` | **手动导出任务文件 → 外部 agent 翻译 → 导入结果** | 是绕开自动链路的替代工作流，**不是修复**；可作为用户侧的临时出路 |

## 4. 决策点（2026-09-25 挂起的原因）—— 已裁决：路线 A（2026-10-02）

需要产品口径，不是纯改代码。两条互斥路线：

- **A. 修**：把超长段按句（或按标点/字符上限）切分后再成批，并同步调整 `AiBookTranslatorTest` 的期望。风险：切碎上下文可能降低译文连贯性，需重新评估翻译质量。
- **B. 明确不支持**：在导入/翻译入口提示「无空行的超长文本请先分段」，把现行为写成**文档化契约**。成本低，但等于承认长文本场景不支持。

选哪条取决于「无空行超长文本」在真实使用里有多常见。

**结论（2026-10-02）**：用户裁决走**路线 A**，已实现，见 §0；路线 B 不再采纳。

## 5. 要推进需要什么

1. **一手失败证据**：一次真实失败的日志（在哪个阶段失败、错误文案/码）；
2. **触发书目的导入形态**：是否无空行 TXT、单章字符数量级；
3. 现状只到「机制可达 + 用户主观失败」，**尚未把主观失败与 2.1 的机制对上号**——这是最关键的一步。

**（2026-10-02 更新）**：机制与失败出口已对上（超长段 → 模型上下文超限 → 重试耗尽 → 连败断路器中止整本），无需再等一手日志；修复见 §0。

## 6. 相关位置

- 代码：`AiBookTranslator.kt`（批次规划）、`AiTranslationRepository.kt`（失败出口）、`TextImporter.kt`（分段）、`AppViewModel.kt`（用户可见文案）
- 文档：`议题整理/G3-裁决记录.md`、`议题整理/代码核查-2026-09-25.md`、提交 `14e2828` 正文
