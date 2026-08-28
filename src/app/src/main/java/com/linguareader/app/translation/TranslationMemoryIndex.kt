package com.linguareader.app.translation

/**
 * 译本查询索引：按章节分桶 + 预归一化英文句/段落，**一本书构建一次**，之后
 * 每次点词只在本章桶里查。
 *
 * 为什么需要它：原始实现每次点词都重新读整份档案（整本小说 12,697 句对、
 * 旧格式 14.67 MB JSON）并对全表跑一次归一化正则 —— 在 minSdk 23 的机器上
 * 既卡顿又有 OOM 风险。
 *
 * 匹配顺序（与 [TranslationMemorySearch] 文档一致）：
 *  1. 精确段落 + 精确句子（句子级，最高置信）
 *  2. 段落候选内的精确句子（句子级）
 *  3. 句子重叠（同一段落内、英文句互相包含）→ 句子级
 *  4. 句子级模糊（同章内相似度达阈值的句子）→ 句子级
 *  5. 兜底：对应段落（段落级）
 *
 * 第 4、5 级是推断出来的匹配，要求置信度不低于
 * [TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE]；1–3 级是文本精确命中，
 * 不受该阈值限制（宁可不高亮，不可错标）。
 */
class TranslationMemoryIndex(private val memory: TranslationMemory) {

    /** 一条句对的归一化视图，避免每次点词重复跑正则。 */
    private class Entry(
        val pair: AlignedSentencePair,
        /** 该句对在 memory.pairs 里的全局下标，句级重翻靠它精确定位档案条目。 */
        val pairIndex: Int,
        val sentence: String,
        val paragraph: String
    )

    private val byChapter: Map<Int, List<Entry>> = build()

    val translationTitle: String get() = memory.translationTitle
    val pairCount: Int get() = memory.pairs.size

    /** 该章有多少条句对（0 表示这一章没有对齐结果）。 */
    fun chapterPairCount(chapterIndex: Int): Int = byChapter[chapterIndex]?.size ?: 0

    fun lookup(chapterIndex: Int, sentence: String, paragraph: String): TranslationLookupResult? {
        val entries = byChapter[chapterIndex] ?: return null
        if (entries.isEmpty()) return null

        val nSentence = TranslationMemorySearch.normalize(sentence)
        val nParagraph = TranslationMemorySearch.normalize(paragraph)

        // 1) 精确段落 + 精确句子
        entries.firstOrNull {
            it.sentence.isNotBlank() && nSentence.isNotBlank() &&
                it.sentence == nSentence && it.paragraph == nParagraph
        }?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 2) 精确句子（任意段落，优先同段落）
        val bySentence = entries.filter {
            it.sentence.isNotBlank() && nSentence.isNotBlank() && it.sentence == nSentence
        }
        (bySentence.firstOrNull { it.paragraph == nParagraph } ?: bySentence.firstOrNull())
            ?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 3) 句子重叠（同一段落内互相包含）
        entries.firstOrNull {
            it.sentence.isNotBlank() && nSentence.isNotBlank() &&
                it.paragraph == nParagraph &&
                (it.sentence.contains(nSentence) || nSentence.contains(it.sentence))
        }?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

        // 4) 句子级模糊：同章内相似度最高且达阈值的句子。
        if (nSentence.isNotBlank()) {
            val queryTokens = TranslationMemorySearch.tokenSet(nSentence)
            var bestEntry: Entry? = null
            var bestSimilarity = TranslationMemorySearch.FUZZY_MIN_SIMILARITY
            for (entry in entries) {
                if (entry.sentence.isBlank()) continue
                val similarity = TranslationMemorySearch.similarity(
                    queryTokens,
                    TranslationMemorySearch.tokenSet(entry.sentence)
                )
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestEntry = entry
                }
            }
            bestEntry?.takeIf { it.pair.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE }
                ?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }
        }

        // 5) 兜底：对应段落
        entries.firstOrNull { it.paragraph == nParagraph }
            ?.takeIf { it.pair.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE }
            ?.let { return toResult(it, TranslationMatchLevel.PARAGRAPH) }

        return null
    }

    private fun build(): Map<Int, List<Entry>> {
        // 同一段落的多条句对共享同一份归一化文本。
        val paragraphCache = HashMap<String, String>()
        return memory.pairs
            .withIndex()
            .groupBy { it.value.enChapter }
            .mapValues { (_, indexed) ->
                indexed.map { (index, pair) ->
                    Entry(
                        pair = pair,
                        pairIndex = index,
                        sentence = TranslationMemorySearch.normalize(pair.enSentence),
                        paragraph = paragraphCache.getOrPut(pair.enParagraph) {
                            TranslationMemorySearch.normalize(pair.enParagraph)
                        }
                    )
                }
            }
    }

    private fun toResult(entry: Entry, level: TranslationMatchLevel): TranslationLookupResult {
        val pair = entry.pair
        return TranslationLookupResult(
            translationTitle = memory.translationTitle,
            english = pair.enSentence.ifBlank { pair.enParagraph },
            chinese = pair.zhSentence.ifBlank { pair.zhParagraph },
            chineseParagraph = pair.zhParagraph,
            matchLevel = level,
            confidence = pair.confidence,
            pairIndex = entry.pairIndex,
            englishParagraph = pair.enParagraph
        )
    }
}

/**
 * 归一化与相似度工具（纯函数，可单测），外加一个「一次性查询」便捷入口。
 *
 * 归一化统一处理「抽取端 vs WebView 端」的差异：折叠空白、弯引号→直引号、
 * 破折号统一、标点转空格、小写。
 */
object TranslationMemorySearch {

    /** 推断类匹配（模糊句 / 段落兜底）低于该置信度视为不可靠，宁可返回 null。 */
    const val MIN_ACCEPT_CONFIDENCE = 0.30f

    /** 句子级模糊匹配的最低相似度；低于阈值宁可走段落兜底也不误配。 */
    const val FUZZY_MIN_SIMILARITY = 0.85

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = Regex("[.,!?;:，。！？；：…、·•]+")
    private val NON_TOKEN = Regex("[^a-z0-9']+")

    /** 一次性查询：建索引后立刻查。频繁点词请复用 [TranslationMemoryIndex]。 */
    fun lookup(
        memory: TranslationMemory,
        chapterIndex: Int,
        sentence: String,
        paragraph: String
    ): TranslationLookupResult? =
        TranslationMemoryIndex(memory).lookup(chapterIndex, sentence, paragraph)

    fun normalize(s: String): String = s.trim()
        .replace(WHITESPACE, " ")
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('—', '-')
        .replace('–', '-')
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .lowercase()

    /** token 级相似度（Dice），0..1；空任一侧返回 0。 */
    fun sentenceSimilarity(a: String, b: String): Double =
        similarity(tokenSet(normalize(a)), tokenSet(normalize(b)))

    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = if (a.size <= b.size) a.count { it in b } else b.count { it in a }
        return 2.0 * intersection / (a.size + b.size)
    }

    /** 已归一化文本 → token 集合。 */
    fun tokenSet(normalized: String): Set<String> = normalized
        .split(NON_TOKEN)
        .mapNotNullTo(LinkedHashSet()) { token ->
            token.trim('\'').takeIf { it.isNotBlank() }
        }
}
