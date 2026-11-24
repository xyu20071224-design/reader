package com.linguareader.shared.tts

/**
 * A runtime-only candidate phrase plan. It does not change TtsChapter sentences/segments, audio
 * cache keys, or pipeline versions, and is not enabled by the playback engine in this change.
 * Durations and the tail multiplier are audition candidates, not claimed language standards.
 */
object ProsodyPlanner {
    enum class Mode { ORIGINAL, PHRASES }

    data class Policy(
        val mode: Mode = Mode.ORIGINAL,
        val commaPauseMillis: Long = 100,
        val clausePauseMillis: Long = 160,
        val tailRateMultiplier: Float = 0.96f,
        val minimumPhraseChars: Int = 24,
        val maximumUnits: Int = 8
    ) {
        init {
            require(commaPauseMillis >= 0 && clausePauseMillis >= 0)
            require(tailRateMultiplier.isFinite() && tailRateMultiplier > 0)
            require(minimumPhraseChars > 0 && maximumUnits > 0)
        }
    }

    data class Unit(
        /** Keeps the parent sentence/segment IDs and speaker; text/offset locate this exact part. */
        val utterance: TtsUtterance,
        /** Runtime part index only: never substitute this for the persistent segmentIndex. */
        val partIndex: Int,
        val rate: Float,
        /** Pause before the next part. The last part is always zero: sentence-end pause is external. */
        val pauseAfterMillis: Long
    )

    /**
     * [protectedRanges] are inclusive ranges relative to [source.text], supplied by the caller when
     * it knows surrounding quote context (for example a continuation of a cross-block quote).
     * Local quoted spans and bracketed text are also protected without changing any source text.
     */
    fun plan(
        source: TtsUtterance,
        baseRate: Float,
        policy: Policy = Policy(),
        protectedRanges: List<IntRange> = emptyList()
    ): List<Unit> {
        require(baseRate.isFinite())
        val rate = baseRate.coerceIn(0.5f, 2f)
        if (policy.mode == Mode.ORIGINAL || source.text.isBlank() || policy.maximumUnits == 1) {
            return listOf(Unit(source, 0, rate, 0))
        }
        val text = source.text
        val protected = BooleanArray(text.length)
        for (range in QuoteSpans.spans(listOf(text)).single() + protectedRanges) {
            if (range.isEmpty()) continue
            require(range.first >= 0 && range.last < text.length) { "Protected range is outside the source utterance" }
            for (index in range) protected[index] = true
        }
        data class Part(val from: Int, val until: Int, val pause: Long)
        val parts = mutableListOf<Part>()
        var from = 0
        var bracketDepth = 0
        for (index in text.indices) {
            if (protected[index]) continue
            when (text[index]) {
                '(', '[', '{', '（', '【' -> { bracketDepth++; continue }
                ')', ']', '}', '）', '】' -> { bracketDepth = (bracketDepth - 1).coerceAtLeast(0); continue }
            }
            if (bracketDepth > 0 || parts.size + 1 >= policy.maximumUnits) continue
            val pause = boundaryPause(text, index, policy) ?: continue
            if (spokenLength(text, from, index + 1) < policy.minimumPhraseChars ||
                spokenLength(text, index + 1, text.length) < policy.minimumPhraseChars) continue
            parts += Part(from, index + 1, pause)
            from = index + 1
        }
        if (parts.isEmpty()) return listOf(Unit(source, 0, rate, 0))
        parts += Part(from, text.length, 0)
        val slowTail = text.trimEnd().lastOrNull() in listOf('.', '!', '?', '。', '！', '？')
        return parts.mapIndexed { index, part ->
            var start = part.from
            var end = part.until
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            val localRate = if (slowTail && index == parts.lastIndex) {
                (rate * policy.tailRateMultiplier).coerceIn(0.5f, 2f)
            } else rate
            Unit(
                utterance = source.copy(
                    text = text.substring(start, end),
                    offset = if (source.offset >= 0) source.offset + start else source.offset
                ),
                partIndex = index,
                rate = localRate,
                pauseAfterMillis = part.pause
            )
        }
    }

    private fun spokenLength(text: String, from: Int, until: Int): Int =
        (from until until).count { text[it].isLetterOrDigit() }

    private fun boundaryPause(text: String, index: Int, policy: Policy): Long? {
        val char = text[index]
        if (char !in listOf(',', '，', ';', '；', ':', '：', '—')) return null
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        // Group repeated delimiters instead of leaving one at the beginning of the next part.
        if (after == char) return null
        if (before?.isDigit() == true && after?.isDigit() == true) return null
        // Keep e.g., i.e., etc., and initials intact without creating a second abbreviation table.
        if ((char == ',' || char == '，') && before == '.') return null
        if (char == ':' || char == '：') {
            if (after == '/' || after == '\\') return null
            val tokenStart = text.lastIndexOf(' ', index).let { if (it < 0) 0 else it + 1 }
            val tokenEnd = text.indexOf(' ', index).let { if (it < 0) text.length else it }
            val token = text.substring(tokenStart, tokenEnd)
            if ("://" in token || ('@' in token) || (after?.isDigit() == true && '.' in token)) return null
        }
        return if (char == ',' || char == '，') policy.commaPauseMillis else policy.clausePauseMillis
    }
}
