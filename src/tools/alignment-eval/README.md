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

## 控制台（一条命令看报告）

Gradle 默认吞掉测试 stdout，工具报告原本只能去 `build/test-results` 的 XML 或 HTML
报告页里翻。`run-tool.sh` 把「跑 + 取报告」收成一条命令，输出与测试内的报告逐字一致：

```bash
bash src/tools/alignment-eval/run-tool.sh proxy            # 语义代理：AUC / 回归门 / 主动采样
bash src/tools/alignment-eval/run-tool.sh cards            # 判定卡：变化样本 → HTML
bash src/tools/alignment-eval/run-tool.sh fixture          # 重建金标准 fixture + 台账
bash src/tools/alignment-eval/run-tool.sh replay           # 金标准重放（整本，约 40s）
bash src/tools/alignment-eval/run-tool.sh generalization   # 公版泛化集（KJV × 和合本）
bash src/tools/alignment-eval/run-tool.sh synthetic        # 合成语料真值对齐
bash src/tools/alignment-eval/run-tool.sh full             # 本地全量：replay→synthetic→generalization→proxy
```

加 `--rerun` 强制重跑；不加时若 Gradle 判定测试未变，直接打印上次结果（秒出）。
各组件小节里也给了等价的直接 Gradle 写法。

## 语料持久化（清单 + 校验 + 一条命令跑全量）

素材本体（KJV/和合本/CC-CEDICT，约 23 MB）**不入库**（体积 + 版权），但「不入库」
不等于「不可重建」：来源 URL、许可、字节数、sha256 全部钉在
`src/tools/alignment-eval/corpus-manifest.tsv`，任何机器都能一字不差地重建并校验。
这不是洁癖——魔戒的 `artifacts/alignment-package` 就在搬迁里静默丢过一次，基准测试
因此空转了很久；泛化集/代理同理，文件在不在必须能一句话答清楚。

```bash
bash src/tools/alignment-eval/fetch-corpus.sh                 # 缺则下载，在则校验
bash src/tools/alignment-eval/fetch-corpus.sh --check         # 只校验，不联网（发版前先跑）
bash src/tools/alignment-eval/fetch-corpus.sh --refresh       # 重新下载
bash src/tools/alignment-eval/fetch-corpus.sh --only kjv,chiun  # 只处理指定条目
```

`fetch-generalization-corpus.sh` / `fetch-semantic-proxy-corpus.sh` 是它的两个薄封装
（`--only kjv,chiun` / `--only cedict`），旧命令照用。**发版前清单**：`fetch-corpus.sh
--check` → `run-tool.sh full`（后者第一步自己会校验语料，语料缺失时相关测试自动跳过
并明确打印 skipped）。

## 六个组件

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

**第七轮起的补充判定**写在 `src/tools/alignment-eval/verdict-overrides.json`（入库、不含书文）：
六轮历史是既成事实，之后每次人工复核只往这里追加一轮 `{round, date, method, verdicts}`，
fixture 工具在合并六轮之后按轮次覆盖，并把结果写进台账的 `rN` 字段。已有第 7 轮
（2026-09-08，5 条 ok2 样本按当前整段展示复核，维持 ok2）。

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

### 4. 判定卡生成器（人工只看增量）

`src/app/src/test/java/com/linguareader/app/translation/TranslationJudgmentCardTool.kt`

重放测试每次会写出 `artifacts/alignment-eval/replay-report.json`（每个样本的
批准展示 vs 当前展示 + `changed` 标记）。本工具把**变化样本**渲染成一份自包含 HTML
判定页（含当年那种一键收集 `sN=verdict;…` 的按钮），人工只判这几条：

```bash
# 1) 重放，生成报告
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationGoldenReplayTest" --console=plain
# 2) 生成判定卡（无变化时会明确说「无需人工判定」并删掉旧卡）
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationJudgmentCardTool" --console=plain
```

产出 `artifacts/alignment-eval/judgment-cards.html`（含书文，本地 gitignored；垃圾样本与
定位不到的样本不进卡）。**排序**：本地有 CC-CEDICT 时按语义代理分数升序排（分数越低
越可疑，人工先看最可能出问题的），卡片元信息里带分数；缺词典时退回报告顺序并在卡片
顶部说明。排序只是省注意力，判定仍以人眼为准。判定完把收集到的判定串写进
`verdict-overrides.json` 的新一轮，再依次跑 fixture 工具（并入台账）→ bless（更新批准
展示）→ 重放校验。

### 5. 公有领域泛化集（KJV × 和合本）

`src/shared/src/test/java/com/linguareader/shared/translation/PublicDomainAlignmentGeneralizationTest.kt`

魔戒金标准集只有一个文体（现代小说 + 现代译本），长度比门槛 [0.45, 2.6] 与 margin 0.12
全是单书拟合。这里用 KJV（英王钦定本）与和合本做第二、第三评测集：两版都是公有领域，
**逐节对齐是免费真值**，文体差别也大（福音书叙事 / 创世记 / 箴言格言）。

```bash
# 素材本地下载（约 19 MB，gitignored；缺失时测试自动跳过）
bash src/tools/alignment-eval/fetch-generalization-corpus.sh
./toolchain/build.sh :shared:test \
  --tests "com.linguareader.shared.translation.PublicDomainAlignmentGeneralizationTest"
```

**2026-09-08 首次实测**（`meaning = null`，只看结构/长度路径）：

| 书 | 章 | 节 | 句对 | 同节精度 | ±1 节精度 | 节级覆盖 | 平均置信度 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| John | 21 | 879 | 1087 | 0.649 | 0.848 | 0.521 | 0.85 | 248ms |
| Genesis | 50 | 1533 | 1905 | 0.599 | 0.816 | 0.408 | 0.84 | 161ms |
| Proverbs | 31 | 915 | 1064 | 0.465 | 0.773 | 0.270 | 0.80 | 77ms |

**结论（重要）**：对齐器在古典经文上明显退化——只有 46–65% 的句对落在同一节，
错误以 Δ±1 节为主（整节漂移）。最可能的原因是长度比启发式「1.7 中文字 / 英文词」
不适配和合本的紧凑用词（同一节的中文字数远少于现代译本），DP 为凑长度而整节错位。
这不是回归，而是**单书过拟合的量化证据**——以后重拟合门槛必须在这几本书上复验。
测试对三本书各设「同节 / ±1 节 / 覆盖率」三条下限（实测留约 5 个点余量），
红了说明改动让泛化进一步退化。

### 6. 语义代理评分（外部信号，先验证再依赖）

`src/app/src/test/java/com/linguareader/app/translation/TranslationSemanticProxyTool.kt`

对齐器自身的信号（长度比 / 锚点 / 词义加分 / 置信度）量的是「DP 有没有优化自己的
目标函数」，是循环论证。这里引入**外部**信号：CC-CEDICT（社区维护的中英词典，
**不是**对齐器用的 ECDICT）反查「英文句内容词的中文候选是否出现在展示的中文里」，
拿六轮人工判定做 ROC/AUC 验证——先证明它分得开，再谈依赖。

```bash
bash src/tools/alignment-eval/fetch-semantic-proxy-corpus.sh   # CC BY-SA 4.0，约 4 MB，gitignored
./toolchain/build.sh :app:testDebugUnitTest \
  --tests "com.linguareader.app.translation.TranslationSemanticProxyTool"
```

**验证结果（2026-09-08，91 条可评分样本：ok/ok2 = 64、bad = 27）**：

| 信号 | ok/ok2 均值 | bad 均值 | AUC |
| --- | --- | --- | --- |
| 语义代理（CC-CEDICT 内容词命中率，**外部**） | 0.311 | 0.071 | **0.802** |
| 长度比贴近 1（内部） | 0.694 | 0.515 | 0.756 |
| 数字 / 拉丁锚点重叠（内部） | 0.047 | 0.130 | 0.434 |

**结论**：外部信号确实分得开（0.80 > 内部长度比 0.76 ≫ 锚点 0.43——锚点甚至反向，
再次印证「句首大写词」这类内部信号不是质量指标）。但报警口径 t=0.2 时精确率仅
0.55 / 召回 0.89：**只够当粗筛，不能单独定质量**。局限：短对白句可用词太少，
分数天然为 0（主动采样列表里多数是短句）。

两个落地用途（工具直接输出）：

- **回归门**：整本句对抽样（`pairs-sample.json`，≤2000 条）的分布基线——当前
  均值 0.261 / 中位 0.250 / p25 0.000 / p75 0.400；相对漂移 >0.03 报警。
- **主动采样**：把「人工判对、代理却存疑」的样本排到最前面（工具打印 10 条），
  下一轮人工只复看这批，判定量从 100 条降到 10 条量级。

**为什么没直接上句向量**：LaBSE 一类多语模型约 1.8 GB 且要 Python/torch，与
「离线优先 + 仓库自带 JVM 工具链」冲突；先用零额外运行时依赖的外部词典验证
「外部信号有没有用」——结论是有用。若要把 AUC 从 0.80 再往上推，再引入句向量，
替换点就是 `SemanticProxyIndex.score(index, en, zh)` 这一个函数（索引与打分已抽到
`src/app/src/test/java/com/linguareader/app/translation/SemanticProxyIndex.kt`，
判定卡排序与本工具共用同一份实现）。

## 待审核：长度归一化改造（章节密度自适应，2026-09-08 研究）

泛化集暴露的 Δ±1 整节漂移，根因是 `TranslationAligner.ZH_CHARS_PER_EN_WORD = 1.7`
这个**全局**常量：它把中文字符折算成英文词，DP 的代价函数与 V5 长度门槛都按它归一。
研究做法：把 aligner 的 `meaning = null` 路径逐行移植到一次性 Python 脚本
（`artifacts/length-scale-study/`，gitignored），**先用全局 1.7 复现 Kotlin 基线到
小数点后三位**（John 0.649/0.848/0.521、Genesis 0.599/0.816/0.408、Proverbs
0.465/0.773/0.270，句对数 1087/1905/1064 也一致）——移植可信后，只换 scale 重跑。

实测密度（zh 字符 / en 词）：

| 书 | 整本 | 章密度均值 ± sd | 章密度范围 |
| --- | --- | --- | --- |
| John | 1.470 | 1.471 ± 0.064 | 1.371–1.590 |
| Genesis | 1.360 | 1.355 ± 0.122 | 1.020–1.581 |
| Proverbs | 1.314 | 1.317 ± 0.066 | 1.199–1.452 |
| 魔戒（现代译本） | **1.776** | — | — |

1.7 对圣经偏高 14–23%，DP 因此系统性偏好更长的中文节（漂移方向实测也是 Δ+1/+2 居多）；
对魔戒只偏低 4.5%。

全 DP 复跑（同书同真值，只换 scale；同节 / ±1 节 / 节级覆盖）：

| 方案 | John | Genesis | Proverbs |
| --- | --- | --- | --- |
| 全局 1.7（现状） | 0.649 / 0.848 / 0.521 | 0.599 / 0.816 / 0.408 | 0.465 / 0.773 / 0.270 |
| 整本密度 | 0.836 / 0.945 / 0.744 | 0.828 / 0.941 / 0.686 | 0.709 / 0.920 / 0.612 |
| 每章密度 | **0.857 / 0.963 / 0.772** | **0.845 / 0.961 / 0.728** | **0.769 / 0.946 / 0.673** |
| 每章密度 + 收缩（α=200 词） | 0.858 / 0.963 / 0.775 | 0.841 / 0.953 / 0.715 | 0.761 / 0.931 / 0.670 |
| 句数归一 `(C/S_zh)/(W/S_en)` | 0.535 / 0.793 / 0.431 | 0.518 / 0.782 / 0.387 | 0.305 / 0.664 / 0.264 |
| 字数 × (S_en/S_zh)^0.25 | 0.871 / 0.977 / 0.796 | 0.850 / 0.957 / 0.722 | 0.636 / 0.873 / 0.590 |

- **用字数，别用句数**：句数归一比现状更差——中文分句比英文多 20–50%（和合本的
  `；` 与引号切分），`(C_zh/S_zh)/(W_en/S_en)` 系统性低估；带句数修正的插值在
  John/Genesis 微涨、Proverbs 掉 13 个点，不稳定，不采用。
- **章级 > 整本级 > 全局**：章级比整本级多 2–6 个点（Proverbs 最明显）；收缩到整本
  先验几乎不掉分，可当防误配护栏。
- **风险在魔戒侧**：魔戒在基准读法下是 45 英文章对 29 中文章（分章粒度本来就不同），
  章密度因误配波动到 0.40–10.31；把 scale 从 1.7 挪到整本 1.776 会让 **21.5%** 的
  句对变化（1.71 已 5.2%，1.75 16.3%）。也就是说**任何 scale 改动都要重跑金标准，
  大概率还要一轮人工判定**。

建议的落地方式（**未实现，等审核**）：

1. `align()` 先算整本密度 `s_book = ΣC_zh / ΣW_en`（不依赖章配对，O(text)）；
2. 若 `|s_book/1.7 − 1| ≤ 0.10` → 整本沿用 1.7（魔戒 1.776 落在带内 → 零 churn）；
3. 否则逐章 `s_c = (C_zh + α·s_book)/(W_en + α)`（α≈200 词，防短章/误配），钳制到
   `[0.9, 2.6]`；
4. scale 只替换 `lengthCost` / `ratioOf` / `lengthRatioOf` 里的常量（V5 门槛与 1:N
   合并门槛随之变成「相对本章密度」），`VERSION` bump 到 6；
5. 验收：泛化集三书同节 ≥0.84 / ±1 ≥0.95，金标准变化样本跑一轮判定卡，
   benchmark 耗时 <3s 不退化。

两个选项：**A（推荐）全量自适应**——不要第 2 步死区，质量最高，代价是魔戒基线约
10–20 条样本变化（一轮判定）；**B 零 churn**——保留 10% 死区，圣经三书照常受益
（都超出 10%），魔戒零变化，代价是密度落在 1.53–1.87 的书拿不到修正。

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
