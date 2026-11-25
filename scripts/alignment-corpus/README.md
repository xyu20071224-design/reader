# 对齐验收的外部输入

F01–F17 是必须执行的真实材料验收，缺材料时失败。加载器和评估器的小型原创单测只验证设施，不替代原书，也不计入原书覆盖率。本工具不联网、不调用 AI、不生成或批准金标准。

## 入口与只读边界

- JVM `linguareader.repoRoot`：仓库根。显式错误值直接失败；未指定时向上寻找 `src/settings.gradle.kts` 与共享层 `AlignmentModels.kt`，不依赖 `.git` 或书籍目录。
- JVM `linguareader.corpusRoot`：外部材料目录，默认 `<repo>/artifacts/alignment-corpus`。构建入口由 Gradle 透传 `-PalignmentCorpusRoot`（优先）或 `-Dlinguareader.corpusRoot`；相对值的构建解析规则见 shared Gradle 配置。
- 固定入口为 `<corpusRoot>/corpus-manifest.json`。加载测试只读取该目录，诊断写 stdout，JUnit XML 写 `shared/build/test-results/test`。不要把输出目录设在 corpusRoot 内。
- `AlignmentCorpusPreflight` 是 test runtime classpath 上可选的离线主类。参数 `--corpus-root <目录>` 检查全部 17 项；追加 `--case F02` 检查单项；追加 `--dictionary-only` 仅核对词典。预检不执行算法、不等同于验收通过。失败退出 1；各项依赖详见 JSON 汇总。

在 `src/` 中可用 `./gradlew :shared:alignmentCorpusPreflight -PalignmentCorpusRoot=<目录>` 调用；追加 `-PalignmentCorpusCase=F02` 检查单项，或追加 `-PalignmentDictionaryOnly=true` 只检查词典。该可选任务不替代 `check` 中的真实验收。仓库根、语料根显式传空值会直接报错，不能悄悄采用默认目录。

运行真实验收仍用 `:shared:test --tests <类名>`，不得改成 Assume、排除用例或先 return。默认本地及 CI 在没有真实材料时会保留 17 个明确的输入失败。

## 清单 v1

UTF-8 JSON（重复键拒绝）。顶层 `schemaVersion: 1`，`corpora` 对象与按需要提供的 `dictionary` 对象。示例见 `corpus-manifest.example.json`；其中占位摘要不能用于正式验收。

`corpora.lotr` 与 `corpora.ai-translation` 相互独立。每个语料有非空 `version`，`source` 和 `target`。两侧均须：

- `language` 分别为 `en`、`zh`，`version` 标明该语言版本。
- `paragraphCount`、`sentenceCount` 为实际非负整数；集合与句数必须非空。句数为当前共享 `SentenceSplitter` 对各段分句后非空句的数量，不等同于评估中的有效英文语言句分母。评估分母另由 `BookSide.sentLangUnit` 核对。
- `textSha256` 固定章节及段落边界、顺序、正文；不能把另一译本或同书不同版本的金标准混用。

`lotr` 两侧另外有非空 `chapters` 数组。**数组顺序就是阅读顺序**，不扫描目录、不按文件名字典序猜顺序。每项有：

- 唯一非空 `id`、原始字符串 `title`（可为空，不改章名影响结构判断）；唯一 `path` 与该文件原始字节 `sha256`。
- `format` 为 `html` 或 `paragraphs-json`。HTML 按现有叶级块口径取段，JSON 为 `{ "paragraphs": ["非空段落", ...] }`，不接受空字符串或非字符串段。显式声明段数/句数均为 0 的图像封面或空结构章会保留；双方全书仍须有有效文本，不静默删章改序。
- 该章的 `paragraphCount` 与 `sentenceCount`。

所有 `path` 必须相对 corpusRoot；支持 `/` 或 Windows `\` 分隔符，拒绝绝对路径、盘符、UNC、空段、`.`、`..` 与控制字符，解析真实路径后仍不得越界（包括符号链接）。文件缺失、UTF-8 损坏、摘要不符、章节重复、顺序或计数变化均失败。

### 正文摘要的字节定义

SHA-256 输入按顺序拼接以下字节；整数均为 4 字节大端、长度均为字节数：

1. 章节数；
2. 对每章：段落数；
3. 对该章每段：UTF-8 字节数，紧接该段原始 UTF-8 字节。

摘要不包含标题，包含所有章节/段落边界。HTML 先按加载器的叶级块、空白归一规则得到段落，JSON 段落保留声明文本。小段参考实现（只算文本摘要，不产生真值）：

```python
import hashlib, struct
h = hashlib.sha256()
h.update(struct.pack('>I', len(chapters)))
for paragraphs in chapters:
    h.update(struct.pack('>I', len(paragraphs)))
    for paragraph in paragraphs:
        raw = paragraph.encode('utf-8')
        h.update(struct.pack('>I', len(raw)))
        h.update(raw)
print(h.hexdigest())
```

### AI 记录与独立真值

`ai-translation.memory` 是 `{path, sha256}`，指向原记录 JSON：`enParagraphs`、`zhParagraphs`、`pairs`。每个 pair 的 `es`、`zs` 非空、属于声明的双方正文；不丢弃坏 pair，不接受归一化后重复英文导致的歧义。分句噪声保留为评估事实，不以修改 pair 或原文消除。

`ai-translation.truth` 与 `lotr.golden` 都必须包含：

- 非空 `version`、`approvedBy`；`method: "independent-review"`；`corpusVersion` 必须等于所属语料 `version`。
- `sourceTextSha256`、`targetTextSha256` 必须等于这两侧实际正文摘要。

这只是可追溯声明，不自动证明人工审核发生。材料提供者/审核者须先于引擎结果独立确定真值，不能用被测引擎输出填这些字段并自批。

`lotr.golden` 还须有 `{path, sha256}`，指向原 `golden-samples.json`。保留 `samples[].en`、`verdict`、`approved.level` 的既有含义（`MISS`、`SENTENCE`、`PARAGRAPH`）；非 MISS 必须有实际属于该中文正文的 `approved.zh`。样本不得为空，至少含一个正对照；金标准重放不允许未匹配样本被静默忽略，歧义片段须独立审核定位。

## 真实 ECDICT 离线导出

只使用 Python 3 标准库的 sqlite3（共享 JVM 不增加 SQLite/Android 运行时）：

```bash
python3 scripts/alignment-corpus/export_ecdict.py \
  --sqlite /绝对路径/ecdict.sqlite \
  --output /外部准备目录/dictionary-export-v1
python3 -m unittest discover -s scripts/alignment-corpus -p 'test_*.py' -v
```

源库须为稳定的离线副本，schema 为 `entries(word, phonetic, translation, definition)`、`forms(form, lemma)`。`mode=ro` + `query_only` 读取，先 quick_check，导出前后验证源库 SHA-256。非空 WAL/journal 拒绝处理，不自动改动源库。输出目录必须不存在；先写私有临时目录、fsync 文件，再重命名发布；不会覆盖旧导出。

输出 `ecdict.tsv` 与 `metadata.json`：

- 词条行 `word\t释义1|释义2`，释义按既有 `parseGlosses` 提取连续 2–4 汉字，不改变生产词典。
- 词形行 `>form\tlemma`。原形须在已导出词条中；多原形歧义不任意选取，计入 `ambiguousForms`。无可用释义/非法分隔符等计入 droppedEntries/droppedForms，避免悄悄把这些行当成已导出。
- 元数据有 `schemaVersion:1`、`exportVersion:"ecdict-alignment-v1"`、`sourceSha256`、TSV `sha256`、`entries`、`forms` 及丢弃/歧义计数；内容确定性，不含时间戳/密钥/机器路径。
- 清单 `dictionary` 为 TSV 的 `{path, sha256}`，附 `metadata:{path, sha256}`。加载器核对元数据版本、来源摘要格式、内容摘要、有效词条/词形数及原形引用。不能把未校验的旧 TSV 当成真实词典输入。

整库及完整导出不入 Git。现成导出的实际计数/摘要只应写本地验证报告，不作为任何真实书覆盖率结论。

## 17 项依赖与门槛

| 方法组 | 必需输入 |
|---|---|
| F01、F07、F08、F09、F14、F16、F17 | lotr 英文、中文、真实 ECDICT 导出 |
| F10、F11 | 上述输入 + 绑定双方文本的独立审核 golden |
| F12、F13、F15 | lotr 英文、中文；不要求词典/golden |
| F02–F06 | AI 记录及绑定正文的独立审核 pair、真实 ECDICT 导出；不要求 lotr |

探针验证非空输入、实际处理及可核对统计，不发明真实书覆盖率指标。F11 保留既定 `bad` 样本被 ALIGNED 数为零的红线，并核对当前输出与独立批准目标；历史 good 不会自动批准当前错译。

F02 的新 AI 引擎只在该独立审核 AI 译本及构造段落对应范围内使用既有目标：falseAligned=0、英文有效句覆盖率>=0.90、句级 precision>=0.98。原始段落与每个真值句成段的两种形态、对照旧引擎均实际跑完后才汇总断言；旧引擎只作对照，不外推门槛到人工译本。每句成段仍会经过 BookSide/SentenceSplitter，并非真的绕过分句器。

指标按英文句而非单元加权，合并 span 按真实覆盖句数计；真值按完整源文本及连续句组查找，不按算法单元下标对应 pair。无法完整归因的 ALIGNED 另记 `unscorableAligned` 并使验收失败，不能伪判正确或把未知直接报成已证错配。

## 当前材料不足时

原书、独立金标准、AI 记录暂无时：基础设施单测可通过，词典导出可独立完成，F01–F17 继续失败并显示明确依赖。预检、导出、小样本或语料入口修复均不能把这 17 项登记为已完成。邻近历史探针的 `SKIP`/return 问题另列本地审计，不在此批顺手扩改。


### 评估器完整句位校验

源输出按文档稳定顺序逐一消费 BookSide 的真实句子，核对章节、起始原段、段内句序、完整文本和实际跨度，末尾必须完整消费输入。覆盖率按真实有效句位置集合计，拒绝伪造 span、重复单元、遗漏句子或虚报 stats。完整文本匹配保留拉丁词边界和标点；忽略词间空白，不用包含或前缀匹配。

独立 golden 与 Oracle 必须在双方输入定位到唯一完整连续句 span；PARAGRAPH 必须定位整个唯一段落。不完整片段、重复全文或跨章拼凑明确失败，不取第一个位置。Oracle 把真实双方跨度传给评分器，不固定1:1。

句级 golden 对合并单元要求独立真值覆盖完整真实源范围、批准目标位置并集与完整当前目标精确一致。N:1/N:M 拆成多个输出单元时，整个批准源组须确实完整、ALIGNED，并共享同一个完整批准目标。缺一部分真值不能把额外源句/译文算正确。段级批准只核其真实段内范围，单独报段级召回，不计入句级精确召回。当前错配、无法完整评估的 ALIGNED 和历史 bad 红线均会使验收失败。
