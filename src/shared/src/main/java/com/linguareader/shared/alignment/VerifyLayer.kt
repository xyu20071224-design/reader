package com.linguareader.shared.alignment

import kotlin.math.ln
import kotlin.math.min

/**
 * 独立验证层（设计 §8）。**这一层只回答「这一对是不是同一句」，绝不换选、绝不改配对。**
 *
 * 三条通道的理论分工：
 *
 * | 通道 | 信号 | 独立失效模式 | 可单独批准 ALIGNED |
 * | --- | --- | --- | --- |
 * | V1 词典反查 | 英文内容词的中文候选在中文句里的 IDF 加权命中 | 词典缺词、译文意译 | 是 |
 * | V2 话语标记 | 句末类型 / 引语 / 破折号的类别一致 | 纯陈述句（无判别力） | 是（限非合并、非收紧区） |
 * | V3 记忆唯一性 | 全书唯一数字 / 拉丁串（见 [AnchorLayer]） | 无此锚点的书 | 是（硬钉） |
 *
 * ## 为什么 V1 不是「查一次算一次」
 *
 * 逐句查词典会让 `MeaningProvider` 的调用量变成 O(句数 × 词数)。这里改成
 * **一次建桥 + 一遍扫描**（设计 §8.2）：只对全书词频 Top-K 的词建桥（K 与句子数无关），
 * 再把所有候选词条拆成 2/3/4-gram 存进哈希集合，对中文正文做一次单遍滚动扫描。
 * 中文 n-gram 用**滚动哈希 + 命中后逐字校验**（防哈希碰撞）。
 */
internal class VerifyLayer(
    private val source: BookSide,
    private val target: BookSide,
    private val density: DensityModel,
    private val meaning: MeaningProvider?
) {
    /** 每句英文：`terms(E)` 的全局词条下标（升序）。 */
    private var sentenceTerms: Array<IntArray> = emptyArray()

    /** 每句中文：命中的全局词条下标（升序）。 */
    private var sentenceHits: Array<IntArray> = emptyArray()

    /** 每句中文：**单字**命中的全局词条下标（升序）。与 [sentenceHits] 分开，打分时打折。 */
    private var sentenceSingleHits: Array<IntArray> = emptyArray()

    /** 单字 → 它作为词条的下标（只收多字词条里出现过的、且非虚字的）。 */
    private var singleCharTerms: Map<Char, Int> = emptyMap()

    /** 词条的 idf 与 df。 */
    private var termIdf: DoubleArray = DoubleArray(0)
    private var termDf: IntArray = IntArray(0)

    /** 中文有效句数（idf 的 N）。 */
    private var targetSentenceCount: Int = 0

    /** 每句的 nullRecall 分子（供 lift 用）。 */
    private var nullRecall: DoubleArray = DoubleArray(0)

    /** V1 是否可用（词典存在且桥非空）。 */
    var lexicalAvailable: Boolean = false
        private set

    /** 繁简归一的覆盖率（写进 notes，用于下调 recall 阈值，§10.5）。 */
    var traditionalCoverage: Double = 0.0
        private set

    /** recall 阈值的本书修正系数（繁体书下调，§10.5）。 */
    var recallThresholdFactor: Double = 1.0
        private set

    val notes: MutableList<String> = ArrayList()

    // ──────────────────────────────────────────────────────────────

    fun build() {
        targetSentenceCount = (0 until target.sentCount).count { target.sentLangUnit[it] }
        measureTraditionalCoverage()
        if (meaning == null) {
            notes += "no meaning provider: lexical channel unavailable"
            lexicalAvailable = false
            return
        }

        // ── 1. 选词（全书英文词频 Top-K，去功能词）──────────────────
        val frequency = HashMap<Int, Int>(8192)
        for (s in 0 until source.sentCount) {
            for (hash in source.sentWordHashes[s]) frequency.merge(hash, 1, Int::plus)
        }
        // 「表面形 → 哈希」一次建表（只用于把 Top-K 哈希还原成可查词典的字符串）。
        val surface = HashMap<Int, String>(Math.min(frequency.size, AlignTuning.LEXICON_TOP_K * 4))
        for (s in 0 until source.sentCount) {
            val text = source.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && isWordContinuation(text[j])) j++
                    if (frequency.containsKey(TextScan.hashLowerAscii(text, i, j))) {
                        surface.putIfAbsent(TextScan.hashLowerAscii(text, i, j), text.substring(i, j))
                    }
                    i = j
                } else i++
            }
        }
        val ranked = frequency.entries
            .filter { it.value >= AlignTuning.LEXICON_MIN_FREQUENCY && it.key !in FUNCTION_WORDS }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(AlignTuning.LEXICON_TOP_K)

        // ── 2. 建桥（词 → 中文候选词条）─────────────────────────────
        //
        // **修复 A**：`terms(E)` 只保留内容词。判据是「该词在本书的 df 不超过
        // 总句数的 2%」——功能词的释义在长文本里几乎必然假命中（`said → 上述的`），
        // 而且会把 recall 的分子分母都污染（实测真实书 recall 中位数 = 0.000）。
        val contentWordDfLimit = (AlignTuning.CONTENT_WORD_DF_RATIO * source.sentCount)
            .coerceAtLeast(AlignTuning.CONTENT_WORD_DF_MIN_LIMIT.toDouble())
        var excludedByFrequency = 0
        var excludedMetaGloss = 0
        val termIndex = HashMap<String, Int>(Math.min(ranked.size * 3, 20_000))
        val termTexts = ArrayList<String>(ranked.size * 3)
        val wordToTerms = HashMap<Int, IntArray>(ranked.size * 2)
        for (entry in ranked) {
            // 修复 A-1：功能词排除（按该词在**英文句**里的 df，即出现句数）
            if (entry.value > contentWordDfLimit) {
                excludedByFrequency++
                continue
            }
            val word = surface[entry.key] ?: continue
            val candidates = meaning.chineseCandidates(word)
            if (candidates.isEmpty()) continue
            val indices = IntBag(candidates.size)
            for (candidate in candidates) {
                val cleaned = cleanTerm(candidate) ?: continue
                // 修复 A-2：元信息释义排除（`became → 的过去式` 不是释义）
                if (isMetaGloss(cleaned)) {
                    excludedMetaGloss++
                    continue
                }
                val index = termIndex.getOrPut(cleaned) {
                    termTexts.add(cleaned)
                    termTexts.size - 1
                }
                indices.add(index)
            }
            if (!indices.isEmpty()) {
                wordToTerms[entry.key] = indices.toSortedUnique()
                // 词表大小上限：桥本身就是 Top-K 的产物，这里只是防极端输入。
                if (termTexts.size > AlignTuning.LEXICON_MAX_TERMS) break
            }
        }
        if (termTexts.isEmpty()) {
            notes += "lexicon empty: provider returned nothing usable"
            lexicalAvailable = false
            return
        }

        // ── 3. n-gram 集合（2/3/4-gram，滚动哈希进 HashSet<Long>）─────
        val gramSet = HashSet<Long>(termTexts.size * 3)
        for (term in termTexts) {
            val n = term.length
            for (from in 0..n - AlignTuning.TERM_MIN_CHARS) {
                for (len in AlignTuning.TERM_MIN_CHARS..min(AlignTuning.TERM_MAX_CHARS, n - from)) {
                    gramSet.add(packGram(len, hashChars(term, from, from + len)))
                }
            }
        }

        // ── 4. 一遍扫描中文正文，统计命中与 document frequency ────────
        val df = IntArray(termTexts.size)
        val hitsBySentence = arrayOfNulls<IntArray>(target.sentCount)
        val singleHitsBySentence = arrayOfNulls<IntArray>(target.sentCount)

        // 单字词条表：汉字 → 词条下标。**只收多字词条里出现过的字**（不是全部汉字），
        // 且压在虚字黑名单外——否则「的」「了」会在每句命中。
        val singleCharTerms: Map<Char, Int> = buildSingleCharTerms(termTexts)

        val termLengthByIndex = IntArray(termTexts.size) { termTexts[it].length }
        // 词条 → 在扫描时用「命中字符串的哈希」反查下标（碰撞由逐字校验排除）。
        val gramToTerm = HashMap<Long, IntArray>(termTexts.size * 2)
        for (index in termTexts.indices) {
            val term = termTexts[index]
            val n = term.length
            for (from in 0..n - AlignTuning.TERM_MIN_CHARS) {
                for (len in AlignTuning.TERM_MIN_CHARS..min(AlignTuning.TERM_MAX_CHARS, n - from)) {
                    val key = packGram(len, hashChars(term, from, from + len))
                    val existing = gramToTerm[key]
                    gramToTerm[key] = if (existing == null) intArrayOf(index)
                    else existing + index
                }
            }
        }
        var totalHits = 0
        for (s in 0 until target.sentCount) {
            if (!target.sentLangUnit[s]) { hitsBySentence[s] = Bags.EMPTY_INT; continue }
            val text = target.sentNormText[s]
            val bag = IntBag(8)
            val seen = HashSet<Int>(8)
            var i = 0
            while (i < text.length) {
                if (!TextScan.isCjk(text[i])) { i++; continue }
                var matched = false
                var len = min(AlignTuning.TERM_MAX_CHARS, text.length - i)
                while (len >= AlignTuning.TERM_MIN_CHARS) {
                    if (allCjk(text, i, i + len)) {
                        val key = packGram(len, hashChars(text, i, i + len))
                        val candidates = gramToTerm[key]
                        if (candidates != null) {
                            for (index in candidates) {
                                if (termLengthByIndex[index] == len &&
                                    text.regionMatches(i, termTexts[index], 0, len)
                                ) {
                                    if (seen.add(index)) bag.add(index)
                                    matched = true
                                }
                            }
                        }
                    }
                    len--
                }
                i++
                if (!matched) continue
            }
            // ── 单字命中（修复 1）：单独收集，打分时打折 + 封顶 ──────────
            val singleBag = IntBag(4)
            if (AlignTuning.SINGLE_CHAR_TERM_ENABLED && singleCharTerms.isNotEmpty()) {
                for (c in text) {
                    if (!TextScan.isCjk(c)) continue
                    val index = singleCharTerms[c] ?: continue
                    singleBag.add(index)
                }
            }
            val array = bag.toSortedUnique()
            val singles = singleBag.toSortedUnique()
            hitsBySentence[s] = array
            singleHitsBySentence[s] = singles
            totalHits += array.size + singles.size
            for (index in array) df[index]++
            for (index in singles) df[index]++
        }
        if (totalHits == 0) {
            notes += "lexicon bridge: no term hit any Chinese sentence (bridge may be misaligned)"
        }

        sentenceHits = Array(target.sentCount) { hitsBySentence[it] ?: Bags.EMPTY_INT }
        sentenceSingleHits = Array(target.sentCount) { singleHitsBySentence[it] ?: Bags.EMPTY_INT }

        // ── 5. IDF（丢弃判别力为零的高频词条）────────────────────────
        val n = targetSentenceCount.coerceAtLeast(1)
        val keep = BooleanArray(termTexts.size)
        val idfArray = DoubleArray(termTexts.size)
        var kept = 0
        // 单字词条集合：它们天然高频（常用汉字几乎每句都有），**不能套用 df 比例过滤**
        // （0.15·N 会把它们全部删光，实测单字匹配因此几乎不生效）。改用「按字频取前 N」。
        val singleCharIndex = HashSet<Int>(Math.min(singleCharTerms.size, 512))
        for (index in singleCharTerms.values) singleCharIndex.add(index)
        for (index in termTexts.indices) {
            if (df[index] == 0) continue
            val isSingleChar = index in singleCharIndex
            if (!isSingleChar) {
                // 比例上限 + 绝对豁免：短书上 0.15·N 会把所有词条都丢掉。
                if (df[index] > AlignTuning.LEXICON_DF_RATIO * n &&
                    df[index] > AlignTuning.LEXICON_DF_MIN_ALLOWANCE
                ) continue
            }
            idfArray[index] = ln((1.0 + n) / (1.0 + df[index]))
            keep[index] = true
            kept++
        }
        // 单字词条按 df 升序（越罕见越有判别力）取前 SINGLE_CHAR_MAX_TERMS 个，其余丢弃。
        if (singleCharIndex.size > AlignTuning.SINGLE_CHAR_MAX_TERMS) {
            val rankedSingles = singleCharIndex
                .filter { keep[it] }
                .sortedBy { df[it] }
            val dropped = rankedSingles.drop(AlignTuning.SINGLE_CHAR_MAX_TERMS).toSet()
            for (index in dropped) {
                keep[index] = false
                kept--
            }
        }
        if (kept == 0) {
            notes += "lexicon bridge: every term was dropped by the df filter"
            lexicalAvailable = false
            return
        }
        termIdf = idfArray
        termDf = df

        // ── 6. 英文句 → terms（词条下标）──────────────────────────────
        val termsBySentence = Array(source.sentCount) { Bags.EMPTY_INT }
        for (s in 0 until source.sentCount) {
            val bag = IntBag(8)
            for (word in source.sentWordHashes[s]) {
                val indices = wordToTerms[word] ?: continue
                for (index in indices) if (keep[index]) bag.add(index)
            }
            termsBySentence[s] = bag.toSortedUnique()
        }
        sentenceTerms = termsBySentence
        wordToTermIndex = wordToTerms
        // 可查词比例的分母：本句**全部**内容词（不止词典查得到的那些）。
        // 功能词要剔除，否则分母会被 the/of/and 撑大、可查率永远偏低。
        sentenceContentWords = Array(source.sentCount) { s ->
            val bag = IntBag(source.sentWordHashes[s].size)
            for (word in source.sentWordHashes[s]) {
                if (word !in FUNCTION_WORDS) bag.add(word)
            }
            bag.toSortedUnique()
        }

        // ── 7. nullRecall 的分子部分（本书的零模型）───────────────────
        val nullScores = DoubleArray(source.sentCount)
        for (s in 0 until source.sentCount) {
            val terms = termsBySentence[s]
            if (terms.isEmpty()) continue
            var weighted = 0.0
            var total = 0.0
            for (index in terms) {
                val weight = idfArray[index]
                total += weight
                weighted += weight * df[index].toDouble() / n
            }
            nullScores[s] = if (total > 0) weighted / total else 0.0
        }
        nullRecall = nullScores

        lexicalAvailable = true
        notes += "lexicon bridge: words=" + ranked.size + " terms=" + kept +
            " hits=" + totalHits + " n=" + n
        if (traditionalCoverage > 0.0) {
            notes += "traditional chinese: norm coverage=" + round(traditionalCoverage)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  V1 三向打分（§8.2）
    // ──────────────────────────────────────────────────────────────

    /** `terms(E)` 是否非空（V1 的「可用」判据，不是「失败」判据）。 */
    fun termsAvailable(sourceSentence: Int): Boolean =
        lexicalAvailable && sourceSentence in sentenceTerms.indices &&
            sentenceTerms[sourceSentence].isNotEmpty()

    /**
     * 合并组护栏：span 内**每句英文**在中文 span 里命中的内容词条数。
     *
     * 返回最小值（最弱那句的命中数）。组级 recall 通过后用它防「靠一句撑起整组」：
     * 3 句英文共享 1 句中文时，若其中 2 句一个词都没命中，那它们被报 ALIGNED 就是错标。
     *
     * @return span 内每句英文的命中词条数的最小值；`span = 1` 时返回该句的命中数
     */
    fun minHitsPerSourceSentence(sourceFrom: Int, spanEn: Int, targetFrom: Int, spanZh: Int): Int {
        if (!lexicalAvailable) return 0
        val hits = unionTerms(sentenceHits, targetFrom, spanZh)
        if (hits.isEmpty()) return 0
        var minimum = Int.MAX_VALUE
        for (k in 0 until spanEn) {
            val sentence = sourceFrom + k
            if (sentence !in sentenceTerms.indices) continue
            var count = 0
            for (index in sentenceTerms[sentence]) {
                if (hits.binarySearch(index) >= 0) count++
            }
            if (count < minimum) minimum = count
        }
        return if (minimum == Int.MAX_VALUE) 0 else minimum
    }

    /**
     * **可查词比例**：本句英文词里「词典查得到候选」的比例（Lead 裁定的护栏）。
     *
     * 为什么需要它：词典桥只覆盖本书词频 Top-K，实测一本合成书的可查率中位数只有
     * **38.5%**——一个句子平均只有 4 成内容词能参与验证。那种情况下 `recall = 100%`
     * 只证明「能查的 4 成都命中了」，不足以承担「这条对照是对的」这个断言。
     *
     * 返回值 ∈ [0,1]；无英文词时返回 0（没有内容可核 → 不能当强证据）。
     */
    fun coverableRatio(sourceSentence: Int): Double {
        if (!lexicalAvailable) return 0.0
        val words = sentenceContentWords[sourceSentence]
        if (words.isEmpty()) return 0.0
        var coverable = 0
        for (word in words) if (wordToTermIndex[word] != null) coverable++
        return coverable.toDouble() / words.size
    }

    /** 本句的内容英文词（`lowercase` 哈希，已升序去重）。 */
    private var sentenceContentWords: Array<IntArray> = emptyArray()

    /** 英文词哈希 → 它是否有可用的中文候选词条。 */
    private var wordToTermIndex: Map<Int, IntArray> = emptyMap()

    /**
     * V1 三向打分：`recall` / `precision` / `lift`（§8.2）。
     *
     * 合并对（span > 1）的 `terms(E)` 取并集、`hits(C)` 取并集。
     */
    fun score(sourceFrom: Int, spanEn: Int, targetFrom: Int, spanZh: Int): LexicalScore {
        if (!lexicalAvailable) return LexicalScore.UNAVAILABLE
        val terms = unionTerms(sentenceTerms, sourceFrom, spanEn)
        if (terms.isEmpty()) return LexicalScore.UNAVAILABLE
        val hits = unionTerms(sentenceHits, targetFrom, spanZh)
        val singleHits = unionTerms(sentenceSingleHits, targetFrom, spanZh)

        var totalIdf = 0.0
        var matchedIdf = 0.0
        var singleMatchedIdf = 0.0
        for (index in terms) {
            val weight = termIdf[index]
            totalIdf += weight
            when {
                hits.binarySearch(index) >= 0 -> matchedIdf += weight
                // 单字命中：打折计入（护栏 2），且单独累计以便封顶（护栏 3）
                singleHits.binarySearch(index) >= 0 ->
                    singleMatchedIdf += weight * AlignTuning.SINGLE_CHAR_TERM_WEIGHT
            }
        }
        // 护栏 3：单字对 recall 的贡献封顶，防「一堆单字凑命中」把长句撑过门槛。
        val singleCap = AlignTuning.SINGLE_CHAR_RECALL_CAP * totalIdf
        if (singleMatchedIdf > singleCap) singleMatchedIdf = singleCap
        val recall = if (totalIdf > 0) (matchedIdf + singleMatchedIdf) / totalIdf else 0.0

        var hitsIdf = 0.0
        for (index in hits) hitsIdf += termIdf[index]
        for (index in singleHits) hitsIdf += termIdf[index] * AlignTuning.SINGLE_CHAR_TERM_WEIGHT
        val precision = if (hitsIdf > 0) (matchedIdf + singleMatchedIdf) / hitsIdf else 0.0

        var nullWeighted = 0.0
        var nullTotal = 0.0
        val n = targetSentenceCount.coerceAtLeast(1)
        for (index in terms) {
            val weight = termIdf[index]
            nullTotal += weight
            nullWeighted += weight * termDf[index].toDouble() / n
        }
        val expected = if (nullTotal > 0) nullWeighted / nullTotal else 0.0
        val lift = recall - expected

        return LexicalScore(recall, precision, lift, true)
    }

    // ──────────────────────────────────────────────────────────────
    //  V2 话语标记（§8.3）
    // ──────────────────────────────────────────────────────────────

    /** [DiscourseScore.witness]：只有「有内容地一致」才算见证（§8.3）。 */
    fun discourse(sourceFrom: Int, spanEn: Int, targetFrom: Int, spanZh: Int): DiscourseScore {
        // 合并对不做 V2 见证（G5：合并对必须有词典或硬钉支撑）
        val merged = spanEn > 1 || spanZh > 1
        val sourceTerm = terminatorOf(source.sentTerm, sourceFrom, spanEn)
        val targetTerm = terminatorOf(target.sentTerm, targetFrom, spanZh)
        val sourceFlags = flagsOf(source.sentFlags, sourceFrom, spanEn)
        val targetFlags = flagsOf(target.sentFlags, targetFrom, spanZh)
        val sourceQuoted = (sourceFlags and SentFlags.QUOTED) != 0
        val targetQuoted = (targetFlags and SentFlags.QUOTED) != 0
        val sourceDashed = (sourceFlags and SentFlags.DASHED) != 0
        val targetDashed = (targetFlags and SentFlags.DASHED) != 0

        val terminatorWitness = !merged &&
            sourceTerm.toInt() == targetTerm.toInt() &&
            sourceTerm.toInt() in WITNESS_TERMINATORS
        val quotedWitness = !merged && sourceQuoted && targetQuoted
        val dashedWitness = !merged && sourceDashed && targetDashed

        // ── 矛盾：只在「英文有该标记、中文丢了」这个方向上算 ──────────
        //
        // 为什么**不对称**：中文的引号/书名号/破折号用法比英文宽得多（「」既可以是引语，
        // 也可以是专名、术语、书名、强调）。若两侧对等比较，一本中文书里大量「」/《》
        // 会让每一次配对都报「引语矛盾」——实测中这才是覆盖率塌到 0 的主因（合成语料的
        // 中文编码就写在「」里，于是 69 句全部被判矛盾）。
        //
        // 英文是原文、是「这句是不是对话」的权威：英文有引语而中文没有 → 译文确实丢了
        // 对话标记（矛盾）；反之只是中文排版习惯（不算矛盾，也不算见证）。
        // 句末类型仍是对称比较：疑问↔陈述在两个方向上都说明「不是同一句」。
        val terminatorConflict = (sourceTerm.toInt() in WITNESS_TERMINATORS &&
            targetTerm.toInt() == TermClass.STATEMENT) ||
            (targetTerm.toInt() in WITNESS_TERMINATORS &&
                sourceTerm.toInt() == TermClass.STATEMENT)
        val quoteConflict = sourceQuoted && !targetQuoted
        val dashConflict = sourceDashed && !targetDashed

        return DiscourseScore(
            witness = terminatorWitness || quotedWitness || dashedWitness,
            conflict = terminatorConflict || quoteConflict || dashConflict
        )
    }

    // ──────────────────────────────────────────────────────────────

    private fun measureTraditionalCoverage() {
        var total = 0L
        var replaced = 0L
        for (s in 0 until target.sentCount) {
            val original = target.sentText[s]
            val normalized = if (s in target.sentNormText.indices) target.sentNormText[s] else original
            for (index in original.indices) {
                val c = original[index]
                if (!TextScan.isCjk(c)) continue
                total++
                if (index < normalized.length && normalized[index] != c) replaced++
            }
        }
        traditionalCoverage = if (total <= 0) 0.0 else replaced.toDouble() / total
        // 表不全会低估 recall，所以只下调 recall 阈值，**不降低 precision**（§10.5）。
        if (traditionalCoverage > 0.05) {
            val relief = AlignTuning.TRADITIONAL_RECALL_RELIEF *
                min(1.0, traditionalCoverage / AlignTuning.TRADITIONAL_COVERAGE_PIVOT)
            recallThresholdFactor = 1.0 - relief
        }
    }

    /**
     * 构建**单字词条表**（修复 1 的核心）：汉字 → 词条下标。
     *
     * ## 为什么需要单字
     *
     * 文学/半文言译本大量用单字词（`戒`、`堂`、`殿`、`天`），而 ECDICT 释义是 2–4 字
     * 现代短语（`rings → 吊环`）。只做 2–4 字匹配时，真实书上的 `recall` 恒为 0
     * （实测魔戒 p50 = p90 = 0.000）。
     *
     * ## 三重护栏（否则误命中爆炸）
     *
     * 1. **只收「多字词条里出现过的字」**：不是任意汉字都能当词条，必须是某条 ECDICT
     *    释义的组成字——这天然限制了范围；
     * 2. **虚字黑名单**：的/了/是/在/和/也… 每句都有，命中零信息量；
     * 3. **一个下标只挂一个字**：避免同一字因多个词条而重复计权。
     */
    private fun buildSingleCharTerms(termTexts: List<String>): Map<Char, Int> {        if (!AlignTuning.SINGLE_CHAR_TERM_ENABLED) return emptyMap()
        val result = HashMap<Char, Int>(256)
        for (index in termTexts.indices) {
            val term = termTexts[index]
            if (term.length != AlignTuning.TERM_MIN_CHARS) continue // 只从 2 字词条取字
            for (c in term) {
                if (c in SINGLE_CHAR_STOP_WORDS) continue
                result.putIfAbsent(c, index)
            }
        }
        return result
    }

    /**
     * 是否**元信息释义**（修复 A-2）：ECDICT 对词形变化只给「的过去式」「的复数」这类
     * 语法标注，而不是释义。
     *
     * 实证污染源：`became → 的过去式`、`errors → 的复数`、`was → 的过去式`——
     * 全是高频词，且在译文里必然假命中（「的过去式」这几个字不会出现在小说正文里，
     * 但「过去式」这类 2–4 字片段可能撞到）。丢弃它们零风险。
     */
    private fun isMetaGloss(term: String): Boolean {
        for (pattern in AlignTuning.META_GLOSS_PATTERNS) {
            if (term == pattern || term.endsWith(pattern)) return true
        }
        return false
    }

    private fun cleanTerm(candidate: String): String? {
        if (candidate.isEmpty()) return null
        var cjk = 0
        for (c in candidate) {
            when {
                TextScan.isCjk(c) -> cjk++
                c.isWhitespace() -> Unit
                else -> return null
            }
        }
        if (cjk < AlignTuning.TERM_MIN_CHARS || cjk > AlignTuning.TERM_MAX_CHARS) return null
        return candidate.filter { TextScan.isCjk(it) }
    }

    private fun unionTerms(table: Array<IntArray>, from: Int, span: Int): IntArray {
        if (span == 1) return table[from]
        val bag = IntBag(16)
        for (k in 0 until span) for (index in table[from + k]) bag.add(index)
        return bag.toSortedUnique()
    }

    private fun flagsOf(table: IntArray, from: Int, span: Int): Int {
        var flags = 0
        for (k in 0 until span) {
            val index = from + k
            if (index in table.indices) flags = flags or table[index]
        }
        return flags
    }

    /** 合并取最后一个非 NONE 的句末类型（`0:1` 不参与 V2，所以实际 span = 1）。 */
    private fun terminatorOf(table: ByteArray, from: Int, span: Int): Byte {
        for (k in span - 1 downTo 0) {
            val index = from + k
            if (index in table.indices && table[index].toInt() != TermClass.NONE) return table[index]
        }
        return TermClass.NONE.toByte()
    }

    private fun allCjk(text: String, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            if (!TextScan.isCjk(text[i])) return false
            i++
        }
        return true
    }

    private fun hashChars(s: String, from: Int, to: Int): Int = TextScan.hash(s, from, to)

    private fun packGram(len: Int, hash: Int): Long =
        (len.toLong() shl 32) or (hash.toLong() and 0xFFFFFFFFL)

    private fun round(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    private fun isWordContinuation(c: Char): Boolean =
        TextScan.isLetter(c) || TextScan.isDigit(c) || c == '\'' || c == '-'

    companion object {
        /**
         * 单字词条的**虚字黑名单**：这些字在中文里几乎每句都出现，命中零判别力。
         *
         * 与 `MeaningPhraseParser` 的虚词思路一致，但作用在**单字**层面——
         * 单字匹配必须比多字匹配严格得多，否则「的」「了」会把任何句子拉过门槛。
         */
        private val SINGLE_CHAR_STOP_WORDS: Set<Char> = (
            "的了是在和也不我他她它你这那有就都而及与被把从对为以之其于所则而且或" +
                "但很更最太又还再才只已曾将要会能可须应得着过们个么什怎样些种次点儿" +
                "上下中里外前后左右大小多少几第每各某本件条只张片块些个来去去到"
            ).toSet()

        /** 能构成 V2 见证的句末类型（STATEMENT↔STATEMENT **不算**，§8.3）。 */
        private val WITNESS_TERMINATORS = setOf(
            TermClass.QUESTION, TermClass.EXCLAMATION, TermClass.ELLIPSIS
        )

        /**
         * 通用功能词（英语语言常识，不是从本书拍出来的常量）。
         *
         * 它们的中文候选（的/是/了/在/和…）几乎出现在每一句里，作为锚点只贡献噪声。
         * 这是设计 §8.2 第 1 步「去掉约 120 个通用功能词」的落地——因为是语言常识，
         * 不受「阈值必须来自本书」那条约束。
         */
        private val FUNCTION_WORDS: Set<Int> = arrayOf(
            "the", "a", "an", "of", "and", "to", "in", "is", "was", "were", "are", "be", "been",
            "being", "it", "its", "he", "she", "they", "them", "his", "her", "their", "you", "your",
            "i", "my", "me", "we", "our", "us", "that", "this", "these", "those", "there", "here",
            "for", "on", "at", "by", "with", "from", "as", "but", "or", "if", "not", "no", "yes",
            "do", "does", "did", "have", "has", "had", "will", "would", "could", "should", "can",
            "may", "might", "must", "shall", "so", "than", "then", "when", "where", "what", "which",
            "who", "whom", "whose", "how", "why", "all", "any", "some", "one", "two", "up", "down",
            "out", "into", "over", "under", "about", "after", "before", "again", "more", "most",
            "very", "just", "now", "only", "also", "too", "such", "own", "same", "other", "each",
            "every", "both", "few", "many", "much", "said", "says", "say"
        ).mapTo(HashSet()) { hash(it) }

        private fun hash(word: String): Int = TextScan.hashLowerAscii(word, 0, word.length)
    }
}

/** V1 的三向打分（§8.2）。`available = false` 表示 `terms(E) = ∅`（不可用，不是失败）。 */
internal class LexicalScore(
    val recall: Double,
    val precision: Double,
    val lift: Double,
    val available: Boolean
) {
    companion object {
        val UNAVAILABLE = LexicalScore(0.0, 0.0, 0.0, false)
    }
}

/** V2 的结果：见证与矛盾是两件独立的事（§8.3/§8.4）。 */
internal class DiscourseScore(val witness: Boolean, val conflict: Boolean)
