package com.linguareader.shared.alignment

import kotlin.math.abs
import kotlin.math.ln

/** 一颗硬钉：全书唯一的区分性锚点把这条英文句与这条中文句钉在一起（§6.3）。 */
internal class HardPin(
    val sourceSentence: Int,
    val targetSentence: Int,
    val anchorKey: Int,
    /** 是否通过了「相邻两钉增量长度一致」剪枝（§6.3 第 3 步）。 */
    val orderConsistent: Boolean,
    /** 用于 `evidence.matchedAnchors` 的可读锚点（原样串，便于人工核对）。 */
    val anchorText: String
)

/**
 * 锚点层（设计 §6，交接单 C1–C4）。
 *
 * 三重过滤挡住数字巧合（反例 F3）：
 * 1. **频率**：单侧出现次数 ≤ [AlignTuning.ANCHOR_MAX_FREQ]，且排除到处都在的个位数；
 * 2. **唯一性**：硬钉要求两侧都唯一（`wt ≥ 1`）；
 * 3. **长度一致性**：相邻两钉之间的增量长度比必须符合密度参考，否则剔除。
 *
 * ⚠️ 一致性剪枝用的是 **`rBook`**，不是设计 §6.3 字面写的 `r_seg`——后者要到 C6 才产生，
 * 在 C3 这一刻 `r_seg` 还不存在，照抄会形成依赖环（设计者已确认，见设计文档 §16 勘误）。
 */
internal class AnchorLayer(
    private val source: BookSide,
    private val target: BookSide,
    private val density: DensityModel
) {
    /** 每句的区分性锚点键（已升序去重）：DP 里只做归并求交，不做字典查找。 */
    var sourceAnchors: Array<IntArray> = emptyArray()
        private set
    var targetAnchors: Array<IntArray> = emptyArray()
        private set

    var pins: List<HardPin> = emptyList()
        private set

    val notes: MutableList<String> = ArrayList()

    // ──────────────────────────────────────────────────────────────

    fun build() {
        // ── 键频（书内词频表，O(总词数)，不依赖任何外部资源）──────────
        val freqEn = HashMap<Int, Int>(4096)
        val freqZh = HashMap<Int, Int>(4096)
        countFrequency(source, freqEn)
        countFrequency(target, freqZh)

        // ── 区分性判据（§6.2）────────────────────────────────────────
        val distinguishing = HashSet<Int>(Math.min(freqEn.size, freqZh.size))
        for ((key, en) in freqEn) {
            val zh = freqZh[key] ?: continue
            if (en > AlignTuning.ANCHOR_MAX_FREQ || zh > AlignTuning.ANCHOR_MAX_FREQ) continue
            distinguishing.add(key)
        }
        if (distinguishing.size > AlignTuning.ANCHOR_MAX_KEYS) {
            // 安全阀：极端输入（满是数字的表格页）下限制规模，避免 LIS/切块退化。
            val trimmed = distinguishing.toList().sorted().subList(0, AlignTuning.ANCHOR_MAX_KEYS)
            distinguishing.clear()
            distinguishing.addAll(trimmed)
            notes += "anchor keys trimmed to ${AlignTuning.ANCHOR_MAX_KEYS}"
        }

        sourceAnchors = Array(source.sentCount) { filterAnchors(source, it, distinguishing) }
        targetAnchors = Array(target.sentCount) { filterAnchors(target, it, distinguishing) }

        // ── 候选钉 + 加权 LIS（§6.3 第 1–2 步）──────────────────────
        pins = buildPins(distinguishing)
            .let(::pruneInconsistent)
            .let(::toHardPins)

        if (pins.isEmpty()) notes += "no distinguishing anchors: single-block fallback"
    }

    /** 硬钉对的残差（`ρ`），供 σ 的主估计集使用（§5.4：不经 DP）。 */
    fun pinResiduals(): DoubleArray =
        DoubleArray(pins.size) { i ->
            val pin = pins[i]
            density.observedRatio(
                source.sentWords[pin.sourceSentence],
                target.sentChars[pin.targetSentence]
            )
        }

    // ──────────────────────────────────────────────────────────────

    private fun countFrequency(side: BookSide, into: HashMap<Int, Int>) {
        for (s in 0 until side.sentCount) {
            val keys = side.sentDigits[s]
            for (k in keys) into.merge(k, 1, Int::plus)
            val latins = side.sentLatins[s]
            for (k in latins) into.merge(k, 1, Int::plus)
        }
    }

    private fun filterAnchors(side: BookSide, sentence: Int, distinguishing: Set<Int>): IntArray {
        val digits = side.sentDigits[sentence]
        val latins = side.sentLatins[sentence]
        if (digits.isEmpty() && latins.isEmpty()) return Bags.EMPTY_INT
        val bag = IntBag(digits.size + latins.size)
        for (k in digits) if (k in distinguishing) bag.add(k)
        for (k in latins) if (k in distinguishing) bag.add(k)
        return bag.toSortedUnique()
    }

    private class CandidatePin(
        val sourceSentence: Int,
        val targetSentence: Int,
        var weight: Double,
        val anchorKey: Int
    )

    private fun buildPins(distinguishing: Set<Int>): List<CandidatePin> {
        // 反向索引：键 → 出现它的句子（两侧各一份）。这是预处理，允许字典。
        val enOccurrences = HashMap<Int, IntArray>(distinguishing.size * 2)
        val zhOccurrences = HashMap<Int, IntArray>(distinguishing.size * 2)
        collectOccurrences(sourceAnchors, source.sentCount, enOccurrences)
        collectOccurrences(targetAnchors, target.sentCount, zhOccurrences)

        val byPair = HashMap<Long, CandidatePin>(256)
        for ((key, enSentences) in enOccurrences) {
            val zhSentences = zhOccurrences[key] ?: continue
            val weight = 1.0 / (enSentences.size.toDouble() * zhSentences.size.toDouble())
            for (e in enSentences) {
                for (z in zhSentences) {
                    val pairKey = (e.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
                    val existing = byPair[pairKey]
                    if (existing == null) {
                        byPair[pairKey] = CandidatePin(e, z, weight, key)
                    } else {
                        existing.weight += weight
                    }
                }
            }
        }
        if (byPair.isEmpty()) return emptyList()

        // 加权 LIS：按 i 升序、j 升序，求 j 方向的最大权上升子序列。
        val candidates = byPair.values.sortedWith(compareBy({ it.sourceSentence }, { it.targetSentence }))
        val n = candidates.size
        val best = DoubleArray(n)
        val previous = IntArray(n) { -1 }
        // Fenwick 树维护「以某个 j 结尾的最大权」，避免 O(n²)。
        var maxJ = 0
        for (c in candidates) if (c.targetSentence > maxJ) maxJ = c.targetSentence
        val treeWeight = DoubleArray(maxJ + 2)
        val treeIndex = IntArray(maxJ + 2) { -1 }

        var bestEnd = -1
        var bestValue = 0.0
        for (index in 0 until n) {
            val c = candidates[index]
            val j = c.targetSentence + 1
            var queryWeight = 0.0
            var queryIndex = -1
            var x = j - 1
            while (x > 0) {
                if (treeWeight[x] > queryWeight) {
                    queryWeight = treeWeight[x]
                    queryIndex = treeIndex[x]
                }
                x -= x and (-x)
            }
            best[index] = queryWeight + c.weight
            previous[index] = queryIndex
            var update = j
            while (update <= maxJ + 1) {
                if (best[index] > treeWeight[update]) {
                    treeWeight[update] = best[index]
                    treeIndex[update] = index
                }
                update += update and (-update)
            }
            if (best[index] > bestValue) {
                bestValue = best[index]
                bestEnd = index
            }
        }
        if (bestEnd < 0) return emptyList()
        val chain = ArrayList<CandidatePin>(64)
        var cursor = bestEnd
        while (cursor >= 0) {
            chain.add(candidates[cursor])
            cursor = previous[cursor]
        }
        chain.reverse()
        return chain
    }

    private fun collectOccurrences(
        anchors: Array<IntArray>,
        sentenceCount: Int,
        into: HashMap<Int, IntArray>
    ) {
        val bags = HashMap<Int, IntBag>(256)
        for (s in 0 until sentenceCount) {
            for (key in anchors[s]) {
                bags.getOrPut(key) { IntBag(4) }.add(s)
            }
        }
        for ((key, bag) in bags) into[key] = bag.toSortedUniqueKeepingOrder()
    }

    /** 一致性剪枝（§6.3 第 3 步）：相邻两钉的增量长度比必须符合密度参考。 */
    private fun pruneInconsistent(chain: List<CandidatePin>): List<CandidatePin> {
        if (chain.size < 2) return chain
        val kept = ArrayList<CandidatePin>(chain.size)
        kept.add(chain[0])
        for (i in 1 until chain.size) {
            val previous = kept.last()
            val current = chain[i]
            val deltaWords = rangeSum(source.sentWords, previous.sourceSentence + 1, current.sourceSentence)
            val deltaChars = rangeSum(target.sentChars, previous.targetSentence + 1, current.targetSentence)
            val rho = density.observedRatio(deltaWords, deltaChars)
            val span = deltaWords + source.sentWords[previous.sourceSentence]
            val sigma = density.sigmaFor(span)
            val deviation = abs(ln(rho) - ln(density.rBook)) / sigma
            if (deviation <= AlignTuning.ANCHOR_CONSISTENCY_Z) {
                kept.add(current)
            } else {
                notes += "anchor pruned: en=${current.sourceSentence} zh=${current.targetSentence} dev=" +
                    round(deviation)
            }
        }
        return kept
    }

    private fun toHardPins(chain: List<CandidatePin>): List<HardPin> {
        if (chain.isEmpty()) return emptyList()
        val freqByKey = HashMap<Int, Pair<Int, Int>>(chain.size * 2)
        val hard = ArrayList<HardPin>(chain.size)
        val inChain = HashSet<Long>(chain.size * 2)
        for (c in chain) inChain.add((c.sourceSentence.toLong() shl 32) or (c.targetSentence.toLong() and 0xFFFFFFFFL))

        for (c in chain) {
            // 硬钉条件（§6.3 第 4 步）：两侧都唯一 + 在 LIS 内（链长 ≥ 2）或全书唯一。
            val unique = c.weight >= 1.0 - 1e-9
            if (!unique) continue
            val counts = freqByKey.getOrPut(c.anchorKey) {
                countOccurrences(c.anchorKey) to countOccurrencesOnTarget(c.anchorKey)
            }
            val inLis = chain.size >= 2 && inChain.contains(
                (c.sourceSentence.toLong() shl 32) or (c.targetSentence.toLong() and 0xFFFFFFFFL)
            )
            val globallyUnique = counts.first == 1 && counts.second == 1
            if (!inLis && !globallyUnique) continue
            hard.add(
                HardPin(
                    sourceSentence = c.sourceSentence,
                    targetSentence = c.targetSentence,
                    anchorKey = c.anchorKey,
                    orderConsistent = true,
                    anchorText = anchorTextOf(source, c.sourceSentence, c.anchorKey)
                )
            )
        }
        return hard
    }

    /**
     * 可读锚点（只为 `evidence.matchedAnchors` 人工核对用，不在热路径）。
     *
     * 数字键的信息已经丢了（只存了哈希），所以这里退化成「读源句里的数字/拉丁原串」，
     * 不重建规范化形式——evidence 是给人看的线索，不是判定输入。
     */
    private fun anchorTextOf(side: BookSide, sentence: Int, key: Int): String {
        val text = side.sentText[sentence]
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (TextScan.isDigit(c) || TextScan.isFullwidthDigit(c)) {
                var j = i + 1
                while (j < text.length && (TextScan.isDigit(text[j]) || TextScan.isFullwidthDigit(text[j]) ||
                        text[j] == '.' || text[j] == ',')) j++
                if (TextScan.hash(text, i, j) == key) return text.substring(i, j)
                i = j
            } else if (TextScan.isLetter(c)) {
                var j = i + 1
                while (j < text.length && (TextScan.isLetter(text[j]) || TextScan.isDigit(text[j]) ||
                        text[j] == '\'' || text[j] == '-')) j++
                if (TextScan.hashLowerAscii(text, i, j) == key) return text.substring(i, j)
                i = j
            } else i++
        }
        return ""
    }

    private fun countOccurrences(key: Int): Int {
        var n = 0
        for (s in 0 until source.sentCount) if (sourceAnchors[s].binarySearch(key) >= 0) n++
        return n
    }

    private fun countOccurrencesOnTarget(key: Int): Int {
        var n = 0
        for (s in 0 until target.sentCount) if (targetAnchors[s].binarySearch(key) >= 0) n++
        return n
    }

    private fun rangeSum(values: IntArray, from: Int, to: Int): Int {
        if (to <= from) return 0
        var sum = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(values.size)) sum += values[i]
        return sum
    }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /** 归并求交（两条已排序 `IntArray`，无分配）：`anchorAgree` 的分子（§7.3）。 */
    companion object {
        fun overlap(a: IntArray, b: IntArray): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            var i = 0
            var j = 0
            var hits = 0
            while (i < a.size && j < b.size) {
                val va = a[i]
                val vb = b[j]
                when {
                    va < vb -> i++
                    va > vb -> j++
                    else -> {
                        hits++
                        i++
                        j++
                    }
                }
            }
            return hits
        }

        /**
         * `anchorAgree ∈ [0,1]`：交集 / 并集；**双侧皆空时返回 1.0（不加罚）**。
         *
         * 依据 §7.3 的说明「同理 anchorAgree = 0（有锚点但两侧对不上）时加 wA = 0.40」——
         * 反过来说，两侧都没有锚点时不应该付这笔罚金，否则每句都白付 0.40。
         */
        fun agreement(a: IntArray, b: IntArray): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val hits = overlap(a, b)
            val union = a.size + b.size - hits
            if (union <= 0) return 1.0
            return hits.toDouble() / union
        }
    }
}
