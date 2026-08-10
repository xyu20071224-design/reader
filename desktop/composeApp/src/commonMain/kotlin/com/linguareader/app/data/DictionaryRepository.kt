package com.linguareader.app.data

import com.linguareader.app.platform.ensureDictionaryFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RawDictionaryEntry(
    val word: String,
    val phonetic: String,
    val translation: String,
    val definition: String
)

class DictionaryRepository {
    private var database: SqliteDatabase? = null
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
            val db = database ?: openDatabase().also { database = it }
            val tokens = ContextAnalyzer.tokenize(lookup.sentence)
            val targetIndex = ContextAnalyzer.targetIndex(tokens, lookup)
            val inferred = ContextAnalyzer.inferPartOfSpeech(tokens, targetIndex)

            val clicked = tokens.getOrNull(targetIndex)
            val clickedLemma = clicked?.let { token ->
                lemmaCandidates(db, dictionarySpelling(token.text)).firstOrNull()
                    ?: ContextAnalyzer.normalize(token.text)
            }.orEmpty()

            // Collect every phrase hit in window order (longest first), then
            // prefer the first hit in which the tapped word is the semantic
            // core. If none is core, keep the longest hit as the "related
            // phrase" entry and fall back to the word lookup.
            val phraseMatches = buildList {
                for (window in ContextAnalyzer.phraseWindows(tokens, targetIndex)) {
                    phraseCandidates(db, window).firstNotNullOfOrNull { candidate ->
                        cachedQuery(db, candidate)
                    }?.let { add(window to it) }
                }
            }
            val coreMatch = phraseMatches.firstOrNull { (_, raw) ->
                ContextAnalyzer.isCorePhraseToken(
                    clickedLemma,
                    raw.word,
                    phraseLemmas(db, raw.word)
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
            val rawWord = wordCandidates(db, lookup.word).firstNotNullOfOrNull { cachedQuery(db, it) }

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

    private fun phraseCandidates(db: SqliteDatabase, window: PhraseWindow): List<String> {
        val surface = window.tokens.joinToString(" ") { dictionarySpelling(it.text) }
        val lemmatized = window.tokens.joinToString(" ") { token ->
            lemmaCandidates(db, dictionarySpelling(token.text)).firstOrNull()
                ?: dictionarySpelling(token.text)
        }
        return listOf(surface, lemmatized).filter(String::isNotBlank).distinct()
    }

    private fun phraseLemmas(db: SqliteDatabase, phrase: String): List<String> =
        phrase.lowercase()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { lemmaCandidates(db, dictionarySpelling(it)).firstOrNull() ?: it }

    private fun wordCandidates(db: SqliteDatabase, surfaceWord: String): List<String> {
        val clean = dictionarySpelling(surfaceWord)
        val mapped = lemmaCandidates(db, clean)
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

    private fun cachedQuery(db: SqliteDatabase, word: String): RawDictionaryEntry? {
        synchronized(entryCache) {
            if (entryCache.containsKey(word)) return entryCache[word]
            val entry = queryEntry(db, word)
            entryCache[word] = entry
            return entry
        }
    }

    private fun lemmaCandidates(db: SqliteDatabase, form: String): List<String> =
        db.query(
            """
            SELECT f.lemma
            FROM forms f
            JOIN entries e ON e.word = f.lemma
            WHERE f.form = ?
            ORDER BY CASE WHEN f.lemma = ? THEN 1 ELSE 0 END, length(f.lemma)
            LIMIT 4
            """.trimIndent(),
            arrayOf(form, form)
        ).map { it.string(0) }

    private fun queryEntry(db: SqliteDatabase, word: String): RawDictionaryEntry? {
        val rows = db.query(
            "SELECT word, phonetic, translation, definition FROM entries WHERE word = ? LIMIT 1",
            arrayOf(word)
        )
        if (rows.isEmpty()) return null
        val row = rows[0]
        return RawDictionaryEntry(
            word = row.string(0),
            phonetic = row.string(1),
            translation = row.string(2),
            definition = row.string(3)
        )
    }

    private fun openDatabase(): SqliteDatabase {
        return SqliteDatabase(ensureDictionaryFile().absolutePath)
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
