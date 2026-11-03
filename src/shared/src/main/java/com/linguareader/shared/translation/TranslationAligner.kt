package com.linguareader.shared.translation

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.math.abs

/**
 * 词义锚点查询：英文词 → 中文释义短语集合（已过滤虚词性、长度 >= 2 的连续汉字）。
 * 由仓库层基于离线词典实现并缓存；对齐器只做哈希查表。
 */
interface MeaningIndex {
    fun phrasesOf(word: String): Set<String>
}

/**
 * 双语文本对齐器（纯 Kotlin，无平台依赖）。
 *
 * 三级对齐，全部走「单调序列 DP」：
 *  1. 章节对齐：按章节整体特征做 1:1 / 1:0 / 0:1 对齐（章节数不一致时允许跳过）。
 *  2. 段落对齐：章节内按叶级段落（与阅读器/TTS 同一选择器）做 DP，允许
 *     1:0 / 0:1 / 1:1 / 2:1 / 1:2（对应省略、合并、拆分）。
 *  3. 句子对齐：对齐段落内用 [SentenceSplitter] 分句后做小规模 DP；V3 起允许
 *     2:1 / 1:2 合并，但必须过 [sentenceMergeAllowed] 三道门槛（语义证据 /
 *     尺寸保护 / 边际收益）。
 *
 * 对齐代价 = 长度比偏差（英文词数 vs 中文有效字符数）− 数字/拉丁专名锚点命中率
 * − 词义锚点加分（V2：ECDICT 释义短语命中）。长度比 V6 起按章自适应：段级用
 * 「本章中文字符数 / 英文词数」，句级用「平均中文句长 / 平均英文句长」——单一全局
 * 常量 1.7 对古典紧凑译本偏高 14–23%，是整节漂移的主因（见 [ZH_CHARS_PER_EN_WORD]）。
 * 置信度 = 1 − 归一化代价，钳制到 [MIN_CONFIDENCE, 1]。
 *
 * **性能上的硬要求**：代价函数在 O(n·m) 的 DP 内层被调用，因此它只允许做算术与
 * 哈希查表，绝不能扫描文本。所有文本特征（词数、字符数、锚点集合、词义短语集、
 * 中文 2–4 字子串集）都在进入 DP 之前按片段预计算一次 —— 早期实现每格都对整段/
 * 整章重新跑正则并 `lowercase()` 拷贝整章文本，真机上整本小说跑 5 分钟以上仍未
 * 结束（单线程 100% CPU）。
 */
object TranslationAligner {

    /**
     * 档案里把多少号对齐器写入 alignerVersion；算法/分句规则变化时必须 +1。
     *
     * v7（相对 v6）：
     *  1. **禁止英方合并**（Q1-t04）：句级 DP 不再走 2 en : 1 zh，任何落盘句对的英文
     *     下标数组长度恒为 1。原因是并合句对把 [join] 成 `"A. B."` 写进档案后，用户点
     *     B 句做精确匹配必然失败，命中查询侧第 3 级的子串匹配，拿回整条**并合**译文
     *     ——表现为「A 句后的 B 句返回 A 句的意思」。被并掉的第二句英文不再有句级条目，
     *     点它会走第 5 级段落兜底（整段译文 + 「段级（未定位到句）」），是有意的诚实降级。
     *     中文侧 1 : 2 不下线（译文拆句是真实形态，且没有「并合英文串」的对应病灶）。
     *  2. 邻近段落兜底的句级 DP 与主路径对齐：`allowMerge` 由写死的 false 改为 true，
     *     并套同一个 [sentenceMergeAllowed] 门槛（此路径拿得到 meanings，非无证据回退）。
     *  3. 中文侧 [joinChinese] 改用空串拼接（英文侧保持空格）：中文并合译文不该出现
     *     「他說。 她笑了。」这种夹空格形态。
     *
     * v8（相对 v7）：
     *  1. **段级位置先验**：段级 DP 的配对走法加 w·|i/n − j/m| 的对角惩罚
     *     （[PARAGRAPH_POSITION_PRIOR]=0.5），治泛化集 Δ±1 整节漂移；句级/章级保持 0。
     *  2. **中文侧句数改精确计数**（[countChineseSentencesExact]，与 [splitChinese] 同口径），
     *     消除第四轮审查 2-4 遗留的「终止符游程近似」已知例外。
     *  3. **英方 2:1 恢复合并且落盘拆回单句**（即 T5 对 v7 第 1 条的修订）：句级 DP 恢复
     *     2 en : 1 zh 走法（仍受 [sentenceMergeAllowed] 三门槛约束），但落盘时把被合并的
     *     每个英文句各写成一条句对、共享同一条 zhSentence（主路径与邻近兜底路径同契约）。
     *     档案里 enSentence 仍恒为单句（[EN_SINGLE_SENTENCE_SINCE] 的不变式不变），而 v7
     *     那个「第二条英文句失去句级条目、只能段落兜底」的覆盖缺口被补上（金标准
     *     s1/s19/s24/s27/s31/s32/s33 的根因，T5 恢复其中 5 条）。Q1-t04 的病灶是**档案里
     *     的 "A. B." 并合串**，不是 DP 走法本身——拆开落盘后点 B 仍走第 1/2 级精确命中
     *     它自己那条。
     *
     * v9（相对 v8）：**只改中文分句口径，不改 DP 走法**，但会改变落盘句对的切点。
     *  1. [ZH_SENTENCE_END] **去掉 `；`**：中英分号地位对齐。英文 `SentenceSplitter`
     *     从不把 `;` 当句界，中文侧过去把 `；` 当句界 → 一句英文在中文侧被切成两句 →
     *     段内句数不等 → 句级 DP off-by-one → 静默错。AI 译本按「不合并、不拆分句子」
     *     生成，`；` 几乎总在英文同一句内部，切它纯粹是破坏。
     *  2. [ZH_CLOSING_LEADS] 补入**弯引号** `”`/`’`：AI 译文一律用弯引号，旧集合只有
     *     直引号，于是 `！”甘道夫说。` 被切成两句，同样制造句数不等。
     *
     * 实测（生产链路 [align]，真值 AI 译本，分母 = 真值不同英文句；IDF 加权口径见
     * `Stage0ProductionBaselineProbe`）：
     *   |    | ch1 句级静默错 | ch1 加权静默错 | ch1 加权正确 | ch3 句级静默错 | ch3 加权静默错 | ch3 加权正确 |
     *   | v8 | 13.08%         | 14.63%         | 84.21%       | 16.34%         | 15.56%         | 83.93%       |
     *   | v9 |  6.94%         |  6.34%         | 92.63%       |  5.87%         |  6.18%         | 92.71%       |
     * 静默错**减半**、有答案且正确 **+8.4pp / +8.8pp**，耗时不变（约 0.5s/章）。
     *
     * **R2（闭合引号残留并入前句）的合并策略已实测选型**：无条件合并胜出。
     * `Stage0PolicyEndToEndProbe` 端到端扫了「选择性合并」（只有短 + 含说话动词 + 无逗号
     * 的尾部才并入），用户代价（无答案×1.0 + 静默错×2.0）：
     *   ch1 无条件 13.71 vs 选择性(6/8/10/14) 17.72 / 16.65 / 16.29 / 16.04
     *   ch3 无条件 13.47 vs 选择性(6/8/10/14) 19.50 / 19.24 / 18.68 / 17.48
     * 选择性合并能把「无答案」从 1.74% 降到 0.93%，但静默错从 6.94% 升到 8.68%+，
     * 按 2 倍代价算净亏。**后人别再试这条路**（[ZhSplitPolicy] 保留该开关只为复测）。
     *
     * v10（相对 v9）：**合并对按句切开**（[splitMergedChinese]）。v9 只修了分句口径，
     * 但「2 个英文句 → 1 条中文合并句」落盘时两边仍共享整串，用户点 A 看到 A+B、
     * 点 B 也看到 A+B。残差归因显示这是静默错的**主成分**（ch1 5.19% / ch3 4.79%），
     * 而真错配只有 0.00%/0.14%。切开后每条英文句只拿自己那段。
     *   - 切点 = 让各段「中文字数 / 对应英文词数」方差最小的组合（穷举句读候选）
     *   - 护栏①每段必须过 [SENTENCE_MIN_LENGTH_RATIO]..[SENTENCE_MAX_LENGTH_RATIO]，
     *     否则整条回退共享（不加时 `‘Wake up, hobbits!’ he cried.` 会被切错）
     *   - 护栏②[SPEECH_TAG_CUT_PENALTY] 对「引语 + 说话人交界」软惩罚（必须软，
     *     硬禁止会让 ch3 劣化 2.82%→3.31%）
     *
     * 实测（累计，生产链路 [align]，真值 AI 译本，IDF 加权）：
     *   ch1 静默错 14.63% → 6.34% → **3.01%**；有答案且正确 84.21% → **96.22%**
     *   ch3 静默错 15.56% → 6.18% → **2.82%**；有答案且正确 83.93% → **96.45%**
     *
     * v11（相对 v10）：**P3 构造性句界**。AI 译文按句换行返回后，调用方把行边界
     * 作为 [ZhSplitPolicy.knownSentences] 传进来，对齐器命中时**跳过中文分句推断**。
     * 这不是算法改动而是输入质量改动——中文句界从「猜」变成「已知」。
     *
     * 实测（真值 AI 译本，IDF 加权，与 v10 同一个对齐器）：
     *   ch1 静默错 4.45% → **2.07%**，代价 9.92 → **4.81**
     *   ch3 静默错 3.59% → **0.82%**，代价 8.29 → **2.10**
     * 侧车缺失或行数不等于英文句数时自动回退到中文分句器（行为与 v10 完全一致），
     * 所以非 AI 译本、旧档案、手动导入的翻译都不受影响。
     *
     * **本版本必须触发重对齐**：旧档案里的句对是按错误的切点写下的，不重跑会继续
     * 展示错配。书架「重新对齐」入口据 [VERSION] 判旧。
     *
     * 旧档案（alignerVersion < [VERSION]）靠版本闸门判旧，由书架「重新对齐」入口重跑。
     */
    const val VERSION = 11

    /**
     * 「英文侧恒为单句」契约的起始版本：**v7/T5 起**落盘句对的
     * [AlignedSentencePair.enSentence] 不可能再是 "A. B." 这种并合形态（v7 靠禁用英方并合
     * 实现；v8/T5 起靠「DP 允许 2:1、落盘拆回单句」实现——不变式相同，理由是 Q1-t04 的
     * 病灶在档案串本身）。**VERSION 后来升到 8 不改变本常量**：v7、v8 档案都满足该不变式，
     * 查询侧护栏对二者都应关闭；写成 [VERSION] 会把 v7/v8 档案重新卷进游程启发式。
     *
     * 查询侧第 3 级的「并合片段」护栏（[TranslationMemorySearch.isCrossSentenceFragment]）
     * 只对 **alignerVersion < 本常量** 的旧档案有意义。对 v7+ 档案继续套终止符游程
     * 启发式会把含缩写点/问号+句号的**普通单句**误判成并合句，把同一句的真子串查询
     * 降级为整段——金标准 s1/s7/s19/s24/s27/s31/s32/s33 八条就是这样从句级掉到段级的。
     * 该常量必须钉死在 7：不能写成 [VERSION]，否则后续版本 +1 会把 v7 档案重新卷进护栏。
     */
    const val EN_SINGLE_SENTENCE_SINCE = 7

    /** 词义锚点每次命中的加分上限与单点权重（与「数字/拉丁锚点」同量级、略高）。 */
    private const val MEANING_MAX_HITS = 4
    private const val MEANING_HIT_SCALE = 0.12f

    /**
     * 句级 1:N 合并门槛（V3）：合并必须同时满足
     *  ① 合并对至少有一个词义锚点命中（无语义证据不合并）；
     *  ② 尺寸保护：英文侧词数 ≥2、中文侧字符数 ≥6（标题/超短句禁合并，
     *     「STRIDER」被吸进邻近长句的教训）；
     *  ③ 边际要求：合并代价要比局部最优的 1:1 拆分便宜 [SENTENCE_MERGE_MARGIN]
     *     以上（「本来就配得好就不要合并」，否则正确配对会被合并抢走）。
     * 无 meaning 源时 ① 恒不满足，合并自动禁用（行为保守回退）。
     *
     * 英方 2:1（T5 恢复）在 DP 里合法，但**落盘一定拆回单句**（见 [align] 的 emitted 循环）；
     * 因此本门槛判的是「这两句英文挤进这一句中文是否说得通」，与档案单句不变式不冲突。
     *
     * **T5 后泛化集同节精度 -1pp 的归因（实测）**：恢复英方合并会新增「同一段内共享同一条
     * zhSentence 的多条句对」。泛化集（meaning=null，同节精度）：
     *   John 含共享 0.906 → 排除共享 **0.918**；Genesis 0.906 → **0.920**；Proverbs 0.878 → **0.884**
     *   （T4 无该机制时为 0.916 / 0.917 / 0.883）——跌幅全部来自**节级口径效应**：共享的中文句
     *   同时覆盖多条英文句，按「每条句对定位到哪个节号」计分时不可能同时落进两个节，必然稀释
     *   同节精度；排除共享对后回到 T4 水平（差 ≤ +0.3pp），T5 无真退化。
     *   **泛化集是完全平行语料（KJV/和合本逐节一一对应），共享句在节级口径下必然稀释同节精度**
     *   ——这是口径性质，不是回归，后人别把它当退化。用户侧则是净收益：点共享句覆盖的任一句
     *   英文都能拿到句级译文，而不是整段兜底（魔戒 benchmark 段级兜底 29.3% → 22.7%）。
     */
    private const val SENTENCE_MERGE_MIN_EN_WORDS = 2
    private const val SENTENCE_MERGE_MIN_ZH_CHARS = 6
    private const val SENTENCE_MERGE_MARGIN = 0.12

    /** 句级合并对的置信度折扣（类比段级 [MERGED_CONSTITUENT_SCALE]：真配对，但粒度跳）。 */
    private const val SENTENCE_MERGE_SCALE = 0.85f

    /**
     * 句对落盘的长度比硬门槛（V5）：zh 字符数 /（en 词数 × 句级自适应比例）
     * 落在区间外的不落盘，降级为段级条目。100 样本判定拟合：残 <0.45 的样本
     * 判定全 bad（3/3），超 >2.6 只剩 ok2/bad（ok 集最大 2.02，留 0.6 余量）；
     * 置信度门槛管不住它们——锚点/词义加分能把长度比崩坏的错对拉回 0.30 以上。
     * 全书占比：残 1.6% / 超 3.0%。落盘后这些句子走段落兜底（整段译文）。
     */
    private const val SENTENCE_MIN_LENGTH_RATIO = 0.45
    private const val SENTENCE_MAX_LENGTH_RATIO = 2.6

    /**
     * 合并句对（1:N）的比例上限放宽一档：中文引文常被分句规则留成不可再分的
     * 整句（「他說：『你好。』」16 字），与两侧短英文句 2:1 合并后比例 ~2.35
     * 属正常形态；1:1 未合并对无此理由，维持 2.6 一票否决。
     */
    private const val SENTENCE_MAX_MERGED_LENGTH_RATIO = 3.2

    const val MIN_CONFIDENCE = 0.15f
    private const val SKIP_COST = 1.2

    // --- 位置先验（V8 起） ---------------------------------------------------
    //
    // 动机：泛化集残差几乎全是 Δ±1 整节平移（within±1 0.945–0.962 vs 同节 0.766–0.857）。
    // 长度相近的片段之间，现有代价只有长度比 + 锚点，整段平移几乎不花代价，单调 DP 于是
    // 可以整节滑走。这里给 DP 加一个 O(1) 的对角先验：路径在 (i/n, j/m) 平面上偏离对角线
    // 的距离乘以权重。三类片段（章/段/句）各自独立可调，0.0 = 与旧版行为完全一致。
    //
    // 权重取值依据见各常量旁注释（泛化集 + 合成语料 + 金标准实测）。
    /** 章节级对「章节数不等」的场景会把真实偏移当漂移惩罚，故保持 0（章配对本来就允许 1:0/0:1）。 */
    private const val CHAPTER_POSITION_PRIOR = 0.0
    /**
     * 段级位置先验权重（V8 起生效；泛化集的「节」就在这一层）。
     *
     * 泛化集扫描（meaning=null，同节/±1节/覆盖率）：
     *   p=0.0  John 0.857/0.962/0.782  Genesis 0.838/0.958/0.753  Proverbs 0.766/0.945/0.733
     *   p=0.5  John 0.915/0.989/0.850  Genesis 0.916/0.991/0.844  Proverbs 0.883/0.989/0.854
     *   p=0.7  John 0.924/0.992/0.861  Genesis 0.935/0.997/0.870  Proverbs 0.906/0.993/0.879
     *   p=1.0  John 0.944/0.996/0.887  Genesis 0.950/0.998/0.887  Proverbs 0.941/0.998/0.914
     *
     * **泛化集是「逐节一一对应」的完全平行语料，指标随 p 单调上升，不能拿它定最优点**
     * （KJV 与和合本每章节数完全相同，越「贴对角线」越对）。真正的上限由非平行场景定：
     * p≥0.7 时 [TranslationAlignerTest.skippedParagraphGetsSentenceLevelFallback] 的
     * 3 英文段 : 1 中文段 场景里，先验把「跳过最远段 + 句级兜底」顶成 2:1 段级合并，
     * 丢掉了该测试守住的兜底行为；p=0.5 是仍保持该行为的上界。
     * 魔戒侧（真正非平行）实测 p=0.5：金标准 65 条契约样本 0 变化；
     * benchmark 段级句对 928→955（+2.9%）、点词命中率 96.9→97.0%、耗时 645→662ms（≤10% 护栏内）；
     * 合成语料 clean/missing/mergedSplit/unmarked-window 四场景 precision/recall 全 1.000
     * （扫描到 p=3.0 也不裂）。
     */
    private const val PARAGRAPH_POSITION_PRIOR = 0.5

    /**
     * 句级位置先验权重：**保持 0.0**（任务书原打算先在句级启用，被数据否掉）。
     *
     * 泛化集扫描里句级先验没有任何收益：p=0 时 s=0.0→0.1 三书 0.857/0.838/0.766
     * → 0.858/0.839/0.765（噪声级），而覆盖率随 s 单调下滑（s=0.5 时 John 0.782→0.757）。
     * 原因是句级 DP 的 n、m 很小（一段通常 3–10 句），归一化位置本身是粗粒度噪声。
     */
    private const val SENTENCE_POSITION_PRIOR = 0.0

    /**
     * 长度归一化的兜底值：约 1.7 个中文字符对应 1 个英文单词。
     *
     * V6 起它只在拿不到文本特征时兜底（空章、全空白）：正常路径用两级自适应密度——
     * 段级 = 本章 `中文字符数 / 英文词数`，句级 = `平均中文句长 / 平均英文句长`。
     * 依据是恒等式 `C/W = (μ_zh/μ_en) × (S_zh/S_en)`：段级对齐要前者、句级对齐要后者，
     * 古典紧凑译本（和合本整本 1.31–1.47、句长比 0.88–1.26）与现代译本（魔戒 1.78 / 2.03）
     * 差得很远，单一全局常量必然偏向一头。泛化集实测：同节精度 0.649→0.858（John）、
     * 0.599→0.845（Genesis）、0.465→0.769（Proverbs）。
     */
    private const val ZH_CHARS_PER_EN_WORD = 1.7

    /**
     * 自适应密度所需的最小英文词数：低于它就退回全局常量/整本密度。
     * 几十个词的样本算出来的密度是噪声（单元测试、封面/标题页这类短章），
     * 真实章节远高于此（魔戒 74–16,322 词、圣经每章 1,000+ 词）。
     */
    private const val MIN_ADAPTIVE_WORDS = 200

    /** 自适应密度的钳制范围：防短章/误配对把比例算飞（魔戒实测章密度 0.40–10.31）。 */
    private const val MIN_ADAPTIVE_SCALE = 0.9
    private const val MAX_ADAPTIVE_SCALE = 2.6

    /**
     * 一次对齐用的两级长度归一化比例（zh 字符 / en 词）。
     * [paragraph] 用于段落级 DP 与段级兜底；[sentence] 用于句级 DP、V5 落盘门槛与 1:N 合并门槛。
     */
    private class LengthScales(val paragraph: Double, val sentence: Double)

    /** 邻近段落兜底的置信度缩放：它只是「大致对应」，必须明显低于真配对。 */
    private const val NEIGHBOUR_CONFIDENCE_SCALE = 0.55

    /** 合并段落的成分条目：是真配对，只是粒度退到段，轻微降权即可。 */
    private const val MERGED_CONSTITUENT_SCALE = 0.85f

    private val ANCHORS = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}")
    private val LATIN_RUNS = Regex("[A-Za-z0-9]+")
    private val EN_WORDS = Regex("[a-z\'\u2019]+")
    private val HAN_RUNS = Regex("[\\u4e00-\\u9fa5]+")
    private val WHITESPACE = Regex("\\s+")
    /** 中文句界。V9 起**不含 `；`**（中英分号地位对齐，理由见上方长注释）。 */
    private val ZH_SENTENCE_END = Regex("(?<=[。！？!?])|(?<=\\n)")

    /** 含 `；` 的中文句界（V8 及更早；现仅用于按段自适应的候选口径）。 */
    private val ZH_SENTENCE_END_WITH_SEMICOLON = Regex("(?<=[。！？；!?;])|(?<=\\n)")

    // 英文侧与中文侧的句数计数都已走真实分句（审查 2-4 口径 A；中文侧 V8 收敛），
    // 不再需要「终止符游程」近似，旧的 ZH_SENTENCE_ENDS 正则已删除。
    //
    // ## 「；」是否算句界：V9 起**不算**（口径 B），推翻了 V8 的决定
    //
    // V8 曾实测「算」（口径 A）在泛化集上更好（John 0.916/Genesis 0.917/Proverbs 0.883
    // vs 口径 B 的 0.928/0.924/0.890 同节精度——其实口径 B 更高，当时因「泛化集是完全
    // 平行语料、偏好句更短」且魔戒查询侧口径 A 句级命中更多而选了 A）。
    //
    // **对齐目标改为 AI 译本后结论反转**。根因是**中英分号地位不对称**：
    // 英文 `SentenceSplitter.terminators` 不含 `;`（`; but ...` 是同一句的一部分），
    // 而中文侧曾把 `；` 当句界 → 一句英文在中文侧被切成两句 → 段内句数不等 →
    // 句级 DP 整体 off-by-one → 静默错。AI 译本按「不合并、不拆分句子」生成，
    // 中文 `；` 几乎总落在**英文同一句内部**，此时切分是纯粹的破坏。
    //
    // 实测（`Stage0SplitterVariantProbe`，真值 AI 译本 ch1/ch3）：
    //   变体                     ch1 句数相等段   ch1 标配对正确   ch3 句数相等段   ch3 标配对正确
    //   V0 现状（；算 + 直引号）    65.45%          93.85%          57.21%          96.64%
    //   V2 去分号（仅本条）         85.86%          97.43%          77.29%          96.29%
    //   V3 去分号 + 补弯引号        88.48%         **100.00%**      90.39%         **99.68%**
    //
    // 注意这是 [TranslationAligner] 的内部口径，**不是** TTS/阅读器的分句口径
    // （后者仍是 [SentenceSplitter]，中文 `；` 照旧算句界）——所以本改动不影响朗读
    // 与高亮，也不会让音频缓存失效。

    // DP 回溯用的走法编码（每格 1 字节，避免每格再分配对象）。
    private const val MOVE_NONE: Byte = 0
    private const val MOVE_SKIP_A: Byte = 1
    private const val MOVE_SKIP_B: Byte = 2
    private const val MOVE_ONE_ONE: Byte = 3
    private const val MOVE_TWO_ONE: Byte = 4
    private const val MOVE_ONE_TWO: Byte = 5

    /**
     * @param enChapters 每章 = 段落文本列表（英文原版，叶级段落）
     * @param zhChapters 每章 = 段落文本列表（中文译本，叶级段落）
     * @param meaning 词义锚点查询（可为 null：行为与旧版完全一致）
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>,
        meaning: MeaningIndex? = null
    ): List<AlignedSentencePair> = align(
        enChapters, zhChapters, meaning,
        sentencePositionPrior = SENTENCE_POSITION_PRIOR,
        paragraphPositionPrior = PARAGRAPH_POSITION_PRIOR
    )

    /**
     * 指定中文分句口径的入口（生产按译本来源分流时用；评测也走这里）。
     * 位置先验取生产默认值，调用方只需关心 [zhSplit]。
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>,
        meaning: MeaningIndex?,
        zhSplit: ZhSplitPolicy
    ): List<AlignedSentencePair> = align(
        enChapters, zhChapters, meaning,
        sentencePositionPrior = SENTENCE_POSITION_PRIOR,
        paragraphPositionPrior = PARAGRAPH_POSITION_PRIOR,
        zhSplit = zhSplit
    )

    /**
     * [align] 的内部调参入口：位置先验权重可逐档扫描（评测用），生产默认值见上方
     * [SENTENCE_POSITION_PRIOR] / [PARAGRAPH_POSITION_PRIOR]。传 0.0 = 与旧版行为完全一致。
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>,
        meaning: MeaningIndex?,
        sentencePositionPrior: Double,
        paragraphPositionPrior: Double,
        chapterPositionPrior: Double = CHAPTER_POSITION_PRIOR,
        zhSplit: ZhSplitPolicy = ZhSplitPolicy.PRODUCTION
    ): List<AlignedSentencePair> {
        if (enChapters.isEmpty() || zhChapters.isEmpty()) return emptyList()

        // 段落特征每篇只算一次；章节特征由段落特征聚合，不再拼接整章文本。
        val enParagraphSpans = enChapters.map { paragraphs -> paragraphs.map { englishSpan(it, meaning) } }
        val zhParagraphSpans = zhChapters.map { paragraphs -> paragraphs.map { chineseSpan(it) } }

        // 整本字/词密度：不依赖章配对（只对全书求和），供章节对齐与章级估计兜底用。
        val bookScale = bookParagraphScale(enParagraphSpans, zhParagraphSpans)
        val result = mutableListOf<AlignedSentencePair>()

        for ((enIdx, zhIdx) in alignChapters(enParagraphSpans, zhParagraphSpans, bookScale, chapterPositionPrior)) {
            val enParagraphs = enChapters[enIdx]
            val zhParagraphs = zhChapters[zhIdx]
            if (enParagraphs.isEmpty() || zhParagraphs.isEmpty()) continue

            val enSpans = enParagraphSpans[enIdx]
            val zhSpans = zhParagraphSpans[zhIdx]
            val scales = chapterScales(enParagraphs, zhParagraphs, enSpans, zhSpans, bookScale, zhSplit)

            // 记录哪些英文段落真的配上了，以及它落在哪个中文段落（供邻近兜底用）。
            val zhForEn = HashMap<Int, Int>()

            for (paragraphPair in alignSpans(
                enSpans, zhSpans, allowMerge = true, scale = scales.paragraph,
                positionPrior = paragraphPositionPrior
            )) {
                val enParagraph = join(enParagraphs, paragraphPair.a)
                val zhParagraph = joinChinese(zhParagraphs, paragraphPair.b)
                if (enParagraph.isBlank() || zhParagraph.isBlank()) continue
                for (index in paragraphPair.a) zhForEn[index] = paragraphPair.b[0]

                val enSentences = SentenceSplitter.split(enParagraph).contentOnly()
                val zhSentences = zhSentencesFor(enParagraph, zhParagraph, zhSplit)
                val enSentenceSpans = enSentences.map { englishSpan(it, meaning) }
                val zhSentenceSpans = zhSentences.map { chineseSpan(it) }

                val sentencePairs =
                    if (enSentences.isEmpty() || zhSentences.isEmpty()) emptyList()
                    else alignSpans(
                        enSentenceSpans, zhSentenceSpans, allowMerge = true,
                        // T5：英方 2:1 走法**在 DP 里恢复**（多英文句挤在一条中文句里是
                        // 真实译本形态，禁掉只会让第二句起全部失去句级条目——金标准
                        // s1/s19/s24/s27/s31/s32/s33 的根因）。落盘前会拆回单句
                        // （见下方 emitted 循环），所以档案里 enSentence 恒为单句，
                        // Q1-t04「点 B 拿回 A 句意思」的结构性病灶不会复现。
                        allowEnMerge = true,
                        mergeGate = { e1, e2, z1, z2 ->
                            sentenceMergeAllowed(e1, e2, z1, z2, scales.sentence)
                        },
                        scale = scales.sentence,
                        positionPrior = sentencePositionPrior
                    )

                // V4：低置信句对是 DP 残渣（实测整本魔戒 2% 的句子精确命中这类对：
                // 27 词英文配上「啊！」、70 词配上 16 字）。查询侧 1–3 级是文本精确
                // 命中、不受置信度门槛限制，落盘必被原样展示成「只翻译了其中一句」。
                // 宁可不产，不可错配：低于门槛的句对不落盘，让这些句子走段落级兜底。
                val emitted = mutableListOf<AlignedSentencePair>()
                for (sentencePair in sentencePairs) {
                    val raw = confidenceOf(
                        enSentenceSpans, sentencePair.a,
                        zhSentenceSpans, sentencePair.b,
                        scales.sentence
                    )
                    // 合并句对（1:N）是真配对但粒度跳，置信度轻折扣。
                    val confidence =
                        if (sentencePair.a.size > 1 || sentencePair.b.size > 1) {
                            (raw * SENTENCE_MERGE_SCALE).coerceIn(MIN_CONFIDENCE, 1f)
                        } else raw
                    if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue
                    // V5：长度比硬门槛——置信度被锚点/词义加分拉高、但长度比
                    // 崩坏的错对（用户主诉「长度明显不匹配的对照」）同样不落盘。
                    // 合并对比例天然偏高（引文整句 vs 短英文引导），上限放宽一档。
                    val ratio = lengthRatioOf(
                        enSentenceSpans, sentencePair.a, zhSentenceSpans, sentencePair.b, scales.sentence
                    )
                    val merged = sentencePair.a.size > 1 || sentencePair.b.size > 1
                    val maxRatio = if (merged) SENTENCE_MAX_MERGED_LENGTH_RATIO else SENTENCE_MAX_LENGTH_RATIO
                    if (ratio < SENTENCE_MIN_LENGTH_RATIO || ratio > maxRatio) continue
                    val zhSentence = joinChinese(zhSentences, sentencePair.b)
                    if (sentencePair.a.size > 1) {
                        // T5：英方 2:1 合并**落盘时拆回单句**——每个被合并英文句各出一条
                        // 句对，共享同一条 zhSentence。档案里 enSentence 恒为单句，查询侧
                        // 各自精确命中（不再有 "A. B." 串让点 B 命中子串匹配）。
                        // 门槛与置信度按**合并对整体**判定一次（拆分后的单句对整条中文句
                        // 的长度比天然超标，按单句口径复核会把它们全部拒掉，见任务汇报）。
                        //
                        // V10：**先把合并串按句切开**（[splitMergedChinese]）。只共享
                        // 整串时，每条英文句都会显示「A+B」的全文中译——实测这是 AI 译本
                        // 剩余静默错的**主要成分**（ch1 5.19%/ch3 4.79%），而真错配只有
                        // 0.00%/0.14%。切开后每条只拿自己那段，静默错直接消掉。
                        val parts = splitMergedChinese(
                            zhSentence, sentencePair.a.size,
                            sentencePair.a.map { enSentenceSpans[it].words }
                        )
                        for ((order, index) in sentencePair.a.withIndex()) {
                            emitted += AlignedSentencePair(
                                enChapter = enIdx,
                                zhChapter = zhIdx,
                                enParagraph = enParagraph,
                                zhParagraph = zhParagraph,
                                enSentence = enSentences[index],
                                zhSentence = parts?.getOrNull(order) ?: zhSentence,
                                confidence = confidence
                            )
                        }
                    } else {
                        emitted += AlignedSentencePair(
                            enChapter = enIdx,
                            zhChapter = zhIdx,
                            enParagraph = enParagraph,
                            zhParagraph = zhParagraph,
                            enSentence = join(enSentences, sentencePair.a),
                            zhSentence = zhSentence,
                            confidence = confidence
                        )
                    }
                }

                if (emitted.isEmpty()) {
                    // 句子级无法对齐（或全部低于置信门槛）→ 降级为段落对照。
                    result += AlignedSentencePair(
                        enChapter = enIdx,
                        zhChapter = zhIdx,
                        enParagraph = enParagraph,
                        zhParagraph = zhParagraph,
                        enSentence = "",
                        zhSentence = "",
                        confidence = confidenceOf(
                            enSpans, paragraphPair.a, zhSpans, paragraphPair.b, scales.paragraph
                        )
                    )
                } else {
                    result += emitted
                }

                // 2:1 合并时存下来的 enParagraph 是两段拼起来的文本，用户点其中一段时
                // 段落文本对不上（标题、诗行这类不以句末标点结尾的段落连句子也切不出来）。
                // 为每个成分段落补一条段级条目，让这种点词至少落在正确的中文段落上。
                if (paragraphPair.a.size > 1) {
                    val merged = confidenceOf(
                        enSpans, paragraphPair.a, zhSpans, paragraphPair.b, scales.paragraph
                    )
                    for (index in paragraphPair.a) {
                        val constituent = enParagraphs[index]
                        if (constituent.isBlank()) continue
                        result += AlignedSentencePair(
                            enChapter = enIdx,
                            zhChapter = zhIdx,
                            enParagraph = constituent,
                            zhParagraph = zhParagraph,
                            enSentence = "",
                            zhSentence = "",
                            confidence = (merged * MERGED_CONSTITUENT_SCALE)
                                .coerceIn(MIN_CONFIDENCE, 1f)
                        )
                    }
                }
            }

            result += neighbourFallbacks(
                enChapter = enIdx,
                zhChapter = zhIdx,
                enParagraphs = enParagraphs,
                zhParagraphs = zhParagraphs,
                enSpans = enSpans,
                zhSpans = zhSpans,
                zhForEn = zhForEn,
                meaning = meaning,
                scales = scales,
                sentencePositionPrior = sentencePositionPrior,
                zhSplit = zhSplit
            )
        }
        return result
    }

    // --- 邻近段落兜底 --------------------------------------------------------

    /**
     * 段落级 DP 只允许 1:1 / 2:1 / 1:2，所以英文段落数超过中文段落数两倍时，多出来的
     * 只能被跳过（实测整本魔戒有 13.3% 的段落落在这里，用户在这些段落里点词完全看不到
     * 对照）。这里把被跳过的段落挂到**下标最近**的已对齐段落所对应的中文段落上：
     *  - 先跑一次句级 DP 产出句级对照（V3）：被跳过段落的句子有真实代价函数可依，
     *    不再只有「整段一锅端」；置信度同样乘 [NEIGHBOUR_CONFIDENCE_SCALE] ÷ 距离；
     *  - 段级条目保留（查询侧 4/5 级降级用）。
     * 低于 [TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE] 的直接不产出：查询阶段反正
     * 会拒掉，落盘只是白占体积。
     */
    private fun neighbourFallbacks(
        enChapter: Int,
        zhChapter: Int,
        enParagraphs: List<String>,
        zhParagraphs: List<String>,
        enSpans: List<Span>,
        zhSpans: List<Span>,
        zhForEn: Map<Int, Int>,
        meaning: MeaningIndex?,
        scales: LengthScales,
        sentencePositionPrior: Double,
        zhSplit: ZhSplitPolicy = ZhSplitPolicy.PRODUCTION
    ): List<AlignedSentencePair> {
        if (zhForEn.isEmpty()) return emptyList()
        val covered = zhForEn.keys.toIntArray()
        covered.sort()

        val fallbacks = mutableListOf<AlignedSentencePair>()
        for (index in enParagraphs.indices) {
            if (zhForEn.containsKey(index)) continue
            val enParagraph = enParagraphs[index]
            if (enParagraph.isBlank()) continue
            val nearest = nearestCovered(index, covered) ?: continue
            val zhIndex = zhForEn[nearest] ?: continue
            val zhParagraph = zhParagraphs.getOrNull(zhIndex) ?: continue
            if (zhParagraph.isBlank()) continue

            val distance = abs(nearest - index).coerceAtLeast(1)

            // 句级兜底（V3）：句级 DP 出来的配对仍是真代价函数的选择，只是段落对应近似。
            val enSentences = SentenceSplitter.split(enParagraph).contentOnly()
            val zhSentences = zhSentencesFor(enParagraph, zhParagraph, zhSplit)
            if (enSentences.isNotEmpty() && zhSentences.isNotEmpty()) {
                val enSentenceSpans = enSentences.map { englishSpan(it, meaning) }
                val zhSentenceSpans = zhSentences.map { chineseSpan(it) }
                for (sentencePair in alignSpans(
                    enSentenceSpans, zhSentenceSpans, allowMerge = true,
                    // 与主路径同一套句级门槛（V7 起；此前这里写死 allowMerge=false，
                    // 覆盖全书的被跳过段落只能整段一锅端）。此路径已在
                    // [neighbourFallbacks] 入参里拿到 meanings，走的是有词义证据的
                    // 正常分支，不是 [sentenceMergeAllowed] 的无证据保守回退。
                    // 英方 2:1 走法与主路径一致恢复（T5），落盘同样拆回单句。
                    allowEnMerge = true,
                    mergeGate = { e1, e2, z1, z2 ->
                        sentenceMergeAllowed(e1, e2, z1, z2, scales.sentence)
                    },
                    scale = scales.sentence,
                    positionPrior = sentencePositionPrior
                )) {
                    val merged = sentencePair.a.size > 1 || sentencePair.b.size > 1
                    val cost = pairCost(
                        enSentenceSpans[sentencePair.a[0]],
                        sentencePair.a.getOrNull(1)?.let { enSentenceSpans[it] },
                        zhSentenceSpans[sentencePair.b[0]],
                        sentencePair.b.getOrNull(1)?.let { zhSentenceSpans[it] },
                        scales.sentence
                    )
                    // 合并对置信度折扣与主路径一致（真配对，粒度跳）。
                    val scaleFactor = if (merged) SENTENCE_MERGE_SCALE.toDouble() else 1.0
                    val confidence = ((1.0 - cost) * scaleFactor * NEIGHBOUR_CONFIDENCE_SCALE / distance)
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                    if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue
                    // V5：兜底句对同样受长度比硬门槛约束（s78/s79 型：71 词英文
                    // 配 13 字中文、置信度 0.35 过了门槛——长度比 0.11 一票否决）。
                    // 合并对的比例上限沿用放宽一档的口径，与主路径一致。
                    val ratio = lengthRatioOf(
                        enSentenceSpans, sentencePair.a, zhSentenceSpans, sentencePair.b, scales.sentence
                    )
                    val maxRatio =
                        if (merged) SENTENCE_MAX_MERGED_LENGTH_RATIO else SENTENCE_MAX_LENGTH_RATIO
                    if (ratio < SENTENCE_MIN_LENGTH_RATIO || ratio > maxRatio) continue
                    val zhSentence = joinChinese(zhSentences, sentencePair.b)
                    if (sentencePair.a.size > 1) {
                        // T5：与主路径同一契约——2:1 合并落盘拆回单句，共享同一条中文句。
                        for (i in sentencePair.a) {
                            fallbacks += AlignedSentencePair(
                                enChapter = enChapter,
                                zhChapter = zhChapter,
                                enParagraph = enParagraph,
                                zhParagraph = zhParagraph,
                                enSentence = enSentences[i],
                                zhSentence = zhSentence,
                                confidence = confidence
                            )
                        }
                    } else {
                        fallbacks += AlignedSentencePair(
                            enChapter = enChapter,
                            zhChapter = zhChapter,
                            enParagraph = enParagraph,
                            zhParagraph = zhParagraph,
                            enSentence = join(enSentences, sentencePair.a),
                            zhSentence = zhSentence,
                            confidence = confidence
                        )
                    }
                }
            }

            val base = 1.0 - pairCost(enSpans[index], null, zhSpans[zhIndex], null, scales.paragraph)
            val confidence = (base * NEIGHBOUR_CONFIDENCE_SCALE / distance)
                .coerceIn(0.0, 1.0)
                .toFloat()
            if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue

            fallbacks += AlignedSentencePair(
                enChapter = enChapter,
                zhChapter = zhChapter,
                enParagraph = enParagraph,
                zhParagraph = zhParagraph,
                enSentence = "",
                zhSentence = "",
                confidence = confidence
            )
        }
        return fallbacks
    }

    private fun nearestCovered(index: Int, sorted: IntArray): Int? {
        if (sorted.isEmpty()) return null
        val found = java.util.Arrays.binarySearch(sorted, index)
        if (found >= 0) return sorted[found]
        val insert = -found - 1
        val before = if (insert > 0) sorted[insert - 1] else null
        val after = if (insert < sorted.size) sorted[insert] else null
        return when {
            before == null -> after
            after == null -> before
            index - before <= after - index -> before
            else -> after
        }
    }

    // --- 片段特征（进 DP 之前算好） ------------------------------------------

    /**
     * 一个待对齐片段的预计算特征。[anchors] 只在英文侧有值，[latin] 只在中文侧有值。
     */
    private class Span(
        val words: Int,
        val chars: Int,
        val anchors: Set<String>,
        val latin: Set<String>,
        /** 英文侧：词义锚短语并集（简繁归一后）；中文侧：2–4 字连续汉字子串集合。 */
        val meaning: Set<String>
    )

    private fun englishSpan(text: String, meaning: MeaningIndex?): Span {
        val phrases = HashSet<String>()
        if (meaning != null) {
            for (word in EN_WORDS.findAll(text.lowercase())) {
                val w = word.value.trim('\'')
                if (w.length >= 2) phrases += meaning.phrasesOf(w)
            }
        }
        return Span(
            words = countWords(text),
            chars = countChars(text),
            anchors = ANCHORS.findAll(text).mapTo(HashSet()) { it.value.lowercase() },
            latin = emptySet(),
            meaning = phrases
        )
    }

    private fun chineseSpan(text: String): Span {
        // 词义锚命中面：繁简归一后的全部 2–4 字连续汉字子串（预计算，DP 内层 O(1) 查表）。
        val simplified = TraditionalSimplified.toSimplified(text)
        val subjects = HashSet<String>()
        for (run in HAN_RUNS.findAll(simplified)) {
            val s = run.value
            for (i in 0 until s.length) {
                for (len in 2..4) {
                    if (i + len > s.length) break
                    subjects.add(s.substring(i, i + len))
                }
            }
        }
        return Span(
            words = countWords(text),
            chars = countChars(text),
            anchors = emptySet(),
            // 中文侧只需要「句中出现过哪些拉丁/数字词」，锚点判定退化成哈希查表。
            latin = LATIN_RUNS.findAll(text).mapTo(HashSet()) { it.value.lowercase() },
            meaning = subjects
        )
    }

    private fun foldSpans(spans: List<Span>): Span {
        var words = 0
        var chars = 0
        val anchors = HashSet<String>()
        val latin = HashSet<String>()
        val meaning = HashSet<String>()
        for (span in spans) {
            words += span.words
            chars += span.chars
            anchors += span.anchors
            latin += span.latin
            meaning += span.meaning
        }
        return Span(words, chars, anchors, latin, meaning)
    }

    private fun countWords(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    private fun countChars(text: String): Int = text.count { !it.isWhitespace() }

    // --- 章节对齐 -----------------------------------------------------------

    private fun alignChapters(
        en: List<List<Span>>,
        zh: List<List<Span>>,
        scale: Double,
        positionPrior: Double
    ): List<Pair<Int, Int>> {
        if (en.size == zh.size) return en.indices.map { it to it }
        val enSpans = en.map { foldSpans(it) }
        val zhSpans = zh.map { foldSpans(it) }
        // 直接用 DP 给出的下标。不要拿文本去 indexOf 回查：两章正文完全相同
        // （或都为空）时会全部映射到第一处，导致整章错配。
        return alignSpans(enSpans, zhSpans, allowMerge = false, scale = scale, positionPrior = positionPrior)
            .map { it.a[0] to it.b[0] }
    }

    /** 整本字/词密度（不依赖章配对）；拿不到时退回全局常量。 */
    private fun bookParagraphScale(en: List<List<Span>>, zh: List<List<Span>>): Double {
        var enWords = 0
        for (chapter in en) for (span in chapter) enWords += span.words
        var zhChars = 0
        for (chapter in zh) for (span in chapter) zhChars += span.chars
        if (enWords < MIN_ADAPTIVE_WORDS || zhChars == 0) return ZH_CHARS_PER_EN_WORD
        return clampScale(zhChars.toDouble() / enWords)
    }

    /**
     * 本章的两级密度（V6 核心）：
     *  - 段级 = 本章中文字符数 / 英文词数；
     *  - 句级 = 平均中文句长 / 平均英文句长（用与 DP 相同的分句规则计数）。
     * 空章/空白章退回整本密度；两者都钳制到 [MIN_ADAPTIVE_SCALE, MAX_ADAPTIVE_SCALE]。
     */
    private fun chapterScales(
        enParagraphs: List<String>,
        zhParagraphs: List<String>,
        enSpans: List<Span>,
        zhSpans: List<Span>,
        bookScale: Double,
        zhSplit: ZhSplitPolicy = ZhSplitPolicy.PRODUCTION
    ): LengthScales {
        var enWords = 0
        for (span in enSpans) enWords += span.words
        var zhChars = 0
        for (span in zhSpans) zhChars += span.chars
        if (enWords < MIN_ADAPTIVE_WORDS || zhChars == 0) return LengthScales(bookScale, bookScale)
        val paragraph = clampScale(zhChars.toDouble() / enWords)

        // 中英两侧都用**与对齐 DP 相同的分句器**做精确计数（V8 起，第四轮审查 2-4 的
        // 「已知例外」就此消除）：英文 = SentenceSplitter.count，中文 = splitChinese +
        // contentOnly，两者与各自 DP 用的句子列表逐条同源，密度先验不再有计数偏差。
        val enSentences = countSentencesExact(enParagraphs)
        val zhSentences = countChineseSentencesExact(zhParagraphs, zhSplit)
        if (enSentences == 0 || zhSentences == 0) return LengthScales(paragraph, paragraph)
        val sentence = clampScale(
            (zhChars.toDouble() / zhSentences) / (enWords.toDouble() / enSentences)
        )
        return LengthScales(paragraph, sentence)
    }

    private fun clampScale(value: Double): Double =
        value.coerceIn(MIN_ADAPTIVE_SCALE, MAX_ADAPTIVE_SCALE)

    /**
     * 英文侧句数**精确**计数：调用与句子对齐同一个 [SentenceSplitter.count]。
     * 第四轮审查 2-4（口径 A）：偏差从近似的 1.65% 降到 0。
     */
    private fun countSentencesExact(texts: List<String>): Int {
        var total = 0
        for (text in texts) total += SentenceSplitter.count(text)
        return total
    }

    /**
     * 中文侧句数**精确**计数：用与句子 DP 完全相同的 [splitChinese] + contentOnly 口径
     * （[terminators] 默认即 [ZH_SENTENCE_END]，与 DP 同一套规则）。
     *
     * V8 起取代旧的「终止符游程」近似计数（偏差约 50%）。旧近似是第四轮审查 2-4 记录的
     * 「已知例外」——当时换精确分句会把 Proverbs 节级覆盖率压到 0.674 < 下限 0.68；
     * V8 有段级位置先验（[PARAGRAPH_POSITION_PRIOR]）托底后重测，三书覆盖率
     * 0.852/0.848/0.854 远高于下限，例外消除。
     *
     * 实测（V8，位置先验 p=0.5；同节/±1/覆盖率）：
     *   近似计数（T3 现状）：John 0.915/0.989/0.850 Genesis 0.916/0.991/0.844 Proverbs 0.883/0.989/0.854
     *   精确计数（本文）：  John 0.916/0.989/0.852 Genesis 0.917/0.991/0.848 Proverbs 0.883/0.989/0.854
     * 魔戒 benchmark：句级命中 2774→2791、段级兜底 1172→1155、整本耗时 674→672ms。
     *
     * 代价：章内多跑一遍中文分句（DP 里本就要分一遍），相对整章 DP 开销可忽略。
     */
    /**
     * 中文分句口径。
     *
     * @property terminators 句界正则。
     * @property closingLeads 出现在片段开头时并入前句的闭合引号/括号（R2）。
     * @property speechTagMaxChars 闭合引号后剩余文本 ≤ 本值且像说话人引导语时并入前句；
     *   null = 无条件并入（V9 生产值，实测最优）。
     * @property semicolonIsBoundary 中文 `；` 是否算句界；**null = 按段自适应**。
     * @property knownSentences 构造性句界：中文段落原文 → 该段的句子列表。命中时**完全跳过**
     *   中文分句推断（P3 的落地形态；也是离线评测「句界完美上界」的入口）。默认 null = 不启用。
     */
    data class ZhSplitPolicy(
        val terminators: Regex,
        val closingLeads: Set<Char> = ZH_CLOSING_LEADS,
        val speechTagMaxChars: Int? = null,
        val semicolonIsBoundary: Boolean? = null,
        val knownSentences: Map<String, List<String>>? = null
    ) {
        companion object {
            /** 含 `；` 的口径（V8 及更早；**人译本最优**，见 [forAiTranslation]）。 */
            val WITH_SEMICOLON = ZhSplitPolicy(
                terminators = ZH_SENTENCE_END_WITH_SEMICOLON,
                semicolonIsBoundary = true
            )

            /** 不含 `；` 的口径（V9；**AI 译本最优**，见 [forAiTranslation]）。 */
            val WITHOUT_SEMICOLON = ZhSplitPolicy(
                terminators = ZH_SENTENCE_END,
                semicolonIsBoundary = false
            )

            /**
             * 用调用方给的构造性句界（P3）。批次校验失败时应**回退**到
             * [forAiTranslation]，不要因为句界缺失就整批失败。
             */
            fun withKnownSentences(known: Map<String, List<String>>): ZhSplitPolicy =
                WITHOUT_SEMICOLON.copy(knownSentences = known)

            /**
             * **生产值 = 按译本来源分流**。
             *
             * 中文 `；` 在两种语料里用法相反，且两者都已端到端实测：
             *
             * | 语料 | 口径 | 指标 |
             * | --- | --- | --- |
             * | AI 译本（真值语料） | 不含 `；` | 静默错 ch1 10.68%→**6.94%**、ch3 8.24%→**5.87%** |
             * | 人译本（朱學恆，整本） | 含 `；` | 句级命中 **84.29%** vs 83.28%（V9 低 1.01pp） |
             *
             * 即 AI 译本用 V9 口径是大改善，人译本用 V8 口径小胜。既然调用方**本来就知道**
             * 译本来源（`Book.isAiTranslation` / `Book.AI_TRANSLATION_ID_PREFIX`），
             * 就不该让对齐器去猜（猜的那条路已实测否决，见 [chooseZhSentences]）。
             */
            fun forAiTranslation(isAiTranslation: Boolean): ZhSplitPolicy =
                if (isAiTranslation) WITHOUT_SEMICOLON else WITH_SEMICOLON

            /**
             * 默认生产值。**注意**：`TranslationAligner.align` 的默认参数必须是编译期常量，
             * 拿不到译本来源，所以这里取「覆盖 AI 与人译本都吃到主要收益」的一侧——
             * 实际生产路径由 `TranslationMemoryRepository` 显式传入 [forAiTranslation]。
             */
            val PRODUCTION = WITHOUT_SEMICOLON

            /** 固定口径（评测用）。 */
            fun fixed(withSemicolon: Boolean) =
                if (withSemicolon) WITH_SEMICOLON else WITHOUT_SEMICOLON

            /**
             * R2 选择性合并：只有「短 + 含说话动词 + 无逗号」的尾部才并入前句。
             * 实测在 AI 译本上劣于无条件合并（见 [VERSION] 的 v9 说明），保留供复测。
             */
            fun selectiveSpeechTag(maxChars: Int) =
                ZhSplitPolicy(terminators = ZH_SENTENCE_END, speechTagMaxChars = maxChars)
        }
    }

    private val SPEECH_VERB = Regex("说|道|问|答|喊|叫|嚷|叹|嘟囔|咕哝|低语|耳语|回答|补充")

    private fun looksLikeSpeechTag(rest: String, maxChars: Int): Boolean {
        if (rest.length > maxChars) return false
        if (rest.any { it in "，,；;" }) return false
        return SPEECH_VERB.containsMatchIn(rest)
    }

    /**
     * 按段选择中文分句口径 —— **已实测否决，保留仅为复测**。
     *
     * ## 背景
     *
     * 中文 `；` 在两种语料里的用法相反：**AI 译本**里它几乎总落在英文同一句内部
     * （当句界是破坏），**人译本**里译者会用它分隔两个英文句（当句界才对，
     * 金标准 s9/s20 就是这样）。所以「全局固定一种口径」看起来必然有损。
     *
     * ## 实测结论：自适应不值得
     *
     * `SemicolonPolicyProbe`（朱學恆人译本 2806 段）确实显示逐段自适应的**句数相等率**
     * 最高（19.96% vs 算 18.18% / 不算 18.85%），但 `Stage0PolicyEndToEndProbe`
     * 端到端跑真 DP 后，用户代价反而略差：
     *   ch1 自适应 **13.47** vs 不含 `；` 13.71（自适应好 0.24）
     *   ch3 自适应 **14.60** vs 不含 `；` 13.47（自适应差 1.13）
     * 净负，且因为要对含 `；` 的段跑第二遍分句，耗时增加。
     *
     * 根因：**「句数相等」不等于「切点正确」**。自适应为一个「差 1 变差 0」的段落
     * 接受了错误的切点，把原本 DP 能靠代价函数纠正的段落反而固定成了错的。
     *
     * **后人别再走这条路**：真要按语料分流，应当由**上游**（知道译文来源是 AI 还是
     * 人工的调用方）显式传入口径，而不是在对齐器里猜。
     */
    private fun chooseZhSentences(
        enParagraph: String,
        zhParagraph: String,
        requireExactMatch: Boolean = true
    ): List<String> {
        val plain = splitChinese(zhParagraph, ZhSplitPolicy.WITHOUT_SEMICOLON).contentOnly()
        // 快路径：没有分号（全角/半角）时两种口径必然一致。
        if (zhParagraph.indexOf('；') < 0 && zhParagraph.indexOf(';') < 0) return plain
        val withSemi = splitChinese(zhParagraph, ZhSplitPolicy.WITH_SEMICOLON).contentOnly()
        if (withSemi.size == plain.size) return plain
        val enCount = SentenceSplitter.count(enParagraph)
        val plainDist = abs(plain.size - enCount)
        val semiDist = abs(withSemi.size - enCount)
        return when {
            requireExactMatch -> if (semiDist == 0 && plainDist > 0) withSemi else plain
            semiDist < plainDist -> withSemi
            else -> plain
        }
    }

    /**
     * 分派到具体口径：`semicolonIsBoundary == null` 时按段自适应，否则用固定口径。
     * 两条路径（主对齐与邻近兜底）都走这里，保证口径一致。
     */
    private fun zhSentencesFor(
        enParagraph: String,
        zhParagraph: String,
        policy: ZhSplitPolicy
    ): List<String> {
        // 构造性句界优先：调用方（AI 翻译链路）若已知该段的句子切分，直接用，
        // 完全不参与中文分句推断。这是 P3 的落地形态，也是离线评测「句界完美上界」的入口。
        // 注意键的口径：`zhParagraph` 来自 `TtsTextExtractor`（空白已折叠成单空格并 trim），
        // 调用方必须用同样的归一化建键，否则查表静默落空。
        policy.knownSentences?.get(zhParagraph)?.let { known ->
            if (known.size > 1) return known.filter { it.isNotBlank() }
        }
        return when (policy.semicolonIsBoundary) {
            null -> chooseZhSentences(enParagraph, zhParagraph)
            true -> splitChinese(zhParagraph, policy).contentOnly()
            false -> splitChinese(zhParagraph, policy).contentOnly()
        }
    }

    /**
     * 把「N 个英文句 → 1 条中文合并句」的译文按中文句读边界切成 N 段（V10）。
     *
     * ## 为什么必须切
     *
     * T5 起英方 2:1 是合法走法，落盘时会把被合并的两条英文各写成一条句对，**共享同一条
     * 中文串**。那让每条英文句都显示「A+B」的全文中译——用户点 A 看到 A+B、点 B 也看到
     * A+B。实测这是 AI 译本剩余静默错的**主要成分**（ch1 5.19% / ch3 4.79%），
     * 而真正的错配（OFFSET）只有 0.00% / 0.14%。所以切开的收益是纯的。
     *
     * ## 切法
     *
     * 中文句读点（`。！？!?` 及其后的闭合引号）天然是候选边界。选出 `N-1` 个切点，使
     * 「每段中文字数 / 对应英文词数」的方差最小——即让各段的中英长度比尽量一致。
     * 切点不足（整串只有一个句读）或切完出现空段时返回 `null`，调用方回退共享整串
     * （行为与 V9 完全一致，不会更差）。
     *
     * @param merged 合并后的中文串
     * @param count 英文句数（≥2）
     * @param enWords 每条英文句的词数，用于按比例分配
     */
    private fun splitMergedChinese(merged: String, count: Int, enWords: List<Int>): List<String>? {
        if (count < 2 || merged.isEmpty()) return null
        // 候选边界：句读符 + 其后可跟闭合引号/括号。
        //
        // `speechTag` 标记这个切点后面是否紧接**说话人引导语**（`。」他說：「…`）。
        // 它**不做硬禁止**而是加代价：对话密集语料（ch3）里这种位置既有「一句英文 +
        // 说话人插入」也有「两句英文」，硬禁止会把该切的也挡掉（实测静默错 2.82%→3.31%）；
        // 让长度比证据主导、引导语只作惩罚，两侧都取到最优（2.87%/2.82%）。
        val boundaries = ArrayList<Int>()
        val speechTagPenalty = ArrayList<Boolean>()
        var i = 0
        while (i < merged.length) {
            if (merged[i] in ZH_MERGE_BREAKS) {
                var j = i + 1
                while (j < merged.length && merged[j] in ZH_MERGE_CLOSERS) j++
                if (j < merged.length) {
                    boundaries += j
                    speechTagPenalty += opensSpeechTag(merged, j)
                }
                i = j
            } else i++
        }
        if (boundaries.size < count - 1) return null

        // 在候选边界里选 count-1 个，使各段中英长度比的方差最小（穷举，候选通常 <20）。
        var best: IntArray? = null
        var bestCost = Double.MAX_VALUE
        val picks = IntArray(count - 1)

        fun evaluate(): Double {
            var start = 0
            var enCum = 0
            val ratios = DoubleArray(count)
            for (k in 0 until count - 1) {
                val end = picks[k]
                val zhLen = end - start
                enCum += enWords.getOrElse(k) { 1 }
                ratios[k] = if (enCum == 0) 0.0 else zhLen.toDouble() / enCum
                start = end
            }
            val zhLen = merged.length - start
            enCum += enWords.getOrElse(count - 1) { 1 }
            ratios[count - 1] = if (enCum == 0) 0.0 else zhLen.toDouble() / enCum
            val mean = ratios.average()
            if (mean <= 0.0) return Double.MAX_VALUE
            var cost = 0.0
            for (r in ratios) {
                val d = r - mean
                cost += d * d
                if (r <= 0.0) return Double.MAX_VALUE // 空段直接淘汰
            }
            // 切点落在「引语 + 说话人引导语」交界处时加罚：那种切法会把说话人标签
            // 和前一句的引语拆开，两侧都读不通。罚值相对长度方差足够大但可被更强的
            // 长度证据压过。
            for (k in 0 until count - 1) {
                val idx = boundaries.indexOf(picks[k])
                if (idx >= 0 && speechTagPenalty[idx]) cost += SPEECH_TAG_CUT_PENALTY
            }
            return cost / (mean * mean)
        }

        fun search(depth: Int, from: Int) {
            if (depth == count - 1) {
                val c = evaluate()
                if (c < bestCost) { bestCost = c; best = picks.copyOf() }
                return
            }
            // 留足后续切点与剩余字符（每段至少 1 字符）
            val maxIdx = boundaries.size - (count - 1 - depth)
            for (k in from..maxIdx) {
                picks[depth] = boundaries[k]
                search(depth + 1, k + 1)
            }
        }
        search(0, 0)

        val chosen = best ?: return null
        val result = ArrayList<String>(count)
        var start = 0
        for (b in chosen) {
            result += merged.substring(start, b).trim()
            start = b
        }
        result += merged.substring(start).trim()
        if (result.any { it.isEmpty() }) return null

        // **长度比护栏**：每段必须过与落盘同一条长度比门槛。
        // 没有它，切点会被「无参引语 + 说话人标签」这类形态骗过去（`‘Wake up, hobbits!’
        // he cried.` 被切成「醒醒！」/「他喊道。」→ 两条都对不上）。宁可整条共享（V9 行为），
        // 也不产错切——用户代价上「整串」与「错切」同为静默错，但错切更难解释。
        // scale 取全串的实测比例：不同语料的中英长度密度不同，用实测值而非全局常量。
        val zhChars = result.sumOf { s -> s.count { !it.isWhitespace() } }
        val totalEn = enWords.sum().coerceAtLeast(1)
        val scale = (zhChars.toDouble() / totalEn).coerceAtLeast(0.1)
        for ((k, part) in result.withIndex()) {
            val w = enWords.getOrElse(k) { 1 }.coerceAtLeast(1)
            val partChars = part.count { !it.isWhitespace() }
            val ratio = partChars / (w * scale)
            if (ratio < SENTENCE_MIN_LENGTH_RATIO || ratio > SENTENCE_MAX_LENGTH_RATIO) return null
        }
        return result
    }

    /** 合并串的候选切点：中英文句读符。 */
    private val ZH_MERGE_BREAKS = setOf('。', '！', '？', '!', '?', '；', ';', '…')

    /**
     * 切点落在「引语 + 说话人引导语」交界处的代价罚值（相对归一化长度方差）。
     * 实测：硬禁止会让 ch3 静默错 2.82%→3.31%，软罚值两侧都最优。
     */
    private const val SPEECH_TAG_CUT_PENALTY = 0.10

    /** 说话人引导语的终止字符：冒号与开引号（`他說：「…`），见 [opensSpeechTag]。 */
    private val ZH_SPEECH_TAG_STOPS = setOf(
        '：', ':', '「', '『', '“', '‘', '（', '【', '［', '(', '['
    )

    /** 句读符之后可能紧跟、应归入**前**一段的闭合字符。 */
    private val ZH_MERGE_CLOSERS = setOf('」', '』', '”', '’', '"', '\'', '）', '】', '］', ')', ']')

    /**
     * `pos` 起的剩余文本是否以**说话人引导语**开头（`他說：「…` / `甘道夫摇着头说。`）。
     *
     * 是的话，这个句读点不能当切点——中文把「引语」和随后的「说话人标签 + 下一句引语」
     * 写在一起是常态（`。」他說：「…」`），从中间切开会让前半段丢掉归属。
     * 判据用「短 + 含说话动词 + 不含逗号」的宽松形态，与 [looksLikeSpeechTag] 同源。
     */
    private fun opensSpeechTag(text: String, pos: Int): Boolean {
        // 跳过空白与闭合引号
        var k = pos
        while (k < text.length && (text[k].isWhitespace() || text[k] in ZH_MERGE_CLOSERS)) k++
        if (k >= text.length) return false
        // 到下一个句读符 / 冒号 / **开引号**为止。冒号与开引号必须也是终止条件：
        // `一個聲音說：「如果你想知道得更清楚一點…` 里 head 若一路吃到下一个句点，
        // 长度会远超说话人引导语的合理范围，形态判定必然失败（实测 s28/s34 就是这样漏掉的）。
        var end = k
        while (end < text.length &&
            text[end] !in ZH_MERGE_BREAKS &&
            text[end] !in ZH_SPEECH_TAG_STOPS
        ) end++
        val head = text.substring(k, end).trim()
        if (head.isEmpty()) return false
        // 「引号后直接续新引语」（`「A。」「B。」`）不是说话人标签，是真的句界。
        if (head.firstOrNull() in ZH_MERGE_CLOSERS) return false
        return looksLikeSpeechTag(head, maxChars = 12)
    }

    private fun countChineseSentencesExact(
        texts: List<String>,
        policy: ZhSplitPolicy = ZhSplitPolicy.PRODUCTION
    ): Int {
        var total = 0
        // 章节密度估计只数句数、拿不到英文句数做参照；自适应口径下取不含 `；`
        // （V9 已验证值），它是「句更少」的一侧，对密度先验的影响可忽略。
        val effective = if (policy.semicolonIsBoundary == null) {
            ZhSplitPolicy.WITHOUT_SEMICOLON
        } else policy
        for (text in texts) total += splitChinese(text, effective).contentOnly().size
        return total
    }

    // --- 通用单调序列对齐 ----------------------------------------------------

    /** 一组对齐结果：[a]/[b] 各是被对齐到一起的下标（1 个或合并的 2 个）。 */
    private class SpanPair(val a: IntArray, val b: IntArray)

    /**
     * 用 DP 把两个片段序列单调对齐。允许 1:0 / 0:1 / 1:1，[allowMerge] 时额外允许
     * 2:1 / 1:2。[allowEnMerge] 单独控制 2:1（两 a : 一 b）——句级路径自 T5 起开启它，
     * 靠调用方在落盘时拆回单句来维持「档案 enSentence 恒单句」的不变式（理由见 [VERSION]
     * 的 v7/T5 说明）；保留该开关是为了给需要硬禁合并的调用方留口子。
     * [mergeGate] 非空时，每个合并走法还要过一道准入检查（句级 1:N 的
     * 语义/尺寸/边际门槛；段落级合并不设门槛）。
     * 返回「已对齐的下标对」（跳过项不产出）。
     *
     * dp 只保留最近三行（2:1 / 1:2 会回看 i−2、j−2），回溯靠每格 1 字节的走法矩阵，
     * 因此内存是 O(n·m) 字节而不是 O(n·m) 个 double + 对象。
     */
    private fun alignSpans(
        a: List<Span>,
        b: List<Span>,
        allowMerge: Boolean,
        mergeGate: ((Span, Span?, Span, Span?) -> Boolean)? = null,
        allowEnMerge: Boolean = true,
        scale: Double,
        positionPrior: Double = 0.0
    ): List<SpanPair> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        var prev2 = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var prev1 = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var cur = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        val moves = Array(n + 1) { ByteArray(m + 1) }

        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) {
                    cur[0] = 0.0
                    moves[0][0] = MOVE_NONE
                    continue
                }
                var best = Double.POSITIVE_INFINITY
                var move = MOVE_NONE
                if (i > 0) {
                    val cost = prev1[j] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_A
                    }
                }
                if (j > 0) {
                    val cost = cur[j - 1] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_B
                    }
                }
                if (i > 0 && j > 0 && prev1[j - 1] < Double.POSITIVE_INFINITY) {
                    val cost = prev1[j - 1] + pairCost(a[i - 1], null, b[j - 1], null, scale) +
                        positionCost(positionPrior, i, n, j, m)
                    if (cost < best) {
                        best = cost
                        move = MOVE_ONE_ONE
                    }
                }
                if (allowMerge) {
                    if (allowEnMerge && i > 1 && j > 0 && prev2[j - 1] < Double.POSITIVE_INFINITY &&
                        (mergeGate == null || mergeGate(a[i - 2], a[i - 1], b[j - 1], null))
                    ) {
                        val cost = prev2[j - 1] + pairCost(a[i - 2], a[i - 1], b[j - 1], null, scale) +
                            positionCost(positionPrior, i, n, j, m)
                        if (cost < best) {
                            best = cost
                            move = MOVE_TWO_ONE
                        }
                    }
                    if (i > 0 && j > 1 && prev1[j - 2] < Double.POSITIVE_INFINITY &&
                        (mergeGate == null || mergeGate(a[i - 1], null, b[j - 2], b[j - 1]))
                    ) {
                        val cost = prev1[j - 2] + pairCost(a[i - 1], null, b[j - 2], b[j - 1], scale) +
                            positionCost(positionPrior, i, n, j, m)
                        if (cost < best) {
                            best = cost
                            move = MOVE_ONE_TWO
                        }
                    }
                }
                cur[j] = best
                moves[i][j] = move
            }
            if (i < n) {
                val recycled = prev2
                prev2 = prev1
                prev1 = cur
                cur = recycled
                cur.fill(Double.POSITIVE_INFINITY)
            }
        }

        val pairs = mutableListOf<SpanPair>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when (moves[i][j]) {
                MOVE_SKIP_A -> i -= 1
                MOVE_SKIP_B -> j -= 1
                MOVE_ONE_ONE -> {
                    pairs += SpanPair(intArrayOf(i - 1), intArrayOf(j - 1))
                    i -= 1
                    j -= 1
                }
                MOVE_TWO_ONE -> {
                    pairs += SpanPair(intArrayOf(i - 2, i - 1), intArrayOf(j - 1))
                    i -= 2
                    j -= 1
                }
                MOVE_ONE_TWO -> {
                    pairs += SpanPair(intArrayOf(i - 1), intArrayOf(j - 2, j - 1))
                    i -= 1
                    j -= 2
                }
                else -> break
            }
        }
        return pairs.asReversed()
    }

    /**
     * 英文侧并合：单下标时返回原 String 实例本身（共享引用，避免复制整段文本），
     * 多下标用空格拼（`"A. B."`，与 [SentenceSplitter] 的分句口径一致）。
     */
    private fun join(source: List<String>, indices: IntArray): String =
        if (indices.size == 1) source[indices[0]]
        else indices.joinToString(" ") { source[it] }

    /**
     * 中文侧并合：单下标共享引用，多下标**空串**拼接（Q1-t04）。
     *
     * 中文没有词间空格，夹一个空格拼出来的是「他說。 她笑了。」这种现实中不存在的
     * 形态：既不像原文，也会让下游按空格切词的展示/复读出现多余断点。落盘文本必须
     * 与译文原文一致地连续。
     */
    private fun joinChinese(source: List<String>, indices: IntArray): String =
        if (indices.size == 1) source[indices[0]]
        else indices.joinToString("") { source[it] }

    // --- 中文分句 -----------------------------------------------------------

    /**
     * 中文分句 + 引号归属修复：
     *  - 段落末尾「。」+ 闭合引号（……熟練。」）会被 [ZH_SENTENCE_END] 在「。」后
     *    切出一个纯「」残渣；纯标点/引号片段并入前句（R1）；
     *  - 「。」后紧跟闭合引号 + 引导语（。」他大喊：「……）时，「」他大喊：「……」
     *    会被切成独立片段，同样并入前句（R2）。
     * 实测（魔戒档案）：3,464 段产生 1,461 个纯残渣段 + 1,742 个裸引号开头段，
     * 修复后两者均为 0，段数 14,870 → 11,667；12,692 条句级句对里有 2,216 条
     * （17.5%）是这种脏配对。
     *
     * R2 的「合并 vs 另起」取舍见 [ZhSplitPolicy.speechTagMaxChars]：无条件合并是
     * V9 生产值（在 AI 译本上标配对正确率 100%/99.68%，是所有实测变体里最优的）。
     */
    private fun splitChinese(
        text: String,
        policy: ZhSplitPolicy = ZhSplitPolicy.PRODUCTION
    ): List<String> {
        val raw = text.split(policy.terminators)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val merged = mutableListOf<String>()
        for (seg in raw) {
            val isPunctuationOnly = seg.none { it.isLetterOrDigit() }
            val leadsWithClosing = seg.firstOrNull() in policy.closingLeads
            val shouldAttach = when {
                merged.isEmpty() -> false
                isPunctuationOnly -> true
                !leadsWithClosing -> false
                policy.speechTagMaxChars == null -> true
                else -> {
                    val k = seg.indexOfFirst { it !in policy.closingLeads }
                        .let { if (it < 0) seg.length else it }
                    val rest = seg.substring(k).trim()
                    rest.isEmpty() || looksLikeSpeechTag(rest, policy.speechTagMaxChars)
                }
            }
            if (shouldAttach) merged[merged.lastIndex] = merged.last() + seg else merged += seg
        }
        return merged
    }

    /**
     * 闭合引号/括号：出现在**片段开头**时并入前句（R2 引号归属修复）。
     *
     * V9 起补入**弯引号** `”`(U+201D) / `’`(U+2019)：AI 译文一律用弯引号，
     * 而这里原先只有直引号 `"`/`'`，于是 `！”甘道夫说。` 被切成
     * 「……！」+「”甘道夫说。」两句 → 段内句数多一句 → DP off-by-one → 静默错。
     * 实测（ch1/ch3 真值 AI 译本）：「句数相等段落」比例 65.45%/57.21% 升至
     * 70.16%/72.49%，与去分号合并后达 88.48%/90.39%，标配对正确率 100%/99.68%。
     */
    private val ZH_CLOSING_LEADS = setOf(
        '」', '』', '”', '’', '"', '\'', '）', '】', '］', ')', ']'
    )

    /** 纯标点句（切句残渣「 . 」这类）不参与对齐：没有可点词，也没有对照价值。 */
    private fun List<String>.contentOnly(): List<String> =
        filter { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }

    /**
     * 句级 1:N 合并准入检查（详见 [SENTENCE_MERGE_MARGIN] 上的说明）。
     * 三道门槛：词义锚点命中 ≥1（无 meaning 源时退化为「局部 1:1 已无法解释
     * 长度关系」——中文引文整句对多条短英文句时，合并是唯一说得通的走法）、
     * 尺寸保护、合并须比局部最优 1:1 便宜 [SENTENCE_MERGE_MARGIN] 以上。
     * 全部是 O(1) 哈希查表与算术，守住性能护栏。
     */
    private fun sentenceMergeAllowed(
        en1: Span,
        en2: Span?,
        zh1: Span,
        zh2: Span?,
        scale: Double
    ): Boolean {
        for (en in listOfNotNull(en1, en2)) {
            if (en.words < SENTENCE_MERGE_MIN_EN_WORDS) return false
        }
        for (zh in listOfNotNull(zh1, zh2)) {
            if (zh.chars < SENTENCE_MERGE_MIN_ZH_CHARS) return false
        }
        val merged = pairCost(en1, en2, zh1, zh2, scale)
        val local = if (en2 == null) {
            minOf(
                pairCost(en1, null, zh1, null, scale),
                pairCost(en1, null, zh2!!, null, scale)
            )
        } else {
            minOf(
                pairCost(en1, null, zh1, null, scale),
                pairCost(en2, null, zh1, null, scale)
            )
        }
        if (merged <= local - SENTENCE_MERGE_MARGIN) {
            val semanticEvidence = meaningBonus(en1.meaning, en2?.meaning, zh1.meaning, zh2?.meaning) > 0.0
            if (semanticEvidence) return true
            // 无词义证据（含 meaning=null 的保守回退）：仅当局部 1:1 自身已无法
            // 解释长度关系（残或超）时放行——引文整句对多条短英文句是唯一形态。
            val enWords = en1.words + (en2?.words ?: 0)
            val zhChars = zh1.chars + (zh2?.chars ?: 0)
            val mergedRatio = if (enWords == 0) 0.0 else zhChars / (enWords * scale)
            if (mergedRatio in SENTENCE_MIN_LENGTH_RATIO..SENTENCE_MAX_MERGED_LENGTH_RATIO) {
                val localRatio = if (en2 == null) {
                    maxOf(ratioOf(en1.words, zh1.chars, scale), ratioOf(en1.words, zh2!!.chars, scale))
                } else {
                    maxOf(ratioOf(en1.words, zh1.chars, scale), ratioOf(en2.words, zh1.chars, scale))
                }
                return localRatio !in SENTENCE_MIN_LENGTH_RATIO..SENTENCE_MAX_LENGTH_RATIO
            }
        }
        return false
    }

    private fun ratioOf(enWords: Int, zhChars: Int, scale: Double): Double =
        if (enWords == 0) 0.0 else zhChars / (enWords * scale)

    // --- 代价与置信度（只做算术与哈希查表） ----------------------------------

    /**
     * 位置先验（0..[weight]）：路径在 (i/n, j/m) 平面上偏离对角线的距离。
     * 只在配对走法上累加（跳过走法已有 [SKIP_COST]），纯算术、无文本扫描。
     */
    private fun positionCost(weight: Double, i: Int, n: Int, j: Int, m: Int): Double =
        if (weight == 0.0) 0.0 else weight * abs(i.toDouble() / n - j.toDouble() / m)

    /** 长度比偏差（0..1）：英文词数 vs 中文有效字符数，按 [scale]（字符/词）归一。 */
    private fun lengthCost(enWords: Int, zhChars: Int, scale: Double): Double {
        val ew = enWords.coerceAtLeast(1)
        val zc = zhChars.coerceAtLeast(1)
        val zcNorm = zc / scale
        val diff = abs(ew - zcNorm)
        return diff / (ew + zcNorm + 1.0)
    }

    /**
     * 数字/拉丁专名锚点命中率（0..1）× 0.5。命中判定用「中文侧出现过的拉丁/数字词
     * 集合」查表，等价于原来的「整句 lowercase 后 contains」，但不再是 O(锚点×文本长)，
     * 且不会因为锚点是别的单词的子串而误命中。
     */
    private fun anchorBonus(
        enA: Set<String>,
        enB: Set<String>?,
        zhA: Set<String>,
        zhB: Set<String>?
    ): Double {
        var total = 0
        var hits = 0
        for (anchor in enA) {
            total++
            if (anchor in zhA || (zhB != null && anchor in zhB)) hits++
        }
        if (enB != null) {
            for (anchor in enB) {
                if (anchor in enA) continue
                total++
                if (anchor in zhA || (zhB != null && anchor in zhB)) hits++
            }
        }
        if (total == 0) return 0.0
        return (hits.toDouble() / total) * 0.5
    }

    private fun pairCost(en1: Span, en2: Span?, zh1: Span, zh2: Span?, scale: Double): Double {
        val enWords = en1.words + (en2?.words ?: 0)
        val zhChars = zh1.chars + (zh2?.chars ?: 0)
        return lengthCost(enWords, zhChars, scale) -
            anchorBonus(en1.anchors, en2?.anchors, zh1.latin, zh2?.latin) -
            meaningBonus(en1.meaning, en2?.meaning, zh1.meaning, zh2?.meaning)
    }

    /**
     * 词义锚点加分：英文侧词义短语有多少条出现在中文侧子串集合里。
     * 稀疏但精确——锚定判断（像数字/拉丁锚点一样），不是全句语义评分。
     * 实测（魔戒 100 样本集）：误配组 76% 零命中、阈值 0.15 下 FP=0。
     */
    private fun meaningBonus(en1: Set<String>, en2: Set<String>?, zh1: Set<String>, zh2: Set<String>?): Double {
        var total = 0
        for (phrase in en1) {
            if (phrase in zh1 || (zh2 != null && phrase in zh2)) total++
        }
        if (en2 != null) {
            for (phrase in en2) {
                if (phrase in en1) continue
                if (phrase in zh1 || (zh2 != null && phrase in zh2)) total++
            }
        }
        if (total == 0) return 0.0
        return Math.min(total, MEANING_MAX_HITS).toDouble() * MEANING_HIT_SCALE
    }

    /** 句对两端折叠后的长度比（zh 字符 / en 词 × 1.7），详见 [SENTENCE_MIN_LENGTH_RATIO]。 */
    private fun lengthRatioOf(
        enSpans: List<Span>,
        a: IntArray,
        zhSpans: List<Span>,
        b: IntArray,
        scale: Double
    ): Double {
        var enWords = 0
        for (i in a) enWords += enSpans[i].words
        var zhChars = 0
        for (j in b) zhChars += zhSpans[j].chars
        return if (enWords == 0) 0.0 else zhChars / (enWords * scale)
    }

    private fun confidenceOf(
        enSpans: List<Span>,
        enIndices: IntArray,
        zhSpans: List<Span>,
        zhIndices: IntArray,
        scale: Double
    ): Float {
        val cost = pairCost(
            enSpans[enIndices[0]],
            enIndices.getOrNull(1)?.let { enSpans[it] },
            zhSpans[zhIndices[0]],
            zhIndices.getOrNull(1)?.let { zhSpans[it] },
            scale
        )
        val confidence = (1.0 - cost).coerceIn(0.0, 1.0)
        return confidence.toFloat().coerceIn(MIN_CONFIDENCE, 1f)
    }
}
