package com.linguareader.app.ai

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

        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                val sentence = match.value.trim()
                WORD_PATTERN.findAll(sentence).forEach { wordMatch ->
                    val raw = wordMatch.value.trim('\'', '’', '"', '“', '”')
                    if (raw.length < 2 || !raw.first().isUpperCase()) return@forEach
                    val key = raw.lowercase()
                    counts[key] = (counts[key] ?: 0) + 1
                    chapterCounts.getOrPut(key) { mutableSetOf() }.add(chapter.index)
                    if (!firstCase.containsKey(key)) firstCase[key] = raw
                    if (!firstSentence.containsKey(key)) firstSentence[key] = sentence.take(120)
                }
            }
        }

        // Repeated capitalized bigrams ("Harry Potter", "Ministry of Magic"
        // misses "of" but still catches most character and place names).
        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                BIGRAM_PATTERN.findAll(match.value).forEach { bigram ->
                    val key = bigram.value.lowercase()
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
            styleNotes = emptyList()
        )
    }

    override suspend fun translate(
        profile: BookContextProfile,
        request: AiLookupRequest
    ): AiLookupResult? {
        val wanted = request.headword.lowercase()
        val surface = request.surfaceWord.lowercase()
        val term = (profile.characters + profile.places + profile.glossary)
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
        private val WORD_PATTERN = Regex("""[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*""")
        private val SENTENCE_PATTERN = Regex("""[^.!?…]+[.!?…]+|[^.!?…]+$""")
        private val BIGRAM_PATTERN = Regex(
            """\b[A-Z][A-Za-zÀ-ÖØ-öø-ÿ']+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\s+[A-Z][A-Za-zÀ-ÖØ-öø-ÿ']+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\b"""
        )
    }
}
