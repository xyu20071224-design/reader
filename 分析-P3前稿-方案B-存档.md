# P3 分析：让 AI 翻译输出句级边界

> 分析日期：2026-11-01
> 目的：消除 `SPLIT-007` 的根因（句界靠推断 → 偏移 → 静默错）
> 结论：**值得做，但不需要改 JSON schema；用一个更小的改动就能拿到 80% 收益**

---

## 1. 现在句界是怎么来的

```
AI 返回 {"segments":[{"i":段号,"t":"该段中文译文"}]}
  → extractValidated 取出每段一条字符串
  → mergeBatchTranslations 按段合并 → 每原始段落一条字符串
  → writeTranslationBook 写成 <p>...</p>
  → attachGenerated 重新用 TtsTextExtractor 抽段落
  → AiTranslationAligner 拿段落级文本
  → **自己用 SentenceSplitter 重新切句**   ← 句界在这一步被"推断"
  → 段内 1:1 顺序对齐
```

**问题**：`SentenceSplitter` 对说话人标签（`他说。`）的切分与英文句界不一致时，
中文句数会与英文不同 → 档 1 的 1:1 对齐整体偏移一位 → 静默错
（实测第 1 章 2 条，见 `SPLIT-007`）。

---

## 2. 三个候选方案

### 方案 A：改 JSON schema 为句级（**不推荐**）

prompt 改成 `{"segments":[{"i":段号,"s":[{"i":句号,"t":"译文"}]}]}`，直接返回句级结构。

| 项 | 影响 |
| --- | --- |
| prompt + `extractValidated` | 需重写校验（数字锚点、术语、长度比都要改成句级） |
| `TranslationBatch` / `mergeBatchTranslations` | 结构从 `List<String>` 变 `List<List<String>>` |
| 检查点格式 | `paragraphs:[String]` → 需嵌套结构，**所有已产出的检查点作废** |
| `writeTranslationBook` | 需在 XHTML 里保留句界信息（或另存 sidecar） |
| `ManualTranslationIo` | 手工导入格式同步改，**用户已导出的任务文件作废** |
| 档案格式 | `attachGenerated` 要能接收句级输入，`TranslationMemory` 可能要加字段 |
| **改动面** | **5–6 个文件 + 2 处格式不兼容** |

**否决理由**：改动面大、破坏两处已持久化格式（检查点 + 手工任务包），
而收益只是「消灭 2 条错判」。

### 方案 B：段落内用换行分隔句子（**推荐**）

prompt 追加一句要求：

> 每段译文内，**每一句翻译单独占一行**（用 `\n` 分隔），句数与原段英文句数一致。

`extractValidated` 只做一件事：把 `t` 按 `\n` 切分，得到句级列表；
`mergeBatchTranslations` 用 `\n` 拼接（而非空串）。

| 项 | 影响 |
| --- | --- |
| prompt | 只加一句（`buildUserPrompt` / `buildPolishUserPrompt` / `TRANSLATION_SYSTEM_PROMPT`） |
| `extractValidated` | 增加句数校验（可选，见下） |
| `mergeBatchTranslations` | `separator` 从 `""` 改为 `"\n"` |
| 检查点 | **格式不变**（仍是 `paragraphs:[String]`，只是字符串内部多了 `\n`）→ **旧检查点仍可读**，只是没有句界信息（退化为现状） |
| `writeTranslationBook` | `<p>` 内保留 `\n`（HTML 会折叠，需 CSS `white-space: pre-line` 或转成 `<br/>`） |
| `attachGenerated` | 抽取段落时**保留换行**（`TtsTextExtractor` 现在是 `\s+→空格`，会吃掉换行！**这是关键障碍**） |
| `AiTranslationAligner` | 新增「若段落含 `\n` 则按 `\n` 切句，否则退化为 `SentenceSplitter`」 |
| **改动面** | **4–5 个文件，无格式破坏** |

**核心障碍**：`TtsTextExtractor` 把文本归一化成单空格（`\s+ → 空格`），
换行会被吃掉。需要保留一个「带句界的段落」通道。

### 方案 C：插入特殊分隔符（**不推荐**）

在译文里插 `‖`，后续剥离。

- 优点：不受 `TtsTextExtractor` 归化影响（`‖` 不是空白）
- 缺点：**模型可能漏插或位置不对**；且 `‖` 会进入译文正文，若剥离失败会显示给用户
- **风险高于方案 B**

---

## 3. 推荐：方案 B + 一个「句界旁路」

### 3.1 关键洞察

**不需要改 `TtsTextExtractor`。** 让句界信息走一条**并行通道**：

```
译文段落字符串（现状，用于 XHTML 展示）
+
checkpoint 里新增可选字段 "sentenceBreaks": [[段0的句长...], ...]   （句级边界，可选）
```

`attachGenerated` 若发现该字段，就把它传给 `AiTranslationAligner`（作为「构造性句界」）；
若没有（旧检查点 / 手工导入），退化为现在的 `SentenceSplitter`。

**这样**：
- `TtsTextExtractor`、`writeTranslationBook`、档案格式**全都不用改**
- 旧检查点**继续可用**（只是没有句界信息，退化为现状）
- 手工任务包**继续可用**

### 3.2 落地步骤

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **P3-1** | prompt 加「每句单独一行」；`extractValidated` 按 `\n` 切分并**校验句数**（不等则视为失败重试，重试轮放行） | 单测：mock 响应含 `\n` → 切分出正确句数 |
| **P3-2** | `mergeBatchTranslations` 用 `\n` 分隔；checkpoint 写入 `sentenceBreaks` | 单测：检查点往返，旧格式仍可读 |
| **P3-3** | `AiTranslationAligner` 接受可选「句界提示」：有则按提示切，无则退化 `SentenceSplitter` | 第 1 章静默错应降为 **0** |
| **P3-4** | 端到端：真实翻译 1 章，验证句界一致 | 第 1 章 + 第 3 章静默错 0，覆盖率不下降 |

### 3.3 收益预估

| 项 | 现在 | P3 后（预估） |
| --- | --- | --- |
| 第 1 章静默错 | 2 条（0.17%） | **0** |
| 第 1 章覆盖率 | 59.92% | 可能略升（不再有句界偏移导致的 UNCERTAIN） |
| 第 3 章 | 71.64% / 0 错 | 不变或略升 |
| `UNALIGNED`（第1章 67 条） | 多因句数不等 | 可能减少（句数天然相等） |

---

## 4. 风险

| 风险 | 严重度 | 缓解 |
| --- | --- | --- |
| **模型不按行返回**（漏 `\n` 或合并句） | **高** | ① 句数校验，不等则重试；② 重试仍败则**退化为现状**（不阻塞整本）；③ 记录退化率 |
| 中文译文里本来就有换行 | 中 | 传入前把原文的 `\n` 归成空格，只由模型产生的 `\n` 表示句界 |
| 手工任务包无句界 | 低 | 退化到 `SentenceSplitter`（现状行为） |
| 已生成译本（XHTML）无句界 | 低 | 同上，退化 |
| 译文长度校验（`sourceWords*0.2~8`）受影响 | 低 | 校验前先去掉 `\n` |

---

## 5. 建议

**做方案 B 的 P3-1 + P3-3（最小闭环），暂不做 P3-2/P3-4。**

理由：
1. **P3-1 + P3-3 就能验证「模型能不能可靠按句返回」**——这是最大的不确定性，必须先测
2. 若模型可靠性差（如退化率 > 20%），则方案 B 不成立，及时止损
3. P3-2（checkpoint 扩展）只有在确认可靠性后才值得做

**第一步实验**：用现有 35 句对的玩具书，改 prompt 加「每句一行」，让子代理重翻一遍，
看**模型输出的 `\n` 切分数是否等于英文句数**。这一步不需要改任何代码。
