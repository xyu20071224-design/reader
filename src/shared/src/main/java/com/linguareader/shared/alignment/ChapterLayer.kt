package com.linguareader.shared.alignment

import kotlin.math.abs
import kotlin.math.ln

/**
 * 章节层（设计 §7.2，交接单 B1–B3）。
 *
 * 单调 DP，动作：`1:1` / `1:N` / `N:1`（N ≤ [AlignTuning.CH_MAX_SPAN]，即 D5 的 `N ≤ 3`）/
 * `skip_en` / `skip_zh`。它的职责是**把搜索空间切成互不影响的章节对**，让漂移无法沿链
 * 传播（反例 F4 的根治手段），而不是把章节对本身判得多么精确。
 *
 * 结构性章节（§2.4 判出）**不参与 DP**：英文侧的句子输出 `UNALIGNED(STRUCTURAL_BLOCK)`，
 * 中文侧直接不消耗。这一点必须在 DP 之前就压缩掉下标，否则封面/目录会霸占配对名额。
 *
 * 代价（§7.2）：
 * ```
 * costCh(i,j) = CH_W_LEN·cL + CH_W_ANCHOR·cA + CH_W_POS·cP + CH_W_DIGIT·cD
 *   cL = min(1, |ln(ρ_ch / r_ref)| / CH_LEN_SCALE)   r_ref = rTotal（第零级暖启动）
 *   cA = 1 − jaccard(anchorKeys(i), anchorKeys(j))   （双侧皆空 → 0）
 *   cP = |posEn(i) − posZh(j)|                        （书内归一化位置）
 *   cD = min(1, |symDiff(titleDigits)| / CH_DIGIT_SCALE)
 * ```
 *
 * **校准是恒等式，不是调参**：`Σw = 1.05 > SKIP_EN + SKIP_ZH = 0.90`，且四项皆近 0 时
 * `cost ≈ 0 « 0.90`。两端界同时成立 ⇒ 章节对只有在「长度畸形 + 锚点不交 + 位置差一大截 +
 * 标题编号对不上」四项全废时才被判 `UNTRANSLATED`（§14.4 第 38 号测试守这条）。
 *
 * ## `margin` 必须是「最优 vs 次优**完整路径**」的差
 *
 * 只看同一个格子的两条入射边是不够的：那回答的是「这一步有没有别的走法」，而 X4 要问的
 * 是「整本书的解释有没有第二种同样合理」。所以这里用标准 DAG 上的 **top-2 路径 DP**：
 * 每个格子保留两条最好的路径（代价 + 前驱 + 动作），传播时对每条入射边同时推 t=0/1
 * 两条来源路径。`margin = secondPath − bestPath`（同一个格子、同一条前缀）。
 */
internal class ChapterLayer(
    private val source: BookSide,
    private val target: BookSide,
    private val anchors: AnchorLayer,
    private val density: DensityModel
) {
    /** 每个英文章节的判定（结构性章节在这里恒为 ACCEPTED，由 `decide()` 的 X2 处理）。 */
    var sourceVerdict: Array<ChapterVerdict> = emptyArray()
        private set

    /** `sourceChapter → targetChapter`；-1 表示无对应。 */
    var sourceToTarget: IntArray = Bags.EMPTY_INT
        private set

    /** `targetChapter → 引用它的英文章节（升序）`：`N:1` 合卷时长度 > 1。 */
    var targetToSources: Array<IntArray> = emptyArray()
        private set

    /** 被接受的章节对的 `ρ_ch` 输入（供 `rBook` 的加权中位数）。 */
    var acceptedPairChars: LongArray = LongArray(0)
        private set
    var acceptedPairWords: LongArray = LongArray(0)
        private set

    val notes: MutableList<String> = ArrayList()

    /**
     * 低 margin（结构信号不可分）的英文章节集合。
     *
     * 这些章节对的句级判定要**更严而非更松**（Lead 裁定 3）：`ALIGNED` 要求 `vLexical`，
     * 且 `z` 收紧一档——章节定位本身不可靠时，内容证据必须更强才能背书。
     */
    var lowMarginChapters: Map<Int, Boolean> = emptyMap()
        private set

    private var enActive: IntArray = Bags.EMPTY_INT
    private var zhActive: IntArray = Bags.EMPTY_INT
    private var enAnchors: Array<IntArray> = emptyArray()
    private var zhAnchors: Array<IntArray> = emptyArray()
    private var posEn: DoubleArray = DoubleArray(0)
    private var posZh: DoubleArray = DoubleArray(0)

    // ──────────────────────────────────────────────────────────────

    fun run() {
        val enCount = source.chapterCount
        val zhCount = target.chapterCount
        sourceVerdict = Array(enCount) { ChapterVerdict.ACCEPTED }
        sourceToTarget = IntArray(enCount) { -1 }
        targetToSources = Array(zhCount) { Bags.EMPTY_INT }

        if (enCount == 0 || zhCount == 0) {
            for (e in 0 until enCount) sourceVerdict[e] = ChapterVerdict.UNTRANSLATED
            notes += "chapter layer: empty side (en=$enCount zh=$zhCount)"
            return
        }

        // ── 剔除结构性章节（§2.4）────────────────────────────────
        enActive = IntArray(enCount) { it }.filter { !source.isStructural(it) }.toIntArray()
        zhActive = IntArray(zhCount) { it }.filter { !target.isStructural(it) }.toIntArray()
        val structuralEn = enCount - enActive.size
        val structuralZh = zhCount - zhActive.size
        if (structuralEn > 0 || structuralZh > 0) {
            notes += "structural chapters excluded: en=$structuralEn zh=$structuralZh"
        }
        if (enActive.isEmpty() || zhActive.isEmpty()) {
            for (e in 0 until enCount) sourceVerdict[e] = ChapterVerdict.UNTRANSLATED
            notes += "chapter layer: no non-structural chapter left"
            return
        }

        enAnchors = Array(enActive.size) { chapterAnchors(anchors.sourceAnchors, source.sentencesOfChapter(enActive[it])) }
        zhAnchors = Array(zhActive.size) { chapterAnchors(anchors.targetAnchors, target.sentencesOfChapter(zhActive[it])) }
        posEn = normalizedPositions(enActive.size)
        posZh = normalizedPositions(zhActive.size)

        val best = topTwoPaths(enActive.size, zhActive.size)
        backtrack(best)
        collectAcceptedPairs()
    }

    // ──────────────────────────────────────────────────────────────
    //  top-2 路径 DP
    // ──────────────────────────────────────────────────────────────

    /** 每个格子的两条最优路径：`cost[cell]`、`previousCell[cell]`、`action[cell]`（各 2 层）。 */
    private class TopTwo(val width: Int, val cells: Int) {
        val cost = DoubleArray(cells * 2) { Double.MAX_VALUE / 4 }
        val previous = IntArray(cells * 2) { -1 }
        val action = IntArray(cells * 2) { ACTION_NONE }

        fun best(cell: Int): Double = cost[cell * 2]
        fun second(cell: Int): Double = cost[cell * 2 + 1]

        /** 把 (candidateCost, fromCell, action) 推入某格的前两名。 */
        fun offer(cell: Int, candidateCost: Double, fromCell: Int, move: Int) {
            val first = cell * 2
            if (candidateCost < cost[first]) {
                // 原第一名降级为第二名
                cost[first + 1] = cost[first]
                previous[first + 1] = previous[first]
                action[first + 1] = action[first]
                cost[first] = candidateCost
                previous[first] = fromCell
                action[first] = move
            } else if (candidateCost < cost[first + 1]) {
                cost[first + 1] = candidateCost
                previous[first + 1] = fromCell
                action[first + 1] = move
            }
        }
    }

    private fun topTwoPaths(n: Int, m: Int): TopTwo {
        val width = m + 1
        val table = TopTwo(width, (n + 1) * width)
        table.offer(0, 0.0, -1, ACTION_NONE)

        for (i in 1..n) {
            for (j in 1..m) {
                val cell = i * width + j
                // skip_en
                offerFrom(table, cell, (i - 1) * width + j, AlignTuning.SKIP_EN, ACTION_SKIP_EN)
                // skip_zh
                offerFrom(table, cell, i * width + (j - 1), AlignTuning.SKIP_ZH, ACTION_SKIP_ZH)
                // 配对：1:1 / 1:N / N:1（N:M 双向交给交错的 1:N + N:1）
                var spanEn = 1
                while (spanEn <= AlignTuning.CH_MAX_SPAN && spanEn <= i) {
                    var spanZh = 1
                    while (spanZh <= AlignTuning.CH_MAX_SPAN && spanZh <= j) {
                        if (spanEn == 1 || spanZh == 1) {
                            val from = (i - spanEn) * width + (j - spanZh)
                            if (table.best(from) < INF / 2) {
                                offerFrom(
                                    table, cell, from,
                                    pairCost(i - spanEn, spanEn, j - spanZh, spanZh),
                                    encodePair(spanEn, spanZh)
                                )
                            }
                        }
                        spanZh++
                    }
                    spanEn++
                }
            }
        }
        return table
    }

    private fun offerFrom(table: TopTwo, cell: Int, from: Int, edgeCost: Double, move: Int) {
        for (rank in 0..1) {
            val prefix = table.cost[from * 2 + rank]
            if (prefix >= INF / 2) continue
            table.offer(cell, prefix + edgeCost, from * 2 + rank, move)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  回溯
    // ──────────────────────────────────────────────────────────────

    private fun backtrack(table: TopTwo) {
        val width = table.width
        val n = enActive.size
        val m = zhActive.size
        val endCell = n * width + m
        if (table.best(endCell) >= INF / 2) {
            notes += "chapter layer: no complete path (en=$n zh=$m)"
            for (e in enActive) sourceVerdict[e] = ChapterVerdict.UNTRANSLATED
            return
        }
        val margin = chapterMargin()
        val lowMargin = margin < AlignTuning.MARGIN_CHAPTER
        val lowMarginFlags = HashMap<Int, Boolean>()

        var state = endCell * 2
        val pairs = ArrayList<IntArray>(n)
        val skippedEn = ArrayList<Int>(n)
        while (true) {
            val move = table.action[state]
            val previous = table.previous[state]
            if (move == ACTION_NONE) break
            val cell = state / 2
            val i = cell / width
            val j = cell % width
            when {
                move == ACTION_SKIP_EN -> skippedEn.add(i - 1)
                move == ACTION_SKIP_ZH -> Unit
                else -> {
                    val spanEn = move shr 8
                    val spanZh = move and 0xFF
                    pairs.add(intArrayOf(i - spanEn, spanEn, j - spanZh, spanZh))
                }
            }
            if (previous < 0) break
            state = previous
        }

        // ── Lead 裁定 1：低 margin 时**不**全盘否决，改用 identity 先验 ──
        // 论证：章节 margin 是**结构**信号，而 ALIGNED 的安全由**内容**通道保证。
        // 内容通道与章节配对独立——配对错了，该章句子的内容见证会系统性失败，自然落
        // UNCERTAIN/UNALIGNED，不会变成 falseAligned；配对对了就恢复覆盖率。
        // 「猜一个章节配对 + 让内容通道把关」不降低安全性，只是把「整章未知」细化成
        // 「逐句可知」。但必须先有裁定 2 的内容复核门作为安全网（见 applyChapterContentSanity）。
        if (lowMargin) {
            if (applyIdentityPrior(pairs)) {
                notes += "chapter pairing low-margin → identity prior (margin=${round(margin)})"
            } else {
                notes += "chapter pairing low-margin, chapter counts differ " +
                    "(en=${enActive.size} zh=${zhActive.size}, margin=${round(margin)}) " +
                    "→ no trustworthy prior"
            }
        }
        for (pair in pairs) {
            val enFrom = pair[0]
            val spanEn = pair[1]
            val zhFrom = pair[2]
            val spanZh = pair[3]
            val sources = IntArray(spanEn) { enActive[enFrom + it] }
            for (chapter in sources) {
                sourceToTarget[chapter] = zhActive[zhFrom]
                sourceVerdict[chapter] = ChapterVerdict.ACCEPTED
                if (lowMargin) lowMarginFlags[chapter] = true
            }
            for (k in 0 until spanZh) {
                targetToSources[zhActive[zhFrom + k]] = sources
            }
            if (spanEn > 1 || spanZh > 1) {
                notes += "chapter action ${spanEn}:${spanZh} en=${sources.toList()} zh=" +
                    (zhFrom until zhFrom + spanZh).map { zhActive[it] }
            }
        }
        for (index in skippedEn) {
            if (index in enActive.indices) sourceVerdict[enActive[index]] = ChapterVerdict.UNTRANSLATED
        }
        // DP 没走到的英文章（理论上不该有）→ 保守判未译
        for (chapter in enActive) {
            if (sourceToTarget[chapter] < 0 &&
                sourceVerdict[chapter] == ChapterVerdict.ACCEPTED
            ) {
                sourceVerdict[chapter] = ChapterVerdict.UNTRANSLATED
            }
        }
        lowMarginChapters = lowMarginFlags
    }

    // ──────────────────────────────────────────────────────────────
    //  margin：逐章节对的「最优 vs 次优配对」
    // ──────────────────────────────────────────────────────────────

    /**
     * 每个已配对的章节对，margin = 「同样成本结构下，把这一对换掉的最小额外代价」。
     *
     * 为什么不用「同一终点 top-2 完整路径」：当全书每章长度同构时，所有路径代价几乎相同，
     * top-2 的差会是 0（实测就是这样），于是**整本书**被判 AMBIGUOUS——证据（结构分不开）
     * 与结论（一句都不可信）不成比例（Lead 已裁定这是设计缺陷）。
     *
     * 这里改成**逐对**口径，语义更接近 X4 真正想问的问题：「这一对章节，有没有另一个
     * 同样便宜的配对」：
     *
     * ```
     * margin(pair) = min over 替代配对 (i→j', i'→j) 的代价 − 当前配对代价
     * ```
     *
     * 替代配对只在**长度相容**的中文/英文章节里找（`|ln(ρ/ρ_cur)| ≤ CH_ALT_WINDOW`），
     * 否则「用一章 300 句对一章 3 句」这种荒谬替代会给出巨大 margin，反而失去判别力。
     * 返回全书已配对章节的**中位数**（不是最小值）：单个错配不该把全书拖进 AMBIGUOUS。
     */
    private fun chapterMargin(): Double {
        val candidates = ArrayList<Double>()
        for (i in enActive.indices) {
            val j = currentTargetIndex(i)
            if (j < 0) continue
            val own = pairCost(i, 1, j, 1)
            val ownRho = density.observedRatio(
                source.chapterWords[enActive[i]],
                target.chapterChars[zhActive[j]]
            )
            var bestAlternative = Double.MAX_VALUE
            for (alt in zhActive.indices) {
                if (alt == j) continue
                // 长度相容性窗口：替代章的字数不能与当前章差一个数量级，
                // 否则「一章 300 句对一章 3 句」这种荒谬替代会给出巨大 margin，失去判别力。
                val altRho = density.observedRatio(
                    source.chapterWords[enActive[i]],
                    target.chapterChars[zhActive[alt]]
                )
                if (ownRho > 0 && altRho > 0 &&
                    abs(ln(altRho / ownRho)) > AlignTuning.CH_ALT_WINDOW
                ) continue
                val alternative = pairCost(i, 1, alt, 1)
                if (alternative < bestAlternative) bestAlternative = alternative
            }
            if (bestAlternative == Double.MAX_VALUE) continue
            candidates.add(bestAlternative - own)
        }
        if (candidates.isEmpty()) return Double.MAX_VALUE
        candidates.sort()
        return candidates[candidates.size / 2]
    }

    /** 已配对章节对里，英文第 [enIndex]（`enActive` 下标）对应的中文下标。 */
    private fun currentTargetIndex(enIndex: Int): Int {
        val chapter = enActive[enIndex]
        val zhChapter = sourceToTarget[chapter]
        if (zhChapter < 0) return -1
        return zhActive.indexOf(zhChapter)
    }

    /**
     * Lead 裁定 1 的落地：低 margin 且**两侧章节数相等**时，用 identity 1:1 作为工作配对。
     *
     * 为什么两侧章节数相等才可以：那时 identity 是一个有依据的先验（译本通常逐章对应）；
     * 章节数不等时 identity 无从定义，也就没有可信先验，只能保留 X4 的 AMBIGUOUS。
     *
     * @return 是否采用了 identity 先验（true = 已重写 [sourceToTarget] / [targetToSources]）
     */
    private fun applyIdentityPrior(pairs: ArrayList<IntArray>): Boolean {
        if (enActive.size != zhActive.size) return false
        // 每个英文章取「它在 DP 里配到的那个中文章」作为起点？不——identity 的定义就是
        // 第 k 个英文章对第 k 个中文章，不看 DP。这正是「先验」的含义。
        pairs.clear()
        for (k in enActive.indices) pairs.add(intArrayOf(k, 1, k, 1))
        return true
    }

    /**
     * Lead 裁定 2 的落地：**章节级内容复核**（低 margin 降级的安全网）。
     *
     * 对每个章节对统计「有内容见证（V1 或 V2）的已配对单元 / 该章已配对单元数」。
     * 低于 [AlignTuning.CHAPTER_CONTENT_SANITY] → 判定这个章节对配错了 → 整章对降级
     * `UNALIGNED(AMBIGUOUS)`。
     *
     * 为什么它比结构 margin 可靠：错配章节的特征就是**全线拿不到内容证据**（英文句讲河、
     * 中文句讲山，词典命中率必然塌到底）。它同时是「低 margin 时允许猜配对」的安全前提
     * ——没有它，降级就成了放行。
     *
     * ## ⚠️ 作用域：**只对低 margin 章节对生效**
     *
     * 这是这条安全网的**定义域**，不是优化：它的存在理由是「结构信号分不开，所以用内容
     * 证据兜底」。对**高 margin**（结构信号已经确信）的章节对再用它，等于让「词典覆盖
     * 率低」变成「整章否决」——实测中一本 69 句的书因 V1 召回偏低，两章正确配对被判
     * 错误、15 句无谓降级。结构已经背书过的配对不该被弱内容通道推翻。
     *
     * 必须在句级配对**之后**、`decide()` **之前**调用。
     *
     * @param witnessOf 英文句下标 → 是否拿到内容见证（由验证层给出）
     * @return 被内容复核否决的章节数
     */
    fun applyChapterContentSanity(witnessOf: (Int) -> Boolean): Int {
        var vetoed = 0
        val chaptersToVeto = ArrayList<Int>(8)
        for (enChapter in enActive) {
            if (sourceVerdict[enChapter] != ChapterVerdict.ACCEPTED) continue
            // 只审「结构分不开」的章节对（见上面作用域说明）。
            if (lowMarginChapters[enChapter] != true) continue
            val zhChapter = sourceToTarget[enChapter]
            if (zhChapter < 0) continue
            var units = 0
            var witnessed = 0
            for (sentence in source.sentencesOfChapter(enChapter)) {
                // 只统计「有配对」的句子：DP 判 1:0 的句子本来就没有内容可核。
                if (!hasPairOf(sentence)) continue
                units++
                if (witnessOf(sentence)) witnessed++
            }
            if (units == 0) continue
            val rate = witnessed.toDouble() / units
            if (rate < AlignTuning.CHAPTER_CONTENT_SANITY) {
                chaptersToVeto.add(enChapter)
                notes += "chapter content sanity veto: en=$enChapter zh=$zhChapter " +
                    "witnessRate=${round(rate)} < ${AlignTuning.CHAPTER_CONTENT_SANITY}"
                vetoed++
            }
        }
        for (chapter in chaptersToVeto) sourceVerdict[chapter] = ChapterVerdict.AMBIGUOUS
        return vetoed
    }

    /** 由 [PairingEngine] 注入：该英文句是否有配对（避免章节层反向依赖配对层）。 */
    var hasPairOf: (Int) -> Boolean = { false }

    /** 章节对代价（§7.2）。四项权重之和必须 > `SKIP_EN + SKIP_ZH`。 */
    fun pairCost(enFrom: Int, spanEn: Int, zhFrom: Int, spanZh: Int): Double {
        val enWords = chapterSum(source.chapterWords, enActive, enFrom, spanEn)
        val zhChars = chapterSum(target.chapterChars, zhActive, zhFrom, spanZh)
        val rho = density.observedRatio(enWords, zhChars)
        val rRef = if (density.rTotal > 0) density.rTotal else AlignTuning.R_MID
        val cL = minOf(1.0, abs(ln(rho / rRef)) / AlignTuning.CH_LEN_SCALE)

        val hit = anchorsIntersection(enFrom, spanEn, zhFrom, spanZh)
        val enSize = anchorsUnionSize(enAnchors, enFrom, spanEn)
        val zhSize = anchorsUnionSize(zhAnchors, zhFrom, spanZh)
        val union = enSize + zhSize - hit
        val cA = if (union <= 0) 0.0 else 1.0 - hit.toDouble() / union

        val cP = abs(positionOf(posEn, enFrom, spanEn) - positionOf(posZh, zhFrom, spanZh))

        val enDigits = titleDigits(enFrom, spanEn, true)
        val zhDigits = titleDigits(zhFrom, spanZh, false)
        val hits = AnchorLayer.overlap(enDigits, zhDigits)
        val symmetricDifference = enDigits.size + zhDigits.size - 2 * hits
        val cD = minOf(1.0, symmetricDifference.toDouble() / AlignTuning.CH_DIGIT_SCALE)

        return AlignTuning.CH_W_LEN * cL +
            AlignTuning.CH_W_ANCHOR * cA +
            AlignTuning.CH_W_POS * cP +
            AlignTuning.CH_W_DIGIT * cD
    }

    /** 供验收台复用：四项皆 0 时的代价（= 0，必须远小于 skip 之和）。 */
    fun costWhenAllTermsZero(): Double = 0.0

    /** 供验收台复用：四项皆满时的代价（必须大于 skip 之和）。 */
    fun costWhenAllTermsFull(): Double =
        AlignTuning.CH_W_LEN + AlignTuning.CH_W_ANCHOR + AlignTuning.CH_W_POS + AlignTuning.CH_W_DIGIT

    // ──────────────────────────────────────────────────────────────

    private fun collectAcceptedPairs() {
        val chars = ArrayList<Long>(enActive.size)
        val words = ArrayList<Long>(enActive.size)
        val seen = HashSet<Int>(enActive.size * 2)
        for (enChapter in enActive) {
            if (sourceVerdict[enChapter] != ChapterVerdict.ACCEPTED) continue
            val zhChapter = sourceToTarget[enChapter]
            if (zhChapter < 0 || !seen.add(zhChapter)) continue
            val group = targetToSources[zhChapter]
            var w = 0L
            for (e in group) {
                for (sentence in source.sentencesOfChapter(e)) {
                    if (source.sentLangUnit[sentence]) w += source.sentWords[sentence]
                }
            }
            var c = 0L
            for (sentence in target.sentencesOfChapter(zhChapter)) {
                if (target.sentLangUnit[sentence]) c += target.sentChars[sentence]
            }
            chars.add(c)
            words.add(w)
        }
        acceptedPairChars = LongArray(chars.size) { chars[it] }
        acceptedPairWords = LongArray(words.size) { words[it] }
    }

    private fun chapterAnchors(anchors: Array<IntArray>, sentences: IntRange): IntArray {
        if (sentences.isEmpty()) return Bags.EMPTY_INT
        val bag = IntBag(32)
        for (s in sentences) for (key in anchors[s]) bag.add(key)
        return bag.toSortedUnique()
    }

    private fun anchorsIntersection(enFrom: Int, spanEn: Int, zhFrom: Int, spanZh: Int): Int {
        val a = IntBag(32)
        for (k in 0 until spanEn) for (key in enAnchors[enFrom + k]) a.add(key)
        val b = IntBag(32)
        for (k in 0 until spanZh) for (key in zhAnchors[zhFrom + k]) b.add(key)
        return AnchorLayer.overlap(a.toSortedUnique(), b.toSortedUnique())
    }

    private fun anchorsUnionSize(table: Array<IntArray>, from: Int, span: Int): Int {
        val bag = IntBag(32)
        for (k in 0 until span) for (key in table[from + k]) bag.add(key)
        return bag.toSortedUnique().size
    }

    private fun titleDigits(from: Int, span: Int, sourceSide: Boolean): IntArray {
        val bag = IntBag(8)
        for (k in 0 until span) {
            val table = if (sourceSide) source.chapterTitleDigits else target.chapterTitleDigits
            val chapter = if (sourceSide) enActive[from + k] else zhActive[from + k]
            for (d in table[chapter]) bag.add(d)
        }
        return bag.toSortedUnique()
    }

    private fun chapterSum(values: IntArray, active: IntArray, from: Int, span: Int): Int {
        var total = 0
        for (k in 0 until span) total += values[active[from + k]]
        return total
    }

    private fun normalizedPositions(count: Int): DoubleArray =
        DoubleArray(count) { if (count <= 1) 0.0 else it.toDouble() / (count - 1) }

    private fun positionOf(positions: DoubleArray, from: Int, span: Int): Double {
        if (positions.isEmpty()) return 0.0
        val first = positions[from.coerceIn(0, positions.size - 1)]
        val last = positions[(from + span - 1).coerceIn(0, positions.size - 1)]
        return (first + last) / 2.0
    }

    private fun round(v: Double): Double =
        if (v >= INF / 2) Double.MAX_VALUE else Math.round(v * 1000.0) / 1000.0

    private companion object {
        const val INF = Double.MAX_VALUE / 4
        const val ACTION_NONE = 0
        const val ACTION_SKIP_EN = 1
        const val ACTION_SKIP_ZH = 2

        /** 配对动作编码：`spanEn<<8 | spanZh`（≥ 0x0101，与三个 skip 动作不冲突）。 */
        fun encodePair(spanEn: Int, spanZh: Int): Int = (spanEn shl 8) or spanZh
    }
}
