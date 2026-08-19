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

    /**
     * @param enChapters 每章 = 段落文本列表（英文原版，叶级段落）
     * @param zhChapters 每章 = 段落文本列表（中文译本，叶级段落）
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>
    ): List<AlignedSentencePair> {
        val result = mutableListOf<AlignedSentencePair>()
        val chapterPairs = alignChapters(enChapters, zhChapters)

        for ((enIdx, zhIdx) in chapterPairs) {
            val enParas = enChapters.getOrNull(enIdx).orEmpty()
            val zhParas = zhChapters.getOrNull(zhIdx).orEmpty()
            if (enParas.isEmpty() || zhParas.isEmpty()) continue

            val paraPairs = alignSpans(enParas, zhParas, allowMerge = true)
            for ((enPara, zhPara) in paraPairs) {
                if (enPara.isBlank() || zhPara.isBlank()) continue
                val enSentences = SentenceSplitter.split(enPara).filter { it.isNotBlank() }
                val zhSentences = splitChinese(zhPara).filter { it.isNotBlank() }

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
                    for ((enSentence, zhSentence) in sentencePairs) {
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
        return alignSpans(enTexts, zhTexts, allowMerge = false)
            .map { (en, zh) ->
                enTexts.indexOf(en) to zhTexts.indexOf(zh)
            }
    }

    // --- 通用单调序列对齐（返回对齐的文本片段对） ---------------------------

    /**
     * 用 DP 把两个文本序列单调对齐。允许 1:0 / 0:1 / 1:1，[allowMerge] 时
     * 额外允许 2:1 / 1:2。返回「已对齐片段对」（跳过项不产出）。
     */
    private fun alignSpans(
        a: List<String>,
        b: List<String>,
        allowMerge: Boolean
    ): List<Pair<String, String>> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.POSITIVE_INFINITY } }
        // 前驱记录 (pi, pj)，用于回溯。
        val prev = Array(n + 1) { Array(m + 1) { intArrayOf(-1, -1) } }
        dp[0][0] = 0.0

        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) continue
                var best = Double.POSITIVE_INFINITY
                var from = intArrayOf(0, 0)
                // 1:0（跳过 a[i-1]）
                if (i > 0 && dp[i - 1][j] + SKIP_COST < best) {
                    best = dp[i - 1][j] + SKIP_COST; from = intArrayOf(i - 1, j)
                }
                // 0:1（跳过 b[j-1]）
                if (j > 0 && dp[i][j - 1] + SKIP_COST < best) {
                    best = dp[i][j - 1] + SKIP_COST; from = intArrayOf(i, j - 1)
                }
                // 1:1
                if (i > 0 && j > 0 && dp[i - 1][j - 1] + pairCost(a[i - 1], b[j - 1]) < best) {
                    best = dp[i - 1][j - 1] + pairCost(a[i - 1], b[j - 1]); from = intArrayOf(i - 1, j - 1)
                }
                if (allowMerge) {
                    // 2:1（a 两段合并对齐 b 一段）
                    if (i > 1 && j > 0 && dp[i - 2][j - 1] + pairCost(a[i - 2] + " " + a[i - 1], b[j - 1]) < best) {
                        best = dp[i - 2][j - 1] + pairCost(a[i - 2] + " " + a[i - 1], b[j - 1]); from = intArrayOf(i - 2, j - 1)
                    }
                    // 1:2（a 一段对齐 b 两段合并）
                    if (i > 0 && j > 1 && dp[i - 1][j - 2] + pairCost(a[i - 1], b[j - 2] + " " + b[j - 1]) < best) {
                        best = dp[i - 1][j - 2] + pairCost(a[i - 1], b[j - 2] + " " + b[j - 1]); from = intArrayOf(i - 1, j - 2)
                    }
                }
                dp[i][j] = best
                prev[i][j] = from
            }
        }

        // 回溯
        val pairs = mutableListOf<Pair<String, String>>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val from = prev[i][j]
            val pi = from[0]
            val pj = from[1]
            if (pi < 0 || pj < 0) break
            val di = i - pi
            val dj = j - pj
            when {
                di == 1 && dj == 1 -> pairs += a[pi] to b[pj]
                di == 2 && dj == 1 -> pairs += (a[pi] + " " + a[pi + 1]) to b[pj]
                di == 1 && dj == 2 -> pairs += a[pi] to (b[pj] + " " + b[pj + 1])
                // 1:0 / 0:1 跳过，不产出
            }
            i = pi
            j = pj
        }
        return pairs.asReversed()
    }

    // --- 中文分句 -----------------------------------------------------------

    private fun splitChinese(text: String): List<String> =
        text.split(Regex("(?<=[。！？；!?;])|(?<=\\n)"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    // --- 代价与置信度 -------------------------------------------------------

    private fun enWordCount(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

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
        val anchors = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}")
            .findAll(en)
            .map { it.value.lowercase() }
            .toSet()
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
