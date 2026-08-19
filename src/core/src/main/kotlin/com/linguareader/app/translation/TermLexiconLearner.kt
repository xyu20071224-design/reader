package com.linguareader.app.translation

import kotlin.math.abs

/**
 * 本书术语表学习器（纯 Kotlin，无平台依赖）。
 *
 * 输入 attach 对齐得到的英—中句对，无监督学习「英文词 → 本书中译本稳定译法」。
 * 原则：
 *  - 中文候选走字符 n-gram（2..4），在同一 en 词跨 ≥2 句反复共现才收录，抑制噪声；
 *  - ECDICT 种子义项（[seeds]，app 层注入）在句中命中则单句也收录（词典可信）；
 *  - 每条目带 0..1 置信度 = 1 - 平均相对位置偏差（+ 种子加成），位置越接近越可信。
 *
 * 结果写入 [TranslationMemory.terms]，查询阶段零计算，仅作候选偏好加成。
 */
object TermLexiconLearner {

    private const val MIN_GRAM = 2
    private const val MAX_GRAM = 4

    /** n-gram 候选至少要跨多少句共现才收录（种子义项不受此限）。 */
    private const val MIN_COUNT = 2

    /** 每个英文词最多保留的译法条目数（按共现次数降序）。 */
    private const val MAX_TERMS_PER_WORD = 3

    /** 种子义项置信度加成。 */
    private const val SEED_BONUS = 0.10f

    /** 每个英文词（小写）→ 候选译法。 */
    fun learn(
        pairs: List<AlignedSentencePair>,
        seeds: Map<String, List<String>> = emptyMap()
    ): List<BookTerm> {
        // (enWord, zhTerm) -> 统计
        data class Acc(var count: Int = 0, var posDistSum: Double = 0.0, var fromSeed: Boolean = false)

        val acc = HashMap<Pair<String, String>, Acc>()

        for (pair in pairs) {
            val enSentence = pair.enSentence.trim()
            val zhSentence = pair.zhSentence.trim()
            if (enSentence.isBlank() || zhSentence.isBlank()) continue

            val zhGrams = zhNGrams(zhSentence)

            for (w in tokens(enSentence).distinct()) {
                val seen = HashSet<String>()
                fun record(term: String, fromSeed: Boolean) {
                    if (term.isBlank() || term.none { it in '\u4e00'..'\u9fff' }) return
                    if (!seen.add(term)) return
                    val cur = acc.getOrPut(w to term) { Acc() }
                    cur.count++
                    cur.posDistSum += positionDistance(enSentence, w, zhSentence, term)
                    cur.fromSeed = cur.fromSeed || fromSeed
                }

                // 1) 种子路：ECDICT 义项在句中命中 → 词典可信，单次也收。
                for (seed in seeds[w].orEmpty().flatMap(::seedTerms)) {
                    if (zhSentence.contains(seed)) record(seed, fromSeed = true)
                }
                // 2) n-gram 路：跨句投票，过滤由 MIN_COUNT 承担。
                for (gram in zhGrams) record(gram, fromSeed = false)
            }
        }

        val result = ArrayList<BookTerm>()
        val byWord = acc.entries
            .filter { (_, a) -> a.count >= MIN_COUNT || a.fromSeed }
            .groupBy { it.key.first }

        for ((w, entries) in byWord) {
            // 并列时优先更长候选（更具体）；保证确定性，避免「温斯顿/温斯」类前缀竞态。
            val ranked = entries.sortedWith(
                compareByDescending<Map.Entry<Pair<String, String>, Acc>> { it.value.count }
                    .thenByDescending { it.key.second.length }
            )
            for ((key, a) in ranked.take(MAX_TERMS_PER_WORD)) {
                val avgDist = if (a.count > 0) a.posDistSum / a.count else 0.0
                val conf = ((1.0 - avgDist).toFloat() + if (a.fromSeed) SEED_BONUS else 0f)
                    .coerceIn(0f, 1f)
                result += BookTerm(enWord = w, zhTerm = key.second, count = a.count, confidence = conf)
            }
        }
        return result.sortedWith(compareBy<BookTerm> { it.enWord }.thenByDescending { it.count })
    }

    /** 英文侧：小写、去撇号/空白，拆词（含 ≥2 字符）。 */
    fun tokens(sentence: String): List<String> = normalizeSentence(sentence)
        .split(Regex("[^a-z0-9']+"))
        .map { it.trim('\'') }
        .filter { it.length >= 2 }

    /** 英文词归一化（与 [tokens] 同一规范，供查询阶段匹配术语表键）。 */
    fun normalizeWord(word: String): String = word.lowercase().trim().trim('\'')

    private fun normalizeSentence(sentence: String): String =
        sentence.lowercase().trim().replace(Regex("\\s+"), " ")

    // --- 中文候选取词（字符级 n-gram，不跨标点/空白） -----------------------

    private fun zhNGrams(text: String): List<String> {
        val runs = text.split(Regex("[^\\u4e00-\\u9fff]+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        for (run in runs) {
            val max = minOf(MAX_GRAM, run.length)
            for (len in MIN_GRAM..max) {
                for (i in 0..run.length - len) {
                    out += run.substring(i, i + len)
                }
            }
        }
        return out
    }

    // --- 种子义项清洗（对齐 WordAligner.candidateTerms 的规则） ---------------

    private fun seedTerms(raw: String): List<String> = raw
        .replace(Regex("[（(].*?[)）]"), "")
        .split(Regex("[,，、;；/／·\\s]+"))
        .map { it.trim() }
        .filter { it.length >= 2 && it.any { c -> c in '\u4e00'..'\u9fff' } }
        .distinct()

    // --- 相对位置偏差（en 词位 vs zh 候选最近出现位，0..1） -------------------

    private fun positionDistance(enSentence: String, enWord: String, zhText: String, zhTerm: String): Double {
        val enLen = enSentence.length.coerceAtLeast(1)
        val enIdx = enSentence.lowercase().indexOf(enWord)
        val enPos = if (enIdx >= 0) enIdx.toDouble() / enLen else 0.5

        var best = Double.MAX_VALUE
        var from = 0
        while (true) {
            val idx = zhText.indexOf(zhTerm, from)
            if (idx < 0) break
            val zhPos = idx.toDouble() / zhText.length.coerceAtLeast(1)
            best = minOf(best, abs(enPos - zhPos))
            from = idx + 1
        }
        return if (best == Double.MAX_VALUE) 1.0 else best
    }
}
