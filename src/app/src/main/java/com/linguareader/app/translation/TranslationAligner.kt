package com.linguareader.app.translation

import com.linguareader.app.tts.SentenceSplitter
import kotlin.math.abs

/**
 * 双语文本对齐器（纯 Kotlin，无平台依赖）。
 *
 * 三级对齐，全部走「单调序列 DP」：
 *  1. 章节对齐：按章节整体特征做 1:1 / 1:0 / 0:1 对齐（章节数不一致时允许跳过）。
 *  2. 段落对齐：章节内按叶级段落（与阅读器/TTS 同一选择器）做 DP，允许
 *     1:0 / 0:1 / 1:1 / 2:1 / 1:2（对应省略、合并、拆分）。
 *  3. 句子对齐：对齐段落内用 [SentenceSplitter] 分句后做小规模 DP。
 *
 * 对齐代价 = 长度比偏差（英文词数 vs 中文有效字符数）− 数字/拉丁专名锚点命中率。
 * 置信度 = 1 − 归一化代价，钳制到 [MIN_CONFIDENCE, 1]。
 *
 * **性能上的硬要求**：代价函数在 O(n·m) 的 DP 内层被调用，因此它只允许做算术与
 * 哈希查表，绝不能扫描文本。所有文本特征（词数、字符数、锚点集合）都在进入 DP
 * 之前按片段预计算一次 —— 早期实现每格都对整段/整章重新跑正则并 `lowercase()`
 * 拷贝整章文本，真机上整本小说跑 5 分钟以上仍未结束（单线程 100% CPU）。
 */
object TranslationAligner {

    const val MIN_CONFIDENCE = 0.15f
    private const val SKIP_COST = 1.2

    /** 启发式：约 1.7 个中文字符对应 1 个英文单词（用于长度归一）。 */
    private const val ZH_CHARS_PER_EN_WORD = 1.7

    private val ANCHORS = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}")
    private val LATIN_RUNS = Regex("[A-Za-z0-9]+")
    private val WHITESPACE = Regex("\\s+")
    private val ZH_SENTENCE_END = Regex("(?<=[。！？；!?;])|(?<=\\n)")

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
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>
    ): List<AlignedSentencePair> {
        if (enChapters.isEmpty() || zhChapters.isEmpty()) return emptyList()

        // 段落特征每篇只算一次；章节特征由段落特征聚合，不再拼接整章文本。
        val enParagraphSpans = enChapters.map { paragraphs -> paragraphs.map { englishSpan(it) } }
        val zhParagraphSpans = zhChapters.map { paragraphs -> paragraphs.map { chineseSpan(it) } }

        val result = mutableListOf<AlignedSentencePair>()

        for ((enIdx, zhIdx) in alignChapters(enParagraphSpans, zhParagraphSpans)) {
            val enParagraphs = enChapters[enIdx]
            val zhParagraphs = zhChapters[zhIdx]
            if (enParagraphs.isEmpty() || zhParagraphs.isEmpty()) continue

            val enSpans = enParagraphSpans[enIdx]
            val zhSpans = zhParagraphSpans[zhIdx]

            for (paragraphPair in alignSpans(enSpans, zhSpans, allowMerge = true)) {
                val enParagraph = join(enParagraphs, paragraphPair.a)
                val zhParagraph = join(zhParagraphs, paragraphPair.b)
                if (enParagraph.isBlank() || zhParagraph.isBlank()) continue

                val enSentences = SentenceSplitter.split(enParagraph).filter { it.isNotBlank() }
                val zhSentences = splitChinese(zhParagraph)
                val enSentenceSpans = enSentences.map { englishSpan(it) }
                val zhSentenceSpans = zhSentences.map { chineseSpan(it) }

                val sentencePairs =
                    if (enSentences.isEmpty() || zhSentences.isEmpty()) emptyList()
                    else alignSpans(enSentenceSpans, zhSentenceSpans, allowMerge = false)

                if (sentencePairs.isEmpty()) {
                    // 句子级无法对齐 → 降级为段落对照。
                    result += AlignedSentencePair(
                        enChapter = enIdx,
                        zhChapter = zhIdx,
                        enParagraph = enParagraph,
                        zhParagraph = zhParagraph,
                        enSentence = "",
                        zhSentence = "",
                        confidence = confidenceOf(enSpans, paragraphPair.a, zhSpans, paragraphPair.b)
                    )
                } else {
                    for (sentencePair in sentencePairs) {
                        result += AlignedSentencePair(
                            enChapter = enIdx,
                            zhChapter = zhIdx,
                            enParagraph = enParagraph,
                            zhParagraph = zhParagraph,
                            enSentence = join(enSentences, sentencePair.a),
                            zhSentence = join(zhSentences, sentencePair.b),
                            confidence = confidenceOf(
                                enSentenceSpans, sentencePair.a,
                                zhSentenceSpans, sentencePair.b
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    // --- 片段特征（进 DP 之前算好） ------------------------------------------

    /**
     * 一个待对齐片段的预计算特征。[anchors] 只在英文侧有值，[latin] 只在中文侧有值。
     */
    private class Span(
        val words: Int,
        val chars: Int,
        val anchors: Set<String>,
        val latin: Set<String>
    )

    private fun englishSpan(text: String) = Span(
        words = countWords(text),
        chars = countChars(text),
        anchors = ANCHORS.findAll(text).mapTo(HashSet()) { it.value.lowercase() },
        latin = emptySet()
    )

    private fun chineseSpan(text: String) = Span(
        words = countWords(text),
        chars = countChars(text),
        anchors = emptySet(),
        // 中文侧只需要「句中出现过哪些拉丁/数字词」，锚点判定退化成哈希查表。
        latin = LATIN_RUNS.findAll(text).mapTo(HashSet()) { it.value.lowercase() }
    )

    private fun foldSpans(spans: List<Span>): Span {
        var words = 0
        var chars = 0
        val anchors = HashSet<String>()
        val latin = HashSet<String>()
        for (span in spans) {
            words += span.words
            chars += span.chars
            anchors += span.anchors
            latin += span.latin
        }
        return Span(words, chars, anchors, latin)
    }

    private fun countWords(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    private fun countChars(text: String): Int = text.count { !it.isWhitespace() }

    // --- 章节对齐 -----------------------------------------------------------

    private fun alignChapters(
        en: List<List<Span>>,
        zh: List<List<Span>>
    ): List<Pair<Int, Int>> {
        if (en.size == zh.size) return en.indices.map { it to it }
        val enSpans = en.map { foldSpans(it) }
        val zhSpans = zh.map { foldSpans(it) }
        // 直接用 DP 给出的下标。不要拿文本去 indexOf 回查：两章正文完全相同
        // （或都为空）时会全部映射到第一处，导致整章错配。
        return alignSpans(enSpans, zhSpans, allowMerge = false).map { it.a[0] to it.b[0] }
    }

    // --- 通用单调序列对齐 ----------------------------------------------------

    /** 一组对齐结果：[a]/[b] 各是被对齐到一起的下标（1 个或合并的 2 个）。 */
    private class SpanPair(val a: IntArray, val b: IntArray)

    /**
     * 用 DP 把两个片段序列单调对齐。允许 1:0 / 0:1 / 1:1，[allowMerge] 时额外允许
     * 2:1 / 1:2。返回「已对齐的下标对」（跳过项不产出）。
     *
     * dp 只保留最近三行（2:1 / 1:2 会回看 i−2、j−2），回溯靠每格 1 字节的走法矩阵，
     * 因此内存是 O(n·m) 字节而不是 O(n·m) 个 double + 对象。
     */
    private fun alignSpans(
        a: List<Span>,
        b: List<Span>,
        allowMerge: Boolean
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
                    val cost = prev1[j - 1] + pairCost(a[i - 1], null, b[j - 1], null)
                    if (cost < best) {
                        best = cost
                        move = MOVE_ONE_ONE
                    }
                }
                if (allowMerge) {
                    if (i > 1 && j > 0 && prev2[j - 1] < Double.POSITIVE_INFINITY) {
                        val cost = prev2[j - 1] + pairCost(a[i - 2], a[i - 1], b[j - 1], null)
                        if (cost < best) {
                            best = cost
                            move = MOVE_TWO_ONE
                        }
                    }
                    if (i > 0 && j > 1 && prev1[j - 2] < Double.POSITIVE_INFINITY) {
                        val cost = prev1[j - 2] + pairCost(a[i - 1], null, b[j - 2], b[j - 1])
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

    /** 单下标时返回原 String 实例本身（共享引用，避免复制整段文本）。 */
    private fun join(source: List<String>, indices: IntArray): String =
        if (indices.size == 1) source[indices[0]]
        else indices.joinToString(" ") { source[it] }

    // --- 中文分句 -----------------------------------------------------------

    private fun splitChinese(text: String): List<String> =
        text.split(ZH_SENTENCE_END)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // --- 代价与置信度（只做算术与哈希查表） ----------------------------------

    /** 长度比偏差（0..1）：英文词数 vs 中文有效字符数，按 1.7 字符/词归一。 */
    private fun lengthCost(enWords: Int, zhChars: Int): Double {
        val ew = enWords.coerceAtLeast(1)
        val zc = zhChars.coerceAtLeast(1)
        val zcNorm = zc / ZH_CHARS_PER_EN_WORD
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

    private fun pairCost(en1: Span, en2: Span?, zh1: Span, zh2: Span?): Double {
        val enWords = en1.words + (en2?.words ?: 0)
        val zhChars = zh1.chars + (zh2?.chars ?: 0)
        return lengthCost(enWords, zhChars) -
            anchorBonus(en1.anchors, en2?.anchors, zh1.latin, zh2?.latin)
    }

    private fun confidenceOf(
        enSpans: List<Span>,
        enIndices: IntArray,
        zhSpans: List<Span>,
        zhIndices: IntArray
    ): Float {
        val cost = pairCost(
            enSpans[enIndices[0]],
            enIndices.getOrNull(1)?.let { enSpans[it] },
            zhSpans[zhIndices[0]],
            zhIndices.getOrNull(1)?.let { zhSpans[it] }
        )
        val confidence = (1.0 - cost).coerceIn(0.0, 1.0)
        return confidence.toFloat().coerceIn(MIN_CONFIDENCE, 1f)
    }
}
