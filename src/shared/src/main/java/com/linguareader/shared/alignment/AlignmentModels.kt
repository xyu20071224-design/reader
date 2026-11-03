package com.linguareader.shared.alignment

/**
 * 句级英中双语对齐的**公开契约**（纯 Kotlin/JVM，无 `android.*`，无第三方依赖）。
 *
 * 这套模型与既有 `translation` 包里的对齐实现**没有任何类型依赖**：旧实现只回答
 * 「配对 + 一个 confidence」，本契约额外回答用户真正关心的问题——**这一句到底对齐
 * 上了没有**，以及「凭什么这么判」。
 *
 * ## 三态语义（本契约的核心）
 *
 * | 状态 | 含义 | 对我们的要求 |
 * | --- | --- | --- |
 * | [AlignmentStatus.ALIGNED] | 有把握，UI 可以直接展示对照 | **不允许错标**。宁可降级成 UNCERTAIN |
 * | [AlignmentStatus.UNCERTAIN] | 有候选，但证据不足以承担「这是对的」 | 必须显式告诉用户「把握不足」，不得静默当成功 |
 * | [AlignmentStatus.UNALIGNED] | 明确没有对应（漏译、结构性章节、纯标点块等） | 必须说明原因（[AlignmentSkipReason]） |
 *
 * 历史教训是「静默降级」：拿不准时也返回一条对照，用户看不出它是猜的。三态 +
 * [AlignmentEvidence] 就是为了让「猜的」和「确定的」在数据层就分得开、且事后可复核。
 *
 * ## 兼容性约定
 *
 * - 所有字段带默认值；新增字段不破坏既有读取方。
 * - 本模型**不落盘**成既有 `TranslationMemory` 格式；接线层负责把 ALIGNED 单元投影
 *   成旧的 `AlignedSentencePair`（见 T6）。落盘格式演进另行决定，不与本契约耦合。
 */
enum class AlignmentStatus {
    /** 有把握：展示对照，并且可以被独立证据复核。 */
    ALIGNED,

    /** 有候选但证据不足：显式弃权，UI 应说明「对齐把握不足」。 */
    UNCERTAIN,

    /** 明确没有对应译文：UI 应说明「该句暂无对照」，而不是留空或给错的。 */
    UNALIGNED;

    val isShowable: Boolean get() = this == ALIGNED
}

/**
 * UNALIGNED 的原因。**每一种都必须能被算法给出**，不允许笼统的「没对上」。
 */
enum class AlignmentSkipReason {
    /** 该英文句所在段落被判定为结构性块（封面/目录/版权/附录等），译文不译。 */
    STRUCTURAL_BLOCK,

    /** 译本在这里漏译（前后都有对应，中间缺一段/一句）。 */
    TRANSLATION_MISSING,

    /** 该句是纯标点/纯符号/空块，不构成可对齐的语言单位。 */
    NOT_A_LANGUAGE_UNIT,

    /** 候选存在但独立验证没通过，且没有更可信的候选可退。 */
    VERIFY_FAILED,

    /** 多个候选的分数太接近，无法在不冒错标风险的前提下选一个。 */
    AMBIGUOUS,

    /** 输入退化（一侧为空、章节为空等）。 */
    DEGENERATE_INPUT
}

/**
 * 可复核的对齐证据。**设计原则**：任何一个 ALIGNED 判定，都必须能在这里找到支撑它的
 * 具体信号，而不是只有一个黑箱分数。用户/开发者看到一条可疑对照时，能回答
 * 「它凭什么配对」。
 *
 * 字段分三组：结构证据、词汇证据、独立验证证据。三组来自**不同的计算路径**——
 * 这是防止「自己证明自己」的关键（用同一个分数既生成候选又验证候选，等于没验证）。
 */
data class AlignmentEvidence(
    /** 观察到的长度比：中文有效字符数 / 英文词数。 */
    val observedRatio: Double = 0.0,

    /** 本书记录自适应估计出的期望长度比（见设计文档的密度估计）。 */
    val expectedRatio: Double = 0.0,

    /** 长度偏离度：`|ln(observed/expected)|`，0 = 完全符合预期。 */
    val lengthDeviation: Double = Double.MAX_VALUE,

    /** 命中并参与对齐的锚点（数字、拉丁专名、编号等），原样保留便于人工核对。 */
    val matchedAnchors: List<String> = emptyList(),

    /** 独立验证信号得分，[0,1]，越高越可信。**不得**由候选生成阶段的分数直接赋值。 */
    val verifyScore: Double = 0.0,

    /** 独立验证用的是什么方法，便于复核（例如「ECDICT 释义反查命中率」）。 */
    val verifyMethod: String = "",

    /** 结构性先验：段落在章节中的相对位置偏差（0 = 位置一致）。 */
    val positionDrift: Double = 0.0,

    /** 人类可读的判定理由，按重要性排序。UI 的「为什么」按钮可直接展示。 */
    val reasons: List<String> = emptyList()
) {
    /** 供日志/报告用的紧凑单行摘要。 */
    fun summary(): String = buildString {
        append("len=").append(round2(observedRatio))
        append('/').append(round2(expectedRatio))
        append(" dev=").append(round2(lengthDeviation))
        append(" anchors=").append(matchedAnchors.size)
        if (verifyMethod.isNotEmpty()) {
            append(" verify=").append(verifyMethod).append(':').append(round2(verifyScore))
        }
        append(" posDrift=").append(round2(positionDrift))
    }

    private fun round2(v: Double): String {
        if (v == Double.MAX_VALUE) return "inf"
        val scaled = Math.round(v * 100.0) / 100.0
        return scaled.toString()
    }
}

/**
 * 对齐结果里的**一个单元**。粒度是句级：一个 [AlignedUnit] 对应一条英文句，
 * 以及它（可能）对应的中文句。
 *
 * 支持 1:N / N:1 合并：合并时 [sourceText] / [targetText] 是拼接后的整段，
 * [sourceSpanSize] / [targetSpanSize] 说明各并了几句。这样调用方既能看到
 * 「用户点的那一句的完整译文」，也能知道这是一条合并对（合并对的置信度天然更低）。
 */
data class AlignedUnit(
    val status: AlignmentStatus,

    /** 英文侧章节下标（0-based，对应输入的 source chapters）。 */
    val sourceChapter: Int,

    /** 中文侧章节下标；UNALIGNED 时为 -1。 */
    val targetChapter: Int = -1,

    /** 英文句所在段落的原文（与阅读器/TTS 同源的叶级段落）。 */
    val sourceParagraph: String,

    /** 中文句所在段落；UNALIGNED 时为空串。 */
    val targetParagraph: String = "",

    /** 英文句子文本（1:N 合并时为拼接文本）。 */
    val sourceText: String,

    /** 中文句子文本；非 ALIGNED/UNCERTAIN 时为空串。 */
    val targetText: String = "",

    /** 英文句在段落内的序号，从 0 开始。 */
    val sourceIndexInParagraph: Int = 0,

    /** 中文句在段落内的序号；-1 表示无。 */
    val targetIndexInParagraph: Int = -1,

    /** 本单元合并了几个英文句（默认 1）。 */
    val sourceSpanSize: Int = 1,

    /** 本单元合并了几个中文句（默认 1）。 */
    val targetSpanSize: Int = 1,

    /** 综合置信度 [0,1]。**仅用于排序/展示强度，不能替代 [status]**。 */
    val confidence: Double = 0.0,

    /** 可复核证据。UNALIGNED 时也可能有（例如长度比证明它确实不该对上）。 */
    val evidence: AlignmentEvidence = AlignmentEvidence(),

    /** UNALIGNED 的原因；其它状态为 null。 */
    val skipReason: AlignmentSkipReason? = null
)

/**
 * 统计口径。**分母定义写死在这里**，避免每次评估各算各的。
 *
 * - [sourceSentenceTotal]：英文侧参与对齐的句子总数（纯标点/空块不计）。
 * - [sourceSentencesAligned]：至少作为一条 ALIGNED 单元被覆盖的英文句数（合并对按
 *   实际覆盖的句数计，不是按单元条数计）。
 * - [coverage]：`sourceSentencesAligned / sourceSentenceTotal`。这就是「对齐率」。
 * - [alignedPrecision]：真值已知时才填（合成语料/金标准重放），生产环境为 null。
 */
data class AlignmentStats(
    val sourceSentenceTotal: Int = 0,
    val targetSentenceTotal: Int = 0,
    val sourceSentencesAligned: Int = 0,

    /**
     * 真值上**存在对应译文**的英文句数。只有真值已知时才有意义（合成语料/金标准重放）；
     * 生产环境为 0，表示「不知道」，此时 [translatableCoverage] 回落到 [coverage]。
     *
     * 为什么要它：漏译的英文句真值上就没有译文，把它算进覆盖率分母等于要求算法把不
     * 存在的译文「对齐」出来——除了错标没有别的满足方式。达标判据用
     * [translatableCoverage]，[coverage] 照样打印。
     */
    val truthTranslatableSentences: Int = 0,

    val alignedUnits: Int = 0,
    val uncertainUnits: Int = 0,
    val unalignedUnits: Int = 0,

    /** 合并对（span>1）的数量，单独计数便于观察「靠合并凑覆盖率」。 */
    val mergedUnits: Int = 0,

    /** 真值已知时的精确率；生产环境为 null（不知道真值，不许瞎填）。 */
    val alignedPrecision: Double? = null,

    /** 结构性跳过的英文句数（封面/目录等），这些**不计入**覆盖率分母的抱怨范围。 */
    val structuralSkipped: Int = 0
) {
    val coverage: Double
        get() = if (sourceSentenceTotal <= 0) 0.0
        else sourceSentencesAligned.toDouble() / sourceSentenceTotal

    /**
     * **主判据**：可对齐覆盖率 = 已对齐英文句 / 真值上确实有译文的英文句数。
     *
     * 真值未知（[truthTranslatableSentences] = 0）时回落到 [coverage]——此时两者
     * 在信息上等价，不能凭空造一个更好看的数。
     */
    val translatableCoverage: Double
        get() = if (truthTranslatableSentences <= 0) coverage
        else sourceSentencesAligned.toDouble() / truthTranslatableSentences

    val uncertainRate: Double
        get() = if (sourceSentenceTotal <= 0) 0.0
        else uncertainUnits.toDouble() / sourceSentenceTotal

    fun summaryLine(): String = buildString {
        append("sentences=").append(sourceSentenceTotal)
        append(" coverage=").append(fmt(coverage))
        if (truthTranslatableSentences > 0) {
            append(" coverageT=").append(fmt(translatableCoverage))
            append(" translatable=").append(truthTranslatableSentences)
        }
        append(" aligned=").append(alignedUnits)
        append(" uncertain=").append(uncertainUnits)
        append(" unaligned=").append(unalignedUnits)
        append(" merged=").append(mergedUnits)
        alignedPrecision?.let { append(" precision=").append(fmt(it)) }
    }

    private fun fmt(v: Double): String = (Math.round(v * 10000.0) / 10000.0).toString()
}

/**
 * 一本书的完整对齐结果。
 *
 * [units] 按英文章节/段落/句序**稳定有序**，调用方可以据此建索引而无需再排序。
 */
data class AlignmentDocument(
    /** 生成该结果的算法版本；算法或分句规则变化时必须 +1。 */
    val alignerVersion: Int,

    /** 算法标识（便于 A/B 与回归定位，例如 `"independent-v1"`）。 */
    val engineId: String,

    val sourceTitle: String = "",

    val translationTitle: String = "",

    val units: List<AlignedUnit> = emptyList(),

    val stats: AlignmentStats = AlignmentStats(),

    /** 整本级的诊断信息（例如「英 36 章 / 中 29 章，3 章按结构性块跳过」）。 */
    val notes: List<String> = emptyList()
) {
    fun aligned(): List<AlignedUnit> = units.filter { it.status == AlignmentStatus.ALIGNED }

    fun uncertain(): List<AlignedUnit> = units.filter { it.status == AlignmentStatus.UNCERTAIN }

    fun unaligned(): List<AlignedUnit> = units.filter { it.status == AlignmentStatus.UNALIGNED }
}

/**
 * 一章的输入形态。段落是**叶级段落**，必须与阅读器/TTS 抽取口径同源。
 */
data class AlignmentChapter(
    val title: String = "",
    val paragraphs: List<String> = emptyList()
)

/**
 * 英文词 → 中文候选词的桥。由上层注入（`:shared` 不碰 sqlite / Android）。
 *
 * 实现方通常是 ECDICT：给定 `word`，返回它在中文里的可能译法（2–4 字短语）。
 * 返回空表示词典里没有，算法必须能在**没有任何词义信号**时退化工作。
 */
fun interface MeaningProvider {
    fun chineseCandidates(englishWord: String): List<String>
}

/**
 * 对齐引擎入口。实现方必须是纯函数：同样的输入产生同样的输出，不读写外部状态。
 *
 * 约定：
 * - 输入章节数可以不相等；任一侧为空时返回 [AlignmentDocument] 且 stats 为零。
 * - [meaning] 为 null 表示没有词义信号——算法必须仍能工作（退化路径），只是置信度更低。
 * - 返回的 [AlignmentDocument.units] 必须覆盖**每一条**英文句（ALIGNED / UNCERTAIN /
 *   UNALIGNED 三者之一），不允许静默丢句。
 */
interface SentenceAligner {
    /** 算法版本号，写进 [AlignmentDocument.alignerVersion]。 */
    val version: Int

    /** 算法标识，写进 [AlignmentDocument.engineId]。 */
    val engineId: String

    fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider? = null
    ): AlignmentDocument
}

/**
 * 验收阈值与**体验计价模型**（集中一处，禁止散落在实现与测试里各写一份）。
 *
 * ## 为什么把「错判」从红线改成可计价成本（2026-11-01）
 *
 * 曾经把 `falseAligned == 0` 当硬红线。实测发现这条红线让优化退化成二选一：
 * 每个能降低错判的规则都要牺牲 40–85% 覆盖率（见 SPLIT-007 的实测表），
 * 而覆盖率直接影响日常使用。
 *
 * 改成计价后，问题变成「在给定错判容忍度下最大化用户体验」，
 * 用 [experienceScore] 统一衡量。**红线并未消失，只是变成了最贵的成本项**
 * （[COST_WRONG_SILENT] 是 [COST_WRONG_WARNED] 的 2 倍、[COST_NO_ANSWER] 的 2 倍）。
 */
object AlignmentAcceptance {
    /** 句级覆盖率下限（= 英文句拿到 ALIGNED 的比例）。注意：AI 译本场景分母是段内句。 */
    const val MIN_COVERAGE = 0.95

    /** ALIGNED 精确率下限（真值已知时）。 */
    const val MIN_ALIGNED_PRECISION = 0.98

    /** UNCERTAIN 率上限：弃权太多等于算法没用，必须封顶。 */
    const val MAX_UNCERTAIN_RATE = 0.25

    /** 整本对齐耗时上限（毫秒），手机预算。 */
    const val MAX_MILLIS_FULL_BOOK = 3_000L

    /**
     * 静默错率的**软上限**（2026-11-01 起）。
     *
     * 历史上的 `MAX_FALSE_ALIGNED = 0` 已废弃——它让「一条都不说」也能"达标"，
     * 且逼得任何改进都要放弃覆盖率。现在改为：**允许少量静默错，
     * 但静态错率高到一定程度即判不通过**（它同时被 [experienceScore] 重罚）。
     *
     * 0.2% 的依据：实测激进配置（`requireWitness=false`）在第 1 章得
     * 2/751 = 0.27% 的原始句级占比、IDF 加权 0.17%。取 0.2% 作为**加权**上限，
     * 意味着当前配置**刚好在门槛上**——再恶化就必须收回放宽。
     */
    const val MAX_SILENT_WRONG_RATE = 0.002

    /**
     * 体验计价系数：**用户侧代价**（不是算法侧难度）。
     *
     * 依据 Lead 2026-10-31 裁定：**「无答案」与「告警错」的损失至少相同**——
     * 用户都点了一次词却没拿到答案；甚至无答案更差（告警错至少有对的可能）。
     *
     * 实测支撑：`UNCERTAIN` 的候选里 **93.7% 是正确的**（第 3 章 298/318），
     * 所以「告警答案」绝大多数情况下是对的。
     */
    /** 什么都没给：用户白点一次。 */
    const val COST_NO_ANSWER = 1.0

    /** 给了答案且有警告、但答案是错的。 */
    const val COST_WRONG_WARNED = 1.0

    /** 答案对但带了多余警告（白给警告，轻微损失）。 */
    const val COST_CORRECT_WARNED = 0.05

    /**
     * 给了答案且**无任何警告**、但答案是错的。
     *
     * 取 2.0 = 其它项的两倍：用户毫无防备地学了错意思，且会记住——
     * 这是学习工具最不能犯的错。
     */
    const val COST_WRONG_SILENT = 2.0

    /**
     * 体验分 `[0, 100]`：`100 × (1 − 期望代价)`。
     *
     * @param pNoAnswer 无答案占比 \[0,1\]
     * @param pWrongWarned 告警错占比 \[0,1\]
     * @param pWrongSilent 静默错占比 \[0,1\]
     * @param pCorrectWarned 告警正确占比 \[0,1\]（白给警告的轻微损耗）
     */
    fun experienceScore(
        pNoAnswer: Double,
        pWrongWarned: Double,
        pWrongSilent: Double,
        pCorrectWarned: Double
    ): Double {
        val cost = pNoAnswer * COST_NO_ANSWER +
            pWrongWarned * COST_WRONG_WARNED +
            pWrongSilent * COST_WRONG_SILENT +
            pCorrectWarned * COST_CORRECT_WARNED
        return (1.0 - cost) * 100.0
    }
}
