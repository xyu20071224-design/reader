package com.linguareader.app.translation

/**
 * 译本查询器（纯 Kotlin）。
 *
 * 点词时按 `chapterIndex + 英文句/段落` 在 [TranslationMemory] 中查找：
 *  1. 精确段落 + 精确句子（句子级，最高置信）
 *  2. 段落候选内的精确句子（句子级）
 *  3. 句子重叠（同一段落内、英文句互相包含）→ 句子级
 *  4. 句子级模糊（同章内相似度达阈值的句子）→ 句子级
 *  5. 兜底：对应段落（段落级）
 *
 * 所有文本先做 [normalize]（空白折叠、弯/直标点统一、标点归一），
 * 以消除「抽取端 vs WebView 端」的归一化差异导致的漏配。
 */
object TranslationMemorySearch {

    /** 低于该置信度视为不可靠，宁可返回 null 也不可错标。 */
    const val MIN_ACCEPT_CONFIDENCE = 0.30f

    /** 句子级模糊匹配的最低相似度；低于阈值宁可走段落兜底也不误配。 */
    const val FUZZY_MIN_SIMILARITY = 0.85

    fun lookup(
        memory: TranslationMemory,
        chapterIndex: Int,
        sentence: String,
        paragraph: String
    ): TranslationLookupResult? {
        val inChapter = memory.pairs.filter { it.enChapter == chapterIndex }
        if (inChapter.isEmpty()) return null

        val nSentence = normalize(sentence)
        val nParagraph = normalize(paragraph)

        // 1) 精确段落 + 精确句子（归一化后）
        inChapter.firstOrNull {
            it.enSentence.isNotBlank() && nSentence.isNotBlank() &&
                normalize(it.enSentence) == nSentence &&
                normalize(it.enParagraph) == nParagraph
        }?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 2) 精确句子（任意段落，优先同段落）
        val bySentence = inChapter.filter {
            it.enSentence.isNotBlank() && nSentence.isNotBlank() &&
                normalize(it.enSentence) == nSentence
        }
        (bySentence.firstOrNull { normalize(it.enParagraph) == nParagraph } ?: bySentence.firstOrNull())
            ?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 3) 句子重叠（同一段落内互相包含，归一化后）
        inChapter.firstOrNull {
            it.enSentence.isNotBlank() && nSentence.isNotBlank() &&
                normalize(it.enParagraph) == nParagraph &&
                (normalize(it.enSentence).contains(nSentence) || nSentence.contains(normalize(it.enSentence)))
        }?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 4) 句子级模糊：同章内相似度最高的句子达阈值即视为句级命中，
        //    让更多点词进入「句子级 → 词级高亮」而不是直接掉到段落级。
        var bestFuzzy: AlignedSentencePair? = null
        var bestSim = FUZZY_MIN_SIMILARITY
        for (pair in inChapter) {
            val pe = normalize(pair.enSentence)
            if (pe.isBlank()) continue
            val sim = sentenceSimilarity(nSentence, pe)
            if (sim > bestSim) {
                bestSim = sim
                bestFuzzy = pair
            }
        }
        bestFuzzy?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 5) 兜底：对应段落（归一化后）
        inChapter.firstOrNull { normalize(it.enParagraph) == nParagraph }
            ?.let { return toResult(it, TranslationMatchLevel.PARAGRAPH) }

        return null
    }

    /** 查询/存储统一归一化：折叠空白、弯引号→直引号、破折号统一、标点转空格、小写。 */
    fun normalize(s: String): String = s.trim()
        .replace(Regex("\\s+"), " ")
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("[.,!?;:，。！？；：…、·•]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    /** token 级相似度（Dice），0..1；空任一侧返回 0。 */
    fun sentenceSimilarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size
        return 2.0 * inter / (ta.size + tb.size)
    }

    private fun tokens(s: String): List<String> = normalize(s)
        .split(Regex("[^a-z0-9']+"))
        .map { it.trim('\'') }
        .filter { it.isNotBlank() }

    private fun toResult(pair: AlignedSentencePair, level: TranslationMatchLevel): TranslationLookupResult {
        val chinese = pair.zhSentence.ifBlank { pair.zhParagraph }
        return TranslationLookupResult(
            translationTitle = "",
            english = pair.enSentence.ifBlank { pair.enParagraph },
            chinese = chinese,
            chineseParagraph = pair.zhParagraph,
            matchLevel = level,
            confidence = pair.confidence
        )
    }
}
