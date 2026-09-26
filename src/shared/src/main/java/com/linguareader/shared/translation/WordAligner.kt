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
 * [prefer] 原本是给「本书术语表」留的偏好加成入口（术语表学习实测需要 1.7 GB
 * 峰值堆，手机上不可行，详见 [BookTerm]）；Q1-t09 ② 起它被真正接线：调用方
 * （`TranslationMemoryRepository`）把**语境优选义项**里的候选词以
 * [CONTEXT_PREFERRED_BONUS] 传进来，让面板上标着「本句优先」的那条义项同时决定
 * 中文句里高亮哪个词。术语表用法的语义不变。
 *
 * **加成只进排序分（`score`），不进置信度（`confidence`）**：两者回答不同问题 ——
 * `confidence` 答「这个出现位置可不可信」（只由位置偏差与候选长度决定，
 * [minConfidenceFor] 的分档阈值据此才有可拒绝区间），`prefer` 答「同一位置上哪个
 * 候选是本语境要的译法」。若把加成并进 `confidence`，单字候选在最差位置
 * （1 − 0.70 = 0.30）只要加一次加成就能回到阈值之上，BUG-039 的位置护栏会被重新
 * 打开（0.30 + 0.30 = 0.60 ≥ 0.40，见 WordAlignerTest 的用例）。
 */
object WordAligner {

    /** 单字候选的接受阈值，也是全档阈值的最小值（BUG-039 的护栏）。 */
    const val MIN_CONFIDENCE = 0.40f

    /**
     * 「语境优选」义项候选的加成（Q1-t09 ②）。
     *
     * 取值依据（与 [WordAlignment.confidence] 同一量纲，1.0 = 整整一句的位置偏差）：
     *  - 同长度候选之间的排序分差只来自位置惩罚 = [positionWeightFor] × 相对位置差。
     *    单字权重 0.70、多字 0.35，故 0.30 的加成可抵消「优选候选比次优候选远」的
     *    0.43（单字）/ 0.86（多字）句长劣势 —— 覆盖中英状语、时间先后造成的正常错位；
     *  - 再大就会把高亮钉到句子另一头（位置证据被偏好彻底压过），故不取更高；
     *  - 与既有的术语表偏好加成同量级（`WordAlignerTest` 里的 0.3f 先例），不引入新量纲。
     */
    const val CONTEXT_PREFERRED_BONUS = 0.30f

    private val LATIN_WORD = Regex("[A-Za-z][A-Za-z0-9]*")
    private val ALL_CAPS = Regex("[A-Z]{2,}")
    private val PARENTHETICAL = Regex("[（(].*?[)）]")
    private val SENSE_SEPARATORS = Regex("[，,、；;／/·\\s]+")

    /**
     * 接受阈值按候选长度分档（Q1-t09 ③）。
     *
     * 修前只有一档 [MIN_CONFIDENCE]，而多字候选的位置惩罚上限只有
     * [positionWeightFor] = 0.35 → 置信度理论下限 0.65，`takeIf` 对多字候选**恒真**，
     * 等于没有过滤。分档后每档都有**非空的可拒绝区间**（拒绝条件为相对位置差
     * Δ > (1 − T) / w，Δ 最大为 1，故 T > 1 − w 即可拒绝）：
     *
     * | 长度  | w    | T    | 可拒绝区间 |
     * | ---   | ---  | ---  | ---        |
     * | 1 字  | 0.70 | 0.40 | Δ > 0.86   |
     * | 2 字  | 0.35 | 0.72 | Δ > 0.80   |
     * | ≥3 字 | 0.35 | 0.68 | Δ > 0.91   |
     *
     * 候选越长越可信（同一段文本里三字以上的巧合远少于单字），容差随之放宽；
     * 单字档沿用 0.40 不动 —— WordAlignerTest 里那条「先红后绿」的位置用例钉着它。
     */
    fun minConfidenceFor(termLength: Int): Float = when {
        termLength <= 1 -> MIN_CONFIDENCE
        termLength == 2 -> 0.72f
        else -> 0.68f
    }

    /**
     * 位置惩罚权重：单字候选（的/地/上/中…）在译句里几乎必然命中，惩罚加倍到 0.70；
     * 多字候选维持 0.35，避免误伤中英词序差异大的正常配对（BUG-039）。
     */
    fun positionWeightFor(termLength: Int): Double = if (termLength == 1) 0.70 else 0.35

    /**
     * @param prefer 候选加成（zhTerm → 加成 0..1），让「词典查不到的本书译法」也能被
     *               选中，或让语境优选义项的候选压过位置更近的次优候选；缺省空。
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
        val terms = candidateOrigins(candidates, prefer)
        if (terms.isEmpty()) return null

        val enPos = if (enOffset >= 0 && enSentence.isNotBlank()) {
            enOffset.toDouble() / enSentence.length.coerceAtLeast(1)
        } else {
            relativePosition(word, enSentence)
        }

        var best: WordAlignment? = null
        var bestScore = -1.0
        // 稳定排序 + 严格大于：同长度同分的候选，先试到的赢。义项按「语境优选在前」
        // 传入（见 candidateOrigins），于是并列时优选义项获胜。
        for (candidate in terms.sortedByDescending { it.term.length }) {
            val term = candidate.term
            // BUG-039：单字候选（的/地/上/中…）在译句里几乎必然命中，位置惩罚加倍到
            // 0.70；多字候选维持 0.35，避免误伤中英词序差异大的正常配对。
            val posWeight = positionWeightFor(term.length)
            val threshold = minConfidenceFor(term.length)
            for ((start, end) in occurrencesOf(term, zhSentence)) {
                val zhPos = start.toDouble() / zhSentence.length.coerceAtLeast(1)
                val posPenalty = abs(enPos - zhPos) * posWeight
                val confidence = (1.0 - posPenalty).coerceIn(0.0, 1.0).toFloat()
                // **先过闸、再排序**（取代原实现的「先按 score 选第一名，最后 takeIf」）：
                // 阈值回答「这个出现点可不可信」，加成只在可信的出现点之间取舍。若反过来
                // 先排序后过滤，一个被加成顶到第一名的远位置候选会把本来能通过的近位置
                // 候选一起挡掉，白白丢掉一次高亮。分档见 [minConfidenceFor]。
                if (confidence < threshold) continue
                val lengthBonus = term.length.coerceAtMost(4) * 0.04
                // 加成只进 score（候选间取舍），不进 confidence（接受门槛）—— 理由见
                // 类 KDoc「加成只进排序分」一段。
                val score = confidence + lengthBonus + candidate.bonus
                if (score > bestScore) {
                    bestScore = score
                    best = WordAlignment(
                        word = term,
                        start = start,
                        endExclusive = end,
                        confidence = confidence,
                        source = WordAlignmentSource.DICTIONARY,
                        sourceSense = candidate.sense,
                        sourceSensePreferred = candidate.preferred
                    )
                }
            }
        }
        return best
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

    /**
     * 从词典义项行提取中文候选词。
     *
     * 公开是给调用方（`TranslationMemoryRepository`）用的：它把「语境优选」义项的
     * 候选词翻译成 [align] 的 `prefer` 键，必须与评分时**逐字相同**地切词，否则
     * 又会出现「两条路径的候选结构上不同源」那类错配。
     */
    fun candidateTerms(senses: List<String>): List<String> =
        senses.flatMap(::termsOf).distinct()

    /** 单条义项行 → 候选词（整行 + 按分隔符切出的中文片段）。 */
    private fun termsOf(raw: String): List<String> {
        val terms = mutableListOf<String>()
        // 去掉括注（如「温斯顿（人名）」→「温斯顿」）
        val cleaned = raw.replace(PARENTHETICAL, "").trim()
        if (cleaned.isBlank()) return terms
        terms += cleaned
        cleaned.split(SENSE_SEPARATORS)
            .map { it.trim() }
            .filter { isChineseCandidate(it) }
            .forEach { terms += it }
        return terms.distinct()
    }

    /**
     * 候选词表（词 → 来源义项 / 加成 / 是否语境优选），保持「首次出现顺序」。
     *
     * 依赖调用方**按「语境优选在前」的顺序**传入义项行（`ContextAnalyzer.senses`
     * 正是这么排的：`sortedByDescending { it.contextPreferred }`）：同一 term 出现在
     * 多条义项时，首次出现的必是优选那条，[Candidate.sense] 记它 —— UI 才能拿
     * `sense.text` 把高亮词指回面板上具体那一行。
     */
    private fun candidateOrigins(
        senses: List<String>,
        prefer: Map<String, Float>
    ): List<Candidate> {
        val byTerm = LinkedHashMap<String, Candidate>()
        for (sense in senses) {
            for (term in termsOf(sense)) {
                byTerm.getOrPut(term) {
                    Candidate(
                        term = term,
                        sense = sense,
                        bonus = (prefer[term] ?: 0f).toDouble(),
                        preferred = prefer.containsKey(term)
                    )
                }
            }
        }
        // 只在 prefer 里、词典候选集合里没有的 term 同样参与匹配（来源义项为空）。
        for ((term, bonus) in prefer) {
            byTerm.getOrPut(term) {
                Candidate(term, sense = null, bonus = bonus.toDouble(), preferred = true)
            }
        }
        return byTerm.values.toList()
    }

    private data class Candidate(
        val term: String,
        /** 产出该候选的义项原文；纯 [align] 的 prefer 加成没有来源义项。 */
        val sense: String?,
        val bonus: Double,
        /** 该候选是否来自「语境优选」义项（prefer 命中）。 */
        val preferred: Boolean
    )

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
