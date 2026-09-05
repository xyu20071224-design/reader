package com.linguareader.shared.translation

import kotlin.math.abs

/**
 * 词级对齐器（纯 Kotlin）：在中译句内定位点击英文词对应的「中文词/短语」。
 *
 * 组合策略（按优先级）：
 *  1. 锚点直配：数字、拉丁专名（含大小写）在中译句内直接子串匹配 → ANCHOR。
 *  2. 词典辅助：用 ECDICT 义项的中文候选词在中译句内匹配，按「长度优先 +
 *     相对位置接近」打分 → DICTIONARY。
 *  3. 无命中：返回 null，调用方降级为句/段级对照（宁可不高亮，不可错标）。
 *
 * [prefer] 是给未来的「本书术语表」留的偏好加成入口；v1 传空（术语表学习实测
 * 需要 1.7 GB 峰值堆，手机上不可行，详见 [BookTerm]）。
 */
object WordAligner {

    const val MIN_CONFIDENCE = 0.40f

    private val LATIN_WORD = Regex("[A-Za-z][A-Za-z0-9]*")
    private val ALL_CAPS = Regex("[A-Z]{2,}")
    private val PARENTHETICAL = Regex("[（(].*?[)）]")
    private val SENSE_SEPARATORS = Regex("[，,、；;／/·\\s]+")

    /**
     * @param prefer 候选加成（zhTerm → 加成 0..1），让「词典查不到的本书译法」
     *               也能被选中；缺省空。
     */
    fun align(
        enWord: String,
        enSentence: String,
        zhSentence: String,
        candidates: List<String>,
        enOffset: Int = -1,
        prefer: Map<String, Float> = emptyMap()
    ): WordAlignment? {
        val word = enWord.trim()
        if (word.isBlank() || zhSentence.isBlank()) return null

        // 1) 锚点（数字 / 拉丁专名）
        anchorMatch(word, zhSentence)?.let { return it }

        // 2) 词典辅助 + 偏好
        val terms = (candidateTerms(candidates) + prefer.keys).distinct()
        if (terms.isEmpty()) return null

        val enPos = if (enOffset >= 0 && enSentence.isNotBlank()) {
            enOffset.toDouble() / enSentence.length.coerceAtLeast(1)
        } else {
            relativePosition(word, enSentence)
        }

        var best: WordAlignment? = null
        var bestScore = -1.0
        for (term in terms.sortedByDescending { it.length }) {
            val bonus = (prefer[term] ?: 0f).toDouble()
            for ((start, end) in occurrencesOf(term, zhSentence)) {
                val zhPos = start.toDouble() / zhSentence.length.coerceAtLeast(1)
                val posPenalty = abs(enPos - zhPos) * 0.35
                val lengthBonus = term.length.coerceAtMost(4) * 0.04
                val score = 1.0 - posPenalty + lengthBonus + bonus
                if (score > bestScore) {
                    bestScore = score
                    val confidence = (1.0 - posPenalty + bonus).coerceIn(0.0, 1.0).toFloat()
                    best = WordAlignment(term, start, end, confidence, WordAlignmentSource.DICTIONARY)
                }
            }
        }
        return best?.takeIf { it.confidence >= MIN_CONFIDENCE }
    }

    // --- 锚点 ----------------------------------------------------------------

    private fun anchorMatch(word: String, zh: String): WordAlignment? {
        val isAnchor = word.all { it.isDigit() } ||
            (word.matches(LATIN_WORD) && word.any { it.isUpperCase() }) ||
            word.matches(ALL_CAPS)
        if (!isAnchor) return null
        val index = zh.indexOf(word, ignoreCase = true)
        if (index < 0) return null
        return WordAlignment(
            word = zh.substring(index, index + word.length),
            start = index,
            endExclusive = index + word.length,
            confidence = 0.95f,
            source = WordAlignmentSource.ANCHOR
        )
    }

    // --- 词典候选提取 --------------------------------------------------------

    private fun candidateTerms(candidates: List<String>): List<String> {
        val terms = mutableListOf<String>()
        for (raw in candidates) {
            // 去掉括注（如「温斯顿（人名）」→「温斯顿」）
            val cleaned = raw.replace(PARENTHETICAL, "").trim()
            if (cleaned.isBlank()) continue
            terms += cleaned
            cleaned.split(SENSE_SEPARATORS)
                .map { it.trim() }
                .filter { isChineseCandidate(it) }
                .forEach { terms += it }
        }
        return terms.distinct()
    }

    /**
     * 中文候选判定。
     *
     * ≥2 字只要含汉字即可；**单字必须整体是汉字** —— 放开单字是为了「洞 / 水 / 火」
     * 这类常见词的译法（以前一律过滤掉，导致这些词永远只有句级对照），同时不让
     * 「a」「1」「の」这种碎片混进来乱标。
     */
    private fun isChineseCandidate(term: String): Boolean = when {
        term.isEmpty() -> false
        term.length == 1 -> term[0] in '\u4e00'..'\u9fff'
        else -> term.any { it in '\u4e00'..'\u9fff' }
    }

    private fun occurrencesOf(term: String, text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val index = text.indexOf(term, from)
            if (index < 0) break
            result += index to (index + term.length)
            from = index + 1
        }
        return result
    }

    private fun relativePosition(word: String, sentence: String): Double {
        if (sentence.isBlank()) return 0.0
        val index = sentence.indexOf(word)
        if (index < 0) return 0.0
        return index.toDouble() / sentence.length.coerceAtLeast(1)
    }
}
