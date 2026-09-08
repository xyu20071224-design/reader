# 译本对齐评估工具链（`src/tools/alignment-eval/`）

译本对齐（F-128 中文译本对照）的**效果**过去只能靠「重放整本 → 生成判定卡 → 人工逐条判
ok/bad」来评估，六轮判定（2026-09-02 ~ 09-03，对齐器 V2→V5）的人工成本没有沉淀成任何
可自动执行的资产，工具本身也散落在 gitignore 的 `artifacts/` 里、还带着搬迁前的旧路径。
本目录是那次评估结论的**永久化**：判定台账入库 + 金标准回归测试 + 复现流程文档。

> 相关记忆：`.agents/memory/ai-context-translation.md`（译本对照实现）、
> `VALIDATION.md` 2026-09-02/09-03 条目（六轮判定的现场记录）。

## 它解决什么

| 问题 | 现在怎么办 |
| --- | --- |
| 改对齐器后，无法自动回答「上轮修好的那批坏没坏回去」 | `TranslationGoldenReplayTest`：重放金标准样本，上轮 ok/ok2 的展示必须逐字保持 |
| 判定结果散在 HTML/CSV/txt/探针脚本里 | `verdict-history.json` 入库（**不含书文**）；全量 fixture 在 `artifacts/alignment-eval/golden-samples.json`（本地） |
| 每轮人工盲判 100 条 | 回归报告直接列出「展示发生变化的样本」，人工只看增量 |
| 评估工具依赖机器上的 Python 与旧路径 | 工具改写成 Kotlin 测试，走仓库自带 Gradle 工具链；Python 只留作历史参考 |

## 三个组件

### 1. 金标准 fixture 生成工具

`src/app/src/test/java/com/linguareader/app/translation/TranslationGoldenFixtureTool.kt`

把六轮判定的散落数据整理成单一 fixture（人工运行；素材缺失的机器上自动跳过）：

```bash
# Linux（仓库根）
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationGoldenFixtureTool" --console=plain
```

产出：

- `artifacts/alignment-eval/golden-samples.json` — 全量 fixture（含英文原文与批准展示，
  **永不入库**：版权 + 它依赖的测试书本来就 gitignore）；
- `src/tools/alignment-eval/verdict-history.json` — **入库**的判定台账：id / 分层 / 章节号 /
  最终判定 / 逐轮判定史，**不含任何书文**。人工判定是已经花掉的成本，丢了不可再生。

重新生成会保留 fixture 里已有的 `approved` 块（按 id 合并），所以「先 bless 再重建」不会丢批准状态。

### 2. 金标准重放回归测试

`src/app/src/test/java/com/linguareader/app/translation/TranslationGoldenReplayTest.kt`

用**生产代码路径**重放：`lotr-book`（app 导入格式）与 `lotr-zh`（解包 EPUB）→
`TtsTextExtractor` 取叶级段落 → `TranslationAligner.align(..., EcdictMeaningIndex)`
（真离线词典）→ `TranslationMemoryIndex.lookup` → 记录「用户点词会看到什么」。

```bash
# 校验（默认）：ok/ok2 样本的展示必须与 approved 完全一致
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationGoldenReplayTest" --console=plain

# 批准（bless）：人工判定完新一轮后，把当前展示写成新的 approved 基线
touch artifacts/alignment-eval/bless.flag      # Windows: New-Item artifacts/alignment-eval/bless.flag
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationGoldenReplayTest" --console=plain
# 测试读完自动删除 flag；随后再跑一次不带 flag 的校验确认基线自洽
```

`bless` 用**文件标志**而不是 `-D` 系统属性：Gradle 的 `-D` 只进构建 JVM，不会自动转发给
fork 出来的测试 JVM，文件标志没有这个坑，两台机器行为一致。

**契约**：

- `verdict = ok / ok2` 的样本 → 当前展示（`level` + 中文）必须与 `approved` 完全一致；
  不一致即失败，输出下一轮人工判定清单。
- `verdict = bad / skip` 的样本 → 只报告与 `approved`（已知坏状态）的差异，不失败。
- 英文原文无任何字母数字的「评估集垃圾」（纯标点切片，应用内不会发起查询）→ 排除在契约外，
  只标注。
- `MISS`（查不到对照）也是一种被批准的展示状态：批准为 MISS 的样本变出展示同样算变化。

### 3. 合成双语语料（真值对齐，跑在 CI）

`src/shared/src/test/java/com/linguareader/shared/translation/SyntheticAlignmentTruthTest.kt`
+ `SyntheticBilingualCorpus.kt`

真实书只能人工判定，量不了「正确率」；合成语料**构造即知真值**：每句嵌一个唯一编号，
编号在中英两侧同时出现，配对对不对直接比对编号。全部离线、无 Android 依赖，跑在
`:shared:test`（CI 的 `unit-tests` job 已包含），所以**正确率第一次有了机械断言**。

覆盖的病灶场景与当前指标（2026-09-08，全部 1.000）：

| 场景 | 句级句对 | precision | recall |
| --- | --- | --- | --- |
| 干净语料 | 60 | 1.000 | 1.000 |
| 中文漏译一段 | 57 | 1.000 | 1.000（漏译段不计） |
| 段落合并/拆分 | 60 | 1.000 | 1.000 |
| 标题行 / 纯标点 / 无对应中文段 | 60 | 1.000 | 1.000 |
| 无编号 + 词义锚点（V2 路径） | 60 | 逐句位置精确 | — |
| 无编号 + 繁体 + 词义锚点 | 60 | 逐句位置精确 | — |
| 中间 3 句抹掉编号 | 57 | 1.000 | 1.000（窗口内也逐句正确） |
| 2:1 句级合并（两英句 → 一中文句） | 59 | 1.000 | 1.000（60 编号全覆盖） |

扩充方式：在 `SyntheticBilingualCorpus.build(markerAt = ...)` 上做段落/句级手术，
再用 `evaluate` 的编号集合比对；新增病灶请同步补进这张表。改对齐器后这个测试红了，
说明改动破坏了上面某类结构——先看是哪一行 `[synthetic]` 指标掉了。

## 首次基线（2026-09-08）

`bless` 把当前生产路径的展示固化成基线，结果：

| 项 | 值 |
| --- | --- |
| 重放（整本对齐 + 真 ECDICT 词义锚） | 11,017 句对（句级 10,323），耗时 18.5s（PC，Robolectric） |
| 批准样本 | 94（100 − 5 垃圾 − 1 无法定位的 bad 样本 s22） |
| 契约样本（ok/ok2） | 65 条，全部与基线一致 |
| 与 `alignment-eval6.csv`（2026-09-03 V5 展示）对账 | 一致 81 / 差异 13 |

**13 处历史差异的归因**（写在这里，避免下次重新考古）：

1. **3 处旧评估工具伪影**——s50 / s66 / s82：当年重放用 EPUB spine 建章并丢掉空章，
   档案里的章节下标与应用（`metadata.json` 章表）不是同一套；旧工具又拿样本记录的应用
   章号去 spine 索引的档案里查桶，于是查空（s50 的段落按应用索引在第 16 章、旧档案里
   在第 12 章）。s82 是 HTML `&amp;` 未反转义导致定位失败。
2. **10 处局部句级/段级落盘差异**——s55 / s57 / s62 / s63 / s69 / s71 / s74 / s77 / s97 / s99：
   旧重放与当前生产路径在个别段落上一个落句级、一个落段级（整本句对总数 11,016 vs
   11,017，差异是局部的）。这些样本里有 5 条是 `ok2`（s57/s62/s69/s71/s74），
   当年判定看的是 09-03 的句级展示，**没有按当前展示重新判定过**——下一轮人工判定
   应优先看这批。

其余 81 条与当年 V5 展示逐字一致，说明重放管线（app 格式书 → `TtsTextExtractor`
→ `TranslationAligner` → `TranslationMemoryIndex.lookup`）忠实于历史结论。

## 数据与历史

### 素材（全部在 `artifacts/`，gitignored，仅存档机有）

| 文件 | 内容 |
| --- | --- |
| `lotr-book/` | 魔戒首部曲英文原版，**app 导入格式**（`metadata.json` + 47 个章节 html），从真机 `files/books/<id>/` 拉回 |
| `lotr-zh/` | 朱学恒中译本，解包 EPUB（`META-INF/container.xml` + `OEBPS/content.opf` spine） |
| `lotr-memory.json` | 设备上产的原始对齐档案（V1 时代） |
| `realigned*.json` | V2~V5 各版本整本重放档案（历史工具产物，只读参考） |
| `alignment-eval.html` | 第 1 轮判定卡片：**样本集权威来源**（100 条：id / 分层 / 章节 / 英文句 / 设备展示） |
| `eval-verdicts.txt` | 第 1 轮判定（100 条，`sN=ok` 串） |
| `probe18.py` 内嵌 `RAW2` | 第 2 轮判定（60 条；原文件在旧机 Downloads 已丢，探针脚本是唯一备份） |
| `alignment-v3-corrected.csv` | 第 3 轮修正后的合并判定态（`verdict` 列） |
| `eval4-verdicts.txt` | 第 4 轮重判（54 条变化） |
| `alignment-eval6.csv` | 第 6 轮重放输出：`verdict4` = 第 4 轮后的权威合并态，`zh_shown` = V5 展示（保真度对账用） |
| `eval5-verdicts.txt` | 第 5 轮重判（18 条变化） |

第 6 轮没有逐样本判定串：用户通过 12 张「V4→V5 修正口径对比卡」整体确认 V5 通过
（`VALIDATION.md` 2026-09-03 条目），只作为 fixture 备注。

### 合并口径（与历史工具链一致，别另起炉灶）

```
最终判定 = alignment-eval6.csv 的 verdict4（第 4 轮后合并态）→ 第 5 轮 eval5-verdicts 覆盖
逐轮史   = r1(eval-verdicts) / r2(RAW2) / r3(v3-corrected) / r4(eval4) / r5(eval5)
对账     = 独立重建链 r1→r2→r3 与 verdict4 比对，差异只打印不阻断
```

### 已知历史问题（写进报告，避免后来者重新踩）

- **样本级长度比 / 去重段落覆盖率是假信号**：前者与人工判定几乎无关，后者把 2:1 合并
  误算成漏译。质量指标只用「点词命中率 + 句对级判定」，别再用这两个。
- **s50 / s66 的 MISS 是评估工具的章节桶错位**，不是应用行为；重放测试按「段落实际所在章」
  查询，会修正这个伪影。
- **`artifacts/alignment-package/` 已丢失**（多次搬迁中），`:shared` 的
  `TranslationAlignerBenchmarkTest` 曾长期静默跳过。2026-09-08 已给它加解包目录回退
  （`artifacts/lotr-book` + `artifacts/lotr-zh`），性能护栏恢复：本机实测
  英 45 / 中 29 章、11,106 句对、**514ms**、点词命中率 96.9%。

### 历史工具（留在 `artifacts/`，不进版本库）

`eval_tool.py` / `eval5_tool.py` / `eval3_compare.py` / `gate_fit.py` /
`anchor_experiment.py` / `semantic_probe.py` / `probe2~23.py` 是 V2~V5 期间的一次性
探针与实验台，带硬编码旧路径（`C:\work\reader`），已被本目录的工具取代。需要考古
历史结论时再去 `artifacts/` 翻，不要把结论直接当现行事实。
