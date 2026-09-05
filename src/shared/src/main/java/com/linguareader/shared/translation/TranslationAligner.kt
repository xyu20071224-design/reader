package com.linguareader.shared.translation

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.math.abs

/**
 * 词义锚点查询：英文词 → 中文释义短语集合（已过滤虚词性、长度 >= 2 的连续汉字）。
 * 由仓库层基于离线词典实现并缓存；对齐器只做哈希查表。
 */
interface MeaningIndex {
    fun phrasesOf(word: String): Set<String>
}

/**
 * 双语文本对齐器（纯 Kotlin，无平台依赖）。
 *
 * 三级对齐，全部走「单调序列 DP」：
 *  1. 章节对齐：按章节整体特征做 1:1 / 1:0 / 0:1 对齐（章节数不一致时允许跳过）。
 *  2. 段落对齐：章节内按叶级段落（与阅读器/TTS 同一选择器）做 DP，允许
 *     1:0 / 0:1 / 1:1 / 2:1 / 1:2（对应省略、合并、拆分）。
 *  3. 句子对齐：对齐段落内用 [SentenceSplitter] 分句后做小规模 DP；V3 起允许
 *     2:1 / 1:2 合并，但必须过 [sentenceMergeAllowed] 三道门槛（语义证据 /
 *     尺寸保护 / 边际收益）。
 *
 * 对齐代价 = 长度比偏差（英文词数 vs 中文有效字符数）− 数字/拉丁专名锚点命中率
 * − 词义锚点加分（V2：ECDICT 释义短语命中）。
 * 置信度 = 1 − 归一化代价，钳制到 [MIN_CONFIDENCE, 1]。
 *
 * **性能上的硬要求**：代价函数在 O(n·m) 的 DP 内层被调用，因此它只允许做算术与
 * 哈希查表，绝不能扫描文本。所有文本特征（词数、字符数、锚点集合、词义短语集、
 * 中文 2–4 字子串集）都在进入 DP 之前按片段预计算一次 —— 早期实现每格都对整段/
 * 整章重新跑正则并 `lowercase()` 拷贝整章文本，真机上整本小说跑 5 分钟以上仍未
 * 结束（单线程 100% CPU）。
 */
object TranslationAligner {

    /** 档案里把多少号对齐器写入 alignerVersion；算法/分句规则变化时必须 +1。 */
    const val VERSION = 5

    /** 词义锚点每次命中的加分上限与单点权重（与「数字/拉丁锚点」同量级、略高）。 */
    private const val MEANING_MAX_HITS = 4
    private const val MEANING_HIT_SCALE = 0.12f

    /**
     * 句级 1:N 合并门槛（V3）：合并必须同时满足
     *  ① 合并对至少有一个词义锚点命中（无语义证据不合并）；
     *  ② 尺寸保护：英文侧词数 ≥2、中文侧字符数 ≥6（标题/超短句禁合并，
     *     「STRIDER」被吸进邻近长句的教训）；
     *  ③ 边际要求：合并代价要比局部最优的 1:1 拆分便宜 [SENTENCE_MERGE_MARGIN]
     *     以上（「本来就配得好就不要合并」，否则正确配对会被合并抢走）。
     * 无 meaning 源时 ① 恒不满足，合并自动禁用（行为保守回退）。
     */
    private const val SENTENCE_MERGE_MIN_EN_WORDS = 2
    private const val SENTENCE_MERGE_MIN_ZH_CHARS = 6
    private const val SENTENCE_MERGE_MARGIN = 0.12

    /** 句级合并对的置信度折扣（类比段级 [MERGED_CONSTITUENT_SCALE]：真配对，但粒度跳）。 */
    private const val SENTENCE_MERGE_SCALE = 0.85f

    /**
     * 句对落盘的长度比硬门槛（V5）：zh 字符数 /（en 词数 × [ZH_CHARS_PER_EN_WORD]）
     * 落在区间外的不落盘，降级为段级条目。100 样本判定拟合：残 <0.45 的样本
     * 判定全 bad（3/3），超 >2.6 只剩 ok2/bad（ok 集最大 2.02，留 0.6 余量）；
     * 置信度门槛管不住它们——锚点/词义加分能把长度比崩坏的错对拉回 0.30 以上。
     * 全书占比：残 1.6% / 超 3.0%。落盘后这些句子走段落兜底（整段译文）。
     */
    private const val SENTENCE_MIN_LENGTH_RATIO = 0.45
    private const val SENTENCE_MAX_LENGTH_RATIO = 2.6

    /**
     * 合并句对（1:N）的比例上限放宽一档：中文引文常被分句规则留成不可再分的
     * 整句（「他說：『你好。』」16 字），与两侧短英文句 2:1 合并后比例 ~2.35
     * 属正常形态；1:1 未合并对无此理由，维持 2.6 一票否决。
     */
    private const val SENTENCE_MAX_MERGED_LENGTH_RATIO = 3.2

    const val MIN_CONFIDENCE = 0.15f
    private const val SKIP_COST = 1.2

    /** 启发式：约 1.7 个中文字符对应 1 个英文单词（用于长度归一）。 */
    private const val ZH_CHARS_PER_EN_WORD = 1.7

    /** 邻近段落兜底的置信度缩放：它只是「大致对应」，必须明显低于真配对。 */
    private const val NEIGHBOUR_CONFIDENCE_SCALE = 0.55

    /** 合并段落的成分条目：是真配对，只是粒度退到段，轻微降权即可。 */
    private const val MERGED_CONSTITUENT_SCALE = 0.85f

    private val ANCHORS = Regex("[0-9]+|[A-Z][a-z]+|[A-Z]{2,}")
    private val LATIN_RUNS = Regex("[A-Za-z0-9]+")
    private val EN_WORDS = Regex("[a-z\'\u2019]+")
    private val HAN_RUNS = Regex("[\\u4e00-\\u9fa5]+")
    private val WHITESPACE = Regex("\\s+")
    private val ZH_SENTENCE_END = Regex("(?<=[。！？；!?;])|(?<=\\n)")

    // DP 回溯用的走法编码（每格 1 字节，避免每格再分配对象）。
    private const val MOVE_NONE: Byte = 0
    private const val MOVE_SKIP_A: Byte = 1
    private const val MOVE_SKIP_B: Byte = 2
    private const val MOVE_ONE_ONE: Byte = 3
    private const val MOVE_TWO_ONE: Byte = 4
    private const val MOVE_ONE_TWO: Byte = 5

    /**
     * @param enChapters 每章 = 段落文本列表（英文原版，叶级段落）
     * @param zhChapters 每章 = 段落文本列表（中文译本，叶级段落）
     * @param meaning 词义锚点查询（可为 null：行为与旧版完全一致）
     */
    fun align(
        enChapters: List<List<String>>,
        zhChapters: List<List<String>>,
        meaning: MeaningIndex? = null
    ): List<AlignedSentencePair> {
        if (enChapters.isEmpty() || zhChapters.isEmpty()) return emptyList()

        // 段落特征每篇只算一次；章节特征由段落特征聚合，不再拼接整章文本。
        val enParagraphSpans = enChapters.map { paragraphs -> paragraphs.map { englishSpan(it, meaning) } }
        val zhParagraphSpans = zhChapters.map { paragraphs -> paragraphs.map { chineseSpan(it) } }

        val result = mutableListOf<AlignedSentencePair>()

        for ((enIdx, zhIdx) in alignChapters(enParagraphSpans, zhParagraphSpans)) {
            val enParagraphs = enChapters[enIdx]
            val zhParagraphs = zhChapters[zhIdx]
            if (enParagraphs.isEmpty() || zhParagraphs.isEmpty()) continue

            val enSpans = enParagraphSpans[enIdx]
            val zhSpans = zhParagraphSpans[zhIdx]

            // 记录哪些英文段落真的配上了，以及它落在哪个中文段落（供邻近兜底用）。
            val zhForEn = HashMap<Int, Int>()

            for (paragraphPair in alignSpans(enSpans, zhSpans, allowMerge = true)) {
                val enParagraph = join(enParagraphs, paragraphPair.a)
                val zhParagraph = join(zhParagraphs, paragraphPair.b)
                if (enParagraph.isBlank() || zhParagraph.isBlank()) continue
                for (index in paragraphPair.a) zhForEn[index] = paragraphPair.b[0]

                val enSentences = SentenceSplitter.split(enParagraph).contentOnly()
                val zhSentences = splitChinese(zhParagraph).contentOnly()
                val enSentenceSpans = enSentences.map { englishSpan(it, meaning) }
                val zhSentenceSpans = zhSentences.map { chineseSpan(it) }

                val sentencePairs =
                    if (enSentences.isEmpty() || zhSentences.isEmpty()) emptyList()
                    else alignSpans(enSentenceSpans, zhSentenceSpans, allowMerge = true, mergeGate = ::sentenceMergeAllowed)

                // V4：低置信句对是 DP 残渣（实测整本魔戒 2% 的句子精确命中这类对：
                // 27 词英文配上「啊！」、70 词配上 16 字）。查询侧 1–3 级是文本精确
                // 命中、不受置信度门槛限制，落盘必被原样展示成「只翻译了其中一句」。
                // 宁可不产，不可错配：低于门槛的句对不落盘，让这些句子走段落级兜底。
                val emitted = mutableListOf<AlignedSentencePair>()
                for (sentencePair in sentencePairs) {
                    val raw = confidenceOf(
                        enSentenceSpans, sentencePair.a,
                        zhSentenceSpans, sentencePair.b
                    )
                    // 合并句对（1:N）是真配对但粒度跳，置信度轻折扣。
                    val confidence =
                        if (sentencePair.a.size > 1 || sentencePair.b.size > 1) {
                            (raw * SENTENCE_MERGE_SCALE).coerceIn(MIN_CONFIDENCE, 1f)
                        } else raw
                    if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue
                    // V5：长度比硬门槛——置信度被锚点/词义加分拉高、但长度比
                    // 崩坏的错对（用户主诉「长度明显不匹配的对照」）同样不落盘。
                    // 合并对比例天然偏高（引文整句 vs 短英文引导），上限放宽一档。
                    val ratio = lengthRatioOf(enSentenceSpans, sentencePair.a, zhSentenceSpans, sentencePair.b)
                    val merged = sentencePair.a.size > 1 || sentencePair.b.size > 1
                    val maxRatio = if (merged) SENTENCE_MAX_MERGED_LENGTH_RATIO else SENTENCE_MAX_LENGTH_RATIO
                    if (ratio < SENTENCE_MIN_LENGTH_RATIO || ratio > maxRatio) continue
                    emitted += AlignedSentencePair(
                        enChapter = enIdx,
                        zhChapter = zhIdx,
                        enParagraph = enParagraph,
                        zhParagraph = zhParagraph,
                        enSentence = join(enSentences, sentencePair.a),
                        zhSentence = join(zhSentences, sentencePair.b),
                        confidence = confidence
                    )
                }

                if (emitted.isEmpty()) {
                    // 句子级无法对齐（或全部低于置信门槛）→ 降级为段落对照。
                    result += AlignedSentencePair(
                        enChapter = enIdx,
                        zhChapter = zhIdx,
                        enParagraph = enParagraph,
                        zhParagraph = zhParagraph,
                        enSentence = "",
                        zhSentence = "",
                        confidence = confidenceOf(enSpans, paragraphPair.a, zhSpans, paragraphPair.b)
                    )
                } else {
                    result += emitted
                }

                // 2:1 合并时存下来的 enParagraph 是两段拼起来的文本，用户点其中一段时
                // 段落文本对不上（标题、诗行这类不以句末标点结尾的段落连句子也切不出来）。
                // 为每个成分段落补一条段级条目，让这种点词至少落在正确的中文段落上。
                if (paragraphPair.a.size > 1) {
                    val merged = confidenceOf(enSpans, paragraphPair.a, zhSpans, paragraphPair.b)
                    for (index in paragraphPair.a) {
                        val constituent = enParagraphs[index]
                        if (constituent.isBlank()) continue
                        result += AlignedSentencePair(
                            enChapter = enIdx,
                            zhChapter = zhIdx,
                            enParagraph = constituent,
                            zhParagraph = zhParagraph,
                            enSentence = "",
                            zhSentence = "",
                            confidence = (merged * MERGED_CONSTITUENT_SCALE)
                                .coerceIn(MIN_CONFIDENCE, 1f)
                        )
                    }
                }
            }

            result += neighbourFallbacks(
                enChapter = enIdx,
                zhChapter = zhIdx,
                enParagraphs = enParagraphs,
                zhParagraphs = zhParagraphs,
                enSpans = enSpans,
                zhSpans = zhSpans,
                zhForEn = zhForEn,
                meaning = meaning
            )
        }
        return result
    }

    // --- 邻近段落兜底 --------------------------------------------------------

    /**
     * 段落级 DP 只允许 1:1 / 2:1 / 1:2，所以英文段落数超过中文段落数两倍时，多出来的
     * 只能被跳过（实测整本魔戒有 13.3% 的段落落在这里，用户在这些段落里点词完全看不到
     * 对照）。这里把被跳过的段落挂到**下标最近**的已对齐段落所对应的中文段落上：
     *  - 先跑一次句级 DP 产出句级对照（V3）：被跳过段落的句子有真实代价函数可依，
     *    不再只有「整段一锅端」；置信度同样乘 [NEIGHBOUR_CONFIDENCE_SCALE] ÷ 距离；
     *  - 段级条目保留（查询侧 4/5 级降级用）。
     * 低于 [TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE] 的直接不产出：查询阶段反正
     * 会拒掉，落盘只是白占体积。
     */
    private fun neighbourFallbacks(
        enChapter: Int,
        zhChapter: Int,
        enParagraphs: List<String>,
        zhParagraphs: List<String>,
        enSpans: List<Span>,
        zhSpans: List<Span>,
        zhForEn: Map<Int, Int>,
        meaning: MeaningIndex?
    ): List<AlignedSentencePair> {
        if (zhForEn.isEmpty()) return emptyList()
        val covered = zhForEn.keys.toIntArray()
        covered.sort()

        val fallbacks = mutableListOf<AlignedSentencePair>()
        for (index in enParagraphs.indices) {
            if (zhForEn.containsKey(index)) continue
            val enParagraph = enParagraphs[index]
            if (enParagraph.isBlank()) continue
            val nearest = nearestCovered(index, covered) ?: continue
            val zhIndex = zhForEn[nearest] ?: continue
            val zhParagraph = zhParagraphs.getOrNull(zhIndex) ?: continue
            if (zhParagraph.isBlank()) continue

            val distance = abs(nearest - index).coerceAtLeast(1)

            // 句级兜底（V3）：句级 DP 出来的配对仍是真代价函数的选择，只是段落对应近似。
            val enSentences = SentenceSplitter.split(enParagraph).contentOnly()
            val zhSentences = splitChinese(zhParagraph).contentOnly()
            if (enSentences.isNotEmpty() && zhSentences.isNotEmpty()) {
                val enSentenceSpans = enSentences.map { englishSpan(it, meaning) }
                val zhSentenceSpans = zhSentences.map { chineseSpan(it) }
                for (sentencePair in alignSpans(enSentenceSpans, zhSentenceSpans, allowMerge = false)) {
                    val cost = pairCost(
                        enSentenceSpans[sentencePair.a[0]],
                        sentencePair.a.getOrNull(1)?.let { enSentenceSpans[it] },
                        zhSentenceSpans[sentencePair.b[0]],
                        sentencePair.b.getOrNull(1)?.let { zhSentenceSpans[it] }
                    )
                    val confidence = ((1.0 - cost) * NEIGHBOUR_CONFIDENCE_SCALE / distance)
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                    if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue
                    // V5：兜底句对同样受长度比硬门槛约束（s78/s79 型：71 词英文
                    // 配 13 字中文、置信度 0.35 过了门槛——长度比 0.11 一票否决）。
                    val ratio = lengthRatioOf(enSentenceSpans, sentencePair.a, zhSentenceSpans, sentencePair.b)
                    if (ratio < SENTENCE_MIN_LENGTH_RATIO || ratio > SENTENCE_MAX_LENGTH_RATIO) continue
                    fallbacks += AlignedSentencePair(
                        enChapter = enChapter,
                        zhChapter = zhChapter,
                        enParagraph = enParagraph,
                        zhParagraph = zhParagraph,
                        enSentence = join(enSentences, sentencePair.a),
                        zhSentence = join(zhSentences, sentencePair.b),
                        confidence = confidence
                    )
                }
            }

            val base = 1.0 - pairCost(enSpans[index], null, zhSpans[zhIndex], null)
            val confidence = (base * NEIGHBOUR_CONFIDENCE_SCALE / distance)
                .coerceIn(0.0, 1.0)
                .toFloat()
            if (confidence < TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE) continue

            fallbacks += AlignedSentencePair(
                enChapter = enChapter,
                zhChapter = zhChapter,
                enParagraph = enParagraph,
                zhParagraph = zhParagraph,
                enSentence = "",
                zhSentence = "",
                confidence = confidence
            )
        }
        return fallbacks
    }

    private fun nearestCovered(index: Int, sorted: IntArray): Int? {
        if (sorted.isEmpty()) return null
        val found = java.util.Arrays.binarySearch(sorted, index)
        if (found >= 0) return sorted[found]
        val insert = -found - 1
        val before = if (insert > 0) sorted[insert - 1] else null
        val after = if (insert < sorted.size) sorted[insert] else null
        return when {
            before == null -> after
            after == null -> before
            index - before <= after - index -> before
            else -> after
        }
    }

    // --- 片段特征（进 DP 之前算好） ------------------------------------------

    /**
     * 一个待对齐片段的预计算特征。[anchors] 只在英文侧有值，[latin] 只在中文侧有值。
     */
    private class Span(
        val words: Int,
        val chars: Int,
        val anchors: Set<String>,
        val latin: Set<String>,
        /** 英文侧：词义锚短语并集（简繁归一后）；中文侧：2–4 字连续汉字子串集合。 */
        val meaning: Set<String>
    )

    private fun englishSpan(text: String, meaning: MeaningIndex?): Span {
        val phrases = HashSet<String>()
        if (meaning != null) {
            for (word in EN_WORDS.findAll(text.lowercase())) {
                val w = word.value.trim('\'')
                if (w.length >= 2) phrases += meaning.phrasesOf(w)
            }
        }
        return Span(
            words = countWords(text),
            chars = countChars(text),
            anchors = ANCHORS.findAll(text).mapTo(HashSet()) { it.value.lowercase() },
            latin = emptySet(),
            meaning = phrases
        )
    }

    private fun chineseSpan(text: String): Span {
        // 词义锚命中面：繁简归一后的全部 2–4 字连续汉字子串（预计算，DP 内层 O(1) 查表）。
        val simplified = TraditionalSimplified.toSimplified(text)
        val subjects = HashSet<String>()
        for (run in HAN_RUNS.findAll(simplified)) {
            val s = run.value
            for (i in 0 until s.length) {
                for (len in 2..4) {
                    if (i + len > s.length) break
                    subjects.add(s.substring(i, i + len))
                }
            }
        }
        return Span(
            words = countWords(text),
            chars = countChars(text),
            anchors = emptySet(),
            // 中文侧只需要「句中出现过哪些拉丁/数字词」，锚点判定退化成哈希查表。
            latin = LATIN_RUNS.findAll(text).mapTo(HashSet()) { it.value.lowercase() },
            meaning = subjects
        )
    }

    private fun foldSpans(spans: List<Span>): Span {
        var words = 0
        var chars = 0
        val anchors = HashSet<String>()
        val latin = HashSet<String>()
        val meaning = HashSet<String>()
        for (span in spans) {
            words += span.words
            chars += span.chars
            anchors += span.anchors
            latin += span.latin
            meaning += span.meaning
        }
        return Span(words, chars, anchors, latin, meaning)
    }

    private fun countWords(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    private fun countChars(text: String): Int = text.count { !it.isWhitespace() }

    // --- 章节对齐 -----------------------------------------------------------

    private fun alignChapters(
        en: List<List<Span>>,
        zh: List<List<Span>>
    ): List<Pair<Int, Int>> {
        if (en.size == zh.size) return en.indices.map { it to it }
        val enSpans = en.map { foldSpans(it) }
        val zhSpans = zh.map { foldSpans(it) }
        // 直接用 DP 给出的下标。不要拿文本去 indexOf 回查：两章正文完全相同
        // （或都为空）时会全部映射到第一处，导致整章错配。
        return alignSpans(enSpans, zhSpans, allowMerge = false).map { it.a[0] to it.b[0] }
    }

    // --- 通用单调序列对齐 ----------------------------------------------------

    /** 一组对齐结果：[a]/[b] 各是被对齐到一起的下标（1 个或合并的 2 个）。 */
    private class SpanPair(val a: IntArray, val b: IntArray)

    /**
     * 用 DP 把两个片段序列单调对齐。允许 1:0 / 0:1 / 1:1，[allowMerge] 时额外允许
     * 2:1 / 1:2。[mergeGate] 非空时，每个合并走法还要过一道准入检查（句级 1:N 的
     * 语义/尺寸/边际门槛；段落级合并不设门槛）。
     * 返回「已对齐的下标对」（跳过项不产出）。
     *
     * dp 只保留最近三行（2:1 / 1:2 会回看 i−2、j−2），回溯靠每格 1 字节的走法矩阵，
     * 因此内存是 O(n·m) 字节而不是 O(n·m) 个 double + 对象。
     */
    private fun alignSpans(
        a: List<Span>,
        b: List<Span>,
        allowMerge: Boolean,
        mergeGate: ((Span, Span?, Span, Span?) -> Boolean)? = null
    ): List<SpanPair> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        var prev2 = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var prev1 = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        var cur = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        val moves = Array(n + 1) { ByteArray(m + 1) }

        for (i in 0..n) {
            for (j in 0..m) {
                if (i == 0 && j == 0) {
                    cur[0] = 0.0
                    moves[0][0] = MOVE_NONE
                    continue
                }
                var best = Double.POSITIVE_INFINITY
                var move = MOVE_NONE
                if (i > 0) {
                    val cost = prev1[j] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_A
                    }
                }
                if (j > 0) {
                    val cost = cur[j - 1] + SKIP_COST
                    if (cost < best) {
                        best = cost
                        move = MOVE_SKIP_B
                    }
                }
                if (i > 0 && j > 0 && prev1[j - 1] < Double.POSITIVE_INFINITY) {
                    val cost = prev1[j - 1] + pairCost(a[i - 1], null, b[j - 1], null)
                    if (cost < best) {
                        best = cost
                        move = MOVE_ONE_ONE
                    }
                }
                if (allowMerge) {
                    if (i > 1 && j > 0 && prev2[j - 1] < Double.POSITIVE_INFINITY &&
                        (mergeGate == null || mergeGate(a[i - 2], a[i - 1], b[j - 1], null))
                    ) {
                        val cost = prev2[j - 1] + pairCost(a[i - 2], a[i - 1], b[j - 1], null)
                        if (cost < best) {
                            best = cost
                            move = MOVE_TWO_ONE
                        }
                    }
                    if (i > 0 && j > 1 && prev1[j - 2] < Double.POSITIVE_INFINITY &&
                        (mergeGate == null || mergeGate(a[i - 1], null, b[j - 2], b[j - 1]))
                    ) {
                        val cost = prev1[j - 2] + pairCost(a[i - 1], null, b[j - 2], b[j - 1])
                        if (cost < best) {
                            best = cost
                            move = MOVE_ONE_TWO
                        }
                    }
                }
                cur[j] = best
                moves[i][j] = move
            }
            if (i < n) {
                val recycled = prev2
                prev2 = prev1
                prev1 = cur
                cur = recycled
                cur.fill(Double.POSITIVE_INFINITY)
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

    /**
     * 中文分句 + 引号归属修复：
     *  - 段落末尾「。」+ 闭合引号（……熟練。」）会被 [ZH_SENTENCE_END] 在「。」后
     *    切出一个纯「」残渣；纯标点/引号片段并入前句（R1）；
     *  - 「。」后紧跟闭合引号 + 引导语（。」他大喊：「……）时，「」他大喊：「……」
     *    会被切成独立片段，同样并入前句（R2）。
     * 实测（魔戒档案）：3,464 段产生 1,461 个纯残渣段 + 1,742 个裸引号开头段，
     * 修复后两者均为 0，段数 14,870 → 11,667；12,692 条句级句对里有 2,216 条
     * （17.5%）是这种脏配对。
     */
    private fun splitChinese(text: String): List<String> {
        val raw = text.split(ZH_SENTENCE_END)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val merged = mutableListOf<String>()
        for (seg in raw) {
            val isPunctuationOnly = seg.none { it.isLetterOrDigit() }
            val leadsWithClosing = seg.firstOrNull() in ZH_CLOSING_LEADS
            if (merged.isNotEmpty() && (isPunctuationOnly || leadsWithClosing)) {
                merged[merged.lastIndex] = merged.last() + seg
            } else {
                merged += seg
            }
        }
        return merged
    }

    private val ZH_CLOSING_LEADS = setOf('」', '』', '"', '\'', '）', '】')

    /** 纯标点句（切句残渣「 . 」这类）不参与对齐：没有可点词，也没有对照价值。 */
    private fun List<String>.contentOnly(): List<String> =
        filter { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }

    /**
     * 句级 1:N 合并准入检查（详见 [SENTENCE_MERGE_MARGIN] 上的说明）。
     * 三道门槛：词义锚点命中 ≥1（无 meaning 源时退化为「局部 1:1 已无法解释
     * 长度关系」——中文引文整句对多条短英文句时，合并是唯一说得通的走法）、
     * 尺寸保护、合并须比局部最优 1:1 便宜 [SENTENCE_MERGE_MARGIN] 以上。
     * 全部是 O(1) 哈希查表与算术，守住性能护栏。
     */
    private fun sentenceMergeAllowed(en1: Span, en2: Span?, zh1: Span, zh2: Span?): Boolean {
        for (en in listOfNotNull(en1, en2)) {
            if (en.words < SENTENCE_MERGE_MIN_EN_WORDS) return false
        }
        for (zh in listOfNotNull(zh1, zh2)) {
            if (zh.chars < SENTENCE_MERGE_MIN_ZH_CHARS) return false
        }
        val merged = pairCost(en1, en2, zh1, zh2)
        val local = if (en2 == null) {
            minOf(
                pairCost(en1, null, zh1, null),
                pairCost(en1, null, zh2!!, null)
            )
        } else {
            minOf(
                pairCost(en1, null, zh1, null),
                pairCost(en2, null, zh1, null)
            )
        }
        if (merged <= local - SENTENCE_MERGE_MARGIN) {
            val semanticEvidence = meaningBonus(en1.meaning, en2?.meaning, zh1.meaning, zh2?.meaning) > 0.0
            if (semanticEvidence) return true
            // 无词义证据（含 meaning=null 的保守回退）：仅当局部 1:1 自身已无法
            // 解释长度关系（残或超）时放行——引文整句对多条短英文句是唯一形态。
            val enWords = en1.words + (en2?.words ?: 0)
            val zhChars = zh1.chars + (zh2?.chars ?: 0)
            val mergedRatio = if (enWords == 0) 0.0 else zhChars / (enWords * ZH_CHARS_PER_EN_WORD)
            if (mergedRatio in SENTENCE_MIN_LENGTH_RATIO..SENTENCE_MAX_MERGED_LENGTH_RATIO) {
                val localRatio = if (en2 == null) {
                    maxOf(ratioOf(en1.words, zh1.chars), ratioOf(en1.words, zh2!!.chars))
                } else {
                    maxOf(ratioOf(en1.words, zh1.chars), ratioOf(en2.words, zh1.chars))
                }
                return localRatio !in SENTENCE_MIN_LENGTH_RATIO..SENTENCE_MAX_LENGTH_RATIO
            }
        }
        return false
    }

    private fun ratioOf(enWords: Int, zhChars: Int): Double =
        if (enWords == 0) 0.0 else zhChars / (enWords * ZH_CHARS_PER_EN_WORD)

    // --- 代价与置信度（只做算术与哈希查表） ----------------------------------

    /** 长度比偏差（0..1）：英文词数 vs 中文有效字符数，按 1.7 字符/词归一。 */
    private fun lengthCost(enWords: Int, zhChars: Int): Double {
        val ew = enWords.coerceAtLeast(1)
        val zc = zhChars.coerceAtLeast(1)
        val zcNorm = zc / ZH_CHARS_PER_EN_WORD
        val diff = abs(ew - zcNorm)
        return diff / (ew + zcNorm + 1.0)
    }

    /**
     * 数字/拉丁专名锚点命中率（0..1）× 0.5。命中判定用「中文侧出现过的拉丁/数字词
     * 集合」查表，等价于原来的「整句 lowercase 后 contains」，但不再是 O(锚点×文本长)，
     * 且不会因为锚点是别的单词的子串而误命中。
     */
    private fun anchorBonus(
        enA: Set<String>,
        enB: Set<String>?,
        zhA: Set<String>,
        zhB: Set<String>?
    ): Double {
        var total = 0
        var hits = 0
        for (anchor in enA) {
            total++
            if (anchor in zhA || (zhB != null && anchor in zhB)) hits++
        }
        if (enB != null) {
            for (anchor in enB) {
                if (anchor in enA) continue
                total++
                if (anchor in zhA || (zhB != null && anchor in zhB)) hits++
            }
        }
        if (total == 0) return 0.0
        return (hits.toDouble() / total) * 0.5
    }

    private fun pairCost(en1: Span, en2: Span?, zh1: Span, zh2: Span?): Double {
        val enWords = en1.words + (en2?.words ?: 0)
        val zhChars = zh1.chars + (zh2?.chars ?: 0)
        return lengthCost(enWords, zhChars) -
            anchorBonus(en1.anchors, en2?.anchors, zh1.latin, zh2?.latin) -
            meaningBonus(en1.meaning, en2?.meaning, zh1.meaning, zh2?.meaning)
    }

    /**
     * 词义锚点加分：英文侧词义短语有多少条出现在中文侧子串集合里。
     * 稀疏但精确——锚定判断（像数字/拉丁锚点一样），不是全句语义评分。
     * 实测（魔戒 100 样本集）：误配组 76% 零命中、阈值 0.15 下 FP=0。
     */
    private fun meaningBonus(en1: Set<String>, en2: Set<String>?, zh1: Set<String>, zh2: Set<String>?): Double {
        var total = 0
        for (phrase in en1) {
            if (phrase in zh1 || (zh2 != null && phrase in zh2)) total++
        }
        if (en2 != null) {
            for (phrase in en2) {
                if (phrase in en1) continue
                if (phrase in zh1 || (zh2 != null && phrase in zh2)) total++
            }
        }
        if (total == 0) return 0.0
        return Math.min(total, MEANING_MAX_HITS).toDouble() * MEANING_HIT_SCALE
    }

    /** 句对两端折叠后的长度比（zh 字符 / en 词 × 1.7），详见 [SENTENCE_MIN_LENGTH_RATIO]。 */
    private fun lengthRatioOf(
        enSpans: List<Span>,
        a: IntArray,
        zhSpans: List<Span>,
        b: IntArray
    ): Double {
        var enWords = 0
        for (i in a) enWords += enSpans[i].words
        var zhChars = 0
        for (j in b) zhChars += zhSpans[j].chars
        return if (enWords == 0) 0.0 else zhChars / (enWords * ZH_CHARS_PER_EN_WORD)
    }

    private fun confidenceOf(
        enSpans: List<Span>,
        enIndices: IntArray,
        zhSpans: List<Span>,
        zhIndices: IntArray
    ): Float {
        val cost = pairCost(
            enSpans[enIndices[0]],
            enIndices.getOrNull(1)?.let { enSpans[it] },
            zhSpans[zhIndices[0]],
            zhIndices.getOrNull(1)?.let { zhSpans[it] }
        )
        val confidence = (1.0 - cost).coerceIn(0.0, 1.0)
        return confidence.toFloat().coerceIn(MIN_CONFIDENCE, 1f)
    }
}
