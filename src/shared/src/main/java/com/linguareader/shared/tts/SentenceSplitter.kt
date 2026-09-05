package com.linguareader.shared.tts

/**
 * Splits mixed English / Chinese prose into TTS-sized sentences.
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
    private val terminators = setOf('.', '!', '?', '…', '。', '！', '？')
    private val cjkTerminators = setOf('。', '！', '？')
    private val closing = setOf('"', '\'', '”', '’', ')', ']', '）', '】', '」', '』')

    fun split(raw: String, maxSentenceLength: Int = Int.MAX_VALUE): List<String> {
        val text = raw.replace(whitespace, " ").trim()
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
                    hasCjkTerminator -> true
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
}
