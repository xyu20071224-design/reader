package com.linguareader.shared.alignment

import kotlin.math.sqrt

/**
 * **全部阈值与代价权重的唯一集中处**（设计 §9.2）。
 *
 * 规则（设计 §14.3 / 交接单 §2.2）：改任何一条都必须附「改了哪条 → 哪个语料 →
 * 哪些指标动了（**含变差的**）→ 代价是什么」。只报变好的数字视为未验证。
 *
 * 本文件之外**不允许**再出现这些阈值：实现与测试都从这里取，测试也不许自己写一份。
 */
internal object AlignTuning {

    // ─────────────────────────────────────────────────────────────
    // 结构门（§3.2 G2–G7，§9.2）
    // ─────────────────────────────────────────────────────────────

    /** G2：1:1 单元的长度偏离上限（σ 倍数）。 */
    const val Z_LIMIT_SINGLE = 2.0

    /** G2：含合并的单元（span>1）长度偏离上限。 */
    const val Z_LIMIT_MERGED = 1.5

    /** G6：超过此 z 进入「收紧区」，只认 V1（词典）见证。 */
    const val Z_TIGHT = 1.5

    /** 候选缺席判据：±2 窗口内不存在 `z ≤ Z_NOISE` 的候选 → 判漏译。 */
    const val Z_NOISE = 4.0

    /** G3：最优与次优解释的代价差下限（单位 = 代价，0..~1.4）。 */
    const val MARGIN_MIN = 0.35

    /**
     * 长度**信息量**的门槛（Lead 裁定 A 的落地）：`|ln(ρ_best / r)|` 达到此值才算长度
     * 「说了话」。
     *
     * ## 为什么需要它
     *
     * `margin < MARGIN_MIN` 有两种完全不同的成因，混在一起会让算法过度保守：
     *
     * | 成因 | ρ 与 r 的关系 | margin 小意味着 | 该怎么办 |
     * | --- | --- | --- | --- |
     * | **无信息型** | 几乎相等（长度对本句无区分力） | 邻位替代必然同价，这是**数学结果** | 不能当歧义证据，交内容通道判 |
     * | **有信息型** | 明显偏离 | 长度本该能区分却区分不开 | 真歧义，保留否决 |
     *
     * 实测依据：一本句长比例恒定的书里 54% 的句子卡在 margin 门，而 V1 词典通道的
     * recall 中位数是 100%——「没有证据反对」不等于「有证据表明歧义」。
     *
     * 初值 0.05（ρ 与 r 差 5% 以上才算长度说了话），待 T5 实测标定。
     */
    const val LENGTH_INFORMATIVENESS_MIN = 0.05

    /**
     * 跳过 margin 门（长度无信息）时要求的内容见证**条数**下限。
     *
     * 论证：长度没说话时，若内容见证也只有 1 条弱证据（实测常见的是词典 IDF 分数
     * 0.6 左右），那就是「两弱证据相加」。无结构证据时内容证据必须加倍——
     * 这是「用更强的证据弥补更弱的结构」的正确形式，也是 `falseAligned == 0`
     * 红线在「长度信号缺失」这一档上的保证。
     *
     * 实测依据：初值取 1 时，`missingTranslation` 变体上出现一条真错标（`nif wam civ`
     * 这句英文真值上根本没有译文，仅凭 `ecdict-idf:0.62` + margin 跳过就被报 ALIGNED）。
     */
    const val MIN_WITNESS_WHEN_MARGIN_SKIPPED = 2

    /**
     * **合并单元的见证放宽开关**（Lead 已批准方案，但**等魔戒数据才启用**）。
     *
     * ## 它解决的问题：两条规则叠加后数学不可满足
     *
     * 1. 合并单元（`spanEn>1 || spanZh>1`）按 §8.3 **禁用 `vDiscourse`**（三处 `!merged`）；
     * 2. 跳过 margin 门要求 `witnessCount >= 2`。
     *
     * 于是合并单元在「长度无信息」时最多只有 1 条见证（`vLexical`），**永远过不了门**。
     * 实测 `sentenceMerge` 覆盖率因此只有 10%；单变量消融显示放宽到 `>=1` 能到 28.75%
     * 且 `falseAligned` 仍为 0。
     *
     * ## 为什么不能全局放宽
     *
     * 同一消融里全局放宽到 `>=1` 会立刻产生 3 条错标
     * （`paragraphSplit` 2 条 `WRONG_TARGET`、`boilerplate` 1 条 `NOT_A_TRUE_UNIT`）。
     * 所以只能**针对合并单元**放宽。
     *
     * ## 启用条件（Lead 裁定的双条 + 严格度条件）
     *
     * 1. `merged == true`；
     * 2. `witnessCount >= 1` 且该见证**必须是 `vLexical`**（不是 V2、不是硬钉）；
     * 3. `vLexical` 的 `recall` 达到 [MERGED_RELAX_RECALL_MIN]（**完整门槛，不放宽**）——
     *    已经享受了「条数放宽」，单条见证的质量必须更严。
     *
     * 启用前必须先报「被放行单元的 recall 分布（p10/p50/p90）」，确认不是贴线通过。
     */
    const val MERGED_WITNESS_RELAX_ENABLED = true

    /**
     * 合并单元放宽时要求的 `recall` 下限：取 `VERIFY_RECALL_MIN` 的**完整值**（不放宽）。
     *
     * 若实测发现放行单元的 recall 紧贴这条线（如 p10 = 0.35），说明它们不是「强证据」，
     * 需要在 `VERIFY_RECALL_MIN` 之上再加余量。
     */
    const val MERGED_RELAX_RECALL_MIN = 0.34

    /**
     * **可查词比例**护栏（Lead 裁定）：`可查英文词 / 本句全部英文词` 低于此值 → 不得 ALIGNED。
     *
     * ## 这是收紧，不是放松
     *
     * 词典桥只覆盖本书词频 Top-K，实测一本合成书的可查率中位数只有 **38.5%**——即一个
     * 句子平均只有 4 成内容词能参与验证。这种情况下 `recall = 100%` 只证明「能查的 4 成
     * 都命中了」，**证据强度不足**，不足以承担「这条对照是对的」这个断言。
     *
     * 它与 [LENGTH_INFORMATIVENESS_MIN] 的放宽方向**相反**，这是有意的：两条一起上，
     * 才能证明最终覆盖率不是单向放松阈值换来的。
     *
     * 初值 0.30（实测 p10 附近）。
     */
    const val MIN_COVERABLE_RATIO = 0.30

    /** X4：章节层 margin 下限（低于此判「低 margin」，走 identity 先验或 AMBIGUOUS）。 */
    const val MARGIN_CHAPTER = 0.20

    /**
     * 章节 margin 的替代配对窗口：替代章的字数比与当前章相差不超过此值才算「同样合理」。
     *
     * 没有这个窗口，替代配对会包含「一章 300 句对上一章 3 句」，那种替代的代价天然巨大，
     * margin 会虚高到永远通过——判别力反而归零。
     */
    const val CH_ALT_WINDOW = 0.70

    /**
     * 低 margin 章节对的**内容复核**门（Lead 裁定的补偿性安全网）。
     *
     * 对每个章节对统计「有内容见证的单元 / 该章已配对单元」。低于这个值的章节对判定为
     * 配对错误 → 整章对降级 `UNALIGNED(AMBIGUOUS)`。
     *
     * 这条比用结构 margin 判可靠得多：**错配章节的特征就是全线拿不到内容证据**。
     * 它也是「低 margin 时允许猜配对」的安全前提——没有它，降级就成了放行。
     */
    const val CHAPTER_CONTENT_SANITY = 0.25

    /** 低 margin 章节对里的 `z` 收紧系数（章节定位不可靠时，内容证据必须更强）。 */
    const val LOW_MARGIN_CHAPTER_TIGHTEN = 0.85

    /** 连续跳句上限（§7.5）：达到后不再生成 `1:0`/`0:1` 边。 */
    const val MAX_SKIP_RUN = 3

    /** G4 漂移预算：自由句数。 */
    const val DRIFT_ABS = 3.0

    /** G4 漂移预算：比例项。 */
    const val DRIFT_REL = 0.03

    /** 块级漂移否决：超预算单元占比超过此值 → 整块 ALIGNED 降级 UNCERTAIN。 */
    const val DRIFT_VETO_RATIO = 0.20

    /** 单点漂移否决倍数：`drift > 3·budget` 的单元直接 UNCERTAIN。 */
    const val DRIFT_VETO_FACTOR = 3.0

    // ─────────────────────────────────────────────────────────────
    // 句级 DP 代价（§7.3）
    // ─────────────────────────────────────────────────────────────

    const val COST_SKIP_EN = 0.35
    const val COST_SKIP_ZH = 0.30
    const val W_ANCHOR = 0.40
    const val W_POSITION = 0.15
    const val W_MERGE = 0.25

    /**
     * 长度项的分母：`z²/Z_CAP²`。
     *
     * **必须由公式算出，禁止硬编码 2.48**（交接单 §2.1）：改 skip 代价它要跟着变。
     * 推导（§7.3）：希望「无锚点 + z > Z_LIMIT_SINGLE」的配对一律贵于两侧都跳过，
     * 即 `Z_LIMIT_SINGLE² / Z_CAP² > COST_SKIP_EN + COST_SKIP_ZH`。
     */
    val Z_CAP: Double = Z_LIMIT_SINGLE / sqrt(COST_SKIP_EN + COST_SKIP_ZH)

    /** 配对形态上限（§7.1）：`1:N` / `N:1` 的 N 上限。 */
    const val MAX_SPAN_ONE_SIDED = 3

    /**
     * 合并组护栏：组级见证通过后，还要求 span 内**每句英文至少命中 1 个内容词**。
     *
     * 防的是「3 句英文里 2 句纯装饰，靠 1 句撑起整组」——那种合并通过了组级 recall，
     * 但其中两句其实没有任何内容贡献，它们被报 ALIGNED 就是错标。
     */
    const val MERGED_MIN_TERM_HIT_PER_SENTENCE = 1

    /** 配对形态上限：`N:M` 双向合并时各自的上限（仅用于「无终止符碎片」）。 */
    const val MAX_SPAN_BI_DIRECTIONAL = 2

    // ─────────────────────────────────────────────────────────────
    // 内容门（§8.2 / §8.3，D7 的双分支）
    // ─────────────────────────────────────────────────────────────

    const val VERIFY_RECALL_MIN = 0.34
    const val VERIFY_PRECISION_MIN = 0.50
    const val VERIFY_LIFT_MIN = 0.20

    /** 矛盾计数的召回线：低于它说明「内容词一个都没出现」。 */
    const val RECALL_CONTRADICT = 0.10

    /** G8 短句门限：英文词数 ≤ 此值的句子要求强见证。 */
    const val SHORT_EN_WORDS = 3

    /** G8 短句的词典分支额外要求 precision 下限（不用 lift：短句 lift 方差极大）。 */
    const val SHORT_PRECISION_MIN = 0.60

    /** §8.6 反向候选：验证分差超过此值才改「展示」，永不改选、永不 ALIGNED。 */
    const val SALVAGE_DELTA = 0.25

    /** 词典桥：本书内容词表大小 K。 */
    const val LEXICON_TOP_K = 1500

    /**
     * 词典桥：进入 Top-K 候选的最低全书词频。
     *
     * **必须是 1**：短书/片段里每个内容词可能只出现一次，设成 2 会让整本书的桥变空
     * （实测：5 句的小语料上桥直接为空 → V1 不可用 → 全部 UNCERTAIN）。
     * 设计 §8.2 只要求「取频次最高的 K 个词」，没有最低频次门槛；冗余由 K 的上限控制。
     */
    const val LEXICON_MIN_FREQUENCY = 1

    /** 词典桥：全局词条数上限（安全阀，防止极端输入把 n-gram 集合撑爆）。 */
    const val LEXICON_MAX_TERMS = 20_000

    /** 词条 document frequency 上限比例：超过此值的词条判别力为零，丢弃。 */
    const val LEXICON_DF_RATIO = 0.15

    /**
     * df 过滤的绝对豁免：`df ≤ 此值` 的词条**永不丢弃**。
     *
     * 设计 §8.2 写「丢弃 df > 0.15·N 的词条」，但那一句隐含大书（N = 11000）的前提。
     * 在 5 句的小语料上 `0.15 × 5 = 0.75`，等于把**所有**词条丢掉（实测：桥直接变空、
     * V1 不可用、全部 UNCERTAIN）。绝对豁免让短书/片段也能建桥，而大书上它不生效
     * （11000 句时 0.15·N = 1650 » 3），所以不影响设计意图。
     */
    const val LEXICON_DF_MIN_ALLOWANCE = 3

    /** 词典候选短语的合法长度（汉字数）。单字在中文里几乎必然出现，是纯噪声。 */
    const val TERM_MIN_CHARS = 2
    const val TERM_MAX_CHARS = 4

    /**
     * **单字词条匹配开关**（默认关闭）。
     *
     * ## 为什么关掉（Lead 裁定）
     *
     * 单字匹配本来是针对「文学译本用单字词、ECDICT 给 2–4 字短语」的粒度错配。
     * 实测（真实书 + 真 ECDICT）表明：**它不能解决问题**——
     * - 单字只让 `recall` p90 从 0.000 提到 0.015，金标准召回仍是 0/89；
     * - 而单字天然是误命中源（「的」「了」满天飞）。
     *
     * 真正的原因是**内容词的释义本身就对不上**（`rings → 吊环`，译本用「戒」），
     * 不是匹配粒度问题。所以关掉它，让基线回归「纯 2–4 字内容词」这个最干净的形态——
     * 这样测到的才是 V1 的真实能力上限，而不是被单字假命中扰动后的数字。
     *
     * 下面的三重护栏代码仍然保留（可随时打开做对照实验），只是默认不启用。
     */
    const val SINGLE_CHAR_TERM_ENABLED = false

    /** 单字命中的权重折扣（多字命中为 1.0）。 */
    const val SINGLE_CHAR_TERM_WEIGHT = 0.5

    /** 单字对 recall 的贡献上限（比例）。 */
    const val SINGLE_CHAR_RECALL_CAP = 0.40

    /**
     * 单字词条的个数上限（按 df 升序取，越罕见越有判别力）。
     *
     * 单字天然高频，**不能套用 [LEXICON_DF_RATIO] 的相对过滤**（`0.15·N` 会把它们
     * 全部删光，实测单字匹配因此几乎不生效）。改用绝对个数上限：按 df 升序保留最有
     * 判别力的前 N 个单字。
     */
    const val SINGLE_CHAR_MAX_TERMS = 800

    /**
     * **`terms(E)` 的功能词排除线**（Lead 裁定的修复 A）：`df > 此比例 × 总句数` 的英文词
     * 不进入 `terms(E)`。
     *
     * ## 取值理由（实测定的，不是从设计文档抄的）
     *
     * 魔戒实测英文词的 df/idf 分布（8523 个有 ECDICT 释义的词）：
     * ```
     * p0  idf=1.27 df=3839 "of"      ← 功能词
     * p10 idf=6.38 df=22   "lit"     ← 已经是内容词
     * p25 idf=7.57 df=6    "safely"
     * ```
     * **功能词全部集中在最低频段，p10 就已经是内容词。** 所以用「分位」会误伤内容词
     * （一处早期方案里的 p55 会砍掉一半有效内容词）。
     *
     * 改用**绝对频率**：`df > 2% 总句数`。魔戒 9336 句 → `df > 187`，恰好切掉
     * `of/to/a/in/he/it/that/i/was/but/they/said/for/his/is/on/at/as/had`，
     * 而保留 `lit`（df=22）。
     *
     * **为什么是 2%**：一个词的释义如果出现在超过 2% 的句子里，它在中长文本里几乎必然
     * 假命中（`said` 的释义「上述的」会撞到大量句子），且对句级区分的信息量极低。
     * 这个比例是**相对总句数**的，换书会自动适配。
     *
     * ## 实测代价（如实记录，不许为了数字好看而回退）
     *
     * 启用本条 + 元信息释义排除后，真实书 `coverage` 从 **0.0085 降到 0.0074**。
     * 这是**正确的代价**：原来的 0.0085 里有一部分是靠功能词释义的假命中撑起来的
     * （`VERIFY_FAILED` 因此少了 360 条，说明它在剔除噪声）。
     * 指标变差不构成回退理由——逻辑正确优先。
     */
    const val CONTENT_WORD_DF_RATIO = 0.02

    /**
     * 功能词排除线的**绝对下限**：`CONTENT_WORD_DF_RATIO × 总句数` 在短书上会太小
     * （100 句的书 → df > 2），会把几乎所有词都排除。这个地板保证短书上仍能建桥。
     */
    const val CONTENT_WORD_DF_MIN_LIMIT = 12

    /**
     * **元信息释义的模式**（修复 A 的第二部分）：ECDICT 对动词/名词变形只给
     * 「的过去式」「的复数」「的现在分词」这类**语法标注，而不是释义**。
     *
     * 实证：`became → 的过去式`、`errors → 的复数`、`was → 的过去式`。
     * 这类词条对 V1 是纯噪声，而且恰好都是高频词（造成假命中的主力之一）。
     *
     * 判据是**纯字符串**：整条释义（去空白后）匹配这些形式就丢弃，零风险。
     */
    val META_GLOSS_PATTERNS: List<String> = listOf(
        "的过去式", "的过去分词", "的现在分词", "的复数", "的第三人称单数",
        "的比较级", "的最高级", "的过去式和过去分词", "的过去分词和过去式",
        "过去式", "过去分词", "现在分词", "复数", "第三人称单数", "比较级", "最高级",
        "的ing形式", "的ed形式", "的s形式"
    )

    /** V2 话语标记一致 / 矛盾的判定不以「都是陈述句」取胜（§8.3）。 */
    const val DISCOURSE_WITNESS_MIN_TERMINATOR = 1

    /**
     * **V3 专名锚点见证的精确档门槛**（translit）。
     *
     * 只取 `scoreInSentence >= 0.9` 这一档：开发者实测表明**误报全部来自 0.5–0.7 的
     * 近似档，精确档误报为 0**。我们要的是「高精度独立见证」，不是覆盖率——
     * 宁可只覆盖 1.4%，也不要覆盖 20% 里混着误报。
     *
     * 这是「三态系统不骗人」的底线：V3 是唯一能在 V1 全灭时单独支撑 ALIGNED 的通道，
     * 它一旦有误报，`falseAligned == 0` 就守不住了。
     */
    const val TRANSLIT_EXACT_SCORE = 0.9

    // ─────────────────────────────────────────────────────────────
    // 密度模型（§5）
    // ─────────────────────────────────────────────────────────────

    /** 观测比伪计数：`ρ = (ch + γ)/(w + γ)`。 */
    const val GAMMA = 4.0

    /** 密度合理性钳制区间（只用于钳制，不作点估计）。 */
    const val R_MIN = 0.8
    const val R_MAX = 3.2

    /** 无估计时的退化点估计。 */
    const val R_MID = 1.9

    /** 短书阈值（英文词总数）：低于此值判「无估计」。 */
    const val SHORT_BOOK_WORDS = 2000L

    /** 锚点硬钉对数量达到此值才用它们当 σ 的主估计集（§5.4）。 */
    const val SIGMA_SEED_MIN_PAIRS = 20

    /** `σ₁` 钳制区间与地板。 */
    const val SIGMA_MIN = 0.18
    const val SIGMA_MAX = 0.60
    const val SIGMA_FLOOR = 0.08

    /**
     * 硬钉对不足时的 σ 缺省值：落在钳制区间中部偏上，偏保守（长度门更严、更易弃权），
     * 方向与公理 A2（宁可弃权）一致。
     *
     * 设计 §5.4 的退路「段落层已接受单元的残差」会形成实现顺序上的环（段落层代价要读
     * `z`，`z` 要读 `σ`），因此改用「锚点估计 ↔ 默认值」的对数空间几何混合
     * （见 [estimateSigma]），避免硬阈值 `N >= 200` 造成的 σ 阶跃。
     */
    const val SIGMA_DEFAULT = 0.35

    /** 硬钉对数的「满信」标度：达到此数 `w = 1`，σ 完全来自锚点估计。 */
    const val ANCHOR_FULL_CONFIDENCE = 200

    /** `m_eff = max(1, wSpan / SIGMA_M_EFF_WORDS)`。 */
    const val SIGMA_M_EFF_WORDS = 12.0

    /** 分段密度触发阈值：`|ln(r_seed / r_book)| > SEG_TRIGGER`。 */
    const val SEG_TRIGGER = 0.35

    /** 分段密度：段数 K 与收缩强度 κ。 */
    const val SEG_COUNT = 8
    const val SEG_SHRINK_WORDS = 200.0

    /** 密度被钳制后 `zLimit` 收紧比例（`rConfidence = LOW`）。 */
    const val LOW_CONFIDENCE_TIGHTEN = 0.75

    /** 繁简归一覆盖率影响 recall 阈值的上限（§10.5）。 */
    const val TRADITIONAL_COVERAGE_PIVOT = 0.15
    const val TRADITIONAL_RECALL_RELIEF = 0.30

    // ─────────────────────────────────────────────────────────────
    // 锚点（§6）
    // ─────────────────────────────────────────────────────────────

    /** 「区分性」判据：单侧出现次数不得超过此值。 */
    const val ANCHOR_MAX_FREQ = 3

    /** 单位数（值 < 10 且 1 位）不作锚点：排除 `` "1" "2" "3" `` 这种到处都在的编号。 */
    const val ANCHOR_MIN_DIGIT_VALUE = 10

    /** 硬钉一致性剪枝：相邻两钉的增量长度比偏离上限（以 σ 为单位）。 */
    const val ANCHOR_CONSISTENCY_Z = 1.5

    /** 锚点键数量上限（安全阀：极端输入下不让 LIS 与切块退化）。 */
    const val ANCHOR_MAX_KEYS = 20_000

    // ─────────────────────────────────────────────────────────────
    // 段落层 DP（§7.4）
    // ─────────────────────────────────────────────────────────────

    /** 段落层代价权重：长度偏离 / 位置先验 / 合并惩罚。 */
    const val PARA_W_LEN = 1.00
    const val PARA_W_POS = 0.15
    const val PARA_W_MERGE = 0.25

    /** 段落层跳段代价（比句级大，因为一整段无对应是更强的证据）。 */
    const val PARA_SKIP_EN = 0.55
    const val PARA_SKIP_ZH = 0.35

    /**
     * 段落层**合并惩罚的权重**（Lead 裁定「句级不得跨段借句」的配套）。
     *
     * 合并（`N:1`/`1:N`/`N:M`）是真实存在形态，但如果代价太低，段落层会**用合并吸收
     * 「中文侧缺了一段」**：它把「英文第 2 段」并进「中文第 1、3 段」的解释里，于是那段
     * 本该判 1:0（漏译）的英文句又被配上了中文——这是「跨段借句」的源头，也是
     * `missingTranslation` 变体上那批编造的成因。
     *
     * 提高合并惩罚让「跳过 + 正常配对」比「合并吸收」更便宜，除非合并确实解释了更多
     * 长度（合并后的 ρ 明显更接近 r）。
     */
    const val PARA_MERGE_PENALTY_SCALE = 3.0

    /** 段落层带宽：`clamp(PARA_BAND_BASE + 2.5·√n, BASE, CAP)`（两侧半宽）。 */
    const val PARA_BAND_BASE = 12
    const val PARA_BAND_CAP = 220

    /**
     * 段落层长度项的缩放：`z` 达到此值时长度项记满（1.0）。
     *
     * 段落级不用句级的 `Z_CAP`：段落层要的是「段级明显错位就罚」，而段落长度的信噪比
     * 本来就比单句高（σ 缩 √m），用同一根线会让段落层的长度项几乎不起作用。
     */
    const val PARA_Z_SCALE = 3.0

    /** 带边缘检测：超过此比例的单元贴带边缘 → 带宽 ×2 重跑（最多一次）。 */
    const val BAND_EDGE_RATIO = 0.05

    /** 漂移否决的分块粒度（句）：块内超预算比例超阈值才整块降级。 */
    const val DRIFT_BLOCK_SENTENCES = 40

    /** 没有局部替代候选时的 margin 取值（＝「不构成歧义」，不是「完美」）。 */
    const val MARGIN_NO_ALTERNATIVE = 1.0

    // ─────────────────────────────────────────────────────────────
    // 章节层 DP（§7.2）
    // ─────────────────────────────────────────────────────────────

    const val CH_W_LEN = 0.40
    const val CH_W_ANCHOR = 0.30
    const val CH_W_POS = 0.15
    const val CH_W_DIGIT = 0.20

    /** 章节层跳过代价：`SKIP_EN + SKIP_ZH` 必须**小于** Σw（校准界，§7.2）。 */
    const val SKIP_EN = 0.55
    const val SKIP_ZH = 0.35

    /** 章节层长度项：|ln 偏离| 达到此值即记满。 */
    const val CH_LEN_SCALE = 0.60

    /** 章节层标题编号对称差：一个错号记 0.5（除以 2）。 */
    const val CH_DIGIT_SCALE = 2.0

    /** 章节层动作上限：`N:1` / `1:N` 的 N（D5：N ≤ 3）。 */
    const val CH_MAX_SPAN = 3

    // ─────────────────────────────────────────────────────────────
    // 结构性章节（§2.4）
    // ─────────────────────────────────────────────────────────────

    /** 结构词表命中时的词数上限：避免把标题里含 "Index" 的正文误判。 */
    const val STRUCTURAL_MAX_WORDS = 400

    /** 目录页形态：行尾带数字的段落占比下限。 */
    const val TOC_DIGIT_TAIL_RATIO = 0.35f

    /** 目录页形态：最少段落数。 */
    const val TOC_MIN_PARAGRAPHS = 8

    /** 目录页形态：平均段落长度上限（字符）。 */
    const val TOC_MAX_AVG_CHARS = 40.0

    // ─────────────────────────────────────────────────────────────
    // 性能 / 内存安全阀（不是调参项，是结构上界）
    // ─────────────────────────────────────────────────────────────

    /** 单个句级 DP 组的格子数上限；超过则沿长度线切成子组（防止 O(N²) 内存）。 */
    const val MAX_GROUP_CELLS = 250_000

    /** 调试开关：把漂移诊断写进 notes（默认关，避免生产环境刷屏）。 */
    const val DEBUG_DRIFT = false

    /** 章节对内部段数超过此值时，沿长度线切子段（防止单章矩形退化）。 */
    const val MAX_CHAPTER_PARAGRAPHS = 1_200

    /** 长度模型里 `σ(span)` 的 `wSpan` 上限（避免超长句让 σ 失真）。 */
    const val SPAN_WORDS_CAP = 400

    /** 结构词表（§2.4）：标题命中即候选结构性章节（还要叠加词数条件）。 */    val STRUCTURAL_TITLE_WORDS: List<String> = listOf(
        "cover", "contents", "table of contents", "title page", "copyright", "dedication",
        "epigraph", "foreword", "preface", "acknowledg", "appendix", "index", "notes",
        "bibliography", "afterword", "about the author", "colophon",
        "封面", "目录", "版權", "版权", "扉页", "扉頁", "献词", "獻詞", "序", "前言",
        "致谢", "致謝", "附录", "附錄", "索引", "注释", "注釋", "译后记", "譯後記", "后记", "後記"
    )
}
