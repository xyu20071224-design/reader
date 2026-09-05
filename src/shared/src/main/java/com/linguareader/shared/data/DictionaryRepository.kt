package com.linguareader.shared.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DictionaryRepository(private val db: DictionaryDatabase) {
    // Context-independent entry cache: the same headword tapped in different
    // sentences shares one lookup. Contextual sense ordering is recomputed per
    // query (cheap in-memory work) and therefore not cached.
    private val entryCache = object : LinkedHashMap<String, RawDictionaryEntry?>(256, .75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, RawDictionaryEntry?>?
        ): Boolean = size > 256
    }

    suspend fun lookup(lookup: WordLookup): DictionaryLookupResult =
        withContext(Dispatchers.IO) {
            val tokens = ContextAnalyzer.tokenize(lookup.sentence)
            val targetIndex = ContextAnalyzer.targetIndex(tokens, lookup)
            val inferred = ContextAnalyzer.inferPartOfSpeech(tokens, targetIndex)

            val clicked = tokens.getOrNull(targetIndex)
            val clickedLemma = clicked?.let { token ->
                db.lemmaCandidates(dictionarySpelling(token.text)).firstOrNull()
                    ?: ContextAnalyzer.normalize(token.text)
            }.orEmpty()

            // Collect every phrase hit in window order (longest first), then
            // prefer the first hit in which the tapped word is the semantic
            // core. If none is core, keep the longest hit as the "related
            // phrase" entry and fall back to the word lookup.
            val phraseMatches = buildList {
                for (window in ContextAnalyzer.phraseWindows(tokens, targetIndex)) {
                    phraseCandidates(window).firstNotNullOfOrNull { candidate ->
                        cachedQuery(candidate)
                    }?.let { add(window to it) }
                }
            }
            val coreMatch = phraseMatches.firstOrNull { (_, raw) ->
                ContextAnalyzer.isCorePhraseToken(
                    clickedLemma,
                    raw.word,
                    phraseLemmas(raw.word)
                )
            }
            val phraseMatch = coreMatch ?: phraseMatches.firstOrNull()
            val phraseCore = coreMatch != null

            fun toEntry(raw: RawDictionaryEntry, matchedPhrase: String?): ContextualDictionaryEntry =
                ContextualDictionaryEntry(
                    surfaceWord = lookup.word,
                    headword = raw.word,
                    phonetic = raw.phonetic,
                    senses = ContextAnalyzer.senses(raw.translation, inferred),
                    definitions = ContextAnalyzer.definitions(raw.definition),
                    matchedPhrase = matchedPhrase,
                    inferredPartOfSpeech = inferred
                )

            val rawPhrase = phraseMatch?.second
            val rawWord = wordCandidates(lookup.word).firstNotNullOfOrNull { cachedQuery(it) }

            if (rawPhrase != null && phraseCore) {
                DictionaryLookupResult(
                    entry = toEntry(rawPhrase, rawPhrase.word),
                    relatedPhrase = null
                )
            } else {
                DictionaryLookupResult(
                    entry = rawWord?.let { toEntry(it, null) },
                    relatedPhrase = rawPhrase?.let { toEntry(it, it.word) }
                )
            }
        }

    private fun phraseCandidates(window: PhraseWindow): List<String> {
        val surface = window.tokens.joinToString(" ") { dictionarySpelling(it.text) }
        val lemmatized = window.tokens.joinToString(" ") { token ->
            db.lemmaCandidates(dictionarySpelling(token.text)).firstOrNull()
                ?: dictionarySpelling(token.text)
        }
        return listOf(surface, lemmatized).filter(String::isNotBlank).distinct()
    }

    private fun phraseLemmas(phrase: String): List<String> =
        phrase.lowercase()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { db.lemmaCandidates(dictionarySpelling(it)).firstOrNull() ?: it }

    private fun wordCandidates(surfaceWord: String): List<String> {
        val clean = dictionarySpelling(surfaceWord)
        val mapped = db.lemmaCandidates(clean)
        return buildList {
            addAll(mapped)
            // Heuristics are a fallback; skip them when the forms table hit.
            if (mapped.isEmpty()) addAll(heuristicCandidates(clean))
            add(clean)
            if ('-' in clean) {
                addAll(clean.split('-').filter { it.length > 1 }.reversed())
            }
        }.filter(String::isNotBlank).distinct()
    }

    private fun cachedQuery(word: String): RawDictionaryEntry? {
        synchronized(entryCache) {
            if (entryCache.containsKey(word)) return entryCache[word]
            val entry = db.queryEntry(word)
            entryCache[word] = entry
            return entry
        }
    }

    private fun dictionarySpelling(value: String): String {
        val lowered = value.lowercase().trim('’', '\'', '"', '“', '”', ',', ';', ':', '!', '?')
        return if (lowered.count { it == '.' } >= 2) lowered else lowered.trim('.')
    }

    private fun heuristicCandidates(clean: String): List<String> = buildList {
        if (clean.endsWith("ing") && clean.length > 5) {
            val stem = clean.dropLast(3)
            if (stem.length > 2 && stem.last() == stem[stem.lastIndex - 1]) add(stem.dropLast(1))
            add(stem)
            add(stem + "e")
        }
        if (clean.endsWith("ied") && clean.length > 5) add(clean.dropLast(3) + "y")
        if (clean.endsWith("ed") && clean.length > 4) {
            val stem = clean.dropLast(2)
            if (stem.length > 2 && stem.last() == stem[stem.lastIndex - 1]) add(stem.dropLast(1))
            add(stem)
            add(stem + "e")
            if (stem.endsWith("i")) add(stem.dropLast(1) + "y")
        }
        if (clean.endsWith("ies") && clean.length > 4) add(clean.dropLast(3) + "y")
        if (clean.endsWith("'s") || clean.endsWith("’s")) add(clean.dropLast(2))
        if (clean.endsWith("es") && clean.length > 3 && !clean.endsWith("ies")) {
            add(clean.dropLast(2))
            add(clean.dropLast(1))
        }
        if (clean.endsWith("s") && clean.length > 3) add(clean.dropLast(1))
    }
}
