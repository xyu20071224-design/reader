package com.linguareader.app.translation

import kotlin.math.abs

/**
 * 词级对齐器（纯 Kotlin）：在中译句内定位点击英文词对应的「中文词/短语」。
 *
 * 组合策略（按优先级）：
 *  1. 锚点直配：数字、拉丁专名（含大小写）在中译句内直接子串匹配 → ANCHOR。
 *  2. 词典辅助 + 本书术语偏好：用 ECDICT 义项（+ 本书术语表 [prefer]）的中文候选
 *     词在中译句内匹配，按「长度优先 + 相对位置接近 + 偏好加成」打分 → DICTIONARY。
 *  3. 无命中：返回 null，调用方降级为句/段级对照（宁可不高亮，不可错标）。
 */
object WordAligner {

    const val MIN_CONFIDENCE = 0.40f

    /**
     * @param prefer 本书术语表带来的候选加成（zhTerm → 加成 0..1），
     *              使「词典查不到的本书译法」也能被选中；缺省空 = 旧行为。
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

        // 2) 词典辅助 + 本书术语偏好（偏好词直接并入候选，无需调用方重复传入）
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
            (word.matches(Regex("[A-Za-z][A-Za-z0-9]*")) && word.any { it.isUpperCase() }) ||
            word.matches(Regex("[A-Z]{2,}"))
        if (!isAnchor) return null
        val idx = zh.indexOf(word, ignoreCase = true)
        if (idx < 0) return null
        return WordAlignment(
            word = zh.substring(idx, idx + word.length),
            start = idx,
            endExclusive = idx + word.length,
            confidence = 0.95f,
            source = WordAlignmentSource.ANCHOR
        )
    }

    // --- 词典候选提取 --------------------------------------------------------

    private fun candidateTerms(candidates: List<String>): List<String> {
        val terms = mutableListOf<String>()
        for (raw in candidates) {
            // 去掉括注（如「温斯顿（人名）」→「温斯顿」）
            val cleaned = raw.replace(Regex("[（(].*?[)）]"), "").trim()
            if (cleaned.isBlank()) continue
            terms += cleaned
            cleaned.split(Regex("[，,、；;／/·\\s]+"))
                .map { it.trim() }
                .filter { it.length >= 2 && it.any { c -> c in '\u4e00'..'\u9fff' } }
                .forEach { terms += it }
        }
        return terms.distinct()
    }

    private fun occurrencesOf(term: String, text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val idx = text.indexOf(term, from)
            if (idx < 0) break
            result += idx to (idx + term.length)
            from = idx + 1
        }
        return result
    }

    private fun relativePosition(word: String, sentence: String): Double {
        if (sentence.isBlank()) return 0.0
        val idx = sentence.indexOf(word)
        if (idx < 0) return 0.0
        return idx.toDouble() / sentence.length.coerceAtLeast(1)
    }
}
