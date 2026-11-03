package com.linguareader.shared.alignment

/**
 * 句级对齐质量的**机械可复现**指标计算。
 *
 * 设计前提：真值不靠人工判定，而是**由合成语料构造即知**（见 `SyntheticBilingualFixture`）。
 * 因此这里只做两件事：
 *
 * 1. **投影**（[AlignmentProjection]）：把对齐器吐出的 [AlignmentDocument] 翻译成
 *    「英文真值句 → 预测」的扁平表。识别靠语料里嵌的唯一编码，不靠文本相似度。
 * 2. **算账**（[AlignmentMetricsCalculator]）：在真值表与预测表之间数数。
 *
 * ## 指标口径（只在本文件定义一次，实现与测试都从这里取）
 *
 * | 指标 | 分子 | 分母 |
 * | --- | --- | --- |
 * | ALIGNED 精确率 | 报 ALIGNED 且真值正确的**英文句** | 被任一 ALIGNED 单元覆盖的**英文句** |
 * | 英文句覆盖率 | 拿到 ALIGNED 句级对照的**英文句** | 真值表里的**英文句总数** |
 * | 错标数 | —（逐条列出） | — |
 * | UNCERTAIN 诚实度 | UNCERTAIN 单元里「真值其实可对齐」的条数 | UNCERTAIN 单元数 |
 * | UNALIGNED 正确率 | 报 UNALIGNED 且真值确实无对应的**英文句** | 报 UNALIGNED 的**英文句** |
 *
 * ### 两条容易错的规则（按 Lead 的权威口径写死）
 *
 * 1. **合并对（span>1）按实际覆盖的英文句数计**覆盖率与精确率，不是按单元条数。
 *    一个覆盖 2 句的 ALIGNED 单元在句级账本里记 2 句；单元级视角另记
 *    [AlignmentMetrics.unitPrecision]。
 * 2. 精确率分母是**被 ALIGNED 覆盖的英文句数**，不是单元数，也不是英文句总数
 *    （后者是覆盖率的分母；混用会把「不敢对齐」算成「精确率高」）。
 *
 * ### 覆盖率的分母定义（必须写清，因为它是目标 ≥0.95 的那个数）
 *
 * 分母 = **真值表里的英文句总数** = 合成语料里带唯一编码的英文句条数。不含纯标点段、
 * 标题行、页码行、硬换行残片——这些在真值里根本不是语言单位，对齐器把它们任意处理
 * 都不该污染覆盖率。全书的英中两版是同一批编码，所以这个数在扰动前后不变（章节增删
 * 变体除外：被删掉的章节仍计入分母，因为英文侧那些句子客观存在却拿不到对照）。
 *
 * ### 分母为 0 时
 *
 * 比率记 1.0（vacuous truth），但行内始终同时打印 `分子/分母`，所以「没有任何 ALIGNED」
 * 不会被误读成「精确率 100%」——覆盖率会立刻暴露它。
 */

/**
 * 「英文真值句 → 预测」的一条记录，是指标计算的**唯一输入形态**。
 *
 * 测试可以直接手工构造它来验证指标本身（不经过任何对齐器），这样「指标算错」与
 * 「对齐器出错」两类红能分开定位。
 *
 * @param sourceKeys 该单元**实际覆盖**的英文句真值键（合并对有多条）
 * @param targetMarkers 该单元指向的中文句编码，由语料编码反推，不来自文本相似度
 * @param unitId 单元在 [AlignmentDocument.units] 里的下标；合并对的多条 sourceKeys 共享同一 unitId
 */
internal data class PredictedAlignment(
    val status: AlignmentStatus,
    val unitId: Int,
    val sourceKeys: Set<String> = emptySet(),
    val targetMarkers: Set<String> = emptySet(),
    val sourceText: String = "",
    val targetText: String = "",
    val skipReason: AlignmentSkipReason? = null,
    /**
     * 支撑该判定的见证摘要（来自 [AlignmentEvidence]），供「逐条打印证据」类断言使用。
     *
     * 空串表示实现没有提供证据——**不臆造**。
     */
    val evidenceSummary: String = "",
    /** 见证通道标识（例如 `lexicon` / `discourse` / `hard-anchor`）。 */
    val verifyMethod: String = "",
    val verifyScore: Double = 0.0,
    /** 实现给出的判定理由（[AlignmentEvidence.reasons]），逐条打印用。 */
    val reasons: List<String> = emptyList(),
    /**
     * 该句上**词典通道的三路状态**（Lead 裁定的核心区分，见
     * [AlignmentMetricsCalculator.LexicalChannel]）。
     *
     * 必须区分三件事，它们对「该报什么状态」的正确答案完全不同：
     *
     * | 状态 | 含义 | 正确状态 |
     * | --- | --- | --- |
     * | `NO_PROVIDER` | 没词典，**我没法查** | UNCERTAIN |
     * | `SPARSE` | 有词典但本句词太少，**证不动** | UNCERTAIN（可带候选） |
     * | `SILENT` | 查了，零命中，**这是证据** | UNALIGNED(VERIFY_FAILED) |
     *
     * 由投影层从实现 `reasons` 解析（见 [AlignmentMetricsCalculator.lexicalChannelOf]）；
     * 解析不出时保守归 `UNKNOWN`，**不误判成「查了没有」**。
     */
    val lexicalChannel: AlignmentMetricsCalculator.LexicalChannel =
        AlignmentMetricsCalculator.LexicalChannel.UNKNOWN
)

/**
 * 中文侧的一个真值单元：生成器产出的**一句（或合并后的一组）中文**。
 *
 * 用「单元」而不是「编码」做真值粒度，是因为译者会把两句并成一句（[SyntheticBilingualFixture.Variant.SENTENCE_MERGE]），
 * 那时一个中文句里同时含两个编码，只有按单元归组才能正确表达「E1 和 E2 都译在这一句里」。
 */
internal class TargetUnit(
    val id: Int,
    /** 这一句里含哪些中文编码。 */
    val markers: List<String>,
    /** 这些编码原本属于哪些英文句。 */
    val sourceKeys: List<String>,
    /** 中文原文，仅供错标明细展示。 */
    val text: String = ""
)

/**
 * 一本书的真值表。**全部字段都由语料生成器按索引直接给出**，没有任何文本匹配推断。
 *
 * 编码（[sourceMarkerUniverse] / [targetMarkerUniverse]）只用于从任意文本里**识别**
 * 「这段文字覆盖了哪几句」，不参与「谁对谁」的判定。
 */
internal class AlignmentTruth(
    /** 英文句真值键，按书内阅读顺序。覆盖率与精确率的分母、分子都从这里取。 */
    val sourceKeysInOrder: List<String>,
    /** 中文侧真值单元，按书内阅读顺序。 */
    val targetUnits: List<TargetUnit>,
    /** 中文编码 → 真值单元 id。译文里出现、但不在此表中的编码 = 译者加写。 */
    val markerToUnit: Map<String, Int>,
    val sourceMarkerUniverse: Set<String>,
    val targetMarkerUniverse: Set<String>
) {
    val sourceKeyCount: Int = sourceKeysInOrder.size
    val targetUnitCount: Int = targetUnits.size

    /** 真值认为「确实有译文」的英文句键。 */
    val translatedSourceKeys: Set<String> =
        targetUnits.flatMapTo(LinkedHashSet()) { it.sourceKeys }

    /** 真值认为「没有对应译文」的英文句键：漏译 / 结构性章节 / 英文独有内容。 */
    val untranslatedSourceKeys: Set<String> = sourceKeysInOrder.toSet() - translatedSourceKeys

    /** 译文里出现、却不对应任何英文句的编码数（译者加写/附录补记）。 */
    val extraTargetMarkers: Int = (targetMarkerUniverse - markerToUnit.keys).size

    private val targetUnitsBySource: Map<String, Set<Int>> = run {
        val index = HashMap<String, MutableSet<Int>>()
        targetUnits.forEach { unit ->
            unit.sourceKeys.forEach { key ->
                index.getOrPut(key) { mutableSetOf() }.add(unit.id)
            }
        }
        index
    }

    /** 该英文句真值上对应的中文真值单元（正常 1 个；中文拆分时仍是 1 个单元）。 */
    fun trueTargetUnitsOf(sourceKey: String): Set<Int> = targetUnitsBySource[sourceKey] ?: emptySet()

    fun isTrulyTranslatable(sourceKey: String): Boolean = trueTargetUnitsOf(sourceKey).isNotEmpty()

    fun summary(): String = buildString {
        append("en=").append(sourceKeyCount)
        append(" zhUnits=").append(targetUnitCount)
        append(" markers=").append(markerToUnit.size)
        append(" untranslated=").append(untranslatedSourceKeys.size)
        append(" extraZh=").append(extraTargetMarkers)
    }
}

/** 一条错标：报了 ALIGNED / UNCERTAIN，但真值上错了。 */
internal data class FalseAligned(
    val variant: String,
    val unitId: Int,
    /** 见 [AlignmentMetricsCalculator] 的 KIND_* 常量。 */
    val kind: String,
    val reportedStatus: AlignmentStatus,
    val sourceKeys: List<String>,
    val targetMarkers: List<String>,
    val sourceText: String,
    val targetText: String,
    val skipReason: AlignmentSkipReason? = null
) {
    fun line(): String = buildString {
        append("FALSE-ALIGN variant=").append(variant)
        append(" status=").append(reportedStatus)
        append(" unit=").append(unitId)
        append(" kind=").append(kind)
        append(" en=").append(sourceKeys.ifEmpty { listOf("<no-marker>") })
        append(" zh=").append(targetMarkers.ifEmpty { listOf("<no-marker>") })
        append(" src=\"").append(sourceText.excerpt()).append('"')
        append(" tgt=\"").append(targetText.excerpt()).append('"')
        if (skipReason != null) append(" reason=").append(skipReason)
    }
}

/**
 * 一个变体在一台引擎上的全部指标。
 *
 * 字段一律显式给「分子/分母」，比率在构造期算好；[line] 同时带比率与分数，
 * 便于 CI 逐行 diff 时既看趋势也看绝对量。
 */
internal class AlignmentMetrics(
    val variant: String,
    val engine: String,

    /** 真值表英文句总数（覆盖率分母）。 */
    val truthSourceSentences: Int,
    val truthTargetUnits: Int,

    /** 拿到 ALIGNED 句级对照的英文句数（覆盖率分子）。 */
    val coveredSentences: Int,

    /** 真值上**确实有译文**的英文句数（`translatableCoverage` 的分母）。 */
    val truthTranslatableSentences: Int,
    /** 拿到 ALIGNED 且真值上确实有译文的英文句数（`translatableCoverage` 的分子）。 */
    val coveredTranslatableSentences: Int,

    /** 被任一 ALIGNED 单元覆盖的英文句数（精确率分母）。 */
    val alignedSentencesReported: Int,
    /** 其中真值正确的英文句数（精确率分子）。 */
    val alignedSentencesCorrect: Int,

    /** 单元级：报 ALIGNED 的单元数（含未命中任何真值句的单元）。 */
    val alignedUnitsReported: Int,
    val alignedUnitsCorrect: Int,

    val falseAligned: List<FalseAligned>,

    /**
     * 其中「指向译者加写 / 真值外中文句」的条数。
     *
     * **单独计一个数**，因为这是最恶劣的错标形态：给译者加写编造一个「译文」看起来
     * 完全合理（长度对得上、语气对得上），总体 falseAligned 里混着它会被稀释。
     * 这个数非 0 就说明算法在**硬凑**，而不是「边界没切准」。
     */
    val foreignTargetFalseAligned: Int,

    val uncertainUnits: Int,
    val uncertainUnitsTrulyTranslatable: Int,
    val uncertainSentences: Int,
    val uncertainSentencesTrulyTranslatable: Int,

    val unalignedSentences: Int,
    val unalignedSentencesTrulyUnmatched: Int,

    /** 报 ALIGNED 但源文本里找不到任何真值英文句编码的单元数。 */
    val unattributedAlignedUnits: Int,

    /** 报 UNCERTAIN 但同样找不到编码的单元数（不进诚实度分母，单独观察）。 */
    val unattributedUncertainUnits: Int,

    val mergedUnits: Int
) {
    val coverage: Double = ratio(coveredSentences, truthSourceSentences)

    /**
     * **可对齐句覆盖率** = 拿到 ALIGNED 且真值上确实有译文的英文句 / 真值上有译文的英文句。
     *
     * 与 [coverage] 的分工：漏译变体里一部分英文句**本来就没有译文**，用 [coverage] 量它
     * 只会测出「译本能覆盖原件多少」，与对齐器水平无关；[translatableCoverage] 剔掉这些
     * 结构性缺口，才真正回答「有译文的句子，对齐器接住了多少」。两个数都打印，变化趋势
     * 一眼可辨。
     */
    val translatableCoverage: Double = ratio(coveredTranslatableSentences, truthTranslatableSentences)

    val precision: Double = ratio(alignedSentencesCorrect, alignedSentencesReported)
    val unitPrecision: Double = ratio(alignedUnitsCorrect, alignedUnitsReported)

    /** UNCERTAIN 单元里「真值其实可对齐」的比例：弃权有没有用，全看这一格。 */
    val uncertainTranslatableRate: Double = ratio(uncertainUnitsTrulyTranslatable, uncertainUnits)

    /** 报 UNALIGNED 的英文句里真值确实无对应的比例。 */
    val unalignedCorrectness: Double = ratio(unalignedSentencesTrulyUnmatched, unalignedSentences)

    /**
     * UNCERTAIN 率 = 报 UNCERTAIN 的英文句 / 英文句总数。
     * 对齐器把句子漏掉（既不给 ALIGNED 也不给 UNCERTAIN/UNALIGNED）时，这条会掉下来。
     */
    val uncertainRate: Double = ratio(uncertainSentences, truthSourceSentences)

    /** 错标数（硬红线，目标 0）。 */
    val falseAlignedCount: Int get() = falseAligned.size

    /** 一个变体一行的结构化指标，CI 逐行比较用。 */
    fun line(): String = line(pendingImplementation = false)

    /**
     * @param pendingImplementation true 时在行尾追加 `PENDING`，明确标记「这条数出自占位
     *   基线、不是真实实现」，避免指标表被误读成产品结论。
     */
    fun line(pendingImplementation: Boolean): String = buildString {
        append("METRIC variant=").append(variant)
        append(" engine=").append(engine)
        append(" precision=").append(fmt(precision))
        append('(').append(alignedSentencesCorrect).append('/').append(alignedSentencesReported).append(')')
        append(" coverage=").append(fmt(coverage))
        append('(').append(coveredSentences).append('/').append(truthSourceSentences).append(')')
        append(" coverageT=").append(fmt(translatableCoverage))
        append('(').append(coveredTranslatableSentences).append('/').append(truthTranslatableSentences).append(')')
        append(" falseAligned=").append(falseAlignedCount)
        append(" foreignAligned=").append(foreignTargetFalseAligned)
        append(" uncertain=").append(uncertainSentences)
        append(" translatable=").append(uncertainSentencesTrulyTranslatable).append('/').append(uncertainSentences)
        append(" unaligned=").append(unalignedSentences)
        append(" correct=").append(unalignedSentencesTrulyUnmatched).append('/').append(unalignedSentences)
        append(" unitPrecision=").append(fmt(unitPrecision))
        append('(').append(alignedUnitsCorrect).append('/').append(alignedUnitsReported).append(')')
        append(" unattributed=").append(unattributedAlignedUnits)
        append(" merged=").append(mergedUnits)
        append(" truthZhUnits=").append(truthTargetUnits)
        if (pendingImplementation) append(" PENDING")
    }

    /**
     * 错标明细，**逐条**。数量可能很大（错位一句就能让全书的对都错），所以测试里打印时
     * 用 [detailLinesCapped] 截断，避免把 CI 日志冲掉。
     */
    fun detailLines(): List<String> = falseAligned.map { it.line() }

    /**
     * 错标明细（最多 [limit] 条 + 一行汇总）。
     *
     * 截断是**为了可读**而不是为了少报：总数在 `falseAligned=` 字段里始终是全量，
     * 汇总行也会写明还有多少条没打印。
     */
    fun detailLinesCapped(limit: Int = MAX_DETAIL_LINES): List<String> {        if (falseAligned.isEmpty()) return emptyList()
        val shown = falseAligned.take(limit).map { it.line() }.toMutableList()
        if (falseAligned.size <= limit) return shown
        val byKind = falseAligned.groupingBy { it.kind }.eachCount()
        shown += "… 另有 ${falseAligned.size - limit} 条错标未打印" +
            "（全量 ${falseAligned.size} 条，按 kind 分组：$byKind）"
        return shown
    }

    /** 供断言失败信息使用：一句话说清哪里破了。 */
    fun failureHint(): String =
        "precision=${fmt(precision)} coverage=${fmt(coverage)} coverageT=${fmt(translatableCoverage)}" +
            " falseAligned=$falseAlignedCount foreignAligned=$foreignTargetFalseAligned" +
            " uncertainRate=${fmt(uncertainRate)}"

    /**
     * raw coverage 与 coverageT 的落差说明（Lead 要求：差 ≥15 个点时点名差在哪些真值缺口）。
     *
     * 落差本身就是「真值上本来就没有译文的句子」占比，所以这一行直接把缺口写出来。
     * 差距不足 15 个点返回空串（没有值得解释的缺口）。
     */
    /**
     * raw coverage 与 coverageT 的落差说明（Lead 要求：差 ≥15 个点时点名差在哪些真值缺口）。
     *
     * 落差本身就是「真值上本来就没有译文的句子」占比，所以这一行直接把缺口写出来。
     * 差距不足 15 个点返回空串（没有值得解释的缺口）。
     */
    fun coverageGapNote(): String {
        val gap = translatableCoverage - coverage
        if (gap < 0.15) return ""
        val missing = truthSourceSentences - truthTranslatableSentences
        val points = Math.round(gap * 10000.0) / 100.0
        return "  ↳ coverage 与 coverageT 差 $points 个点：真值上有 " +
            "$missing/$truthSourceSentences 句本来就没有译文（漏译/结构性章节），" +
            "这部分不应由对齐器承担，**主判据看 coverageT**"
    }

    private companion object {
        /** 每行最多打印多少条错标明细（总数永远是全量的，见 [detailLinesCapped]）。 */
        const val MAX_DETAIL_LINES = 10

        fun ratio(numerator: Int, denominator: Int): Double =
            if (denominator <= 0) 1.0 else numerator.toDouble() / denominator

        fun fmt(v: Double): String = (Math.round(v * 10000.0) / 10000.0).toString()
    }
}

private fun String.excerpt(max: Int = 72): String =
    if (length <= max) this else take(max - 1) + "…"

/**
 * 把 [AlignmentDocument] 投影成「英文真值句 → 预测」。**只做编码识别，不新增判定**。
 *
 * 识别的机械性来自语料约定：每条真值英文句里嵌一个 `「zqk vur nif」` 形态的伪词编码，
 * 每条真值中文句里嵌一个「叺丂丄」形态的三字组编码，两侧**不共享任何字符**。于是
 * 「这段文本覆盖了哪几句」是查表，不是猜。
 *
 * 边界判定：
 * - 源文本里一个已知编码都没有的单元 → `unattributed`。它要么是纯标点/标题/页码这类
 *   非语言单位，要么是硬换行残片。真值表里不存在对应英文句，所以**它一旦被报 ALIGNED
 *   就是错标**（给不存在的东西宣布了对照）。
 * - 同一英文句被多个 ALIGNED 单元覆盖时**先到先得**，不重复计数，避免一份重复输出
 *   把覆盖率刷上去。
 */
internal object AlignmentProjection {

    private val latinToken = Regex("[A-Za-z]+")

    /** 英文真值编码的 token 数，与 `SyntheticBilingualFixture` 的编码表长度一致（有断言守着）。 */
    const val SOURCE_MARKER_TOKENS = 3

    /** 中文真值编码的汉字数，与 `SyntheticBilingualFixture` 的编码表长度一致（有断言守着）。 */
    const val TARGET_MARKER_LENGTH = 3

    fun project(document: AlignmentDocument, truth: AlignmentTruth): Projection {
        val bySourceKey = LinkedHashMap<String, PredictedAlignment>()
        val unattributed = ArrayList<PredictedAlignment>()

        document.units.forEachIndexed { unitId, unit ->
            val sourceKeys = sourceKeysIn(unit.sourceText, truth.sourceMarkerUniverse)
            val targetMarkers = targetMarkersIn(unit.targetText, truth.targetMarkerUniverse)
            val evidence = unit.evidence
            // 三路词典通道状态：从实现自己给出的 reasons/verifyMethod 解析，
            // 解析不出来时保守归 UNKNOWN（不误判成「查了没有」）。
            val channel = AlignmentMetricsCalculator.lexicalChannelOf(
                evidence.reasons,
                evidence.verifyMethod,
                evidence.verifyScore,
                evidence.summary()
            )
            val prediction = PredictedAlignment(
                status = unit.status,
                unitId = unitId,
                sourceKeys = sourceKeys.toSet(),
                targetMarkers = targetMarkers.toSet(),
                sourceText = unit.sourceText,
                targetText = unit.targetText,
                skipReason = unit.skipReason,
                evidenceSummary = evidence.summary(),
                verifyMethod = evidence.verifyMethod,
                verifyScore = evidence.verifyScore,
                reasons = evidence.reasons,
                lexicalChannel = channel
            )
            if (sourceKeys.isEmpty()) {
                unattributed += prediction
                return@forEachIndexed
            }
            sourceKeys.forEach { key -> bySourceKey.putIfAbsent(key, prediction) }
        }
        return Projection(bySourceKey, unattributed)
    }

    /**
     * 英文编码是 [SOURCE_MARKER_TOKENS] 个连续伪词，按词序滑窗查表。
     *
     * 滑窗而不是「找三个都在表里的词」：后者会把 `…dusk «zqk` 与别处的 `vur nif…`
     * 拼成一个假编码。窗口必须与原句**词序连续**，这与生成器的拼法是同一约定。
     */
    fun sourceKeysIn(text: String, universe: Set<String>): List<String> {
        if (text.isEmpty() || universe.isEmpty()) return emptyList()
        val tokens = latinToken.findAll(text).map { it.value.lowercase() }.toList()
        if (tokens.size < SOURCE_MARKER_TOKENS) return emptyList()
        val found = ArrayList<String>()
        for (start in 0..tokens.size - SOURCE_MARKER_TOKENS) {
            val window = tokens.subList(start, start + SOURCE_MARKER_TOKENS).joinToString(" ")
            if (window in universe) found += window
        }
        return found
    }

    /**
     * 中文编码是连续 [TARGET_MARKER_LENGTH] 个汉字，按汉字游程滑窗。
     * 改编码长度必须同步改 [SyntheticBilingualFixture]（生成器里有 require 复核）。
     */
    fun targetMarkersIn(text: String, universe: Set<String>): List<String> {
        if (text.isEmpty() || universe.isEmpty()) return emptyList()
        val found = ArrayList<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= TARGET_MARKER_LENGTH) {
                for (start in 0..run.length - TARGET_MARKER_LENGTH) {
                    val window = run.substring(start, start + TARGET_MARKER_LENGTH)
                    if (window in universe) found += window
                }
            }
            run.setLength(0)
        }
        for (ch in text) {
            if (ch.isCjkIdeograph()) run.append(ch) else flush()
        }
        flush()
        return found
    }

    private fun Char.isCjkIdeograph(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'
}

internal class Projection(
    val bySourceKey: Map<String, PredictedAlignment>,
    val unattributed: List<PredictedAlignment>
)

/**
 * 指标算账。**只读真值表与预测表，不碰任何文本相似度**。
 *
 * ## 一条 ALIGNED 单元「真值正确」的判定
 *
 * 设该单元覆盖的英文句集合为 `E`，指向的真值中文单元集合为 `P`，真值期望为
 * `expected = ∪ trueTargetUnitsOf(e), e ∈ E`：
 *
 * 1. `E` 里每一句真值上都**有译文**（否则是 [KIND_ALIGNED_WITHOUT_TRANSLATION]）；
 * 2. 预测里的中文编码**全部可识别**（否则是 [KIND_FOREIGN_TARGET]，指向译者加写）；
 * 3. `expected ⊆ P`（否则是 [KIND_PARTIAL_TARGET] 或 [KIND_WRONG_TARGET]，见下）；
 * 4. `P` 里每个单元**至少**被 `E` 中的某一句拥有（否则 [KIND_WRONG_TARGET]：串到别人的译文上）。
 *
 * 第 4 条用「至少」而不是「完全」是**有意的放宽**：中文把 E1+E2 并成一句时，E1 的单元
 * 指向那个中文句是唯一正确做法，而那个中文句同时承载 E2 的译文。要求「完全拥有」会
 * 让句级合并变体在原理上不可能通过。但「多指向一个纯属别人的中文句」仍会被第 4 条拒绝。
 *
 * ## PARTIAL_TARGET 与 WRONG_TARGET 的分界
 *
 * 两者都是 `expected ⊄ P`，区别在「有没有对上一部分」：
 *
 * - **有交集但不完整** → [KIND_PARTIAL_TARGET]：多句合并时漏了其中一句的译文。
 * - **毫无交集** → [KIND_WRONG_TARGET]：整体指到了别人的译文上（例如整体错位一句）。
 *
 * 这个区分让「合并实现少并了一句」与「索引整体漂移」在报告里长得不一样，便于按类定位。
 */
internal object AlignmentMetricsCalculator {

    const val KIND_NOT_A_TRUE_UNIT = "NOT_A_TRUE_UNIT"
    const val KIND_ALIGNED_WITHOUT_TRANSLATION = "ALIGNED_WITHOUT_TRANSLATION"
    const val KIND_FOREIGN_TARGET = "FOREIGN_TARGET"
    const val KIND_WRONG_TARGET = "WRONG_TARGET"
    const val KIND_PARTIAL_TARGET = "PARTIAL_TARGET"
    const val KIND_NO_TARGET_MARKER = "NO_TARGET_MARKER"

    fun compute(
        variant: String,
        engine: String,
        truth: AlignmentTruth,
        projection: Projection
    ): AlignmentMetrics =
        compute(variant, engine, truth, projection.bySourceKey, projection.unattributed)

    fun compute(
        variant: String,
        engine: String,
        truth: AlignmentTruth,
        predictions: Map<String, PredictedAlignment>,
        unattributed: List<PredictedAlignment> = emptyList()
    ): AlignmentMetrics {
        val aligned = predictions.filterValues { it.status == AlignmentStatus.ALIGNED }
        val correctKeys = aligned.keys.filterTo(LinkedHashSet()) { isCorrect(truth, it, aligned.getValue(it)) }
        val coveredTranslatable = aligned.keys.count { truth.isTrulyTranslatable(it) }

        // ---- 单元级：按 unitId 归组，合并对只算一条 ----
        val alignedUnitKeys = LinkedHashMap<Int, MutableList<String>>()
        aligned.forEach { (key, prediction) ->
            alignedUnitKeys.getOrPut(prediction.unitId) { mutableListOf() }.add(key)
        }
        val unattributedAligned = unattributed.filter { it.status == AlignmentStatus.ALIGNED }
        val alignedUnitsReported = alignedUnitKeys.size + unattributedAligned.size
        val alignedUnitsCorrect = alignedUnitKeys.values.count { keys -> keys.all { it in correctKeys } }

        val falseAligned = ArrayList<FalseAligned>()
        alignedUnitKeys.forEach { (unitId, keys) ->
            if (keys.all { it in correctKeys }) return@forEach
            val prediction = aligned.getValue(keys.first())
            falseAligned += FalseAligned(
                variant = variant,
                unitId = unitId,
                kind = classify(truth, keys, prediction),
                reportedStatus = AlignmentStatus.ALIGNED,
                sourceKeys = keys.toList(),
                targetMarkers = prediction.targetMarkers.toList(),
                sourceText = prediction.sourceText,
                targetText = prediction.targetText,
                skipReason = prediction.skipReason
            )
        }
        unattributedAligned.forEach { prediction ->
            falseAligned += FalseAligned(
                variant = variant,
                unitId = prediction.unitId,
                kind = KIND_NOT_A_TRUE_UNIT,
                reportedStatus = AlignmentStatus.ALIGNED,
                sourceKeys = emptyList(),
                targetMarkers = prediction.targetMarkers.toList(),
                sourceText = prediction.sourceText,
                targetText = prediction.targetText,
                skipReason = prediction.skipReason
            )
        }

        // ---- UNCERTAIN 诚实度 ----
        val uncertainUnitKeys = LinkedHashMap<Int, MutableList<String>>()
        predictions.forEach { (key, prediction) ->
            if (prediction.status != AlignmentStatus.UNCERTAIN) return@forEach
            uncertainUnitKeys.getOrPut(prediction.unitId) { mutableListOf() }.add(key)
        }
        val uncertainTrulyTranslatable = uncertainUnitKeys.values.count { keys ->
            keys.isNotEmpty() && keys.all { truth.isTrulyTranslatable(it) }
        }
        val unattributedUncertain = unattributed.count { it.status == AlignmentStatus.UNCERTAIN }
        val uncertainSentences = uncertainUnitKeys.values.sumOf { it.size }

        // ---- UNALIGNED 正确率 ----
        val unalignedKeys = predictions.filterValues { it.status == AlignmentStatus.UNALIGNED }.keys
        val unalignedCorrect = unalignedKeys.count { it in truth.untranslatedSourceKeys }

        val mergedUnits = alignedUnitKeys.values.count { it.size > 1 }

        return AlignmentMetrics(
            variant = variant,
            engine = engine,
            truthSourceSentences = truth.sourceKeyCount,
            truthTargetUnits = truth.targetUnitCount,
            coveredSentences = aligned.keys.size,
            truthTranslatableSentences = truth.translatedSourceKeys.size,
            coveredTranslatableSentences = coveredTranslatable,
            alignedSentencesReported = aligned.keys.size,
            alignedSentencesCorrect = correctKeys.size,
            alignedUnitsReported = alignedUnitsReported,
            alignedUnitsCorrect = alignedUnitsCorrect,
            falseAligned = falseAligned,
            foreignTargetFalseAligned = falseAligned.count {
                it.kind == KIND_FOREIGN_TARGET
            },
            uncertainUnits = uncertainUnitKeys.size,
            uncertainUnitsTrulyTranslatable = uncertainTrulyTranslatable,
            uncertainSentences = uncertainSentences,
            uncertainSentencesTrulyTranslatable = uncertainUnitKeys.values
                .filter { keys -> keys.all { truth.isTrulyTranslatable(it) } }
                .sumOf { it.size },
            unalignedSentences = unalignedKeys.size,
            unalignedSentencesTrulyUnmatched = unalignedCorrect,
            unattributedAlignedUnits = unattributedAligned.size,
            unattributedUncertainUnits = unattributedUncertain,
            mergedUnits = mergedUnits
        )
    }

    /** 一条预测对英文句 [key] 是否真值正确（规则见类文档）。 */
    fun isCorrect(truth: AlignmentTruth, key: String, prediction: PredictedAlignment): Boolean {
        if (!truth.isTrulyTranslatable(key)) return false
        val expected = truth.trueTargetUnitsOf(key)
        if (expected.isEmpty()) return false
        if (prediction.targetMarkers.isEmpty()) return false
        // 译文侧编码必须全部可识别，否则是「指向了真值里不存在的译文」。
        if (prediction.targetMarkers.any { it !in truth.markerToUnit }) return false
        val predictedUnits = prediction.targetMarkers.mapNotNull { truth.markerToUnit[it] }.toSet()
        if (!predictedUnits.containsAll(expected)) return false
        val enKeys = prediction.sourceKeys.ifEmpty { setOf(key) }
        // 指向的每个中文单元至少要被覆盖的某一句拥有：不允许串到别人的译文上。
        return predictedUnits.all { unitId ->
            truth.targetUnits[unitId].sourceKeys.any { it in enKeys }
        }
    }

    private fun classify(
        truth: AlignmentTruth,
        enKeys: List<String>,
        prediction: PredictedAlignment
    ): String {
        val predictedUnits = prediction.targetMarkers
            .mapNotNull { truth.markerToUnit[it] }
            .toSet()
        // 有编码可识别、但整体落在真值表外 → 指向译者加写/附录。
        if (predictedUnits.isEmpty()) return KIND_FOREIGN_TARGET
        if (enKeys.any { it in truth.untranslatedSourceKeys }) return KIND_ALIGNED_WITHOUT_TRANSLATION
        if (prediction.targetMarkers.any { it !in truth.markerToUnit }) {
            // 一部分是真译文的编码、一部分落在真值外：仍按「指向了不该指向的译文」记。
            return KIND_FOREIGN_TARGET
        }
        val expected = enKeys.flatMapTo(LinkedHashSet()) { truth.trueTargetUnitsOf(it) }
        if (expected.isEmpty()) return KIND_ALIGNED_WITHOUT_TRANSLATION
        if (expected.all { it in predictedUnits }) return KIND_WRONG_TARGET
        // 对上了一部分 → 合并漏句；一点没对上 → 整体错位。
        return if (expected.any { it in predictedUnits }) KIND_PARTIAL_TARGET else KIND_WRONG_TARGET
    }

    /**
     * 收集「报告自相矛盾」的全部证据（三条不变量，**按三路语义拆分后重写**）。
     *
     * ## 三路语义（Lead 裁定，实现侧已确认）
     *
     * | 情形 | 判据 | 正确状态 | reasons |
     * | --- | --- | --- | --- |
     * | 无词典 | `!hasMeaningProvider` | UNCERTAIN | `no lexicon provider` |
     * | 有词典但本句词少/词不够（sparse） | `!lexicalAvailable` | **UNCERTAIN** | `lexical channel sparse (…)` |
     * | 有词典、词够、零命中 | `lexicalAvailable && hits==0` | **UNALIGNED(VERIFY_FAILED)** | `lexical channel silent (…)` |
     *
     * **只有第三行是「我查了，什么都没有」= 证据。前两行是「我没法查」= 不是证据。**
     *
     * 这条拆分是必要的：早先把「没法查」和「查了没有」混成同一个 `witnessCount == 0`，
     * 于是两条看似冲突的测试无法同时满足。现在实现分开了，断言也必须分开。
     *
     * ## 三条不变量
     *
     * | # | 条件 | 含义 |
     * | --- | --- | --- |
     * | 1 | `targetText` 非空 && reasons 含 `silent` 或 `no lexicon provider` | 有译文却宣称「查了没有」/「没词典」 |
     * | 2 | 零见证 && `lexicalUsable`（词够、查了） && `status != UNALIGNED` | 查了零命中，却不报「没有」 |
     * | 3 | `!lexicalUsable` && `status == UNALIGNED` && 该句真有译文 | 没法查时把有译文的句子判成「明确没有」（过度否决） |
     *
     * **`sparse` 情形不参与不变量 1、2，也不参与 3**：它既不是「查了没有」的证据，
     * 也不是「没法查」，UNCERTAIN **且带候选译文是允许的**。
     *
     * ## 分别记账，不做短路
     *
     * 同一单元可能同时违反多条——那是**多个独立的缺陷维度**。若用 `return`/`else if`
     * 短路，修掉一条后另一条会以「新红」的样子冒出来，被误判成回归。
     *
     * @param truth 真值表（仅不变量 3 需要）。传 null 时跳过不变量 3——它要判断
     *   「该句真有译文」，只能在有真值的合成语料上判。
     */
    fun inconsistentReports(
        predictions: Map<String, PredictedAlignment>,
        truth: AlignmentTruth? = null
    ): List<InconsistentReport> {
        val out = ArrayList<InconsistentReport>()
        predictions.forEach { (key, prediction) ->
            val reasonsText = prediction.reasons.joinToString(" ")
            val hasTarget = prediction.targetText.isNotBlank()
            val zeroWitness = prediction.verifyMethod.isBlank() || prediction.verifyScore <= 0.0

            // 三路信号（由投影层从实现的 reasons 解析，见 [LexicalChannel]）。
            val channel = prediction.lexicalChannel
            val claimsSilent = SILENT_REASON_TOKENS.any { it in reasonsText }
            val claimsNoProvider = NO_PROVIDER_REASON_TOKENS.any { it in reasonsText }

            val violated = ArrayList<String>(3)

            // 不变量 1（改写）：给了译文，却说「查了没有」或「根本没有词典」。
            // 注意 `sparse` 不在其中——sparse 是「证据太薄」，允许带候选译文。
            if (hasTarget && (claimsSilent || claimsNoProvider)) {
                violated.add(INVARIANT_TARGET_WITHOUT_WITNESS)
            }

            // 不变量 2（改写）：只有 `lexicalUsable`（词够、真查了）才参与。
            // sparse / 无词典都不属于「查了没有」，不参与这条。
            if (zeroWitness && channel == LexicalChannel.SILENT &&
                prediction.status != AlignmentStatus.UNALIGNED
            ) {
                violated.add(INVARIANT_ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED)
            }

            // 不变量 3：只有真「没法查」（无词典 / sparse）时报 UNALIGNED 才算过度否决。
            if (channel != LexicalChannel.SILENT && channel != LexicalChannel.UNKNOWN &&
                prediction.status == AlignmentStatus.UNALIGNED && truth != null &&
                truth.isTrulyTranslatable(key)
            ) {
                violated.add(INVARIANT_UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED)
            }

            if (violated.isEmpty()) return@forEach

            out += InconsistentReport(
                sourceKey = key,
                sourceText = prediction.sourceText,
                status = prediction.status,
                unitId = prediction.unitId,
                targetText = prediction.targetText,
                reasons = prediction.reasons,
                verifyMethod = prediction.verifyMethod,
                verifyScore = prediction.verifyScore,
                violated = violated
            )
        }
        return out
    }

    /**
     * 违反分布的统计（Lead 要求：用来判断「修一处还是修多处」）。
     *
     * 三个维度：
     * - **按变体**：集中在几个变体，还是全变体均匀？
     * - **按 status**：UNCERTAIN 还是 ALIGNED？
     * - **按兜底译文指纹**：若大量违反指向**同一批中文句**，说明是同一个兜底源，
     *   修一处即可（[targetFingerprint] 把译文归一化后取哈希，抹掉繁简/空格差异）。
     */
    fun violationDistribution(
        violations: List<Pair<String, InconsistentReport>>
    ): String = buildString {
        append("violations=").append(violations.size)
        violations.groupingBy { it.first }.eachCount().toSortedMap()
        append("\n  by variant: ").append(
            violations.groupingBy { it.first }.eachCount().toSortedMap()
        )
        append("\n  by status:  ").append(
            violations.groupingBy { it.second.status }.eachCount().toSortedMap()
        )
        append("\n  by invariant: ").append(
            violations.flatMap { it.second.violated }
                .groupingBy { it }.eachCount().toSortedMap()
        )

        // 兜底译文指纹：模糊到「同一句兜底文本」能合并，但保留可读前缀。
        val byTarget = violations.groupingBy { targetFingerprint(it.second.targetText) }.eachCount()
        val ranked = byTarget.entries.sortedByDescending { it.value }
        val total = violations.size
        val topShare = if (total == 0) 0.0 else ranked.first().value.toDouble() / total
        val top5Share = if (total == 0) 0.0 else
            ranked.take(5).sumOf { it.value }.toDouble() / total
        append("\n  by target fingerprint (top 8 of ").append(ranked.size).append("):")
        ranked.take(8).forEach { (fingerprint, count) ->
            append("\n    ").append(count).append("× ").append(fingerprint)
        }
        append("\n  top-1 fingerprint share=").append(pctText(topShare))
        append("  top-5 share=").append(pctText(top5Share))
        if (topShare >= 0.8) {
            append("\n  → 结论：**单一兜底源**（同一批中文句占 ")
                .append(pctText(topShare)).append("），修一处即可")
        } else if (top5Share >= 0.8) {
            append("\n  → 结论：**少数几个兜底源**（top-5 占 ")
                .append(pctText(top5Share)).append("），需修几处")
        } else {
            append("\n  → 结论：**分散**，多处独立成因，需逐个排查")
        }
    }

    /**
     * 译文指纹：抹掉空白与标点差异后取前若干字符 + 长度。
     *
     * 目的是让「同一句兜底文本」在不同变体/繁简变体下仍归并为一组，
     * 同时**不隐藏**它是哪一句（保留可读前缀，人眼能认）。
     */
    fun targetFingerprint(target: String): String {
        val normalized = target.filter { it.isLetter() }
        val head = if (normalized.length <= 14) normalized else normalized.take(14)
        return "\"$head…\"(len=${normalized.length})"
    }

    private fun pctText(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    /**
     * 该句上**词典通道的三路状态**（Lead 裁定的核心区分）。
     *
     * 早先把「没法查」和「查了没有」混成一个 `witnessCount == 0`，导致两条看似冲突的
     * 测试无法同时满足。现在实现侧已拆开，评估侧也必须按同一粒度判定。
     */
    enum class LexicalChannel {
        /** 没有词典 provider：**我没法查** → UNCERTAIN。 */
        NO_PROVIDER,

        /** 有词典但本句可查词太少（证据太薄）：**我有能力查，但这句证不动** → UNCERTAIN。 */
        SPARSE,

        /** 有词典、词够、零命中：**我查了，什么都没查到** = 证据 → UNALIGNED(VERIFY_FAILED)。 */
        SILENT,

        /** 有命中（有见证）——不属于零见证的任何一路。 */
        ACTIVE,

        /** 解析不出来（实现没给信号）：**保守**按「没法查」对待，不误判成「查了没有」。 */
        UNKNOWN
    }

    /**
     * 从实现给出的 `reasons` 解析三路词典通道状态。
     *
     * 为什么从 `reasons` 解析而不是新增契约字段：这是**评估侧**对现有信号的解读，
     * 不改 T1 契约。实现方在三路的措辞是稳定的（`Decide.kt:235-267`）：
     *
     * - `structural-only: no lexicon provider`
     * - `lexical channel sparse (coverable=… < …)`
     * - `lexical channel silent (coverable=…, hits=0)`
     */
    fun lexicalChannelOf(
        reasons: List<String>,
        verifyMethod: String,
        verifyScore: Double,
        evidenceSummary: String
    ): LexicalChannel {
        // 有见证（分数 > 0）→ 不属于零见证的任何一路。
        if (verifyMethod.isNotBlank() && verifyScore > 0.0) return LexicalChannel.ACTIVE
        val text = (reasons + verifyMethod + evidenceSummary).joinToString(" ")
        return when {
            NO_PROVIDER_TOKENS.any { it in text } -> LexicalChannel.NO_PROVIDER
            SPARSE_TOKENS.any { it in text } -> LexicalChannel.SPARSE
            SILENT_TOKENS.any { it in text } -> LexicalChannel.SILENT
            // 旧措辞 `no content witness` 在三路拆分前是通用文案，无法区分是哪一路，
            // 保守归 UNKNOWN（不参与不变量 1/2 的判定，避免误判）。
            else -> LexicalChannel.UNKNOWN
        }
    }

    /** 从已投影的单元解析（委托给字段版）。 */
    fun lexicalChannelOf(prediction: PredictedAlignment): LexicalChannel = lexicalChannelOf(
        prediction.reasons, prediction.verifyMethod, prediction.verifyScore, prediction.evidenceSummary
    )

    /** 不变量 1：有译文却宣称「查了没有」或「根本没有词典」。 */
    const val INVARIANT_TARGET_WITHOUT_WITNESS = "TARGET_WITHOUT_WITNESS"

    /** 不变量 2：查了零命中（silent），却不是 UNALIGNED。 */
    const val INVARIANT_ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED =
        "ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED"

    /** 不变量 3：没法查（无词典/sparse）却把有译文的句子判成 UNALIGNED（过度否决）。 */
    const val INVARIANT_UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED =
        "UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED"

    /** 「没有词典 provider」。 */
    private val NO_PROVIDER_TOKENS = listOf(
        "no lexicon provider",
        "no meaning provider",
        "lexical channel unavailable"
    )

    /** 「有词典但本句可查词太少」——**不属于「查了没有」**。 */
    private val SPARSE_TOKENS = listOf(
        "lexical channel sparse",
        "sparse (coverable"
    )

    /** 「查了，零命中」——**这才是证据**。 */
    private val SILENT_TOKENS = listOf(
        "lexical channel silent",
        "silent (coverable"
    )

    /** 「查了没有」类文案（不变量 1 用）。 */
    private val SILENT_REASON_TOKENS = listOf(
        "lexical channel silent",
        "silent (coverable"
    )

    /** 「没有词典」类文案（不变量 1 用）。 */
    private val NO_PROVIDER_REASON_TOKENS = listOf(
        "no lexicon provider",
        "no meaning provider",
        "lexical channel unavailable"
    )

    /**
     * 实现方用来表达「没有任何内容见证」的理由措辞。
     *
     * 命中任一子串即认定它宣称无见证。实现侧措辞若不同，只需在这里补一个 token——
     * **判据不散落在断言里**。
     */
    private val NO_WITNESS_REASON_TOKENS = listOf(
        "no content witness",
        "NO_CONTENT_WITNESS",
        "no witness",
        "witnesses=0"
    )

    /**
     * 收集「真值说没有译文、算法却给出了译文」的全部证据（逐条，按英文句展开）。
     *
     * 这是 `missingTranslation_neverFabricatesAlignment` 用例的唯一数据来源：
     * 它把 [AlignmentMetrics.falseAligned] 里按单元聚合的
     * [KIND_ALIGNED_WITHOUT_TRANSLATION] 还原成「哪一句英文 / 算法指到哪句中文 / 什么见证」，
     * 便于人眼与开发直接定位，而不是只知道「有 1 条错标」。
     */
    fun fabrications(
        truth: AlignmentTruth,
        predictions: Map<String, PredictedAlignment>
    ): List<FabricationEvidence> {
        val out = ArrayList<FabricationEvidence>()
        predictions.forEach { (key, prediction) ->
            if (key !in truth.untranslatedSourceKeys) return@forEach
            // 只有「报了 ALIGNED/UNCERTAIN 并指向了某句中文」才算编造；UNALIGNED 是正确行为。
            if (prediction.status == AlignmentStatus.UNALIGNED) return@forEach
            if (prediction.targetMarkers.isEmpty()) return@forEach
            out += FabricationEvidence(
                sourceKey = key,
                sourceText = prediction.sourceText,
                reportedTargetText = prediction.targetText,
                reportedStatus = prediction.status,
                unitId = prediction.unitId,
                verifyMethod = prediction.verifyMethod,
                verifyScore = prediction.verifyScore,
                reasons = prediction.reasons,
                evidenceSummary = prediction.evidenceSummary
            )
        }
        return out
    }
}

/**
 * 「给没有译文的句子编一个对照」的完整证据（独立用例用）。
 *
 * 与 [FalseAligned] 的区别：那个是**按单元聚合**的指标输入；这里是**按英文句**展开的
 * 人类可读证据，专门给「宁可说没有，不许编一个」那条用例打印用。
 */
internal data class FabricationEvidence(
    /** 英文真值句键（编码），便于在语料里定位。 */
    val sourceKey: String,
    val sourceText: String,
    /** 算法给的译文（应为空——真值说这句没有译文）。 */
    val reportedTargetText: String,
    val reportedStatus: AlignmentStatus,
    val unitId: Int,
    val verifyMethod: String,
    val verifyScore: Double,
    val reasons: List<String>,
    val evidenceSummary: String
) {
    fun line(): String = buildString {
        append("FABRICATED-ALIGNMENT key=").append(sourceKey)
        append(" status=").append(reportedStatus)
        append(" unit=").append(unitId)
        append(" src=\"").append(sourceText.excerpt(96)).append('"')
        append(" fabricatedTarget=\"").append(reportedTargetText.excerpt(96)).append('"')
        append(" truth=NO_TRANSLATION")
        if (verifyMethod.isNotEmpty()) {
            append(" witness=").append(verifyMethod).append(':').append(round2(verifyScore))
        }
        if (reasons.isNotEmpty()) append(" reasons=").append(reasons.take(3))
    }
}

/**
 * 「报告说谎」的一条证据：`UNCERTAIN` 的契约语义是「**有候选**但证据不足」，
 * 所以它必须有候选、且理由要说明候选来自哪一类见证。
 *
 * `targetText` 非空 + `reasons` 含 `no content witness`，两者互相矛盾——要么根本没有候选
 * （那就该是 `UNALIGNED`），要么原因写错了。**这是伪装成谨慎的谎报**，比明确的错标更危险：
 * 用户看到「把握不足」+ 一条具体译文，会以为「大概是这个意思，只是不确定」。
 */
internal data class InconsistentReport(
    val sourceKey: String,
    val sourceText: String,
    val status: AlignmentStatus,
    val unitId: Int,
    val targetText: String,
    val reasons: List<String>,
    val verifyMethod: String,
    val verifyScore: Double,
    /**
     * 违反了哪几条不变量（[AlignmentMetricsCalculator] 的 INVARIANT_* 常量）。
     *
     * **是列表而不是单值**：同一单元可能同时违反两条——那是**两个独立的缺陷维度**，
     * 不是「报了两遍」。合并成一条会让「修掉一半」之后的红看起来像新回归。
     */
    val violated: List<String>
) {
    fun line(): String = buildString {
        append("INCONSISTENT-REPORT key=").append(sourceKey)
        append(" status=").append(status)
        append(" unit=").append(unitId)
        append(" violated=").append(violated)
        append(" src=\"").append(sourceText.excerpt(96)).append('"')
        append(" target=\"").append(targetText.excerpt(96)).append('"')
        append(" witness=").append(verifyMethod.ifEmpty { "<none>" }).append(':').append(round2(verifyScore))
        append(" reasons=").append(reasons.take(4))
    }
}

private fun round2(v: Double): String = (Math.round(v * 100.0) / 100.0).toString()

/** 报告渲染：一个变体一行 + 错标逐条。 */
internal object AlignmentReport {

    const val HEADER: String =
        "METRIC variant=<name> engine=<id> precision=<correct/reported> coverage=<aligned/truth>" +
            " coverageT=<alignedTranslatable/translatable> falseAligned=<n> foreignAligned=<n>" +
            " uncertain=<n> translatable=<n/n> unaligned=<n> correct=<n/n> unitPrecision=<n/n>" +
            " unattributed=<n> merged=<n> truthZhUnits=<n>"

    /** 结构化表头 + 每变体一行 + 错标明细。 */
    fun render(metrics: List<AlignmentMetrics>): String = buildString {
        append(HEADER).append('\n')
        metrics.forEach { row ->
            append(row.line()).append('\n')
            row.detailLines().forEach { append(it).append('\n') }
        }
    }

    /** 人类可读的表格（写进报告/日志，不用逐字段解析）。 */
    fun table(metrics: List<AlignmentMetrics>): String = buildString {
        append("| variant | precision | coverage | coverageT | falseAligned | foreign |")
        append(" uncertain(tr/total) | unaligned(ok/total) |")
        append('\n').append("| --- | --- | --- | --- | --- | --- | --- | --- |").append('\n')
        metrics.forEach { row ->
            append("| ").append(row.variant)
            append(" | ").append(pct(row.precision))
            append(" | ").append(pct(row.coverage))
            append(" | ").append(pct(row.translatableCoverage))
            append(" | ").append(row.falseAlignedCount)
            append(" | ").append(row.foreignTargetFalseAligned)
            append(" | ").append(row.uncertainSentencesTrulyTranslatable)
            append("/").append(row.uncertainSentences)
            append(" | ").append(row.unalignedSentencesTrulyUnmatched)
            append("/").append(row.unalignedSentences).append(" |")
            append('\n')
        }
    }

    /**
     * raw coverage 与 coverageT 的落差说明（Lead 要求：差 ≥15 个点要说明差在哪些真值缺口）。
     *
     * 落差就是「真值上本来就没有译文的句子」占比，所以这一行直接把缺口写出来。
     */
    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"
}
