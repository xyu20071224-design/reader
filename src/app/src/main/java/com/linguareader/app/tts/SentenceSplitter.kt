package com.linguareader.app.tts

/**
 * Splits mixed English / Chinese prose into TTS-sized sentences.
 *
 * Rules:
 * - Terminators: `. ! ? … 。 ！ ？` (a run of terminators is one boundary).
 * - A trailing closing quote / bracket belongs to the finished sentence.
 * - Common abbreviations (`Mr.`, `Dr.`, `e.g.`, `U.S.`) do not split.
 * - English boundaries require whitespace (so `Hello.World` stays one token);
 *   Chinese boundaries split without whitespace.
 * - A continuation ellipsis (`Wait… what?`) stays inside the sentence.
 */
object SentenceSplitter {
    private val whitespace = Regex("\\s+")
    private val abbreviation = Regex("""(?i)\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr|St|vs|etc)\.""")
    private val initials = Regex("""\b(?:[A-Za-z]\.){2,}""")
    // "J. R. R. Tolkien"：缩写之间带空格，句点同样不是句末。
    private val spacedInitials = Regex("""\b[A-Za-z]\.(?:\s+[A-Za-z]\.)+""")
    private val terminators = setOf('.', '!', '?', '…', '。', '！', '？')
    private val cjkTerminators = setOf('。', '！', '？')
    private val closing = setOf('"', '\'', '”', '’', ')', ']', '）', '】', '」', '』')

    fun split(raw: String): List<String> {
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
                    char == '…' -> {
                        val next = nextNonSpace?.let { text[it] }
                        next == null || !next.isLowerCase()
                    }
                    // 收紧版：仅当句末标点后**有闭合引号归属**、且后面跟小写字母时，
                    // 才是说话人引导语（'I am sorry, Frodo!' he cried... 是一句）。
                    // 无引号归属的小写延续（正常句界）照旧切分，避免把大段对话并成一句。
                    else -> text[end] == ' ' &&
                        !(sawClosingQuote && (nextNonSpace?.let { text[it].isLowerCase() } ?: false))
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
        return result
    }

    private fun protectedPeriods(text: String): Set<Int> {
        val protected = mutableSetOf<Int>()
        abbreviation.findAll(text).forEach { match ->
            protected += match.range.last
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
}
