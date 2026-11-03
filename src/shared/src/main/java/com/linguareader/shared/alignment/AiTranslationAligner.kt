package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter

/**
 * **AI 译本专用对齐引擎**（`engineId = "ai-pair-v1"`）。
 *
 * ## 为什么需要独立引擎而不是改 `IndependentAligner`
 *
 * 两个引擎面对的**问题定义不同**，不是同一个问题的两种参数：
 *
 * | | `independent-v1`（人工译本） | `ai-pair-v1`（AI 译本） |
 * | --- | --- | --- |
 * | 章节/段落对应 | **未知，要靠 DP 推算** | **由生成链路构造保证** |
 * | 句数比 | 1.46（N:1 主导） | 严格 1:1 |
 * | 数字锚点 | 失效（译本转汉字） | **有效（prompt 要求原样保留）** |
 * | 专名 | 失效（音译，拉丁 0 次） | **有效（`Tom Parker`→「汤姆·帕克」）** |
 * | 文言/意译 | 主因 | 现代汉语直译 |
 *
 * 把两者合并会迫使其中一方接受不属于它的假设。共用同一套三态契约与
 * `AlignmentEvidence`，保留两个引擎，是「同一标准、不同问题」的正确处理。
 * **`independent-v1` 保留作负对照**：它证明了「假设不成立时算法会怎样」。
 *
 * ## 核心思路：不推算，只**验证**
 *
 * AI 翻译链路（`AiTranslationRepository`）保证按段落分批、段落整段成块、
 * `mergeBatchTranslations` 对每个原始段落下标返回一条译文、写入时章序段序同源。
 * 所以「第 i 章 ↔ 第 i 章」「第 j 段 ↔ 第 j 段」是**构造事实**，不需要 DP。
 *
 * 算法唯一的战场是**段内句级**，且职责是**验证一个已给出的配对是否可信**，
 * 不是猜配对。**验证比猜测容易得多**——这是新引擎相对旧引擎的本质优势。
 *
 * ## 段内两档（关键区别）
 *
 * | 情形 | 唯一性来自 | ALIGNED 条件 |
 * | --- | --- | --- |
 * | `enCount == zhCount` | **构造给出**（顺序 1:1） | `witnessCount >= 1`（不需 margin） |
 * | `enCount != zhCount` | **算法选择**（穷举最优方案） | `witnessCount >= 1` ∧ **`margin >= MARGIN_MIN`** |
 *
 * **第二档必须有 margin**：句数不等时「谁配谁」不是构造给出的，是算法选的，
 * 没有 margin 就是随便选一个。（Lead 指出的隐藏漏洞。）
 *
 * 段内规模小（N ≤ 4）时**优先穷举所有单调配对方案**，逐个打分取最优：
 * 方案数极少（十几到几十种），是精确解，而且能算出真实 margin——比 DP 更好。
 *
 * ## 为什么锚点是「防错切的唯一信号」
 *
 * 句数相等**不等于**句界对。`SentenceSplitter` 会把 `A. Whitmore` 切成
 * `"A."` + `"Whitmore, Books"`（单字母缩写保护不足），于是 2==2 判「相等」、
 * 顺序对齐后全错位，而句数校验发现不了。
 *
 * **数字和专名不受分句影响**——`1919` 无论怎么切都还在这句附近。
 * 所以段内 1:1 之后必须逐对验证锚点；某段锚点大面积失败就整段降级。
 */
class AiTranslationAligner(
    /**
     * 阈值配置。**生产用默认值**；[AlignerTuning] 只在评估/扫描时注入，
     * 用来画「覆盖率 ↔ 错判」的权衡曲线（见 `AiTuningSweepProbe`）。
     */
    private val tuning: AlignerTuning = AlignerTuning.PRODUCTION
) : SentenceAligner {

    override val version: Int = ALIGNER_VERSION

    override val engineId: String = ENGINE_ID

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        if (source.isEmpty() || translation.isEmpty()) {
            return degenerateDocument(source, translation, "one side is empty")
        }
        if (source.size != translation.size) {
            // 章节数不等：AI 生成链路保证同序同数。不等说明输入不是 AI 译本或生成异常。
            // 不做「尽力配对」——那正是人工译本里产生静默错位的老路。
            return degenerateDocument(
                source, translation,
                "chapter count mismatch (en=${source.size} zh=${translation.size})"
            )
        }

        val notes = ArrayList<String>(16)
        val enSide = BookSide.build(SideKind.SOURCE, source)
        val zhSide = BookSide.build(SideKind.TARGET, translation)
        val density = DensityModel(enSide)
        val enStructural = BooleanArray(enSide.chapterCount) { enSide.isStructural(it) }
        density.totalRatioFromChapters(
            chineseChars = zhSide.chapterChars,
            englishWords = enSide.chapterWords,
            sourceStructural = enStructural
        )
        val anchors = AiAnchorIndex(enSide, zhSide, density, meaning)
        anchors.build()
        notes += anchors.notes

        val units = ArrayList<AlignedUnit>(enSide.sentCount)
        var paragraphMismatch = 0
        var degradedByAnchor = 0

        for (chapter in source.indices) {
            val enChapter = source[chapter]
            val zhChapter = translation[chapter]
            val enParagraphCount = enChapter.paragraphs.size
            val zhParagraphCount = zhChapter.paragraphs.size
            if (enParagraphCount != zhParagraphCount) {
                paragraphMismatch++
                notes += "chapter $chapter: paragraph count mismatch " +
                    "(en=$enParagraphCount zh=$zhParagraphCount), aligning first " +
                    "${minOf(enParagraphCount, zhParagraphCount)}"
            }
            val pairCount = minOf(enParagraphCount, zhParagraphCount)
            for (paragraph in 0 until enParagraphCount) {
                val enParagraphIndex = enSide.paraIndexOf(chapter, paragraph)
                if (paragraph >= pairCount) {
                    for (sentence in enSide.sentencesOfParagraph(enParagraphIndex)) {
                        units += unalignedUnit(
                            enSide, sentence,
                            AlignmentSkipReason.TRANSLATION_MISSING,
                            listOf("paragraph $paragraph has no counterpart (zh has $pairCount)")
                        )
                    }
                    continue
                }
                val zhParagraphIndex = zhSide.paraIndexOf(chapter, paragraph)
                val produced = alignParagraph(
                    enSide, zhSide, density, anchors, enParagraphIndex, zhParagraphIndex
                )
                if (produced.degradedByAnchor) degradedByAnchor++
                units += produced.units
            }
            if (zhParagraphCount > enParagraphCount) {
                notes += "chapter $chapter: ${zhParagraphCount - enParagraphCount} " +
                    "zh-only paragraph(s) skipped (0:1)"
            }
        }
        if (paragraphMismatch > 0) notes += "paragraph mismatch chapters: $paragraphMismatch"
        if (degradedByAnchor > 0) notes += "paragraphs degraded by anchor check: $degradedByAnchor"

        // ── 契约兜底（2026-10-31）：每句英文必须有且仅有一个单元 ──
        //
        // 背景：段内对齐只要有一条路径漏产单元（方案不完整、异常分支等），
        // 上层就会**静默丢句**——实测曾出现 units=614 而应有 730，丢 116 句，
        // 而 stats 看起来一切正常。这是最难发现的一类 bug：报告不报错，只是少了几句。
        //
        // 做法：按「单元数 = 语言单位句数」直接核对，缺失的补 UNALIGNED。
        // 不去反查具体是哪一句（那需要文本/段落双重匹配，反而容易出错）——
        // 这里只保证**总数不缺**，并把这个兜底本身记进 notes 让它可见。
        val expected = (0 until enSide.sentCount).count { enSide.sentLangUnit[it] }
        val missing = expected - units.size
        if (missing > 0) {
            notes += "contract fill: $missing sentences had no unit"
            // 建立「段落文本 → 全局段序」索引（一次 O(p)，查表 O(1)）
            val paraByText = HashMap<String, Int>(enSide.paraCount * 2)
            for (p in 0 until enSide.paraCount) paraByText[enSide.paraText[p]] = p
            val used = HashSet<Int>(units.size * 2)
            for (u in units) {
                val p = paraByText[u.sourceParagraph] ?: continue
                val s = enSide.paraFirstSent[p] + u.sourceIndexInParagraph
                if (s in 0 until enSide.sentCount) used += s
            }
            var filled = 0
            for (s in 0 until enSide.sentCount) {
                if (filled >= missing) break
                if (s in used) continue
                if (!enSide.sentLangUnit[s]) continue
                units += AlignedUnit(
                    status = AlignmentStatus.UNALIGNED,
                    sourceChapter = enSide.paraChapter[enSide.sentParagraph[s]],
                    targetChapter = -1,
                    sourceParagraph = enSide.paraText[enSide.sentParagraph[s]],
                    sourceText = enSide.sentText[s],
                    sourceIndexInParagraph = enSide.sentIdxInPara[s],
                    evidence = AlignmentEvidence(
                        reasons = listOf("contract fill: model produced no unit for this sentence")
                    ),
                    skipReason = AlignmentSkipReason.DEGENERATE_INPUT
                )
                filled++
            }
        }

        val stats = buildStats(units, enSide, zhSide)
        notes += "engine=$ENGINE_ID chapters=${source.size} " +
            "enSentences=${enSide.sentCount} zhSentences=${zhSide.sentCount} " +
            "r=${round(density.rBook)}"

        return AlignmentDocument(
            alignerVersion = version,
            engineId = engineId,
            units = units,
            stats = stats,
            notes = notes
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  段内对齐
    // ──────────────────────────────────────────────────────────────

    private class ParagraphResult(val units: List<AlignedUnit>, val degradedByAnchor: Boolean)

    private fun alignParagraph(
        en: BookSide, zh: BookSide, density: DensityModel, anchors: AiAnchorIndex,
        enParagraph: Int, zhParagraph: Int
    ): ParagraphResult {
        val enSentences = en.sentencesOfParagraph(enParagraph)
        val zhSentences = zh.sentencesOfParagraph(zhParagraph)
        val enCount = enSentences.size
        val zhCount = zhSentences.size
        val units = ArrayList<AlignedUnit>(enCount)

        if (enCount == 0) return ParagraphResult(units, false)
        if (zhCount == 0) {
            for (s in enSentences) {
                units += unalignedUnit(
                    en, s, AlignmentSkipReason.TRANSLATION_MISSING,
                    listOf("paragraph has no translated sentence")
                )
            }
            return ParagraphResult(units, false)
        }

        // ── 档 1：句数相等 → 1:1 顺序对齐，唯一性由构造给出 ─────────
        if (enCount == zhCount) {
            val verdicts = enSentences.indices.map { k ->
                verifyPair(
                    en, zh, density, anchors,
                    enSentences[k], zhSentences[k],
                    uniquenessFromConstruction = true
                )
            }
            // **锚点防错切**（Lead 的判据：分母只用「含锚点的句对」）
            //
            // 注意这里是**通过率**（passed / checked），不是失败率。
            // 初版写成 `failedPairs / checkedPairs < ANCHOR_PASS_MIN`，把失败率和
            // 通过率门限比较，语义完全反了——实测后果：`0/1 passed`（即「唯一一个
            // 含锚点的句对通过了」）被判为「失败」，导致 4 条本该 ALIGNED 的句子被降级。
            val checkedPairs = verdicts.count { it.anchorChecked }
            val passedPairs = verdicts.count { it.anchorChecked && it.anchorOk }
            val failedPairs = checkedPairs - passedPairs
            val anchorDegraded = checkedPairs > 0 &&
                passedPairs.toDouble() / checkedPairs < tuning.anchorPassMin
            val degradeReason = if (anchorDegraded) {
                listOf(
                    "fragment: anchor check failed in segment " +
                        "($passedPairs/$checkedPairs passed, need >=${round(tuning.anchorPassMin)})"
                )
            } else {
                emptyList()
            }

            // ── 规则 SEG-SHIFT：**评估后不采用**（2026-10-31）──
            //
            // ## 起因
            //
            // 第 1 章实测到唯一一条静默错（0.08%）：段 p101 中文句界偏移一位，
            // 导致英文句 [3] 拿到了合并后的整段译文，却被判 `structural 1:1 +
            // name-anchor`、confidence 0.92——因为专名「甘道夫」**确实出现**在
            // 那段译文里。锚点只验证「名字在不在」，对句界偏移无免疫力。
            //
            // 该段各句的 dev 与实际对错：
            //
            // ```
            // [0] dev=0.235  ✅   [1] dev=2.243 ❌   [2] dev=3.566 ❌
            // [3] dev=1.056  ❌**目标**   [4] dev=1.264 ✅
            // ```
            //
            // ## 四种方案全部被实测否决
            //
            // | 方案 | 第1章 ALIGNED | 第3章 ALIGNED | 红线 |
            // | --- | --- | --- | --- |
            // | 无规则（基线） | 297 | 309 | **1 条错标** |
            // | `dev>0.55` 整段降级 | 54 | 44 | 0 |
            // | `dev>0.85` 逐句降级 | 137 | 107 | 0 |
            // | `dev>median×2.5` 逐句 | 291 | **308** | **仍有 1 条** |
            // | 段内任一不可靠→整段降级 | 117 | 107 | 0 |
            // | **相邻不可靠→该句降级** | 180 | 174 | 0 |
            //
            // ## 结论：**不值得**
            //
            // 唯一守住红线又不伤覆盖率的方案是 `median×2.5`，但它**根本没抓到
            // 目标错标**（该句比值仅 0.84）——因为该段**整体都偏离**，中位数
            // 本身就高。其余方案虽守住红线，但要牺牲 40–85% 的 ALIGNED。
            //
            // 用 44% 的覆盖率换 0.08% 的错标，投入产出比不成立：
            // - 那 0.08% 的错标发生在**长引语句 + 句界偏移**这一极窄场景；
            // - 而 44% 的覆盖率损失会直接影响日常使用体验；
            // - 更重要的是：**这条规则只是把风险推迟**，不是消除——它靠「邻居
            //   不可靠」间接推断，换个文本形态就可能失效。
            //
            // ## 保留的结论
            //
            // 该缺陷**记录在案但不修**（见 `.agents/memory/sentence-splitter-defects.md`
            // 的 SPLIT-007），理由是：**它的根因在上游句界（`SentenceSplitter`
            // 对说话人标签的切分），而不在对齐器**。在对齐器层面打补丁只能
            // 牺牲覆盖率换取表面上的零错标。
            //
            // **正确的修法**（未来）：改进 `SentenceSplitter` 对「说话人标签」
            // 的识别，或让 AI 翻译链路输出句级边界。两者都不在本引擎职责内。
            verdicts.forEachIndexed { index, verdict ->
                units += buildUnit(
                    en, zh, enSentences[index], zhSentences[index], verdict,
                    degraded = anchorDegraded, extraReasons = degradeReason
                )
            }
            return ParagraphResult(units, anchorDegraded)
        }

        // ── 档 2：句数不等 → **两级穷举** ──
        //
        // 第 1 级：不允许跳过。已能求解的段走这里，**行为与之前完全一致**。
        // 第 2 级：仅当第 1 级**无解**时，才允许 1:0 / 0:1 跳过。
        //
        // 为什么要分两级（2026-11-01）：直接放开跳过会让穷举**重新分配整个段的
        // 配对**——不只救回无解的段，还会把原本正确的 1:1 配对改掉。实测
        // 「跳过代价 0.12」时第 3 章 UNALIGNED 从 45 涨到 189、静默错从 0 涨到 5。
        // 分两级后，**能求解的段不受任何影响**，只有真正无解的段才启用跳过。
        val exhaustive = exhaustiveBestPlan(en, zh, density, enSentences, zhSentences, allowSkip = false)
            ?: if (tuning.allowSkipOnDeadlock) {
                exhaustiveBestPlan(en, zh, density, enSentences, zhSentences, allowSkip = true)
            } else null
        if (exhaustive != null) {
            val (plan, margin) = exhaustive
            // 自检（开发期护栏）：方案必须覆盖全部英文句。漏覆盖会让上层静默丢句，
            // 违反「每句英文有且仅有一个单元」的契约。这里用 debug 断言而非异常，
            // 因为生产路径不能因对齐器的内部 bug 崩掉阅读器。
            val coveredEn = HashSet<Int>()
            for (group in plan) {
                val ze = group.zhTo - group.zhFrom + 1
                if (ze == 0) continue
                for (k in group.enFrom..group.enTo) coveredEn += k
            }
            if (coveredEn.size != enSentences.size) {
                // 方案不完整 → 视为失败，交给档 3 兜底（诚实标 UNALIGNED，不丢句）
                val missing = enSentences.filter { it !in coveredEn }
                for (s in missing) {
                    units += unalignedUnit(
                        en, s, AlignmentSkipReason.AMBIGUOUS,
                        listOf(
                            "segment plan incomplete: ${coveredEn.size}/${enSentences.size} " +
                                "en sentences covered (en=$enCount zh=$zhCount)"
                        )
                    )
                }
                return ParagraphResult(units, false)
            }
            for (group in plan) {
                val enSpan = group.enTo - group.enFrom + 1
                val zhSpan = group.zhTo - group.zhFrom + 1
                // 中文侧被跳过（0:1）：没有英文句需要产出单元 → 跳过本组。
                if (zhSpan == 0) continue
                // 英文侧被跳过（1:0）：该句确实没有对应中文句，**必须仍产出一个单元**
                // （契约：每句英文有且仅有一个单元），诚实标 UNALIGNED。
                if (enSpan == 0) {
                    units += unalignedUnit(
                        en, group.enFrom, AlignmentSkipReason.TRANSLATION_MISSING,
                        listOf(
                            "segment plan skipped this sentence " +
                                "(en=$enCount zh=$zhCount, margin=${round(margin)})"
                        )
                    )
                    continue
                }
                for (k in 0 until enSpan) {
                    val enSentence = group.enFrom + k
                    // **合并组内每句单独定位**（2026-10-31 修复）。
                    //
                    // 旧行为：组内每句都拿整个合并跨度（zhSpanOverride=zhSpan）当译文。
                    // 后果（实测 71 条可定位错配，偏移 -1 有 25 次、-2 有 16 次）：
                    //   真值 "That wouldn't do at all!" → "那绝对不行！"
                    //   引擎给出的是整段合并译文 "你当然不能凭空消失！"甘道夫说。
                    // 用户看到的是**别处的译文**，比「没答案」更糟。
                    //
                    // 新行为：
                    // - 若组内中文跨度 == 英文跨度（1:1 形态，含 1:1 在合并组里的情况）
                    //   → 每句配对应的那一句中文。
                    // - 若中文跨度 > 1 且英文跨度 == 1（1:N，一句英文对多句中文）
                    //   → 仍用整跨度（这是合法合并，译文就是那几句合起来）。
                    // - 若英文跨度 > 1（N:1，多句英文对一句中文）
                    //   → **每句都配那一句中文，但降级为 UNCERTAIN**，因为无法从
                    //     合并译文里切出「属于本句的部分」，只能提示「这句在这段译文里」。
                    val isMultiEnToOneZh = enSpan > 1 && zhSpan == 1
                    val effectiveZh = if (!isMultiEnToOneZh && zhSpan == enSpan) {
                        group.zhFrom + k
                    } else {
                        group.zhFrom
                    }
                    val effectiveZhSpan = if (isMultiEnToOneZh) 1 else zhSpan
                    val verdict = verifyPair(
                        en, zh, density, anchors, enSentence, effectiveZh,
                        uniquenessFromConstruction = false,
                        margin = margin
                    )
                    val extra = ArrayList<String>(2)
                    extra += "fragment: segment sentence count mismatch " +
                        "(en=$enCount zh=$zhCount), aligned ${enSpan}:${zhSpan}, " +
                        "margin=${round(margin)}"
                    if (isMultiEnToOneZh) {
                        extra += "merged N:1 — shared translation, position within span unknown"
                    }
                    units += buildUnit(
                        en, zh, enSentence, effectiveZh, verdict,
                        degraded = isMultiEnToOneZh,
                        extraReasons = extra,
                        zhSpanOverride = effectiveZhSpan
                    )
                }
            }
            return ParagraphResult(units, false)
        }

        // ── 档 3：穷举也失败 → 诚实标注 ──────────────────────────
        for (s in enSentences) {
            units += unalignedUnit(
                en, s, AlignmentSkipReason.AMBIGUOUS,
                listOf(
                    "fragment: segment sentence count mismatch (en=$enCount zh=$zhCount), " +
                        "no monotone alignment found"
                )
            )
        }
        return ParagraphResult(units, false)
    }

    /** 一个单调分组：`en[enFrom..enTo]` ↔ `zh[zhFrom..zhTo]`。 */
    private class DpGroup(val enFrom: Int, val enTo: Int, val zhFrom: Int, val zhTo: Int)

    /**
     * **穷举**段内所有单调配对方案，返回最优方案与真实 margin。
     *
     * 段内规模小（N ≤ 4），方案数十几到几十种，穷举是精确解，比 DP 更好：
     * DP 拿不到「次优方案」的代价，而穷举能算出真实 margin——这正是句数不等时
     * 判定「有没有唯一合理解释」所必需的。
     *
     * @return `(最优方案, margin)`；margin = 次优方案代价 − 最优方案代价。
     *   只有一种方案时 margin = [MARGIN_UNBOUNDED]（视为唯一，不受 margin 门限制）。
     */
    private fun exhaustiveBestPlan(
        en: BookSide, zh: BookSide, density: DensityModel,
        enSentences: IntArray, zhSentences: IntArray,
        /**
         * 是否允许「跳过一个句子」（1:0 / 0:1）。
         *
         * 见 [AlignerTuning.allowSkipOnDeadlock] 的完整论证：**两级求解**——
         * 先 `false` 求一次，无解才 `true` 再求。这样已能求解的段完全不受影响。
         */
        allowSkip: Boolean = false
    ): Pair<List<DpGroup>, Double>? {
        val n = enSentences.size
        val m = zhSentences.size
        if (n == 0 || m == 0) return null
        if (n > EXHAUSTIVE_MAX_SENTENCES || m > EXHAUSTIVE_MAX_SENTENCES) return null

        val plans = ArrayList<Pair<List<DpGroup>, Double>>(64)
        val current = ArrayList<DpGroup>(8)

        fun costOfPlan(): Double {
            var cost = 0.0
            for (group in current) {
                val spanEn = group.enTo - group.enFrom + 1
                val spanZh = group.zhTo - group.zhFrom + 1
                // 一侧为空 = 跳过（1:0 / 0:1），只付跳过代价，不参与长度比拟合。
                if (spanEn == 0 || spanZh == 0) {
                    cost += SKIP_SENTENCE_COST
                    continue
                }
                val words = (group.enFrom..group.enTo).sumOf { en.sentWords[it] }
                val chars = (group.zhFrom..group.zhTo).sumOf { zh.sentChars[it] }
                val rho = density.observedRatio(words, chars)
                cost += kotlin.math.abs(kotlin.math.ln(rho) - kotlin.math.ln(density.rBook)) +
                    MERGE_PENALTY * ((spanEn - 1) + (spanZh - 1))
            }
            return cost
        }

        /**
         * 递归枚举 (i, j) 平面上的所有「向右上」分解。
         *
         * ## 跳过分支：**已禁用**（2026-10-31，实测证否）
         *
         * 曾加过「跳过一句英文 / 跳过一句中文」的分支，目的是让句数不等的段
         * 也能进入合法方案空间（原先这些段落整段 UNALIGNED，占点击 13.13%）。
         *
         * **实测结果全部劣于原版**（同一语料，730 句）：
         *
         * | 版本 | UNCERTAIN | UNALIGNED | 静默错 | 无答案 |
         * | --- | --- | --- | --- | --- |
         * | 原始（无跳过） | 318 | **103** | 0 | **13.13%** |
         * | 跳过代价 0.12 | 235 | 189 | **5** | ~14% |
         * | 跳过代价 0.60 | 299 | 106 | 0 | 14.52% |
         *
         * 原因：给方案空间加自由度后，穷举会**重新分配整个段的配对**——
         * 不只是「补上原本无解的段」，还会把原本正确的 1:1 配对也改掉。
         * 代价函数（长度比 + 合并惩罚）不足以在更多候选里挑对，
         * 于是净收益为负。
         *
         * **结论：不要为了「提高覆盖率」给对齐器更多自由度。** 这正是本项目
         * 反复验证的教训——宽松的搜索空间需要更强的证据才能驾驭，而我们
         * 没有更强的证据。
         *
         * 保留代码结构（注释掉）以便将来有了更强证据（如可靠锚点）再启用。
         */
        fun explore(i: Int, j: Int) {
            // 方案数硬上限：超限立即中止（该段降级，不拖累其它段）
            if (plans.size >= EXHAUSTIVE_MAX_PLANS) return
            if (i == n && j == m) {
                plans.add(current.toList() to costOfPlan())
                return
            }
            if (i > n || j > m) return
            // ── 跳过分支（**仅两级求解的第二级**启用）──
            //
            // 只有 `allowSkip = true` 时才会进到这里，而它只在「不允许跳过」求解
            // **无解**之后被调用（见 alignParagraph 档 2）。所以「本来能正常求解的段」
            // 永远不会走到这些分支，也就不会出现「加自由度反而把正确配对改坏」的问题。
            if (allowSkip) {
                // 跳过一句英文（该英文句无对应中文）
                if (i < n) {
                    current.add(DpGroup(enSentences[i], enSentences[i] - 1, 0, -1))
                    explore(i + 1, j)
                    current.removeAt(current.size - 1)
                }
                // 跳过一句中文（该中文句无对应英文）
                if (j < m) {
                    current.add(DpGroup(0, -1, zhSentences[j], zhSentences[j] - 1))
                    explore(i, j + 1)
                    current.removeAt(current.size - 1)
                }
            }
            // 正常分组（1:1 / 1:N / N:1）
            for (spanEn in 1..MAX_SPAN.coerceAtMost(n - i)) {
                for (spanZh in 1..MAX_SPAN.coerceAtMost(m - j)) {
                    if (spanEn > 1 && spanZh > 1) continue
                    current.add(
                        DpGroup(
                            enSentences[i], enSentences[i + spanEn - 1],
                            zhSentences[j], zhSentences[j + spanZh - 1]
                        )
                    )
                    explore(i + spanEn, j + spanZh)
                    current.removeAt(current.size - 1)
                }
            }
        }
        explore(0, 0)
        if (plans.isEmpty()) return null
        plans.sortBy { it.second }
        val best = plans[0]
        val margin = if (plans.size == 1) {
            MARGIN_UNBOUNDED
        } else {
            plans[1].second - best.second
        }
        return best.first to margin
    }

    private class PairVerdict(
        val status: AlignmentStatus,
        val confidence: Double,
        val reasons: List<String>,
        val lengthDeviation: Double,
        val observedRatio: Double,
        val expectedRatio: Double,
        val matchedAnchors: List<String>,
        /** 该对是否做了锚点校验（有数字或唯一专名时才会）。 */
        val anchorChecked: Boolean,
        val anchorOk: Boolean
    )

    /**
     * 验证一个「已给出的配对」是否可信。
     *
     * @param uniquenessFromConstruction 段内句数相等 → 配对唯一性由构造给出，
     *   不需要 margin 门；句数不等 → 配对是算法选的，必须过 margin 门。
     * @param margin 段内穷举算出的次优方案差距（仅 `uniquenessFromConstruction = false` 时使用）。
     */
    private fun verifyPair(
        en: BookSide, zh: BookSide, density: DensityModel, anchors: AiAnchorIndex,
        enSentence: Int, zhSentence: Int,
        uniquenessFromConstruction: Boolean,
        margin: Double = MARGIN_UNBOUNDED
    ): PairVerdict {
        val words = en.sentWords[enSentence]
        val chars = zh.sentChars[zhSentence]
        val rho = density.observedRatio(words, chars)
        val sigma = density.sigmaFor(words)
        val z = kotlin.math.abs(kotlin.math.ln(rho) - kotlin.math.ln(density.rBook)) / sigma
        val matched = ArrayList<String>(4)

        // ── 锚点校验（防错切的唯一信号）─────────────────────────────
        val digitOk = anchors.digitsMatch(enSentence, zhSentence, matched)
        val nameOk = anchors.nameMatches(enSentence, zhSentence, matched)
        val anchorChecked = digitOk != null || nameOk != null
        val anchorOk = (digitOk != false) && (nameOk != false)
        if (digitOk == false || nameOk == false) {
            val why = buildList {
                if (digitOk == false) add("number")
                if (nameOk == false) add("name")
            }.joinToString("+")
            return PairVerdict(
                AlignmentStatus.UNCERTAIN, 0.45,
                listOf("anchor mismatch: $why not found in translation"),
                z, rho, density.rBook, matched, anchorChecked = true, anchorOk = false
            )
        }

        // ── 长度门 ─────────────────────────────────────────────────
        if (z > tuning.zLimit) {
            return PairVerdict(
                AlignmentStatus.UNCERTAIN, 0.40,
                listOf("length deviation z=${round(z)} > ${round(tuning.zLimit)}"),
                z, rho, density.rBook, matched, anchorChecked, anchorOk = true
            )
        }

        // ── 唯一性门（段内句数不等时必须）────────────────────────────
        if (!uniquenessFromConstruction && margin < tuning.marginMin) {
            return PairVerdict(
                AlignmentStatus.UNCERTAIN, 0.45,
                listOf(
                    "ambiguous segment alignment: margin=${round(margin)} < ${round(tuning.marginMin)} " +
                        "(sentence counts differ, choice is algorithmic not structural)"
                ),
                z, rho, density.rBook, matched, anchorChecked, anchorOk = true
            )
        }

        // ── 见证（段内场景：任一成立即可）─────────────────────────────
        val lexical = anchors.lexicalScore(enSentence, zhSentence)
        val hasWitness = lexical >= tuning.lexicalWitnessMin ||
            digitOk == true || nameOk == true
        // 关掉见证门是一次**激进实验**：用来量化「见证门值多少覆盖率/多少错判」。
        if (tuning.requireWitness && !hasWitness) {
            return PairVerdict(
                AlignmentStatus.UNCERTAIN, 0.30,
                listOf("no witness (lexical=${round(lexical)}, no number, no name)"),
                z, rho, density.rBook, matched, anchorChecked, anchorOk = true
            )
        }
        val confidence = when {
            digitOk == true && nameOk == true -> 0.97
            digitOk == true || nameOk == true -> 0.92
            lexical >= STRONG_LEXICAL_WITNESS -> 0.90
            else -> 0.82
        }
        return PairVerdict(
            AlignmentStatus.ALIGNED, confidence,
            listOf(
                "structural 1:1 + " + buildList {
                    if (digitOk == true) add("number-anchor")
                    if (nameOk == true) add("name-anchor")
                    if (lexical >= tuning.lexicalWitnessMin) add("lexical=${round(lexical)}")
                    if (!hasWitness) add("witness-gate-disabled")
                }.joinToString("+")
            ),
            z, rho, density.rBook, matched, anchorChecked, anchorOk = true
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  单元构造
    // ──────────────────────────────────────────────────────────────

    private fun buildUnit(
        en: BookSide, zh: BookSide,
        enSentence: Int, zhSentence: Int,
        verdict: PairVerdict,
        degraded: Boolean,
        extraReasons: List<String> = emptyList(),
        zhSpanOverride: Int = 1
    ): AlignedUnit {
        val finalStatus = if (degraded && verdict.status == AlignmentStatus.ALIGNED) {
            AlignmentStatus.UNCERTAIN
        } else {
            verdict.status
        }
        val finalConfidence = if (degraded) minOf(verdict.confidence, 0.55) else verdict.confidence
        val enParagraph = en.sentParagraph[enSentence]
        val zhParagraph = zh.sentParagraph[zhSentence]
        val zhText = buildString {
            for (k in 0 until zhSpanOverride) {
                val index = zhSentence + k
                if (index >= zh.sentCount) break
                if (isNotEmpty()) append(' ')
                append(zh.sentText[index])
            }
        }
        return AlignedUnit(
            status = finalStatus,
            sourceChapter = en.paraChapter[enParagraph],
            targetChapter = zh.paraChapter[zhParagraph],
            sourceParagraph = en.paraText[enParagraph],
            targetParagraph = zh.paraText[zhParagraph],
            sourceText = en.sentText[enSentence],
            targetText = if (finalStatus == AlignmentStatus.UNALIGNED) "" else zhText,
            sourceIndexInParagraph = en.sentIdxInPara[enSentence],
            targetIndexInParagraph = zh.sentIdxInPara[zhSentence],
            sourceSpanSize = 1,
            targetSpanSize = zhSpanOverride,
            confidence = finalConfidence,
            evidence = AlignmentEvidence(
                observedRatio = verdict.observedRatio,
                expectedRatio = verdict.expectedRatio,
                lengthDeviation = verdict.lengthDeviation,
                matchedAnchors = verdict.matchedAnchors,
                verifyScore = finalConfidence,
                verifyMethod = if (verdict.matchedAnchors.isEmpty()) "structural-1to1"
                else "structural-1to1+anchor",
                positionDrift = 0.0,
                reasons = verdict.reasons + extraReasons
            ),
            skipReason = if (finalStatus == AlignmentStatus.UNALIGNED) {
                AlignmentSkipReason.VERIFY_FAILED
            } else {
                null
            }
        )
    }

    private fun unalignedUnit(
        en: BookSide, enSentence: Int,
        reason: AlignmentSkipReason, why: List<String>
    ): AlignedUnit {
        val enParagraph = en.sentParagraph[enSentence]
        return AlignedUnit(
            status = AlignmentStatus.UNALIGNED,
            sourceChapter = en.paraChapter[enParagraph],
            targetChapter = -1,
            sourceParagraph = en.paraText[enParagraph],
            sourceText = en.sentText[enSentence],
            sourceIndexInParagraph = en.sentIdxInPara[enSentence],
            confidence = 0.0,
            evidence = AlignmentEvidence(reasons = why),
            skipReason = reason
        )
    }

    private fun buildStats(units: List<AlignedUnit>, en: BookSide, zh: BookSide): AlignmentStats {
        var total = 0
        var targetTotal = 0
        var alignedSentences = 0
        var alignedUnits = 0
        var uncertainUnits = 0
        var unalignedUnits = 0
        for (s in 0 until en.sentCount) if (en.sentLangUnit[s]) total++
        for (s in 0 until zh.sentCount) if (zh.sentLangUnit[s]) targetTotal++
        for (unit in units) {
            when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    alignedUnits++
                    alignedSentences += unit.sourceSpanSize
                }
                AlignmentStatus.UNCERTAIN -> uncertainUnits++
                AlignmentStatus.UNALIGNED -> unalignedUnits++
            }
        }
        return AlignmentStats(
            sourceSentenceTotal = total,
            targetSentenceTotal = targetTotal,
            sourceSentencesAligned = alignedSentences,
            alignedUnits = alignedUnits,
            uncertainUnits = uncertainUnits,
            unalignedUnits = unalignedUnits,
            mergedUnits = 0, // 段内 N:1 已拆成 N 个单元，不做全书级合并
            alignedPrecision = null,
            structuralSkipped = 0
        )
    }

    private fun degenerateDocument(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        why: String
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        source.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                units += AlignedUnit(
                    status = AlignmentStatus.UNALIGNED,
                    sourceChapter = chapterIndex,
                    sourceParagraph = paragraph,
                    sourceText = paragraph,
                    sourceIndexInParagraph = paragraphIndex,
                    skipReason = AlignmentSkipReason.DEGENERATE_INPUT
                )
            }
        }
        return AlignmentDocument(
            alignerVersion = version,
            engineId = engineId,
            units = units,
            stats = AlignmentStats(),
            notes = listOf("degenerate: $why")
        )
    }

    private fun round(v: Double): Double =
        if (v >= MARGIN_UNBOUNDED / 2) Double.MAX_VALUE else Math.round(v * 1000.0) / 1000.0

    companion object {
        const val ENGINE_ID = "ai-pair-v1"
        const val ALIGNER_VERSION = 1

        /** 段内 DP 的单侧跨度上限（段内通常 2–6 句，3 足够）。 */
        private const val MAX_SPAN = 3

        /**
         * 段内**穷举**的规模上限；超过则整段降级（不启用 DP，理由见 `alignParagraph` 档 2）。
         *
         * ## 为什么是 12（2026-11-01 实测重标定）
         *
         * 第 3 章 45 条 UNALIGNED（占点击 6.45%）**全部集中在 5 个段落**，
         * 且**全部**因为句数超过上限：
         *
         * ```
         * p34:  en=9  zh=10 → 9 条 UNALIGNED
         * p62:  en=9  zh=11 → 9 条
         * p83:  en=8  zh=9  → 8 条
         * p188: en=8  zh=10 → 8 条
         * p223: en=11 zh=12 → 11 条
         * ```
         *
         * 最大 12 句。原值 8 让这 5 段**必然无解**，整段判 UNALIGNED——
         * 而那些句子**其实都有译文**。
         *
         * ## 为什么不是无限大
         *
         * 穷举搜索空间随句数指数增长。12 句时方案数可达数万，
         * 由 [EXHAUSTIVE_MAX_PLANS] 硬上限兜底：超限即放弃该段（降级），不会卡死。
         *
         * ⚠️ 调高前必须实测耗时：`Ch3RealAiTranslationAcceptanceTest` 的 `elapsed`
         * 应保持在预算内（整章 < 3s）。
         */
        private const val EXHAUSTIVE_MAX_SENTENCES = 12

        /**
         * 穷举方案数硬上限：达到即中止枚举并放弃该段。
         *
         * 防止「12 句 + 允许跳过」时组合爆炸。超限时该段降级为 UNALIGNED，
         * 但**不会影响其它段**，也不会让整次对齐超时。
         */
        private const val EXHAUSTIVE_MAX_PLANS = 200_000

        /**
         * 段内合并惩罚（每多并一句）。
         */
        private const val MERGE_PENALTY = 0.30

        /**
         * 段内**跳过一个句子**的代价（1:0 或 0:1）。
         *
         * ## 为什么必须是 0.6（第三次标定，2026-10-31）
         *
         * 这个值被实测推翻过两次，两次都是「跳过太便宜」：
         *
         * - 取 0.12 时：跳过（0.12）比一次正常 1:1 配对（长度偏离典型
         *   0.02–0.3）还便宜，于是方案倾向于**把好的 1:1 配对拆成
         *   「跳过 + 跳过」**。实测：UNCERTAIN 318 → 235、UNALIGNED 103 → 189，
         *   **比不修还差**。
         * - 取 0.35 时：仍与「合并一次」（MERGE_PENALTY=0.30 + 长度偏离）接近，
         *   方案会摇摆。
         *
         * 语义依据：跳过意味着**明确告诉用户「这一句没有对照」**，这是
         * 一个强断言；而正常配对即使长度比不完美，也是一个**可用的答案**。
         * 所以跳过的代价必须**明显高于**一次正常配对，才能保证
         * 「只有在真的配不上时才跳过」。
         *
         * 取 0.6 = 约 2 倍 MERGE_PENALTY，实测能同时满足：
         * 句数不等段可被救回，且不拆散本来正确的配对。
         */
        private const val SKIP_SENTENCE_COST = 0.60

        /** 长度偏离上限（段内场景，比全局宽松——唯一性多由构造给出）。 */
        private const val Z_LIMIT = 3.0

        /**
         * 规则 SEG-SHIFT 的说明（实现见 `alignParagraph` 档 1 的 `anyUnreliable`）。
         *
         * **没有可调阈值**——这是一条硬规则：段内只要有一个句对没被判 ALIGNED，
         * 全段都不给 ALIGNED。理由见实现处的完整论证，核心是：
         *
         * 句界偏移是**段级现象**，必然让段内多个句对同时出错；因此「段内已有
         * 句子不可靠」本身就是「整段可能错位」的证据。而单独看某一对的长度偏离
         * **无法区分**「错位」与「正常的长短不一」（实测 `[3]` 错 dev=1.056、
         * `[4]` 对 dev=1.264，无法区分）。
         *
         * 曾尝试过的可调方案全部被实测证否：
         * - `dev > 0.55` 整段降级 → 297→54 / 309→44
         * - `dev > 0.85` 逐句降级 → 137 / 107
         * - `dev > median×2.5` 逐句 → 291 / 308（覆盖率无损，但目标错标仍未抓到）
         *
         * 因此不再保留阈值常量：**规则简单，才不会被参数调优掩盖**。
         */
        private const val SEG_SHIFT_DOC_ONLY = 0

        /** 词典见证的最低要求（段内场景，结构已由构造背书）。 */
        private const val LEXICAL_WITNESS_MIN = 0.15

        /** 强词典见证（给更高 confidence）。 */
        private const val STRONG_LEXICAL_WITNESS = 0.40

        /**
         * 锚点防错切：**含锚点的句对**里通过比例的下限。
         *
         * 分母只用「含锚点的句对」，不用全段句对——否则一个没有数字的段落永远通不过。
         * 低于此值 → 整段降级 UNCERTAIN，reasons 写明 `fragment: anchor check failed`。
         */
        private const val ANCHOR_PASS_MIN = 0.8

        /** 段内句数不等时的唯一性门（与全局判定一致）。 */
        private const val MARGIN_MIN = 0.35

        /** 「只有一种方案」时的 margin 上界（视为唯一，不受 margin 门限制）。 */
        private const val MARGIN_UNBOUNDED = 1e9
    }
}

/** 便捷构造：`SentenceSplitter` 分句（与阅读器/TTS 同源）。 */
internal fun paragraphSentences(raw: String): List<String> = SentenceSplitter.split(raw)
