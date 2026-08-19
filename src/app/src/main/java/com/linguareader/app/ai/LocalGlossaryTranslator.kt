package com.linguareader.app.ai

/**
 * Fully offline lightweight "AI" that needs no API key.
 *
 * English books: capitalized proper nouns and repeated multiword names are
 * recorded with their frequency and first occurrence. Chinese/Japanese/Korean
 * books: repeated 2-4 character CJK sequences touching a word boundary are
 * collected the same way (with a small function-character stoplist and
 * substring dedup to keep the noise down). Lookups for those terms get a
 * book-specific hint instead of a generic dictionary sense.
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

        fun record(key: String, surface: String, sentence: String, chapterIndex: Int) {
            counts[key] = (counts[key] ?: 0) + 1
            chapterCounts.getOrPut(key) { mutableSetOf() }.add(chapterIndex)
            if (!firstCase.containsKey(key)) firstCase[key] = surface
            if (!firstSentence.containsKey(key)) firstSentence[key] = sentence.take(120)
        }

        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                val sentence = match.value.trim()
                var firstWord = true
                WORD_PATTERN.findAll(sentence).forEach { wordMatch ->
                    val raw = wordMatch.value.trim('\'', '’', '"', '“', '”')
                    if (firstWord) {
                        firstWord = false
                        if (raw.lowercase() in SENTENCE_START_STOP_WORDS) return@forEach
                    }
                    if (raw.length < 2 || !raw.first().isUpperCase()) return@forEach
                    record(raw.lowercase(), raw, sentence, chapter.index)
                }
            }
        }

        // Repeated capitalized bigrams ("Harry Potter", "Ministry of Magic"
        // misses "of" but still catches most character and place names).
        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                BIGRAM_PATTERN.findAll(match.value).forEach { bigram ->
                    record(bigram.value.lowercase(), bigram.value, match.value.trim(), chapter.index)
                }
            }
        }

        // CJK has no casing to key on, so collect repeated 2-4 character
        // n-grams that touch a non-CJK boundary on at least one side (interior
        // windows of a longer run are spurious splits of a longer name).
        chapters.forEach { chapter ->
            SENTENCE_PATTERN.findAll(chapter.text).forEach { match ->
                CJK_RUN_PATTERN.findAll(match.value).forEach { run ->
                    val value = run.value
                    if (value.length < 2) return@forEach
                    for (size in 2..minOf(4, value.length)) {
                        for (start in 0..value.length - size) {
                            if (start != 0 && start + size != value.length) continue
                            val gram = value.substring(start, start + size)
                            if (gram.any { it in CJK_STOP_CHARS }) continue
                            record(gram.lowercase(), gram, match.value.trim(), chapter.index)
                        }
                    }
                }
            }
        }

        val entries = counts.entries
            .filter { entry ->
                val minCount = if (entry.key.all { it.isCjk() }) CJK_MIN_COUNT else 2
                entry.value >= minCount && (chapterCounts[entry.key]?.size ?: 0) >= 2
            }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val terms = dropContainedCjkSubstrings(entries)
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
        // CJK has no spaces, so a tapped 2+ character word may be a prefix of
        // a longer book term ("哈利" -> "哈利波特"); match containment there.
        val cjkWanted = wanted.length >= 2 && wanted.all { it.isCjk() }
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
                    (request.matchedPhrase?.lowercase()?.contains(key) == true) ||
                    (cjkWanted && key.contains(wanted))
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
        /**
         * Words routinely capitalized at the start of an English sentence but
         * not named entities on their own. Keeping them out stops "The / He /
         * She / It" from filling the context slots with word-frequency noise.
         */
        private val SENTENCE_START_STOP_WORDS = setOf(
            "the", "a", "an", "this", "that", "these", "those",
            "he", "she", "it", "we", "they", "i", "you",
            "there", "here", "when", "where", "what", "who", "whom",
            "which", "why", "how", "and", "but", "or", "so", "if", "then",
            "than", "as", "at", "by", "for", "from", "in", "into", "of",
            "on", "to", "with", "without", "not", "no", "yes", "all",
            "any", "each", "every", "some", "such", "only", "one", "two"
        )

        /** CJK n-grams need a higher bar than capitalized English words to stay useful. */
        private const val CJK_MIN_COUNT = 3

        /**
         * Common CJK function characters. Any n-gram containing one is
         * dropped, which filters most grammatical noise ("他们", "但是"…)
         * while keeping proper nouns and content vocabulary.
         */
        private val CJK_STOP_CHARS = setOf(
            '的', '了', '是', '在', '我', '你', '他', '她', '它', '们', '这', '那',
            '和', '与', '或', '而', '但', '就', '都', '也', '很', '被', '把', '让',
            '给', '对', '从', '向', '到', '着', '过', '又', '再', '还', '才', '呢',
            '吗', '吧', '啊', '么', '什', '怎', '只', '可', '没', '不', '个', '些',
            '已', '经', '因', '为', '所', '以', '如', '果', '时', '候'
        )

        private data class TermCandidate(
            val term: String,
            val translation: String,
            val note: String
        )

        /**
         * Drops CJK n-grams fully contained in a longer, at-least-as-frequent
         * CJK term — spurious splits of a longer name ("哈利波" inside
         * "哈利波特", "沃茨" inside "霍格沃茨"). A shorter term that also
         * occurs on its own keeps a higher count and survives.
         */
        private fun dropContainedCjkSubstrings(
            entries: List<Map.Entry<String, Int>>
        ): List<Map.Entry<String, Int>> =
            entries.filter { candidate ->
                if (!candidate.key.all { it.isCjk() }) return@filter true
                entries.none { other ->
                    other.key != candidate.key &&
                        other.key.length > candidate.key.length &&
                        other.key.all { it.isCjk() } &&
                        other.key.contains(candidate.key) &&
                        other.value >= candidate.value
                }
            }

        private val WORD_PATTERN = Regex("""[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*""")
        private val SENTENCE_PATTERN = Regex("""[^.!?…]+[.!?…]+|[^.!?…]+$""")
        private val BIGRAM_PATTERN = Regex(
            """\b[A-Z][A-Za-zÀ-ÖØ-öø-ÿ']+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\s+[A-Z][A-Za-zÀ-ÖØ-öø-ÿ']+(?:['’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*\b"""
        )
        private val CJK_RUN_PATTERN = Regex(
            """[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\uAC00-\uD7AF]{2,}"""
        )
    }
}
