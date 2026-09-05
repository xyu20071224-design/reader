package com.linguareader.shared.ai

/**
 * Fully offline lightweight "AI" that needs no API key.
 *
 * It builds a per-book glossary from word statistics: capitalized proper
 * nouns and repeated multiword names are recorded with their frequency and
 * first occurrence. Lookups for those terms get a book-specific hint instead
 * of a generic dictionary sense.
 */
class LocalGlossaryTranslator : AiTranslator {
    override val id = "local-glossary"
    override val displayName = "本地轻量语境"
    override val offline = true

    override suspend fun buildBookContext(
        bookTitle: String,
        chapters: List<ChapterText>
    ): BookContextProfile {
        val counts = mutableMapOf<String, Int>()
        val chapterCounts = mutableMapOf<String, MutableSet<Int>>()
        val firstCase = mutableMapOf<String, String>()
        val firstSentence = mutableMapOf<String, String>()
        val midSentenceCounts = mutableMapOf<String, Int>()

        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                val sentence = match.value.trim()
                WORD_PATTERN.findAll(sentence).forEach { wordMatch ->
                    val raw = wordMatch.value.trim('\'', '’', '"', '“', '”')
                    if (raw.length < 2 || !raw.first().isUpperCase()) return@forEach
                    val key = raw.lowercase()
                    // 句首大写是英文常态（And/As/At…），不能作为专名证据：
                    // 停用词直接排除；普通词也必须至少一次出现在句中位置。
                    if (key in STOP_WORDS) return@forEach
                    counts[key] = (counts[key] ?: 0) + 1
                    chapterCounts.getOrPut(key) { mutableSetOf() }.add(chapter.index)
                    if (!isSentenceInitial(sentence, wordMatch.range.first)) {
                        midSentenceCounts[key] = (midSentenceCounts[key] ?: 0) + 1
                    }
                    if (!firstCase.containsKey(key)) firstCase[key] = raw
                    if (!firstSentence.containsKey(key)) firstSentence[key] = sentence.take(120)
                }
            }
        }

        // Repeated capitalized bigrams ("Harry Potter", "Ministry of Magic"
        // misses "of" but still catches most character and place names).
        val bigramKeys = mutableSetOf<String>()
        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                BIGRAM_PATTERN.findAll(match.value).forEach { bigram ->
                    val key = bigram.value.lowercase()
                    val words = key.split(Regex("\\s+"))
                    if (words.first() in STOP_WORDS && words.last() in STOP_WORDS) return@forEach
                    bigramKeys += key
                    counts[key] = (counts[key] ?: 0) + 1
                    chapterCounts.getOrPut(key) { mutableSetOf() }.add(chapter.index)
                    if (!firstCase.containsKey(key)) firstCase[key] = bigram.value
                    if (!firstSentence.containsKey(key)) {
                        firstSentence[key] = match.value.trim().take(120)
                    }
                }
            }
        }

        val terms = counts.entries
            .filter { it.value >= 2 && (chapterCounts[it.key]?.size ?: 0) >= 2 }
            // 句中位置要求只针对单词：大写双词组合（Harry Potter…）本身已是
            // 强专名证据，且人名/书名组合常只出现在句首，不能因此误杀。
            .filter { (midSentenceCounts[it.key] ?: 0) >= 1 || it.key in bigramKeys }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(60)
            .map { (key, count) ->
                ContextTerm(
                    term = firstCase[key] ?: key,
                    translation = "",
                    note = "本书出现 $count 次；首次见：${firstSentence[key] ?: ""}"
                )
            }

        return BookContextProfile(
            bookId = "",
            bookTitle = bookTitle,
            summary = "本地轻量语境档案：由词频统计生成，仅提供本书反复出现的专名/术语一致性提示，不做语义推断。",
            characters = terms.take(30),
            glossary = terms.drop(30),
            styleNotes = emptyList(),
            source = "local"
        )
    }

    override suspend fun translate(
        profile: BookContextProfile,
        request: AiLookupRequest
    ): AiLookupResult? {
        val wanted = request.headword.lowercase()
        val surface = request.surfaceWord.lowercase()
        val candidates = (
            if (request.glossary.isNotEmpty()) {
                request.glossary.map { TermCandidate(it.term, it.translation, it.note) }
            } else {
                (profile.characters + profile.places + profile.glossary)
                    .map { TermCandidate(it.term, it.translation, it.note) }
            }
            )
        val term = candidates
            .firstOrNull {
                val key = it.term.lowercase()
                key == wanted || key == surface ||
                    (request.matchedPhrase?.lowercase()?.contains(key) == true)
            }
            ?: return null

        val meaning = term.translation.ifBlank {
            request.localSenses.firstOrNull().orEmpty()
        }
        if (meaning.isBlank() && term.note.isBlank()) return null

        return AiLookupResult(
            headword = request.headword,
            contextualMeaning = meaning,
            explanation = term.note,
            phrase = request.matchedPhrase,
            source = displayName
        )
    }

    companion object {
        private data class TermCandidate(
            val term: String,
            val translation: String,
            val note: String
        )

        private val WORD_PATTERN = Regex("""[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*""")
        private val SENTENCE_PATTERN = Regex("""[^.!?…]+[.!?…]+|[^.!?…]+$""")
        private val BIGRAM_PATTERN = Regex(
            """\b[A-Z][A-Za-zÀ-ÖØ-öø-ÿ']+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\s+[A-Z][A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\b"""
        )

        /**
         * 英文高频功能词：它们出现在句首时必然大写，是「And/As/At 被当成角色」
         * 这类误报的主要来源。专名判定直接跳过（人名撞词如 Will 属可接受损失）。
         * [BookGlossary.sanitized] 用同一张表清理历史脏数据，所以保持 internal。
         */
        internal val STOP_WORDS = setOf(
            "a", "an", "the", "and", "but", "or", "nor", "for", "yet", "so",
            "as", "at", "by", "in", "on", "of", "to", "up", "off", "out",
            "if", "it", "its", "is", "am", "are", "was", "were", "be", "been", "being",
            "he", "she", "they", "we", "you", "i", "him", "her", "them", "us", "me",
            "his", "hers", "their", "theirs", "my", "your", "our",
            "this", "that", "these", "those", "there", "here",
            "when", "while", "then", "than", "thus", "hence",
            "what", "which", "who", "whom", "whose", "how", "why", "where",
            "all", "any", "some", "no", "not", "now", "never", "ever",
            "yes", "oh", "ah", "well", "mr", "mrs", "ms", "dr", "sir",
            "chapter", "part", "book"
        )

        /** [offset] 处的词是否位于句首（允许前面有引号/破折号/括号）。 */
        private fun isSentenceInitial(sentence: String, offset: Int): Boolean {
            val prefix = sentence.substring(0, offset).trimEnd()
            if (prefix.isEmpty()) return true
            return prefix.last() in OPENERS
        }

        private val OPENERS = setOf('“', '"', '‘', '\'', '—', '-', '(', '（', '«')
    }
}
