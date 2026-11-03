package com.linguareader.shared.alignment

import kotlin.math.ln

/**
 * **AI 译本对齐引擎的锚点/见证索引**（`ai-pair-v1` 专用）。
 *
 * ## 与 `AnchorLayer`（`independent-v1` 用）的区别
 *
 * | | `AnchorLayer` | 本类 |
 * | --- | --- | --- |
 * | 目的 | **切块 / 选路**（把搜索空间切小） | **验证**（一个已给出的配对是否可信） |
 * | 硬钉 | LIS + 一致性剪枝 + 矩形切块 | **不做切块**（不需要，段内规模极小） |
 * | 译法唯一性 | 靠「全书出现次数 ≤ 3」 | **靠「全书精确命中串唯一」** |
 *
 * 为什么 `independent-v1` 的锚点定义在 AI 译本上也要重写：
 * 那边要求「键在两侧都出现」，而 `Frodo` 在人工译本中文侧根本不存在。
 * AI 译本里 `Tom Parker` → 「汤姆·帕克」是**音译**，键不同源，必须用
 * `Transliteration` 的候选表匹配。数字则两侧都是阿拉伯数字，可以直接比。
 *
 * ## 本类的两个用途
 *
 * 1. **数字锚点**：英文句里的数字必须在配对的中文句里出现（prompt 要求原样保留）。
 * 2. **专名锚点**：英文句里的专名，其音译候选必须出现在配对的中文句里，
 *    且该专名**全书译法唯一**（`Tom` 若既译「汤姆」又译「托姆」就不能做锚点）。
 * 3. **词典见证**：给 `vLexical` 打分（沿用 `VerifyLayer` 的 IDF 加权口径，
 *    但用更低的门槛——段内场景结构已由构造背书）。
 */
internal class AiAnchorIndex(
    private val source: BookSide,
    private val target: BookSide,
    private val density: DensityModel,
    private val meaning: MeaningProvider?
) {
    val notes: MutableList<String> = ArrayList()

    /** 每句英文的数字键（已升序去重，来自 `BookSide.sentDigits`）。 */
    private var sourceDigits: Array<IntArray> = emptyArray()

    /** 每句中文的数字键。 */
    private var targetDigits: Array<IntArray> = emptyArray()

    /** 每句英文抽到的专名（已过滤候选非空）。 */
    private var sourceNames: Array<List<String>> = emptyArray()

    /** 可做锚点的专名（全书精确命中串唯一）→ 唯一中文形。 */
    private var uniqueNameForm: Map<String, String> = emptyMap()

    /** 中文句的繁→简归一缓存（惰性）。 */
    private var zhSimplified: Array<String?> = emptyArray()

    /** 词条 IDF（词典见证用）。 */
    private var termIdf: Map<Int, Double> = emptyMap()

    /** 每句英文的内容词条下标。 */
    private var sourceTerms: Array<IntArray> = emptyArray()

    /** 每句中文命中的词条下标。 */
    private var targetTermHits: Array<IntArray> = emptyArray()

    fun build() {
        sourceDigits = Array(source.sentCount) { source.sentDigits[it] }
        targetDigits = Array(target.sentCount) { target.sentDigits[it] }
        sourceNames = Array(source.sentCount) { sentence ->
            Transliteration.extractNames(source.sentText[sentence])
                .filter { Transliteration.candidates(it).isNotEmpty() }
        }
        zhSimplified = arrayOfNulls(target.sentCount)
        buildNameForms()
        buildLexical()
        notes += "ai-anchor: names=${sourceNames.sumOf { it.size }} " +
            "unique-name-forms=${uniqueNameForm.size} " +
            "lexical-terms=${termIdf.size}"
    }

    // ──────────────────────────────────────────────────────────────
    //  专名锚点：全书译法唯一
    // ──────────────────────────────────────────────────────────────

    /**
     * 建立「专名 → 唯一中文形」。
     *
     * 判据：该英文名的音译候选里，**只有一个**在全书中文正文里出现过。
     * 出现多个 → 译法不唯一，不能做锚点（`Tom` 若既译「汤姆」又译「托姆」就出局）。
     */
    private fun buildNameForms() {
        val distinct = LinkedHashSet<String>(256)
        for (names in sourceNames) distinct.addAll(names)
        if (distinct.isEmpty()) return
        val forms = HashMap<String, MutableSet<String>>(distinct.size * 2)
        for (name in distinct) {
            val candidates = Transliteration.candidates(name)
            if (candidates.isEmpty()) continue
            val appeared = LinkedHashSet<String>(2)
            for (t in 0 until target.sentCount) {
                val text = simplified(t)
                if (text.isEmpty()) continue
                for (candidate in candidates) {
                    if (text.contains(candidate)) {
                        appeared.add(candidate)
                        break
                    }
                }
                if (appeared.size > 1) break // 已不唯一，提前退出
            }
            if (appeared.size == 1) forms[name] = appeared
        }
        uniqueNameForm = forms.mapValues { it.value.first() }
    }

    private fun simplified(sentence: Int): String {
        zhSimplified[sentence]?.let { return it }
        val text = Transliteration.toSimplified(target.sentText[sentence])
        zhSimplified[sentence] = text
        return text
    }

    /**
     * 专名锚点校验。
     *
     * @return `null` = 该英文句没有可用专名（不做校验）；`true` = 命中；`false` = 未命中。
     */
    fun nameMatches(enSentence: Int, zhSentence: Int, into: MutableList<String>): Boolean? {
        if (uniqueNameForm.isEmpty()) return null
        if (enSentence !in sourceNames.indices) return null
        val names = sourceNames[enSentence].filter { it in uniqueNameForm }
        if (names.isEmpty()) return null
        val text = simplified(zhSentence)
        var anyChecked = false
        var anyHit = false
        for (name in names) {
            val form = uniqueNameForm[name] ?: continue
            anyChecked = true
            if (text.contains(form)) {
                anyHit = true
                into.add("name:$name→$form")
            }
        }
        if (!anyChecked) return null
        return anyHit
    }

    // ──────────────────────────────────────────────────────────────
    //  数字锚点
    // ──────────────────────────────────────────────────────────────

    /**
     * 数字锚点校验。
     *
     * AI 翻译 prompt 要求「数字、年份、编号必须原样保留」，所以两侧都是阿拉伯数字，
     * 可以直接比规范化后的哈希键。**这正是 AI 译本相对人工译本的最大优势之一**
     * （人工译本把 `1987` 写成「一九八七年」，数字锚点全灭）。
     *
     * @return `null` = 该英文句没有数字（不做校验）；`true` = 全部命中；`false` = 有遗漏。
     */
    fun digitsMatch(enSentence: Int, zhSentence: Int, into: MutableList<String>): Boolean? {
        val enDigits = sourceDigits.getOrNull(enSentence) ?: return null
        if (enDigits.isEmpty()) return null
        val zhDigits = targetDigits.getOrNull(zhSentence) ?: Bags.EMPTY_INT
        var allHit = true
        for (key in enDigits) {
            if (zhDigits.binarySearch(key) < 0) {
                allHit = false
            } else {
                into.add("digit:${readableDigit(enSentence, key)}")
            }
        }
        return allHit
    }

    /** 把数字键还原成可读串（只为 evidence 展示）。 */
    private fun readableDigit(sentence: Int, key: Int): String {
        val text = source.sentText[sentence]
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (TextScan.isDigit(c) || TextScan.isFullwidthDigit(c)) {
                var j = i + 1
                while (j < text.length && (TextScan.isDigit(text[j]) ||
                        TextScan.isFullwidthDigit(text[j]) || text[j] == '.' || text[j] == ',')) j++
                if (TextScan.hash(text, i, j) == key) return text.substring(i, j)
                i = j
            } else i++
        }
        return "<digit>"
    }

    // ──────────────────────────────────────────────────────────────
    //  词典见证（沿用 VerifyLayer 的 IDF 口径，但门槛更低）
    // ──────────────────────────────────────────────────────────────

    private fun buildLexical() {
        if (meaning == null) return
        // 选词：英文词频 Top-K，排除功能词与元信息释义（与 VerifyLayer 同口径）
        val frequency = HashMap<Int, Int>(8192)
        for (s in 0 until source.sentCount) {
            for (h in source.sentWordHashes[s]) frequency.merge(h, 1, Int::plus)
        }
        val surface = HashMap<Int, String>(2048)
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            text[j] == '\'' || text[j] == '-')) j++
                    val hash = TextScan.hashLowerAscii(text, i, j)
                    if (frequency.containsKey(hash)) surface.putIfAbsent(hash, text.substring(i, j).lowercase())
                    i = j
                } else i++
            }
        }
        val dfLimit = (AlignTuning.CONTENT_WORD_DF_RATIO * source.sentCount)
            .coerceAtLeast(AlignTuning.CONTENT_WORD_DF_MIN_LIMIT.toDouble())
        val ranked = frequency.entries
            .filter { it.value <= dfLimit }
            .sortedByDescending { it.value }
            .take(AlignTuning.LEXICON_TOP_K)

        val termIndex = HashMap<String, Int>(2048)
        val termTexts = ArrayList<String>(2048)
        val wordToTerms = HashMap<Int, IntArray>(1024)
        for (entry in ranked) {
            val word = surface[entry.key] ?: continue
            val candidates = meaning.chineseCandidates(word)
            if (candidates.isEmpty()) continue
            val bag = IntBag(candidates.size)
            for (candidate in candidates) {
                val cleaned = candidate.filter { TextScan.isCjk(it) }
                if (cleaned.length !in AlignTuning.TERM_MIN_CHARS..AlignTuning.TERM_MAX_CHARS) continue
                if (isMetaGloss(cleaned)) continue
                val index = termIndex.getOrPut(cleaned) {
                    termTexts.add(cleaned)
                    termTexts.size - 1
                }
                bag.add(index)
            }
            if (!bag.isEmpty()) wordToTerms[entry.key] = bag.toSortedUnique()
            if (termTexts.size > AlignTuning.LEXICON_MAX_TERMS) break
        }
        if (termTexts.isEmpty()) return

        // 中文侧扫描：每句命中哪些词条
        val df = IntArray(termTexts.size)
        val hits = Array(target.sentCount) { Bags.EMPTY_INT }
        val n = target.sentCount.coerceAtLeast(1)
        for (t in 0 until target.sentCount) {
            val text = simplified(t)
            if (text.isEmpty()) continue
            val bag = IntBag(8)
            for (index in termTexts.indices) {
                if (text.contains(termTexts[index])) bag.add(index)
            }
            val array = bag.toSortedUnique()
            hits[t] = array
            for (index in array) df[index]++
        }
        // IDF + df 过滤
        val idf = HashMap<Int, Double>(termTexts.size * 2)
        for (index in termTexts.indices) {
            if (df[index] == 0) continue
            if (df[index] > AlignTuning.LEXICON_DF_RATIO * n &&
                df[index] > AlignTuning.LEXICON_DF_MIN_ALLOWANCE
            ) continue
            idf[index] = ln((1.0 + n) / (1.0 + df[index]))
        }
        if (idf.isEmpty()) return
        termIdf = idf
        targetTermHits = hits
        // 英文句 → 词条
        sourceTerms = Array(source.sentCount) { s ->
            val bag = IntBag(8)
            for (word in source.sentWordHashes[s]) {
                val indices = wordToTerms[word] ?: continue
                for (index in indices) if (idf.containsKey(index)) bag.add(index)
            }
            bag.toSortedUnique()
        }
    }

    private fun isMetaGloss(term: String): Boolean {
        for (pattern in AlignTuning.META_GLOSS_PATTERNS) {
            if (term == pattern || term.endsWith(pattern)) return true
        }
        return false
    }

    /**
     * 词典见证分：`recall`（IDF 加权命中率）。
     *
     * 段内场景门槛比全局低（`LEXICAL_WITNESS_MIN = 0.15`），因为**结构已由构造背书**，
     * 词典只用来剔除「明显不是同一句」的情况。
     */
    fun lexicalScore(enSentence: Int, zhSentence: Int): Double {
        if (termIdf.isEmpty()) return 0.0
        val terms = sourceTerms.getOrNull(enSentence) ?: return 0.0
        if (terms.isEmpty()) return 0.0
        val zhHits = targetTermHits.getOrNull(zhSentence) ?: return 0.0
        var total = 0.0
        var matched = 0.0
        for (index in terms) {
            val weight = termIdf[index] ?: continue
            total += weight
            if (zhHits.binarySearch(index) >= 0) matched += weight
        }
        return if (total > 0) matched / total else 0.0
    }
}
