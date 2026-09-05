package com.linguareader.shared.translation

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
 *  5. 兜底：对应段落；段内先做句级找回（token 重叠达
 *     [TranslationMemorySearch.PARAGRAPH_RECOVERY_MIN_SIMILARITY]，或释义词
 *     命中达 [TranslationMemorySearch.PARAGRAPH_RECOVERY_MIN_SENSE_CONFIDENCE]，
 *     重叠优先）→ 句子级，找不回才降为段落级
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

    fun lookup(
        chapterIndex: Int,
        sentence: String,
        paragraph: String,
        enWord: String = "",
        enWordOffset: Int = -1,
        senseCandidates: () -> List<String> = { emptyList() }
    ): TranslationLookupResult? {
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

        // 5) 兜底：对应段落。段落精确命中说明点击句确实在这段里，先做两级
        // 段内句级找回：段落兜底原本一刀切展示整段，但段内往往能找到与点击句
        // 对应的那条句对，找回后升级为句子级，词级高亮与句级重翻随之可用。
        // 找回条件都是「与点击句本身对应」而非 V4 那种「按位置取段内第一条」，
        // 错位残句不会复发。找不回才降为整段展示。
        val paragraphEntries = entries.filter { it.paragraph == nParagraph }
        if (paragraphEntries.isNotEmpty() && nSentence.isNotBlank()) {
            val queryTokens = TranslationMemorySearch.tokenSet(nSentence)

            // 5a) token 重叠找回（强证据，优先）：与点击句 Dice ≥ 阈值。
            // 门槛低于第 4 级（章内盲扫必须严），因为段落已精确命中、候选被
            // 约束在本段之内；而第 4 级同阈值扫描过全章都没命中，用同阈值在
            // 段内重扫是纯冗余。
            var bestEntry: Entry? = null
            var bestSimilarity = 0.0
            for (entry in paragraphEntries) {
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
            bestEntry?.takeIf {
                bestSimilarity >= TranslationMemorySearch.PARAGRAPH_RECOVERY_MIN_SIMILARITY &&
                    it.pair.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE
            }?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }

            // 5b) 释义找回（弱证据，兜在重叠之后）：意译句 token 对不上，但被点
            // 单词的释义词（ECDICT 义项中文候选）出现在某条中译句里。候选生成
            // 交给调用方按需提供（词典查询有 IO 成本，L1–4 命中不该付这笔钱），
            // 这里只负责定位与排序：多个候选按「释义词匹配置信度（已含中英
            // 相对位置一致性）+ 重叠度微弱加成」综合取最高。
            val candidates = senseCandidates()
            if (enWord.isNotBlank() && candidates.isNotEmpty()) {
                var bestSense: Entry? = null
                var bestSenseScore = -1.0
                for (entry in paragraphEntries) {
                    if (entry.sentence.isBlank()) continue
                    if (entry.pair.confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue
                    val alignment = WordAligner.align(
                        enWord = enWord,
                        enSentence = sentence,
                        zhSentence = entry.pair.zhSentence,
                        candidates = candidates,
                        enOffset = enWordOffset
                    ) ?: continue
                    if (alignment.confidence < TranslationMemorySearch.PARAGRAPH_RECOVERY_MIN_SENSE_CONFIDENCE) {
                        continue
                    }
                    val overlap = TranslationMemorySearch.similarity(
                        queryTokens,
                        TranslationMemorySearch.tokenSet(entry.sentence)
                    )
                    val score = alignment.confidence + overlap * 0.2
                    if (score > bestSenseScore) {
                        bestSenseScore = score
                        bestSense = entry
                    }
                }
                bestSense?.let { return toResult(it, TranslationMatchLevel.SENTENCE) }
            }
        }
        // 5c) 段落级展示。即使命中的是句对条目，也展示完整 zhParagraph：兜底
        // 就是「这段的译文大致是这些」，单条句对在两级句级找回都失败的前提
        // 下面临的是错位句，残句比整段更误导（「长句只翻译了其中一句」的
        // 主诉之一就是这里）。
        paragraphEntries.firstOrNull { it.pair.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE }
            ?.let {
                return TranslationLookupResult(
                    translationTitle = memory.translationTitle,
                    english = it.pair.enSentence.ifBlank { it.pair.enParagraph },
                    chinese = it.pair.zhParagraph,
                    chineseParagraph = it.pair.zhParagraph,
                    matchLevel = TranslationMatchLevel.PARAGRAPH,
                    confidence = it.pair.confidence,
                    pairIndex = it.pairIndex,
                    englishParagraph = it.pair.enParagraph
                )
            }

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

    /**
     * 段落兜底内「段内句级找回」的最低相似度。
     *
     * 比第 4 级（0.85）宽松是有意的：走到这里时英文段落已**精确**命中，候选句
     * 被约束在本段之内，误配面远小于章内盲扫；而第 4 级同阈值扫描过全章都没
     * 命中，用同阈值在段内重扫是纯冗余。0.60 是首刀值——点击句与本段某句
     * token 重叠过半即认为找到了对应句，错配最坏也就是展示邻句译文（段落
     * 上下文仍在「整句对照」展开里），比一刀切整段更有用。
     */
    const val PARAGRAPH_RECOVERY_MIN_SIMILARITY = 0.60

    /**
     * 段落兜底内「释义找回」的最低词级置信度（[WordAligner.align] 的置信度）。
     *
     * WordAligner 的 DICTIONARY 置信度下限是 0.65（位置惩罚封顶 0.35），所以
     * 0.70 实际排除的是「释义词在中文句里的相对位置与英文词明显错位」的命中
     * （|Δpos| > 0.857）——释义词本身出现几乎总是能过 0.65，这道门槛挡不住
     * 常用词的义项噪声，真正的防错标靠三点：段落已精确命中（候选被约束在本
     * 段内）、pair 自身置信度门槛、以及重叠证据优先于释义证据。
     */
    const val PARAGRAPH_RECOVERY_MIN_SENSE_CONFIDENCE = 0.70f

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = Regex("[.,!?;:，。！？；：…、·•]+")
    private val NON_TOKEN = Regex("[^a-z0-9']+")

    /** 一次性查询：建索引后立刻查。频繁点词请复用 [TranslationMemoryIndex]。 */
    fun lookup(
        memory: TranslationMemory,
        chapterIndex: Int,
        sentence: String,
        paragraph: String,
        enWord: String = "",
        enWordOffset: Int = -1,
        senseCandidates: () -> List<String> = { emptyList() }
    ): TranslationLookupResult? =
        TranslationMemoryIndex(memory).lookup(
            chapterIndex, sentence, paragraph, enWord, enWordOffset, senseCandidates
        )

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
