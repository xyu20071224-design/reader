package com.linguareader.app.translation

import com.linguareader.app.tts.SentenceSplitter
import kotlin.math.abs

/**
 * 双语文本对齐器（纯 Kotlin，无平台依赖）。
 *
 * 三级对齐，全部走「单调序列 DP」：
 *  1. 章节对齐：按章节全文做 1:1 / 1:0 / 0:1 对齐（章节数不一致时允许跳过）。
 *  2. 段落对齐：章节内按叶级段落（与阅读器/TTS 同一选择器）做 DP，允许
 *     1:0 / 0:1 / 1:1 / 2:1 / 1:2（对应省略、合并、拆分）。
 *  3. 句子对齐：对齐段落内用 [SentenceSplitter] 分句后做小规模 DP。
 *
 * 对齐代价 = 长度比偏差（英文词数 vs 中文有效字符数）+ 数字/拉丁专名锚点。
 * 置信度 = 1 - 归一化代价，钳制到 [MIN_CONFIDENCE, 1]。
 */
object TranslationAligner {

    const val MIN_CONFIDENCE = 0.15f
    private const val SKIP_COST = 1.2

    /** 启发式：约 1.7 个中文字符对应 1 个英文单词（用于长度归一）。 */
    private const val ZH_CHARS_PER_EN_WORD = 1.7

    // 这三个正则处在 O(n·m) 的 DP 内层，必须复用：每格新建 Regex 是整本书
    // 对齐耗时（实测 29s）里最大的一笔浪费。
    private val ANCHORS = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}")
    private val WHITESPACE = Regex("\\s+")
    private val ZH_SENTENCE_END = Regex("(?<=[。！？；!?;])|(?<=\\n)")

    // DP 回溯用的走法编码（每格 1 字节，避免每格再分配一个 IntArray）。
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
        val result = mutableListOf<AlignedSentencePair>()

        for ((enIdx, zhIdx) in alignChapters(enChapters, zhChapters)) {
            val enParas = enChapters.getOrNull(enIdx).orEmpty()
            val zhParas = zhChapters.getOrNull(zhIdx).orEmpty()
            if (enParas.isEmpty() || zhParas.isEmpty()) continue

            for (span in alignSpans(enParas, zhParas, allowMerge = true)) {
                val enPara = join(enParas, span.a)
                val zhPara = join(zhParas, span.b)
                if (enPara.isBlank() || zhPara.isBlank()) continue

                val enSentences = SentenceSplitter.split(enPara).filter { it.isNotBlank() }
                val zhSentences = splitChinese(zhPara)

                val sentencePairs =
                    if (enSentences.isEmpty() || zhSentences.isEmpty()) emptyList()
                    else alignSpans(enSentences, zhSentences, allowMerge = false)

                if (sentencePairs.isEmpty()) {
                    // 句子级无法对齐 → 降级为段落对照。
                    result += AlignedSentencePair(
                        enChapter = enIdx,
                        zhChapter = zhIdx,
                        enParagraph = enPara,
                        zhParagraph = zhPara,
                        enSentence = "",
                        zhSentence = "",
                        confidence = confidence(enPara, zhPara)
                    )
                } else {
                    for (sentenceSpan in sentencePairs) {
                        val enSentence = join(enSentences, sentenceSpan.a)
                        val zhSentence = join(zhSentences, sentenceSpan.b)
                        result += AlignedSentencePair(
                            enChapter = enIdx,
                            zhChapter = zhIdx,
                            enParagraph = enPara,
                            zhParagraph = zhPara,
                            enSentence = enSentence,
                            zhSentence = zhSentence,
                            confidence = confidence(enSentence, zhSentence)
                        )
                    }
                }
            }
        }
        return result
    }

    // --- 章节对齐：按章节全文做 1:1/1:0/0:1 对齐 -------------------------------

    private fun alignChapters(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>
    ): List<Pair<Int, Int>> {
        if (enChapters.isEmpty() || zhChapters.isEmpty()) return emptyList()
        if (enChapters.size == zhChapters.size) {
            return enChapters.indices.map { it to it }
        }
        val enTexts = enChapters.map { it.joinToString(" ").trim() }
        val zhTexts = zhChapters.map { it.joinToString(" ").trim() }
        // 直接用 DP 给出的下标。不要拿文本去 indexOf 回查：两章正文完全相同
        // （或都为空）时会全部映射到第一处，导致整章错配。
        return alignSpans(enTexts, zhTexts, allowMerge = false).map { it.a[0] to it.b[0] }
    }

    // --- 通用单调序列对齐 ----------------------------------------------------

    /** 一组对齐结果：[a]/[b] 各是被对齐到一起的下标（1 个或合并的 2 个）。 */
    private class SpanPair(val a: IntArray, val b: IntArray)

    /**
     * 用 DP 把两个文本序列单调对齐。允许 1:0 / 0:1 / 1:1，[allowMerge] 时
     * 额外允许 2:1 / 1:2。返回「已对齐的下标对」（跳过项不产出）。
     */
    private fun alignSpans(
        a: List<String>,
        b: List<String>,
        allowMerge: Boolean
    ): List<SpanPair> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        val moves = Array(n + 1) { ByteArray(m + 1) }
        dp[0][0] = 0.0

        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) continue
                var best = Double.POSITIVE_INFINITY
                var move: Byte = 0
                if (i > 0) {
                    val cost = dp[i - 1][j] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_A
                    }
                }
                if (j > 0) {
                    val cost = dp[i][j - 1] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_B
                    }
                }
                if (i > 0 && j > 0) {
                    val cost = dp[i - 1][j - 1] + pairCost(a[i - 1], b[j - 1])
                    if (cost < best) {
                        best = cost
                        move = MOVE_ONE_ONE
                    }
                }
                if (allowMerge) {
                    if (i > 1 && j > 0) {
                        val cost = dp[i - 2][j - 1] + pairCost(a[i - 2] + " " + a[i - 1], b[j - 1])
                        if (cost < best) {
                            best = cost
                            move = MOVE_TWO_ONE
                        }
                    }
                    if (i > 0 && j > 1) {
                        val cost = dp[i - 1][j - 2] + pairCost(a[i - 1], b[j - 2] + " " + b[j - 1])
                        if (cost < best) {
                            best = cost
                            move = MOVE_ONE_TWO
                        }
                    }
                }
                dp[i][j] = best
                moves[i][j] = move
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

    // --- 代价与置信度 -------------------------------------------------------

    private fun enWordCount(s: String): Int = s.split(WHITESPACE).count { it.isNotBlank() }

    private fun zhCharCount(s: String): Int = s.count { !it.isWhitespace() }

    /** 长度比偏差（0..1）：英文词数 vs 中文有效字符数，按 1.7 字符/词归一。 */
    private fun lengthCost(en: String, zh: String): Double {
        val ew = enWordCount(en).coerceAtLeast(1)
        val zc = zhCharCount(zh).coerceAtLeast(1)
        val zcNorm = zc / ZH_CHARS_PER_EN_WORD
        val diff = abs(ew - zcNorm)
        return diff / (ew + zcNorm + 1.0)
    }

    /** 数字/拉丁专名锚点命中率（0..1），命中越多对齐越可信。 */
    private fun anchorBonus(en: String, zh: String): Double {
        val anchors = ANCHORS.findAll(en).map { it.value.lowercase() }.toSet()
        if (anchors.isEmpty()) return 0.0
        val zhLower = zh.lowercase()
        val hits = anchors.count { zhLower.contains(it) }
        return (hits.toDouble() / anchors.size) * 0.5
    }

    private fun pairCost(en: String, zh: String): Double =
        lengthCost(en, zh) - anchorBonus(en, zh)

    private fun confidence(en: String, zh: String): Float {
        val cost = pairCost(en, zh)
        val conf = (1.0 - cost).coerceIn(0.0, 1.0)
        return conf.toFloat().coerceIn(MIN_CONFIDENCE, 1f)
    }
}
