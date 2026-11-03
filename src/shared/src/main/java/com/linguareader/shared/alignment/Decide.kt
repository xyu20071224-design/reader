package com.linguareader.shared.alignment

import kotlin.math.min

/**
 * 判定层（设计 §9，**照抄级实现契约**）。
 *
 * ## 为什么这一层单独成文件、只读 `UnitFeatures`
 *
 * [decide] 是**纯函数**：不读文本、不读 `MeaningProvider`、不读 DP 的可变状态。它唯一
 * 的输入是已预计算好的 [UnitFeatures]。这是「判定层不可能偷偷重算」的结构保证——
 * 只要函数签名里拿不到原始句串与词典，就不会出现「用生成候选的分数去验证候选」的
 * 循环论证（设计 §8.5）。
 *
 * ## 三条硬规矩（改这里之前先读）
 *
 * 1. **`witnessCount == 0` ⇒ 永不 `ALIGNED`**：三态设计的地基。
 * 2. **状态决定 `confidence` 区间，区间不反过来决定状态**：`confidence` 只在状态已定后赋值，
 *    任何「因为置信度高所以升级」的路径都是 bug（契约要求 `UNCERTAIN ≤ 0.60 < 0.80 ≤ ALIGNED`）。
 * 3. **D1 关闭**：`gapExactlyOneToOne` / `neighboursAligned` 恒为 `false`，短句门里
 *    `vDiscourse` 那条路**永久不可达**——不要为它写测试期望（设计 §9.4 的「消除规则」被
 *    Lead 硬性关闭，因为它的第三分支依赖邻居的最终状态，会与 `decide()` 形成环）。
 */

internal enum class ChapterVerdict {
    /** 章节对已被接受（margin ≥ [AlignTuning.MARGIN_CHAPTER]）。 */
    ACCEPTED,

    /** 章节对分配不可判定（margin 过小）→ X4，整章 `UNALIGNED(AMBIGUOUS)`。 */
    AMBIGUOUS,

    /** 该章在译本里没有对应 → X6，整章 `UNALIGNED(TRANSLATION_MISSING)`。 */
    UNTRANSLATED
}

/**
 * 判定函数的**唯一输入**。全部字段已在阶段 0–6 预计算，[decide] 内部不做任何文本处理。
 *
 * 字段分组与设计 §9.1 逐条对应。`gapExactlyOneToOne` / `neighboursAligned` 保留字段但
 * **恒为 false**（D1 关闭，不计算），这样 `decide()` 的结构与设计逐字对应、行为却是关闭态。
 */
internal class UnitFeatures(
    val degenerate: Boolean, // X3
    val isLangUnit: Boolean, // X1
    val structural: Boolean, // X2
    val chapterVerdict: ChapterVerdict, // X4 / X6

    val hasPair: Boolean,
    val spanEn: Int,
    val spanZh: Int,
    val enWords: Int,
    val noPlausibleCandidate: Boolean,

    val z: Double,
    val margin: Double,

    /**
     * 长度信息量 `|ln(ρ_best / r)|`（Lead 裁定 A 的输入）。
     *
     * `margin < MARGIN_MIN` 有两种成因，必须分开（见 [AlignTuning.LENGTH_INFORMATIVENESS_MIN]）：
     * 长度对本句无区分力时，邻位替代同价是**数学结果**而非歧义证据。
     * 这个字段就是用来判断「长度到底说了话没有」。
     */
    val lengthInformativeness: Double = Double.MAX_VALUE,
    val drift: Double,
    val driftBudget: Double,

    val anchorHard: Boolean = false,
    val anchorOrderOk: Boolean = false,
    val anchorConflict: Boolean = false,

    val lexicalAvailable: Boolean = false,
    val lexicalRecall: Double = 0.0,
    val lexicalPrecision: Double = 0.0,
    val lexicalLift: Double = 0.0,

    /**
     * **可查词比例**：本句英文词里「词典查得到候选」的比例（[AlignTuning.MIN_COVERABLE_RATIO]）。
     *
     * 护栏，方向是**收紧**：只有 4 成词可查、且都命中，不足以证明「这条对照是对的」。
     * 与 [lengthInformativeness] 的放宽方向相反，这是有意的——两条一起上才能证明
     * 覆盖率提升不是单向放松阈值换来的。
     */
    val coverableRatio: Double = 1.0,

    /**
     * 合并组护栏：span 内每句英文在中文 span 里命中的内容词条数**最小值**。
     *
     * 组级 recall 通过后用它防「靠一句撑起整组」。`span = 1` 时不参与判定。
     */
    val minHitsPerSourceSentence: Int = Int.MAX_VALUE,

    /** 可查词比例是否低于护栏门槛（派生字段，避免 evidence 与 decide 各算一份）。 */
    val coverableRatioTooLow: Boolean = false,

    /**
     * **词典通道是否可用**（**二元事实**，Lead 裁定选项 3）。
     *
     * 语义：`provider != null` —— 只回答「**我有没有词典**」，不回答「这句能查多少」。
     *
     * ## 为什么把 `coverableRatio` 从这里移走
     *
     * 早先写成 `provider != null && coverableRatio >= MIN_COVERABLE_RATIO`，把两个
     * 不同层次的判断混在一起：
     *
     * | 判断 | 问题性质 | 正确的量 |
     * | --- | --- | --- |
     * | 「我有没有能力查」 | 二元事实 | `provider != null` |
     * | 「这句话能查多少」 | 连续强度 | [coverableRatio]，只参与 ALIGNED 判定 |
     *
     * 混用的实测后果：合成语料在「桥稀疏（terms=63）」时 `coverableRatio` 中位数降到
     * 0.333、47.8% 的句子低于 0.30 护栏，于是大量句子被误判成「**没有词典**」，
     * 连 reasons 都说了错话。而真实书上同一个 0.30 只拦 1.32%——
     * **同一个相对阈值在两种桥密度下行为差 36 倍**。
     */
    val lexicalChannelUsable: Boolean = false,

    val discourseWitness: Boolean = false,
    val discourseConflict: Boolean = false,

    /**
     * **V3 专名锚点见证**（translit，独立于 V1 的第三条内容通道）。
     *
     * 成立条件（三条同时，见 `TranslitAnchorLayer`）：
     * 1. 英文句里有可抽出的专名；
     * 2. 中文句**精确**含其音译名（`scoreInSentence >= 0.9`，近似档不用——实测误报全在近似档）；
     * 3. 该专名**全书译法唯一**（一个英文名只对应一个中文串）。
     *
     * 它是唯一在 V1 完全失效时仍可能工作的通道：专名译法固定、跨章稳定，
     * 不依赖 ECDICT 的普通词释义质量。
     */
    val translitWitness: Boolean = false,

    val paragraphVerified: Boolean = false,
    /** D1 关闭：恒 false，不计算。 */
    val gapExactlyOneToOne: Boolean = false,
    /** D1 关闭：恒 false，不计算。 */
    val neighboursAligned: Boolean = false,
    val rConfidenceLow: Boolean = false,

    /**
     * 所在章节对是**低 margin**（章节层的结构信号分不开，配对来自 identity 先验）。
     *
     * 语义（Lead 裁定 3）：章节定位本身不可靠时，内容证据必须**更强**才能背书，
     * 所以这一档的 `ALIGNED` 要求 `vLexical`（不能只靠 `vDiscourse`），且 `z` 收紧一档。
     * 方向是**更严**，不是更松——低 margin 不是放行理由。
     */
    val lowMarginChapter: Boolean = false,

    // ── 仅用于组装 evidence，不参与任何 gate（留档 / 可复核）────
    val observedRatio: Double = 0.0,
    val expectedRatio: Double = 0.0,
    val matchedAnchors: List<String> = emptyList()
)

/** [decide] 的返回值。`skipReason` 仅在 `UNALIGNED` 时非 null。 */
internal class Verdict(
    val status: AlignmentStatus,
    val skipReason: AlignmentSkipReason?,
    val confidence: Double,
    val reasons: List<String>
)

/**
 * 主判定（设计 §9.3，逐条照抄）。
 *
 * 顺序即语义：**先否决，后批准**。短路返回，不做加权累加——「任何一条独立通道都能否决，
 * 三条通道里只要有一条拒绝就不批准」（公理 A2）。
 */
internal fun decide(u: UnitFeatures): Verdict {
    // ── 否决层（顺序即优先级）────────────────────────────────────
    if (u.degenerate) {
        return Verdict(AlignmentStatus.UNALIGNED, AlignmentSkipReason.DEGENERATE_INPUT, 0.0, listOf("degenerate input"))
    }
    if (!u.isLangUnit) {
        return Verdict(AlignmentStatus.UNALIGNED, AlignmentSkipReason.NOT_A_LANGUAGE_UNIT, 0.0, listOf("not a language unit"))
    }
    if (u.structural) {
        return Verdict(AlignmentStatus.UNALIGNED, AlignmentSkipReason.STRUCTURAL_BLOCK, 0.0, listOf("structural chapter"))
    }
    when (u.chapterVerdict) {
        ChapterVerdict.AMBIGUOUS ->
            return Verdict(AlignmentStatus.UNALIGNED, AlignmentSkipReason.AMBIGUOUS, 0.0, listOf("chapter pairing ambiguous"))
        ChapterVerdict.UNTRANSLATED ->
            return Verdict(
                AlignmentStatus.UNALIGNED, AlignmentSkipReason.TRANSLATION_MISSING, 0.0,
                listOf("chapter untranslated")
            )
        ChapterVerdict.ACCEPTED -> Unit // 继续
    }

    // ── 候选缺席 ────────────────────────────────────────────────
    if (!u.hasPair) {
        return if (u.noPlausibleCandidate) {
            Verdict(
                AlignmentStatus.UNALIGNED, AlignmentSkipReason.TRANSLATION_MISSING, 0.0,
                listOf("no candidate within Z_NOISE")
            )
        } else {
            Verdict(
                AlignmentStatus.UNCERTAIN, null, 0.15,
                listOf("dp skipped but plausible candidate exists")
            )
        }
    }
    // 防御分支：z 超过噪声上限却仍被配上（正常路径下 DP 代价决定其不可达），不敢断言「漏译」。
    if (u.z > AlignTuning.Z_NOISE && !u.anchorHard) {
        return Verdict(AlignmentStatus.UNCERTAIN, null, 0.10, listOf("z=${round(u.z)} > Z_NOISE (defensive)"))
    }

    // ── 内容见证（三条独立通道，判据各自独立、量纲不混用）──────────
    val vLexical = u.lexicalAvailable &&
        u.lexicalRecall >= AlignTuning.VERIFY_RECALL_MIN &&
        (u.lexicalPrecision >= AlignTuning.VERIFY_PRECISION_MIN ||
            u.lexicalLift >= AlignTuning.VERIFY_LIFT_MIN)

    // ── 护栏（Lead 裁定）：可查词比例太低时，V1 不构成强证据 ────────
    // 方向是收紧。注意它**只否决 V1**，不否决 V2/硬钉——那两条通道的独立性不受
    // 词典覆盖影响（话语标记来自原文标点、硬钉来自全书唯一锚点）。
    val coverableTooLow = u.coverableRatioTooLow && u.lexicalAvailable
    val vLexicalEffective = vLexical && !coverableTooLow

    val vDiscourse = u.discourseWitness
    val vHard = u.anchorHard && u.anchorOrderOk
    // **V3 专名锚点见证**（translit）：独立于 V1 的第三条内容通道。
    // 只在 V1 拿不到见证时才启用（Lead 给的「替代见证」形态，我选它而不是纯独立通道，
    // 理由见 [TranslitAnchorLayer]：专名只能证明「这句大致在这段」，不能证明「就是这句」，
    // 所以它不该在 V1 已经给出明确信号时再叠加放行）。
    val vTranslit = u.translitWitness && !vLexicalEffective
    val witnessCount = (if (vLexicalEffective) 1 else 0) +
        (if (vDiscourse) 1 else 0) +
        (if (vHard) 1 else 0) +
        (if (vTranslit) 1 else 0)

    val contradictions = (if (u.lexicalAvailable && u.lexicalRecall < AlignTuning.RECALL_CONTRADICT) 1 else 0) +
        (if (u.discourseConflict) 1 else 0) +
        (if (u.anchorConflict) 1 else 0)

    // ── 否决：两条独立通道同时否认，且无人支持 ──────────────────
    if (contradictions >= 2 && witnessCount == 0) {
        return Verdict(
            AlignmentStatus.UNALIGNED, AlignmentSkipReason.VERIFY_FAILED, 0.0,
            listOf("2 contradictions, 0 witness")
        )
    }

    // ── 零见证：三种情形必须分开报（Lead 裁定的「正确报告」）────────────
    //
    // 这三件事在数据层完全不同，早先把前两种混成了 `lexical channel unavailable`，
    // 于是「有词典但这句词太少」被误报成「没有词典」——那是**报告说谎**。
    //
    // | 情形 | 含义 | status | reason 文案 |
    // | --- | --- | --- | --- |
    // | 没有 provider | 我没法查 | UNCERTAIN | `structural-only: no lexicon provider` |
    // | 有词典、本句词太少 | 我有能力查，但这条证据太薄 | UNCERTAIN | `lexical channel sparse (...)` |
    // | 有词典、词够但零命中 | 我查了，什么都没查到 | UNALIGNED | `lexical channel silent (...)` |
    //
    // 第三种是**证据**（「我验证过，不成立」），不是「没证据」，所以报 UNALIGNED。
    // 前两种都是「我没资格下结论」，所以诚实弃权报 UNCERTAIN，并带结构候选供展示。
    if (witnessCount == 0) {
        if (!u.lexicalChannelUsable) {
            return Verdict(
                AlignmentStatus.UNCERTAIN, null, 0.30,
                listOf(
                    "structural-only: no lexicon provider",
                    "witnesses=0"
                )
            )
        }
        if (u.coverableRatioTooLow) {
            // 有词典，但本句可查内容词太少 → 证据太薄，不足以断言「不成立」
            return Verdict(
                AlignmentStatus.UNCERTAIN, null, 0.30,
                listOf(
                    "lexical channel sparse (coverable=${round(u.coverableRatio)} < " +
                        "${AlignTuning.MIN_COVERABLE_RATIO})",
                    "witnesses=0"
                )
            )
        }
        // 有词典、词够、零命中 → 这是「我查过、没查到」的证据
        return Verdict(
            AlignmentStatus.UNALIGNED, AlignmentSkipReason.VERIFY_FAILED, 0.0,
            listOf(
                "lexical channel silent (coverable=${round(u.coverableRatio)}, hits=0)",
                "witnesses=0"
            )
        )
    }

    // ── 结构门 G2 / G5 / G6 ────────────────────────────────────
    val merged = u.spanEn > 1 || u.spanZh > 1
    val zLimit = if (merged) AlignTuning.Z_LIMIT_MERGED else AlignTuning.Z_LIMIT_SINGLE
    var zEff = if (u.rConfidenceLow) zLimit * AlignTuning.LOW_CONFIDENCE_TIGHTEN else zLimit
    // Lead 裁定 3：低 margin 章节对（结构分不开、配对来自先验）→ 长度门再收紧一档。
    if (u.lowMarginChapter) zEff *= AlignTuning.LOW_MARGIN_CHAPTER_TIGHTEN
    if (u.z > zEff) {
        return Verdict(AlignmentStatus.UNCERTAIN, null, 0.35, listOf("z=${round(u.z)} > limit=${round(zEff)}"))
    }
    if (merged && !(vLexicalEffective || vHard)) {
        return Verdict(AlignmentStatus.UNCERTAIN, null, 0.40, listOf("merged span needs lexical witness"))
    }
    // Lead 裁定 3：低 margin 章节对**只认词典**（V2 单独不够），且不放宽任何其它门。
    if (u.lowMarginChapter && !vLexicalEffective) {
        return Verdict(
            AlignmentStatus.UNCERTAIN, null, 0.45,
            listOf("low-margin chapter pair needs lexical witness")
        )
    }
    if (u.z > AlignTuning.Z_TIGHT && !vLexicalEffective) {
        return Verdict(AlignmentStatus.UNCERTAIN, null, 0.45, listOf("tight zone, only discourse witness"))
    }

    // ── G8 短句加严（D1 关闭时第三分支永久不可达）────────────────
    if (u.enWords <= AlignTuning.SHORT_EN_WORDS) {
        val shortOk = vHard ||
            (vLexicalEffective && u.lexicalPrecision >= AlignTuning.SHORT_PRECISION_MIN) ||
            (vDiscourse && u.gapExactlyOneToOne && u.paragraphVerified && u.neighboursAligned)
        if (!shortOk) {
            return Verdict(AlignmentStatus.UNCERTAIN, null, 0.45, listOf("short sentence, weak witness"))
        }
    }

    // ── 结构门 G3 / G4 / G7 ────────────────────────────────────
    //
    // G3（Lead 裁定 A）：`margin` 只在「长度信息量足够」时才参与否决。
    //
    // 理由：`margin` 小有两种成因，它们的含义完全不同——(a) 长度对本句**没有区分力**，
    // 邻位替代与本位同价是必然的数学结果，这不是「歧义」而是「这一维没数据」；
    // (b) 长度**本该**区分（ρ 明显偏离 r）却区分不开，那才是真歧义。
    // 把 (a) 当歧义否决，等于用「我不掌握这个信息」惩罚句子——实测中这是一本书 54%
    // 的句子被弃权的唯一原因，而同期 V1 词典通道的 recall 中位数是 100%。
    val marginVetoable = u.lengthInformativeness >= AlignTuning.LENGTH_INFORMATIVENESS_MIN

    // ── Lead 护栏：跳过 margin 门（长度无信息）时，内容见证必须加倍 ──────
    //
    // 论证：`lengthInformativeness` 低意味着**长度对这句完全没信息**。此时如果内容
    // 见证也只有 1 条（而且往往是 IDF 分数 0.6 左右的弱词典见证），那就是「两弱证据
    // 相加」——结构没说话、内容也只说了一句，不足以承担「这条对照是对的」。
    // 无结构证据时内容证据必须加倍，这是「用更强的证据弥补更弱的结构」的正确形式。
    //
    // ## 合并单元的特殊处理（开关默认关闭，等魔戒数据）
    //
    // 合并单元按 §8.3 禁用 `vDiscourse`，所以它**最多只有 1 条见证**——要求 ≥2 在数学上
    // 不可满足（实测 `sentenceMerge` 覆盖率因此只有 10%）。放宽条件是三条同时成立：
    // `merged` ∧ 唯一见证是 `vLexical` ∧ 该 recall 达到完整门槛。
    // 见 [AlignTuning.MERGED_WITNESS_RELAX_ENABLED]。
    val mergedWitnessScope = merged && AlignTuning.MERGED_WITNESS_RELAX_ENABLED
    val mergedRelaxSatisfied = mergedWitnessScope &&
        vLexicalEffective &&
        u.lexicalRecall >= AlignTuning.MERGED_RELAX_RECALL_MIN &&
        // 护栏（Lead 裁定）：span 内每句英文都至少要有内容贡献，否则是「靠一句撑起整组」。
        u.minHitsPerSourceSentence >= AlignTuning.MERGED_MIN_TERM_HIT_PER_SENTENCE
    if (!marginVetoable && !mergedRelaxSatisfied &&
        witnessCount < AlignTuning.MIN_WITNESS_WHEN_MARGIN_SKIPPED
    ) {
        return Verdict(
            AlignmentStatus.UNCERTAIN, null, 0.45,
            listOf(
                "margin skipped but witnesses=$witnessCount < " +
                    "${AlignTuning.MIN_WITNESS_WHEN_MARGIN_SKIPPED} " +
                    "(|ln p/r|=${round(u.lengthInformativeness)})"
            )
        )
    }
    if (marginVetoable && u.margin < AlignTuning.MARGIN_MIN) {
        return Verdict(
            AlignmentStatus.UNCERTAIN, null, 0.50,
            listOf("margin=${round(u.margin)} < ${AlignTuning.MARGIN_MIN} (ambiguous)")
        )
    }
    if (u.drift > u.driftBudget) {
        return Verdict(
            AlignmentStatus.UNCERTAIN, null, 0.50,
            listOf("drift=${round(u.drift)} > ${round(u.driftBudget)}")
        )
    }
    if (contradictions >= 1) {
        return Verdict(AlignmentStatus.UNCERTAIN, null, 0.55, listOf("1 contradiction present"))
    }

    // ── 全过 → ALIGNED（confidence 在状态已定之后才赋值）──────────
    // 通过时也把「margin 被跳过」如实记进 reasons：这是「正确报告」的一部分，
    // 让人能看出这条为什么在 margin 不足时仍然通过，而不是悄悄放行。
    val confidence = when (witnessCount) {
        3 -> 0.97
        2 -> 0.92
        else -> 0.80
    }
    val reasons = if (marginVetoable) {
        listOf("witnesses=$witnessCount")
    } else if (mergedRelaxSatisfied) {
        // 合并单元走放宽通道：reasons 必须标明，否则测试/审计无法区分它和「违规通过」。
        listOf(
            "witnesses=$witnessCount",
            "margin skipped: length uninformative (|ln p/r|=${round(u.lengthInformativeness)} " +
                "< ${AlignTuning.LENGTH_INFORMATIVENESS_MIN})",
            "merged-span witness relax (recall=${round(u.lexicalRecall)} ≥ " +
                "${AlignTuning.MERGED_RELAX_RECALL_MIN}, per-sentence hits ≥ " +
                "${u.minHitsPerSourceSentence})"
        )
    } else {
        listOf(
            "witnesses=$witnessCount",
            "margin skipped: length uninformative (|ln p/r|=${round(u.lengthInformativeness)} " +
                "< ${AlignTuning.LENGTH_INFORMATIVENESS_MIN})"
        )
    }
    return Verdict(AlignmentStatus.ALIGNED, null, confidence, reasons)
}

/**
 * 证据组装（设计 §9.3）。**与 `status` 分开算**，避免顺手写成同一件事。
 *
 * `verifyScore` 只由内容通道构成——**禁止**在这里读 `u.z` / `u.margin` / 任何 DP 结果，
 * 否则「证据」就退化成「分数换个名字」（交接单 §2.1 硬红线）。
 */
internal fun evidenceOf(u: UnitFeatures, v: Verdict): AlignmentEvidence {
    val vLexicalRaw = u.lexicalAvailable &&
        u.lexicalRecall >= AlignTuning.VERIFY_RECALL_MIN &&
        (u.lexicalPrecision >= AlignTuning.VERIFY_PRECISION_MIN ||
            u.lexicalLift >= AlignTuning.VERIFY_LIFT_MIN)
    // 证据侧也要用「生效后」的 V1：被可查词比例护栏拦下的通道不能算作见证，否则
    // evidence 会说「有词典见证」而 status 是 UNCERTAIN，两者自相矛盾。
    val vLexical = vLexicalRaw && !u.coverableRatioTooLow
    val parts = ArrayList<Double>(3) // 无 lambda 捕获，便于内联
    if (vLexical) parts.add((min(u.lexicalRecall, u.lexicalPrecision) / 0.60).coerceIn(0.0, 1.0))
    if (u.discourseWitness) parts.add(0.8)
    if (u.anchorHard && u.anchorOrderOk) parts.add(1.0)
    var sum = 0.0
    for (p in parts) sum += p
    val verifyScore = if (parts.isEmpty()) 0.0 else (sum / parts.size).coerceIn(0.0, 1.0)
    val verifyMethod = if (parts.isEmpty()) {
        "none"
    } else {
        buildString {
            if (vLexical) append("ecdict-idf")
            if (u.discourseWitness) append(if (isEmpty()) "discourse" else "+discourse")
            if (u.anchorHard && u.anchorOrderOk) append(if (isEmpty()) "anchor-unique" else "+anchor-unique")
            if (u.anchorHard) append("+hard-seed")
        }
    }
    return AlignmentEvidence(
        observedRatio = u.observedRatio,
        expectedRatio = u.expectedRatio,
        lengthDeviation = u.z,
        matchedAnchors = u.matchedAnchors,
        verifyScore = verifyScore,
        verifyMethod = verifyMethod,
        positionDrift = u.drift,
        reasons = if (u.anchorHard) v.reasons + "hard seed" else v.reasons
    )
}

private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0
