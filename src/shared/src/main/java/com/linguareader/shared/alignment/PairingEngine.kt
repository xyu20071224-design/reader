package com.linguareader.shared.alignment

import kotlin.math.abs
import kotlin.math.ln

/**
 * 一个英文句的对齐草稿：DP 阶段的全部结果，供 [decide] 的输入组装使用。
 *
 * 这里**刻意不与 `AlignedUnit` 合并**：`AlignedUnit` 是交付契约（一单元 = 一句英文），
 * 而 `N:1` 的 N 条英文句共享同一个 DP 边——两者粒度不同（设计 §9.5）。
 */
internal class UnitDraft(
    val sourceSentence: Int,
    var hasPair: Boolean = false,
    var spanEn: Int = 1,
    var spanZh: Int = 0,
    var zhFrom: Int = -1,
    var margin: Double = 0.0,
    var z: Double = Double.MAX_VALUE,

    /**
     * 长度信息量 `|ln(ρ / r_eff)|`：长度对「这句配谁」到底有没有说话。
     *
     * 与 `z` 的区别：`z` 是把偏离**除以 σ 归一化**后的量（用于长度门），这个是**原始的
     * 对数偏离**（用于判断「长度这一维有没有数据」）。两者不可互换：σ 很大的书里
     * `z` 会很小但偏离其实不小，反之亦然。
     */
    var lengthInformativeness: Double = Double.MAX_VALUE,
    var drift: Double = 0.0,
    var driftBudget: Double = Double.MAX_VALUE,
    var anchorHard: Boolean = false,
    var anchorOrderOk: Boolean = false,
    var anchorConflict: Boolean = false,
    var noPlausibleCandidate: Boolean = true,
    var matchedAnchors: String = ""
) {
    /** 该句所属的 DP 边（用于 `mergedUnits` 按边计数）。 */
    var edge: DpEdge? = null

    /**
     * 漂移否决（§7.7）：块级超预算比例过高，或单点 `drift > 3·budget`。
     *
     * **降级而非重排**：重排等于让漂移检测参与选路，会重新变成自证。这里只置一个标志，
     * 判定层据此把 ALIGNED 压成 UNCERTAIN（`driftBudget` 保持原值，不改写它去「骗过」门）。
     */
    var driftVetoed: Boolean = false

    /** 判定结果（F2 之后填入，供投影层使用）。 */
    var status: AlignmentStatus = AlignmentStatus.UNALIGNED
    var skipReason: AlignmentSkipReason? = null
    var confidence: Double = 0.0
    var reasons: List<String> = emptyList()
    var evidence: AlignmentEvidence = AlignmentEvidence()

    /**
     * §8.6 反向候选：验证器认为更强的那一句的中文下标（-1 = 无）。
     *
     * **只影响展示**：投影层把 `targetText` 换成它的文本，但状态已经被强制成 `UNCERTAIN`，
     * 所以不会增加任何错标风险。
     */
    var salvageTarget: Int = -1
}

/**
 * 配对层（设计 §7，交接单 C1–C4 + D1–D3）。
 *
 * 三层结构把「误差传播」逐层切断：
 *
 * | 层 | 单元 | 作用 |
 * | --- | --- | --- |
 * | 章节层 | 章 | 泄漏一章不会把后面全部错位（[ChapterLayer]） |
 * | 锚点块 | 硬钉之间的矩形 | 一颗错钉只污染一个块，块边界钉死 |
 * | 段落层 | 段 | 段级的长度信噪比比句级高一个量级（√10 的 σ 收缩） |
 * | 句级 | 句 | 真正的输出粒度 |
 *
 * ## 在这四层之上再加一层「事后否决」
 *
 * 漂移否决（[driftVeto]）**降级而不重排**：重排等于让漂移检测参与选路，而漂移检测用的是
 * 同一套结构特征，会重新变成自证。降级是纯否决，方向安全（§7.7）。
 *
 * ## 热路径纪律（§12.4）
 *
 * 段层/句级 DP 的内层只碰 `FloatArray`/`IntArray` 下标与整数/浮点算术：不 `lowercase`、
 * 不 `Regex`、不 `substring`、不查 `HashMap<String,*>`、不产生每格对象。长度偏离 `z` 在
 * **进 DP 之前按组预计算成 `FloatArray`**（每个 (i,j,spanEn,spanZh) 一格），内层只读表。
 */
internal class PairingEngine(
    private val source: BookSide,
    private val target: BookSide,
    private val density: DensityModel,
    private val anchors: AnchorLayer
) {
    val drafts: Array<UnitDraft> = Array(source.sentCount) { UnitDraft(it) }

    /** 全部 DP 边（`mergedUnits` 按这一层计数，§9.5）。 */
    val edges: MutableList<DpEdge> = ArrayList()

    /** 块级漂移否决触发的记录（写进 notes）。 */
    val notes: MutableList<String> = ArrayList()

    /** 每个英文章节对的英文句区间与中文句区间（供漂移计算与诊断）。 */
    private val chapterPairSource = HashMap<Int, IntRange>(32)
    private val chapterPairTarget = HashMap<Int, IntRange>(32)

    // ──────────────────────────────────────────────────────────────

    fun run(chapters: ChapterLayer) {
        for (enChapter in 0 until source.chapterCount) {
            val verdict = chapters.sourceVerdict.getOrNull(enChapter) ?: ChapterVerdict.ACCEPTED
            val zhChapter = chapters.sourceToTarget.getOrNull(enChapter) ?: -1
            if (verdict != ChapterVerdict.ACCEPTED || zhChapter < 0) continue
            // N:1 时只在组的第一章跑一次
            val group = chapters.targetToSources.getOrNull(zhChapter)
            if (group != null && group.isNotEmpty() && group.min() != enChapter) continue
            val enChapters = group ?: intArrayOf(enChapter)
            pairChapters(enChapters, zhChapter)
        }
        applyDriftVeto()
    }

    // ──────────────────────────────────────────────────────────────
    //  章节对 → 段落组 → 句级边
    // ──────────────────────────────────────────────────────────────

    private fun pairChapters(enChapters: IntArray, zhChapter: Int) {
        val enParagraphs = ArrayList<Int>()
        for (chapter in enChapters) {
            for (p in source.paragraphsOfChapter(chapter)) enParagraphs.add(p)
        }
        val zhParagraphs = target.paragraphsOfChapter(zhChapter).toList()
        if (enParagraphs.isEmpty() || zhParagraphs.isEmpty()) return

        val enFirst = enParagraphs.first()
        val enLast = enParagraphs.last()
        chapterPairSource[enChapters.first()] =
            source.paraFirstSent[enFirst] until
                (source.paraFirstSent[enLast] + source.paraSentCount[enLast])
        val zhFirst = zhParagraphs.first()
        val zhLast = zhParagraphs.last()
        chapterPairTarget[enChapters.first()] =
            target.paraFirstSent[zhFirst] until
                (target.paraFirstSent[zhLast] + target.paraSentCount[zhLast])

        // ── 段落层 DP ───────────────────────────────────────────
        val groups = paragraphGroups(enParagraphs, zhParagraphs)
        // ── 句级 DP（每个段落组内，按硬钉切块）────────────────────
        //
        // **结构约束（Lead 裁定）**：句级配对不能跨段落边界借中文句。
        //
        // 段落层已经决定了「英文第 i 段 ↔ 中文第 j 段」，句级只能在这个对的内部做单调
        // 对齐。若允许句级跨段，等于绕过段落层的结论——实测后果：中文侧缺一整段时，
        // DP 会把邻近段落的中文句「借」过来凑一条边，输出一条指向别的段的对照（错标），
        // 且那条边在语义上根本不是候选。
        //
        // 唯一例外：段落层判定为**合并**（`N:1` / `1:N` / `N:M`）时，组内共享句子池是
        // 定义的一部分（译者确实会把两段并成一段），必须允许。所以约束按组下发：
        // 组是 1:1 时严格段内，组是合并时组内自由。
        for (group in groups) {
            val merged = group.enFrom != group.enTo || group.zhFrom != group.zhTo
            sentenceGroups(
                sourceSentences = source.paraFirstSent[group.enFrom] until
                    (source.paraFirstSent[group.enTo] + source.paraSentCount[group.enTo]),
                targetSentences = target.paraFirstSent[group.zhFrom] until
                    (target.paraFirstSent[group.zhTo] + target.paraSentCount[group.zhTo]),
                strictParagraph = !merged
            )
        }
    }

    private class ParagraphGroup(val enFrom: Int, val enTo: Int, val zhFrom: Int, val zhTo: Int)

    /**
     * 段落层单调 DP（§7.4）：动作 `1:1` / `1:N` / `N:1`（N ≤ 3）/ `skip_en` / `skip_zh`。
     *
     * 带宽以累计**字数**线为中心，而不是段序：段序插值的隐含假设是「每段密度均匀」，
     * 而真实书里对话段与描写段的句数密度差一个数量级。
     */
    private fun paragraphGroups(enParagraphs: ArrayList<Int>, zhParagraphs: List<Int>): List<ParagraphGroup> {
        val n = enParagraphs.size
        val m = zhParagraphs.size
        if (n == 0 || m == 0) return emptyList()

        val enWords = IntArray(n) { source.paraWords[enParagraphs[it]] }
        val zhChars = IntArray(m) { target.paraChars[zhParagraphs[it]] }
        val cumulativeZh = LengthLine.cumulative(zhChars)
        val band = LengthLine.paragraphBand(Math.max(n, m))

        val width = m + 1
        val INF = 1e18
        val best = DoubleArray((n + 1) * width) { INF }
        val action = IntArray((n + 1) * width) { ACTION_NONE }
        best[0] = 0.0

        var runningWords = 0
        for (a in 1..n) {
            runningWords += enWords[a - 1]
            val center = LengthLine.lastAtOrBelow(cumulativeZh, density.rBook * runningWords)
            val lo = (center - band).coerceAtLeast(0)
            val hi = (center + band).coerceAtMost(m)
            for (b in 1..m) {
                if (b < lo || b > hi) {
                    // 带外仍然允许 skip 通行（否则整条路径不可达），只是不评估配对
                }
                var bestCost = INF
                var topAction = ACTION_NONE
                var index = (a - 1) * width + b
                if (best[index] < INF) {
                    val cost = best[index] + AlignTuning.PARA_SKIP_EN
                    if (cost < bestCost) { bestCost = cost; topAction = ACTION_SKIP_EN }
                }
                index = a * width + (b - 1)
                if (best[index] < INF) {
                    val cost = best[index] + AlignTuning.PARA_SKIP_ZH
                    if (cost < bestCost) { bestCost = cost; topAction = ACTION_SKIP_ZH }
                }
                if (b in lo..hi) {
                    val maxEn = AlignTuning.MAX_SPAN_ONE_SIDED.coerceAtMost(a)
                    val maxZh = AlignTuning.MAX_SPAN_ONE_SIDED.coerceAtMost(b)
                    for (spanEn in 1..maxEn) {
                        for (spanZh in 1..maxZh) {
                            if (spanEn > 1 && spanZh > 1) continue
                            val from = (a - spanEn) * width + (b - spanZh)
                            if (best[from] >= INF) continue
                            val cost = best[from] + paragraphCost(
                                enParagraphs, a - spanEn, spanEn,
                                zhParagraphs, b - spanZh, spanZh,
                                enWords, zhChars
                            )
                            if (cost < bestCost) { bestCost = cost; topAction = encodePair(spanEn, spanZh) }
                        }
                    }
                }
                best[a * width + b] = bestCost
                action[a * width + b] = topAction
            }
        }

        // 回溯成段落组
        val groups = ArrayList<ParagraphGroup>()
        var a = n
        var b = m
        while (a > 0 && b > 0) {
            val act = action[a * width + b]
            if (act == ACTION_NONE) break
            when {
                act == ACTION_SKIP_EN -> { a--; }
                act == ACTION_SKIP_ZH -> { b--; }
                else -> {
                    val spanEn = act shr 8
                    val spanZh = act and 0xFF
                    val enFrom = a - spanEn
                    val zhFrom = b - spanZh
                    groups.add(
                        ParagraphGroup(
                            enParagraphs[enFrom], enParagraphs[a - 1],
                            zhParagraphs[zhFrom], zhParagraphs[b - 1]
                        )
                    )
                    a = enFrom
                    b = zhFrom
                }
            }
        }
        // 剩下的（未配对段落）不产出边：中文多出的段落在译文里没有原文（0:1），
        // 英文多出的段落由句级层判 UNALIGNED（1:0）。
        return groups
    }

    private fun paragraphCost(
        enParagraphs: ArrayList<Int>, enFrom: Int, spanEn: Int,
        zhParagraphs: List<Int>, zhFrom: Int, spanZh: Int,
        enWords: IntArray, zhChars: IntArray
    ): Double {
        var words = 0
        var chars = 0
        for (k in 0 until spanEn) words += enWords[enFrom + k]
        for (k in 0 until spanZh) chars += zhChars[zhFrom + k]
        val rho = density.observedRatio(words, chars)
        val rEff = density.rBook
        val sigma = density.sigmaFor(words)
        val z = abs(ln(rho) - ln(rEff)) / sigma
        val lengthTerm = minOf(1.0, (z / AlignTuning.PARA_Z_SCALE) * (z / AlignTuning.PARA_Z_SCALE))
        val n = enWords.size.toDouble().coerceAtLeast(1.0)
        val m = zhChars.size.toDouble().coerceAtLeast(1.0)
        val posDelta = abs((enFrom + spanEn / 2.0) / n - (zhFrom + spanZh / 2.0) / m)
        val mergePenalty = AlignTuning.PARA_W_MERGE * AlignTuning.PARA_MERGE_PENALTY_SCALE *
            ((spanEn - 1) + (spanZh - 1))
        return AlignTuning.PARA_W_LEN * lengthTerm +
            AlignTuning.PARA_W_POS * posDelta +
            mergePenalty
    }

    // ──────────────────────────────────────────────────────────────
    //  句级 DP
    // ──────────────────────────────────────────────────────────────

    /**
     * 在一个段落组的句区间内做单调 DP，并按硬钉切块（§6.4）。
     *
     * 硬钉走**切块**，不进代价函数：`+∞` 在浮点累加里会传染成 `NaN`，比较全部为假，
     * DP 的最优路径会**静默**退化成「随便一条」（交接单 §3 第 3 点）。
     *
     * @param strictParagraph 组是 1:1 时为 true：句级配对**不得跨段落边界**。
     *   组是合并（`N:1`/`1:N`）时为 false：组内共享句子池是合并语义的一部分。
     */
    private fun sentenceGroups(
        sourceSentences: IntRange,
        targetSentences: IntRange,
        strictParagraph: Boolean
    ) {
        if (sourceSentences.isEmpty() || targetSentences.isEmpty()) return
        if (strictParagraph) {
            // 1:1 段落组：按段内句子池独立跑 DP，边界不可跨越。
            val blocks = blockRangesWithinParagraphs(sourceSentences, targetSentences)
            for (block in blocks) {
                sentenceDp(block.sourceRange, block.targetRange, block.startPin, block.endPin)
            }
            return
        }
        val blocks = blockRanges(sourceSentences, targetSentences)
        for (block in blocks) {
            sentenceDp(block.sourceRange, block.targetRange, block.startPin, block.endPin)
        }
    }

    /**
     * 1:1 段落组内的切块：**每个段落独立成块**，块间不共享句子池。
     *
     * 这是「句级不得跨段落借句」的落地方式——不是靠代价惩罚（惩罚只是让借句变贵，
     * 但仍可能被选中），而是让跨段转移**在 DP 里根本不存在**。
     */
    private fun blockRangesWithinParagraphs(
        sourceSentences: IntRange,
        targetSentences: IntRange
    ): List<Block> {
        val sourceParagraphs = paragraphsIn(source, sourceSentences)
        val targetParagraphs = paragraphsIn(target, targetSentences)
        val blocks = ArrayList<Block>(sourceParagraphs.size + targetParagraphs.size)
        // 段落按「同序号」配对（1:1 组的定义）；多出来的段落在自己那一侧独立成块，
        // 其句子由句级 DP 判 1:0（英文多）或 0:1（中文多）。
        val paired = minOf(sourceParagraphs.size, targetParagraphs.size)
        for (index in 0 until paired) {
            val enParagraph = sourceParagraphs[index]
            val zhParagraph = targetParagraphs[index]
            val enRange = source.paraFirstSent[enParagraph] until
                (source.paraFirstSent[enParagraph] + source.paraSentCount[enParagraph])
            val zhRange = target.paraFirstSent[zhParagraph] until
                (target.paraFirstSent[zhParagraph] + target.paraSentCount[zhParagraph])
            if (enRange.isEmpty() || zhRange.isEmpty()) continue
            // 段内仍按硬钉切块（硬钉可能落在段内）
            for (block in blockRanges(enRange, zhRange)) blocks.add(block)
        }
        return blocks
    }

    /** 给定句区间，返回它覆盖的段落下标（升序）。 */
    private fun paragraphsIn(side: BookSide, sentences: IntRange): List<Int> {
        val result = ArrayList<Int>(4)
        var index = -1
        for (s in sentences) {
            val paragraph = side.sentParagraph[s]
            if (paragraph != index) {
                if (index < 0 || result.lastOrNull() != paragraph) result.add(paragraph)
                index = paragraph
            }
        }
        return result
    }

    /** 一个切块后的矩形：英文/中文句区间，以及两端的硬钉（`-1` = 块边界）。 */
    private class Block(
        val sourceRange: IntRange,
        val targetRange: IntRange,
        val startPin: IntArray?, // [sourceSentence, targetSentence]
        val endPin: IntArray?
    )

    private fun blockRanges(sourceSentences: IntRange, targetSentences: IntRange): List<Block> {
        val pins = anchors.pins.filter {
            it.sourceSentence in sourceSentences && it.targetSentence in targetSentences
        }.sortedBy { it.sourceSentence }
        if (pins.isEmpty()) {
            return listOf(Block(sourceSentences, targetSentences, null, null))
        }
        val blocks = ArrayList<Block>(pins.size + 1)
        var sourceFrom = sourceSentences.first
        var targetFrom = targetSentences.first
        for (pin in pins) {
            if (pin.sourceSentence < sourceFrom || pin.targetSentence < targetFrom) continue
            if (pin.sourceSentence > sourceFrom && pin.targetSentence > targetFrom) {
                blocks.add(
                    Block(
                        sourceFrom until pin.sourceSentence,
                        targetFrom until pin.targetSentence,
                        null, null
                    )
                )
            }
            // 硬钉本身作为一条强制边（1:1）
            blocks.add(
                Block(
                    pin.sourceSentence..pin.sourceSentence,
                    pin.targetSentence..pin.targetSentence,
                    intArrayOf(pin.sourceSentence, pin.targetSentence),
                    intArrayOf(pin.sourceSentence, pin.targetSentence)
                )
            )
            sourceFrom = pin.sourceSentence + 1
            targetFrom = pin.targetSentence + 1
        }
        if (sourceFrom <= sourceSentences.last && targetFrom <= targetSentences.last) {
            blocks.add(Block(sourceFrom..sourceSentences.last, targetFrom..targetSentences.last, null, null))
        }
        return blocks
    }

    /**
     * 句级单调 DP（§7.3/§7.5/§7.6）。
     *
     * 状态 `(i, j, run)`：已消耗 i 条英文句、j 条中文句、当前连续跳句数 `run`。
     * `run` 达到 [AlignTuning.MAX_SKIP_RUN] 时**不生成** skip 边（不是给一个大代价）。
     */
    private fun sentenceDp(
        sourceRange: IntRange,
        targetRange: IntRange,
        startPin: IntArray?,
        endPin: IntArray?
    ) {
        val n = sourceRange.last - sourceRange.first + 1
        val m = targetRange.last - targetRange.first + 1
        if (n <= 0 || m <= 0) return

        // 硬钉块：直接产出一条 1:1 边（切块即强制，不需要在 DP 里表达）
        if (startPin != null && endPin != null && n == 1 && m == 1) {
            val s = startPin[0]
            val t = startPin[1]
            emitPinEdge(s, t)
            return
        }

        val runs = AlignTuning.MAX_SKIP_RUN + 1
        val width = m + 1
        val INF = 1e18f
        val cellCount = (n + 1) * width * runs
        if (cellCount > AlignTuning.MAX_GROUP_CELLS) {
            // 安全阀：超大的单个段落组（极端合卷书）沿长度线对半切开递归，
            // 结构上禁止分配 O(N²·run) 的存储（§12.3 内存红线的落地方式）。
            val halfSource = n / 2
            val halfTarget = m / 2
            if (halfSource == 0 || halfTarget == 0) return
            sentenceDp(
                sourceRange.first until sourceRange.first + halfSource,
                targetRange.first until targetRange.first + halfTarget,
                null, null
            )
            sentenceDp(
                sourceRange.first + halfSource..sourceRange.last,
                targetRange.first + halfTarget..targetRange.last,
                null, null
            )
            notes += "sentence group split by MAX_GROUP_CELLS: ${n}x$m"
            return
        }

        val zTable = precomputeZ(sourceRange, targetRange)
        val best = FloatArray(cellCount) { INF }
        val action = IntArray(cellCount) { ACTION_NONE }
        best[0] = 0f

        val sourceCumWords = IntArray(n + 1)
        val sourceCumChars = IntArray(n + 1)
        for (k in 1..n) {
            val s = sourceRange.first + k - 1
            sourceCumWords[k] = sourceCumWords[k - 1] + source.sentWords[s]
            sourceCumChars[k] = sourceCumChars[k - 1] + source.sentChars[s]
        }
        val targetCumChars = IntArray(m + 1)
        for (k in 1..m) {
            val t = targetRange.first + k - 1
            targetCumChars[k] = targetCumChars[k - 1] + target.sentChars[t]
        }

        fun index(i: Int, j: Int, run: Int): Int = ((i * width) + j) * runs + run

        for (i in 1..n) {
            for (j in 1..m) {
                // skip_en（i 方向的跳句）
                for (run in 0 until AlignTuning.MAX_SKIP_RUN) {
                    val from = index(i - 1, j, run)
                    if (best[from] >= INF) continue
                    val to = index(i, j, run + 1)
                    val cost = best[from] + AlignTuning.COST_SKIP_EN.toFloat()
                    if (cost < best[to]) {
                        best[to] = cost
                        action[to] = ACTION_SKIP_EN
                    }
                }
                // skip_zh
                for (run in 0 until AlignTuning.MAX_SKIP_RUN) {
                    val from = index(i, j - 1, run)
                    if (best[from] >= INF) continue
                    val to = index(i, j, run + 1)
                    val cost = best[from] + AlignTuning.COST_SKIP_ZH.toFloat()
                    if (cost < best[to]) {
                        best[to] = cost
                        action[to] = ACTION_SKIP_ZH
                    }
                }
                // 配对动作
                val maxEn = AlignTuning.MAX_SPAN_ONE_SIDED.coerceAtMost(i)
                val maxZh = AlignTuning.MAX_SPAN_ONE_SIDED.coerceAtMost(j)
                for (spanEn in 1..maxEn) {
                    for (spanZh in 1..maxZh) {
                        val bidirectional = spanEn > 1 && spanZh > 1
                        if (bidirectional && (spanEn > AlignTuning.MAX_SPAN_BI_DIRECTIONAL ||
                                spanZh > AlignTuning.MAX_SPAN_BI_DIRECTIONAL)
                        ) continue
                        if (bidirectional && !hasTerminatorlessFragment(
                                sourceRange.first + i - spanEn,
                                sourceRange.first + i - 1
                            ) && !hasTerminatorlessFragment(
                                targetRange.first + j - spanZh,
                                targetRange.first + j - 1
                            )
                        ) continue
                        val to = index(i, j, 0)
                        var bestForCell = INF
                        var bestFrom = -1
                        for (run in 0 until runs) {
                            val from = index(i - spanEn, j - spanZh, run)
                            if (best[from] >= INF) continue
                            val cost = best[from] + pairCost(
                                i, spanEn, j, spanZh,
                                sourceRange, targetRange, zTable, width, m
                            )
                            if (cost < bestForCell) {
                                bestForCell = cost
                                bestFrom = from
                            }
                        }
                        if (bestFrom >= 0 && bestForCell < best[to]) {
                            best[to] = bestForCell
                            action[to] = encodePair(spanEn, spanZh)
                        }
                    }
                }
            }
        }

        // 终点：取任意 run 中最优
        var bestEnd = -1
        var bestEndCost = INF
        for (run in 0 until runs) {
            val candidate = index(n, m, run)
            if (best[candidate] < bestEndCost) {
                bestEndCost = best[candidate]
                bestEnd = candidate
            }
        }
        if (bestEnd < 0) return

        // ── 回溯 ─────────────────────────────────────────────────
        val path = ArrayList<IntArray>(n + m)
        var cursor = bestEnd
        while (true) {
            val run = cursor % runs
            val cell = cursor / runs
            val j = cell % width
            val i = cell / width
            if (i == 0 && j == 0) break
            val act = action[cursor]
            when {
                act == ACTION_SKIP_EN -> {
                    path.add(intArrayOf(i - 1, j, encodePair(1, 0)))
                    cursor = index(i - 1, j, (run - 1).coerceAtLeast(0))
                }
                act == ACTION_SKIP_ZH -> {
                    path.add(intArrayOf(i, j - 1, encodePair(0, 1)))
                    cursor = index(i, j - 1, (run - 1).coerceAtLeast(0))
                }
                act == ACTION_NONE -> break
                else -> {
                    val spanEn = act shr 8
                    val spanZh = act and 0xFF
                    path.add(intArrayOf(i - spanEn, j - spanZh, encodePair(spanEn, spanZh)))
                    // 回到 run = 0（配对转移把 run 归零）
                    cursor = index(i - spanEn, j - spanZh, 0)
                }
            }
        }
        path.reverse()

        // ── 落边 + margin ────────────────────────────────────────
        for (step in path) {
            val spanEn = step[2] shr 8
            val spanZh = step[2] and 0xFF
            if (spanEn == 0) continue // 0:1 不产出英文单元
            val i = step[0]
            val j = step[1]
            if (spanEn == 1 && spanZh == 0) {
                // 1:0：英文句无译文，留给判定层（noPlausibleCandidate 在下面统一算）
                continue
            }
            val enFrom = sourceRange.first + i
            val zhFrom = targetRange.first + j
            val cost = pairCost(i, spanEn, j, spanZh, sourceRange, targetRange, zTable, width, m).toDouble()
            val margin = localMargin(i, spanEn, j, spanZh, sourceRange, targetRange, zTable, width, m, cost)
            val edge = DpEdge(enFrom, spanEn, zhFrom, spanZh, cost, margin)
            edges.add(edge)
            for (k in 0 until spanEn) {
                val sentence = enFrom + k
                val draft = drafts[sentence]
                draft.hasPair = true
                draft.spanEn = spanEn
                draft.spanZh = spanZh
                draft.zhFrom = zhFrom
                draft.margin = margin
                draft.z = zOf(zTable, i, spanEn, j, spanZh, width)
                draft.lengthInformativeness = lengthInformativenessOf(
                    sourceRange, targetRange, sourceFrom = enFrom, spanEn = spanEn,
                    targetFrom = zhFrom, spanZh = spanZh
                )
                draft.edge = edge
                draft.anchorHard = startPin != null && endPin != null && spanEn == 1 && spanZh == 1
                draft.anchorOrderOk = draft.anchorHard
            }
        }
        if (startPin != null && endPin != null) {
            notes += "hard seed pinned: en=${startPin[0]} zh=${startPin[1]}"
        }
    }

    /** 硬钉块直接产边（切块即强制，§6.4）。 */
    private fun emitPinEdge(sourceSentence: Int, targetSentence: Int) {
        val pin = anchors.pins.firstOrNull {
            it.sourceSentence == sourceSentence && it.targetSentence == targetSentence
        }
        // 硬钉边在结构上「无替代解释」：它由唯一锚点强制，不是 DP 比出来的，
        // 所以 margin 取「无替代候选」值，而不是伪造一个局部比较。
        val edge = DpEdge(
            sourceSentence, 1, targetSentence, 1,
            cost = 0.0,
            margin = AlignTuning.MARGIN_NO_ALTERNATIVE
        )
        edges.add(edge)
        val draft = drafts[sourceSentence]
        draft.hasPair = true
        draft.spanEn = 1
        draft.spanZh = 1
        draft.zhFrom = targetSentence
        draft.margin = AlignTuning.MARGIN_NO_ALTERNATIVE
        draft.z = 0.0
        draft.edge = edge
        draft.anchorHard = true
        draft.anchorOrderOk = true
        draft.matchedAnchors = pin?.anchorText ?: ""
        // 硬钉由唯一锚点强制配对，长度这一维对它的取舍没有发言权。
        draft.lengthInformativeness = 0.0
    }

    // ──────────────────────────────────────────────────────────────
    //  z 表：进 DP 之前一次性预计算（内层只读，不取对数）
    // ──────────────────────────────────────────────────────────────

    private fun precomputeZ(sourceRange: IntRange, targetRange: IntRange): FloatArray {
        val n = sourceRange.last - sourceRange.first + 1
        val m = targetRange.last - targetRange.first + 1
        val width = m + 1
        val table = FloatArray((n + 1) * width * 9)
        for (spanEn in 1..3) {
            for (spanZh in 1..3) {
                if (spanEn > 1 && spanZh > 1 && (spanEn > 2 || spanZh > 2)) continue
                for (i in spanEn..n) {
                    var words = 0
                    for (k in 0 until spanEn) words += source.sentWords[sourceRange.first + i - spanEn + k]
                    val rEff = density.rEffOfSentence(sourceRange.first + i - spanEn)
                    val sigma = density.sigmaFor(words)
                    val lnR = ln(rEff)
                    for (j in spanZh..m) {
                        var chars = 0
                        for (k in 0 until spanZh) chars += target.sentChars[targetRange.first + j - spanZh + k]
                        val rho = density.observedRatio(words, chars)
                        val z = abs(ln(rho) - lnR) / sigma
                        table[zIndex(i, spanEn, j, spanZh, width)] = z.toFloat()
                    }
                }
            }
        }
        return table
    }

    private fun zIndex(i: Int, spanEn: Int, j: Int, spanZh: Int, width: Int): Int =
        ((i * width) + j) * 9 + (spanEn - 1) * 3 + (spanZh - 1)

    private fun zOf(table: FloatArray?, i: Int, spanEn: Int, j: Int, spanZh: Int, width: Int): Double =
        if (table == null) 0.0 else table[zIndex(i, spanEn, j, spanZh, width)].toDouble()

    /**
     * 长度信息量（Lead 裁定 A）：`|ln(ρ_best / r_eff)|`。
     *
     * **不是** `z`：`z` 是除以 σ 归一化后的量（长度门用），这个是原始对数偏离
     * （判断「长度这一维有没有数据」用）。σ 很大的书里 `z` 会很小但偏离其实不小。
     */
    private fun lengthInformativenessOf(
        sourceRange: IntRange, targetRange: IntRange,
        sourceFrom: Int, spanEn: Int, targetFrom: Int, spanZh: Int
    ): Double {
        var words = 0
        var chars = 0
        for (k in 0 until spanEn) {
            val index = sourceFrom + k
            if (index in 0 until source.sentCount) words += source.sentWords[index]
        }
        for (k in 0 until spanZh) {
            val index = targetFrom + k
            if (index in 0 until target.sentCount) chars += target.sentChars[index]
        }
        val rho = density.observedRatio(words, chars)
        val rEff = density.rEffOfSentence(sourceFrom.coerceIn(0, source.sentCount - 1))
        if (rho <= 0 || rEff <= 0) return Double.MAX_VALUE
        return abs(ln(rho / rEff))
    }

    // ──────────────────────────────────────────────────────────────
    //  代价（只允许结构信号：长度 + 锚点 + 位置 + 合并惩罚）
    // ──────────────────────────────────────────────────────────────

    private fun pairCost(
        i: Int, spanEn: Int, j: Int, spanZh: Int,
        sourceRange: IntRange, targetRange: IntRange,
        zTable: FloatArray, width: Int, m: Int
    ): Float {
        val z = zTable[zIndex(i, spanEn, j, spanZh, width)]
        val zNormalized = z / AlignTuning.Z_CAP.toFloat()
        val lengthTerm = minOf(1f, zNormalized * zNormalized)

        val sourceFrom = sourceRange.first + i - spanEn
        val targetFrom = targetRange.first + j - spanZh
        val sourceAnchors = unionAnchors(anchors.sourceAnchors, sourceFrom, spanEn)
        val targetAnchors = unionAnchors(anchors.targetAnchors, targetFrom, spanZh)
        val agree = AnchorLayer.agreement(sourceAnchors, targetAnchors).toFloat()

        val n = sourceRange.last - sourceRange.first + 1
        val posI = (i - spanEn / 2.0) / n.coerceAtLeast(1)
        val posJ = (j - spanZh / 2.0) / m.coerceAtLeast(1)
        val posDelta = abs(posI - posJ).toFloat()

        val mergePenalty = AlignTuning.W_MERGE.toFloat() * ((spanEn - 1) + (spanZh - 1))
        return lengthTerm +
            AlignTuning.W_ANCHOR.toFloat() * (1f - agree) +
            AlignTuning.W_POSITION.toFloat() * posDelta +
            mergePenalty
    }

    /**
     * 局部 margin（§7.6）：`(i, j±2)` 与 `(i±2, j)` 邻域内的最小替代代价 − 选中代价。
     *
     * 只重算 4 个 1:1 局部候选（设计原文的 4 个）。**可以为负**：替代解释可能违反单调性
     * （把两句对调就不单调），那正是错位暴露出来的地方（§10.9）。
     */
    private fun localMargin(
        i: Int, spanEn: Int, j: Int, spanZh: Int,
        sourceRange: IntRange, targetRange: IntRange,
        zTable: FloatArray?, width: Int, m: Int,
        chosenCost: Double
    ): Double {
        if (zTable == null) return AlignTuning.MARGIN_NO_ALTERNATIVE
        val n = sourceRange.last - sourceRange.first + 1
        var bestAlternative = Double.MAX_VALUE
        val deltas = intArrayOf(0, -1, 0, 1, -2, 2)
        for (d in deltas) {
            val altJ = j + d
            if (d == 0 || altJ < 1 || altJ > m) continue
            if (i < 1 || i > n) continue
            val alternative = pairCost(i, 1, altJ, 1, sourceRange, targetRange, zTable, width, m).toDouble()
            if (alternative < bestAlternative) bestAlternative = alternative
        }
        for (d in deltas) {
            val altI = i + d
            if (d == 0 || altI < 1 || altI > n) continue
            if (j < 1 || j > m) continue
            val alternative = pairCost(altI, 1, j, 1, sourceRange, targetRange, zTable, width, m).toDouble()
            if (alternative < bestAlternative) bestAlternative = alternative
        }
        if (bestAlternative == Double.MAX_VALUE) return AlignTuning.MARGIN_NO_ALTERNATIVE
        return bestAlternative - chosenCost
    }

    private fun unionAnchors(table: Array<IntArray>, from: Int, span: Int): IntArray {
        if (span <= 0 || from < 0 || from >= table.size) return Bags.EMPTY_INT
        if (span == 1) return table[from]
        val bag = IntBag(8)
        for (k in 0 until span) {
            val index = from + k
            if (index in table.indices) for (key in table[index]) bag.add(key)
        }
        return bag.toSortedUnique()
    }

    /** 无终止符碎片（§10.6）：`N:M` 双向合并只允许出现在这里。 */
    private fun hasTerminatorlessFragment(from: Int, to: Int): Boolean {
        for (s in from..to) {
            if (s < 0 || s >= source.sentCount) continue
            if ((source.sentFlags[s] and SentFlags.NO_TERMINATOR) != 0) return true
        }
        return false
    }

    // ──────────────────────────────────────────────────────────────
    //  漂移（§7.7）+ 块级否决
    // ──────────────────────────────────────────────────────────────

    private fun applyDriftVeto() {
        // 每个英文章节对：先算每句的期望中文位置，再按块统计超预算比例
        for (enChapter in chapterPairSource.keys.toList().sorted()) {
            val enRange = chapterPairSource[enChapter] ?: continue
            val zhRange = chapterPairTarget[enChapter] ?: continue
            val pinsInChapter = anchors.pins.filter {
                it.sourceSentence in enRange && it.targetSentence in zhRange
            }.sortedBy { it.sourceSentence }

            val cumSourceWords = IntArray(enRange.last - enRange.first + 2)
            for (s in enRange) {
                cumSourceWords[s - enRange.first + 1] =
                    cumSourceWords[s - enRange.first] + source.sentWords[s]
            }
            val cumTargetChars = IntArray(zhRange.last - zhRange.first + 2)
            for (t in zhRange) {
                cumTargetChars[t - zhRange.first + 1] =
                    cumTargetChars[t - zhRange.first] + target.sentChars[t]
            }

            var blockOverBudget = 0
            var blockCounted = 0
            var blockStart = enRange.first
            var counted = 0
            var overBudget = 0

            for (s in enRange) {
                val draft = drafts[s]
                if (!draft.hasPair) continue
                val expected = expectedTargetIndex(
                    s, enRange, zhRange, pinsInChapter, cumSourceWords, cumTargetChars
                )
                val drift = abs(draft.zhFrom.toDouble() - expected)
                val blockTargetSentences = zhRange.last - zhRange.first + 1
                val budget = AlignTuning.DRIFT_ABS + AlignTuning.DRIFT_REL * blockTargetSentences
                draft.drift = drift
                draft.driftBudget = budget
                counted++
                blockCounted++
                if (drift > budget) {
                    overBudget++
                    blockOverBudget++
                }
                // 单点否决：drift > 3·budget 的单元直接弃权（§7.7 第二条）
                if (drift > AlignTuning.DRIFT_VETO_FACTOR * budget) draft.driftVetoed = true

                if (blockCounted >= AlignTuning.DRIFT_BLOCK_SENTENCES) {
                    if (blockOverBudget.toDouble() / blockCounted > AlignTuning.DRIFT_VETO_RATIO) {
                        notes += "block drift veto: en=$blockStart..$s ratio=" +
                            round(blockOverBudget.toDouble() / blockCounted)
                        markBlockVetoed(blockStart, s)
                    }
                    blockOverBudget = 0
                    blockCounted = 0
                    blockStart = s + 1
                }
            }
            if (blockCounted > 0 &&
                blockOverBudget.toDouble() / blockCounted > AlignTuning.DRIFT_VETO_RATIO
            ) {
                notes += "block drift veto (tail): en=$blockStart..${enRange.last} ratio=" +
                    round(blockOverBudget.toDouble() / blockCounted)
                markBlockVetoed(blockStart, enRange.last)
            }
            if (counted > 0 && AlignTuning.DEBUG_DRIFT) {
                notes += "chapter drift: en=$enChapter over=$overBudget/$counted"
            }
        }
    }

    /** 把 [from]..[to]（**英文句下标**）里已配对的单元标记为漂移否决。 */
    private fun markBlockVetoed(from: Int, to: Int) {
        for (s in from.coerceAtLeast(0)..to.coerceAtMost(drafts.size - 1)) {
            val draft = drafts[s]
            if (draft.hasPair) draft.driftVetoed = true
        }
    }

    /**
     * 期望中文位置（§7.7）：
     * - 口径 A（块内 ≥ 2 颗硬钉）：钉间线性插值；
     * - 口径 B（无钉/单钉）：按累计**长度**线定位（不是句序）。
     *
     * 口径 B 的正当性：句序线性插值的隐含假设是「每章每段密度均匀」，而真实书里一段
     * 对话（20 句 60 词）与一段描写（3 句 300 词）密度差一个数量级，句序插值会把正常
     * 排版判成漂移。累计长度线才是翻译的真实「坐标系」。
     */
    private fun expectedTargetIndex(
        sourceSentence: Int, enRange: IntRange, zhRange: IntRange,
        pins: List<HardPin>, cumSourceWords: IntArray, cumTargetChars: IntArray
    ): Double {
        var previous: HardPin? = null
        var next: HardPin? = null
        for (pin in pins) {
            if (pin.sourceSentence <= sourceSentence &&
                (previous == null || pin.sourceSentence > previous!!.sourceSentence)
            ) previous = pin
            if (pin.sourceSentence > sourceSentence &&
                (next == null || pin.sourceSentence < next!!.sourceSentence)
            ) next = pin
        }
        val previousPin = previous
        val nextPin = next
        if (previousPin != null && nextPin != null && nextPin.sourceSentence > previousPin.sourceSentence) {
            val t = (sourceSentence - previousPin.sourceSentence).toDouble() /
                (nextPin.sourceSentence - previousPin.sourceSentence)
            return previousPin.targetSentence + t * (nextPin.targetSentence - previousPin.targetSentence)
        }
        // ── 口径 B：按累计长度线定位 ────────────────────────────────
        // 起点：上一颗钉（若有）或本章中文区间起点。
        val baseSource = previousPin?.sourceSentence ?: enRange.first
        val baseTarget = previousPin?.targetSentence ?: zhRange.first
        val sourceOffset = (sourceSentence - enRange.first).coerceIn(0, cumSourceWords.size - 1)
        val baseOffset = (baseSource - enRange.first).coerceIn(0, cumSourceWords.size - 1)
        val wordsFromBase = (cumSourceWords[sourceOffset] - cumSourceWords[baseOffset]).coerceAtLeast(0)
        // 目标字数 = 密度 × 这段英文的词数；
        // 再在累计中文长度线上找第一个超过它的句子（等价于「累计长度线定位」）。
        val targetCharsFromBase = density.rEffOfSentence(sourceSentence) * wordsFromBase
        val baseTargetOffset = (baseTarget - zhRange.first).coerceIn(0, cumTargetChars.size - 1)
        val baseChars = cumTargetChars[baseTargetOffset]
        var candidate = baseTarget
        while (candidate <= zhRange.last) {
            val offset = (candidate - zhRange.first).coerceIn(0, cumTargetChars.size - 1)
            if (cumTargetChars[offset] - baseChars >= targetCharsFromBase) break
            candidate++
        }
        return candidate.coerceAtMost(zhRange.last).toDouble()
    }

    private fun valueAt(array: IntArray, index: Int): Int =
        if (index <= 0) 0 else if (index < array.size) array[index] else array.last()

    // ──────────────────────────────────────────────────────────────
    //  noPlausibleCandidate ±2 窗口（X5）
    // ──────────────────────────────────────────────────────────────

    /**
     * 对未配对的英文句，检查同章节对内的 ±2 句窗口是否存在 `z ≤ Z_NOISE` 的中文候选。
     *
     * 这一步必须在 DP **之后**做：它只在「DP 判 1:0」时才被 X5 用到（§3.2 X5）。
     */
    fun computeNoPlausibleCandidate(chapters: ChapterLayer) {
        for (enChapter in chapterPairSource.keys.toList().sorted()) {
            val enRange = chapterPairSource[enChapter] ?: continue
            val zhRange = chapterPairTarget[enChapter] ?: continue
            val pinsInChapter = anchors.pins.filter {
                it.sourceSentence in enRange && it.targetSentence in zhRange
            }
            for (s in enRange) {
                val draft = drafts[s]
                if (draft.hasPair) {
                    draft.noPlausibleCandidate = false
                    continue
                }
                // ±2 窗口：以硬钉插值/长度线给出的期望位置为中心
                val center = if (pinsInChapter.size >= 2) {
                    expectedTargetIndex(
                        s, enRange, zhRange, pinsInChapter,
                        IntArray(0), IntArray(0)
                    ).toInt()
                } else {
                    zhRange.first + (s - enRange.first)
                }
                val words = source.sentWords[s]
                val rEff = density.rEffOfSentence(s)
                val sigma = density.sigmaFor(words)
                var plausible = false
                var offset = -2
                while (offset <= 2 && !plausible) {
                    val t = center + offset
                    if (t in zhRange) {
                        val rho = density.observedRatio(words, target.sentChars[t])
                        val z = abs(ln(rho) - ln(rEff)) / sigma
                        if (z <= AlignTuning.Z_NOISE) plausible = true
                    }
                    offset++
                }
                draft.noPlausibleCandidate = !plausible
            }
        }
        // 没有章节对的英文句：DP 从未覆盖到，按「无候选」处理
        for (s in 0 until source.sentCount) {
            val draft = drafts[s]
            if (!draft.hasPair && draft.noPlausibleCandidate) {
                draft.noPlausibleCandidate = true
            }
        }
    }

    /** 是否在 DP 里见过这条英文句（用于区分「DP 跳过」与「DP 从未覆盖」）。 */
    fun coveredByDp(sourceSentence: Int): Boolean =
        chapterPairSource.values.any { sourceSentence in it }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private companion object {
        const val ACTION_NONE = 0
        const val ACTION_SKIP_EN = 1
        const val ACTION_SKIP_ZH = 2

        /**
         * 配对动作编码：`spanEn<<8 | spanZh`。
         *
         * 必须与 [ACTION_SKIP_EN]/[ACTION_SKIP_ZH]（1/2）不冲突，且段落层与句级层
         * **共用同一个编码函数**——两层此前各写一份、用了不同的移位，导致回溯把 skip
         * 动作当成配对动作解包（`act=1` 被解成 spanEn=0）。这类错误不会报错，只会静默
         * 产出空跨度，所以两层统一到这里、只此一份。
         */
        fun encodePair(spanEn: Int, spanZh: Int): Int = (spanEn shl 8) or spanZh
    }
}
