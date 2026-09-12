package com.linguareader.shared.tts

/**
 * Splits mixed English / Chinese prose into TTS-sized sentences.
 *
 * ⚠️ **改这里必须 bump [TtsPipelineContract.VERSION]** —— 句号是音频缓存键与
 * 音频包清单的一部分，改了切分而不 bump，存量音频会静默对不上文本。
 *
 * Rules:
 * - Terminators: `. ! ? … 。 ！ ？` (a run of terminators is one boundary).
 * - A run of three or more ASCII dots is an ellipsis and behaves exactly like
 *   `…` (a lowercase continuation such as `Wait... what` stays one sentence).
 * - A trailing closing quote / bracket belongs to the finished sentence.
 * - Abbreviations come in two tiers: title abbreviations (`Mr.`, `Dr.`) never
 *   end a sentence; sentence-final capable ones (`etc.`, `No. 5`, `Inc.`) only
 *   split when followed by a capitalised word.
 * - English boundaries require whitespace (`Hello.World` stays one token) or a
 *   directly following CJK character (`said.她走了` splits); Chinese
 *   boundaries split without whitespace.
 * - A continuation ellipsis (`Wait… what?`) stays inside the sentence.
 * - With [maxSentenceLength] set (the TTS line passes
 *   [TTS_MAX_SENTENCE_CHARS]), longer sentences are hard-split at word
 *   boundaries so no synthesis request exceeds a safe size.
 */
object SentenceSplitter {

    /**
     * TTS 朗读线的单句长度上限。正常文学作品里 300 字符已远超单句均值，
     * 只兜住无终止符的块（诗歌断行被归一化、`pre`/代码块、TXT 无空行整段）。
     * 译本对齐线不传上限，保持句子结构与既有对齐档案稳定。
     */
    const val TTS_MAX_SENTENCE_CHARS = 300

    private val whitespace = Regex("\\s+")

    /** 头衔/称谓类缩写：句点永远不结束句子（`Dr. Watson`、`St. Louis`）。 */
    private val titleAbbreviation = Regex(
        """(?i)\b(?:Mr|Mrs|Ms|Messrs|Dr|Prof|Sr|Jr|St|Mt|Ft|Capt|Gen|Col|Lt|""" +
            """Sgt|Sen|Rep|Gov|Rev|Hon|Msgr|vs|cf|viz)\."""
    )

    /**
     * 句末可结束类缩写：后跟大写词视为真句界（`etc. The next morning` 要切），
     * 后跟数字/小写/文本末尾则保护（`No. 5`、`pp. 12`、`Inc. was`）。
     */
    private val sentenceFinalAbbreviation = Regex(
        """(?i)\b(?:etc|pp|no|nos|inc|ltd|corp|co|ave|blvd|rd|dept|univ|approx|""" +
            """jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\."""
    )
    private val initials = Regex("""\b(?:[A-Za-z]\.){2,}""")
    // "J. R. R. Tolkien"：缩写之间带空格，句点同样不是句末。
    private val spacedInitials = Regex("""\b[A-Za-z]\.(?:\s+[A-Za-z]\.)+""")

    /**
     * **总是保护**档的点号缩写：`a.m.` / `p.m.` / `i.e.` / `e.g.` / `Ph.D.` 等。
     *
     * 它们不在两档表里时，行为会取决于下一个词的大小写（`9 a.m. Then left` 被切两句，
     * 而 `at 5 p.m. he said` 不切），既不可预期又不正确（审查 2-2）。这类缩写几乎
     * 不会真的出现在句末（句末会写成 `in the morning`），所以一律保护。
     *
     * `Ph.D.` 的末端句点由内层 `D.` 命中；`a.m.`/`i.e.` 末端句点由整体命中。
     */
    private val alwaysProtectedAbbreviation = Regex(
        """(?i)\b(?:a\.m|p\.m|i\.e|e\.g|cf|viz|Ph\.D|D\.Phil|M\.A|B\.A|M\.Sc|B\.Sc)\."""
    )
    private val terminators = setOf('.', '!', '?', '…', '。', '！', '？')
    private val cjkTerminators = setOf('。', '！', '？')
    private val closing = setOf('"', '\'', '”', '’', ')', ']', '）', '】', '」', '』')

    fun split(raw: String, maxSentenceLength: Int = Int.MAX_VALUE): List<String> {
        // U+3000 全角空格是 CJK 排版里的缩进符，Java 的 `\s` 不匹配它，
        // 原样留着会让朗读在句首多一个停顿、高亮坐标带不可见字符（审查 2-3）。
        val text = raw.replace('\u3000', ' ').replace(whitespace, " ").trim()
        if (text.isEmpty()) return emptyList()

        val protectedPeriods = protectedPeriods(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            current.append(char)
            if (char in terminators && index !in protectedPeriods) {
                var end = index + 1
                var hasCjkTerminator = char in cjkTerminators
                while (end < text.length && text[end] in terminators) {
                    if (text[end] in cjkTerminators) hasCjkTerminator = true
                    current.append(text[end])
                    end++
                }
                val runLength = end - index
                // 单字符 … 或三个及以上 ASCII 句点都是省略号；1~2 个句点是普通句界。
                val isEllipsis = char == '…' || (char == '.' && runLength >= 3)
                val closingStart = end
                while (end < text.length && text[end] in closing) {
                    current.append(text[end])
                    end++
                }
                val sawClosingQuote = end > closingStart
                val nextNonSpace = (end until text.length).firstOrNull { text[it] != ' ' }
                val shouldSplit = when {
                    end >= text.length -> true
                    // 中文引号嵌套：`他问：「你听见『谁在敲门？』了吗？」` 里的 ？/！ 不是
                    // 外层引语的句界，否则整行会被切成两半、两个声音读同一句（审查 2-1）。
                    hasCjkTerminator -> !hasUnclosedCjkQuote(text, end)
                    isEllipsis -> {
                        // 中文省略号后接 CJK 字符一律视为句界：句末省略号
                        // （他走了……她哭了）与悬停语气（我想…算了）无法区分，
                        // 切分对 TTS 停顿更安全；英文只有小写延续才合并。
                        val next = nextNonSpace?.let { text[it] }
                        next == null || !next.isLowerCase()
                    }
                    // 收紧版：仅当句末标点后**有闭合引号归属**、且后面跟小写字母时，
                    // 才是说话人引导语（'I am sorry, Frodo!' he cried... 是一句）。
                    // 无引号归属的小写延续（正常句界）照旧切分，避免把大段对话并成一句。
                    // 英文边界要求空白；英文终止符后直接跟 CJK 字符（said.她走了）同样算句界。
                    else -> {
                        val next = text[end]
                        val boundary = next == ' ' || next.isCjkIdeograph()
                        boundary &&
                            !(sawClosingQuote && (nextNonSpace?.let { text[it].isLowerCase() } ?: false))
                    }
                }
                if (shouldSplit) {
                    result.add(current.toString().trim())
                    current.setLength(0)
                }
                index = end
            } else {
                index++
            }
        }
        if (current.isNotBlank()) {
            result.add(current.toString().trim())
        }
        return result.flatMap { hardSplit(it, maxSentenceLength) }
    }

    /**
     * 把超长句在空格（词边界）处贪心切成 ≤[max] 的块；整段无空格时按字符硬切。
     * 硬切块仍是原文的连续子串，TTS 高亮的 indexOf 定位和说话人平行数组不受
     * 影响，代价只是块边界处的朗读停顿。
     */
    private fun hardSplit(sentence: String, max: Int): List<String> {
        require(max >= 1) { "maxSentenceLength must be positive, got $max" }
        if (sentence.length <= max) return listOf(sentence)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < sentence.length) {
            if (sentence.length - start <= max) {
                chunks.add(sentence.substring(start))
                break
            }
            val lastSpace = sentence.lastIndexOf(' ', start + max)
            val end = if (lastSpace > start) lastSpace else start + max
            chunks.add(sentence.substring(start, end))
            start = if (lastSpace > start) lastSpace + 1 else end
        }
        return chunks.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun protectedPeriods(text: String): Set<Int> {
        val protected = mutableSetOf<Int>()
        // 「总是保护」档先收集：即使同段文字里同时命中其它规则，也不该被当成句界。
        alwaysProtectedAbbreviation.findAll(text).forEach { match ->
            protected += match.range.last
        }
        titleAbbreviation.findAll(text).forEach { match ->
            protected += match.range.last
        }
        sentenceFinalAbbreviation.findAll(text).forEach { match ->
            // 句点后可能紧跟闭合引号/括号（"No." Then he left.）——真正的
            // 「下一个词」在闭合串之后，必须越过它再看，否则保护会吞掉
            // 引语结束后的真句界，发言和旁白被并成一句只能用同一个声音读。
            var cursor = match.range.last + 1
            while (cursor < text.length && (text[cursor] == ' ' || text[cursor] in closing)) {
                cursor++
            }
            val after = text.getOrNull(cursor)
            if (after == null || !after.isUpperCase()) {
                protected += match.range.last
            }
        }
        initials.findAll(text).forEach { match ->
            // Internal periods are always protected; the final period is only
            // protected when followed by a lowercase word, so
            // "in the U.S. Next year" still splits after "U.S."
            val periods = match.range.filter { text[it] == '.' }
            periods.dropLast(1).forEach(protected::add)
            val last = periods.lastOrNull() ?: return@forEach
            val after = (match.range.last + 1 until text.length)
                .firstOrNull { text[it] != ' ' }
                ?.let { text[it] }
            if (after != null && after.isLowerCase()) {
                protected += last
            }
        }
        spacedInitials.findAll(text).forEach { match ->
            match.range.filter { text[it] == '.' }.forEach(protected::add)
        }
        return protected
    }

    private fun Char.isCjkIdeograph(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'

    /**
     * [until] 之前是否存在**未闭合的中文引号**（`「` / `『`）。
     *
     * 用途：中文 `！`/`？` 后紧跟 CJK 时本应无条件切分，但如果这个问号/叹号落在嵌套
     * 引语内部（`他问：「你听见『谁在敲门？』了吗？」`），切下去会把外层引语行劈成
     * 两半 —— TTS 会用两个声音读同一句，对齐侧也会多出一对句对（审查 2-1）。
     */
    private fun hasUnclosedCjkQuote(text: String, until: Int): Boolean {
        var depth = 0
        for (i in 0 until until.coerceAtMost(text.length)) {
            when (text[i]) {
                '「', '『' -> depth++
                '」', '』' -> if (depth > 0) depth--
            }
        }
        return depth > 0
    }
}
