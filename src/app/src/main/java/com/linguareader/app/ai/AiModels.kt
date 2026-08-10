package com.linguareader.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Plain text of one chapter, used as AI book-context input. */
data class ChapterText(
    val index: Int,
    val title: String,
    val text: String
)

/** One book-specific term: character, place or recurring vocabulary. */
data class ContextTerm(
    val term: String,
    val translation: String = "",
    val note: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("term", term)
        .put("translation", translation)
        .put("note", note)

    companion object {
        fun fromJson(json: JSONObject) = ContextTerm(
            term = json.optString("term"),
            translation = json.optString("translation"),
            note = json.optString("note")
        )
    }
}

/**
 * Book-level translation context generated once per book and stored locally.
 *
 * The DeepSeek implementation produces this from the book text; the local
 * lightweight implementation derives a smaller version from word statistics.
 */
data class BookContextProfile(
    val bookId: String,
    val bookTitle: String,
    val summary: String = "",
    val characters: List<ContextTerm> = emptyList(),
    val places: List<ContextTerm> = emptyList(),
    val glossary: List<ContextTerm> = emptyList(),
    val styleNotes: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId)
        .put("bookTitle", bookTitle)
        .put("summary", summary)
        .put("characters", JSONArray().apply { characters.forEach { put(it.toJson()) } })
        .put("places", JSONArray().apply { places.forEach { put(it.toJson()) } })
        .put("glossary", JSONArray().apply { glossary.forEach { put(it.toJson()) } })
        .put("styleNotes", JSONArray().apply { styleNotes.forEach { put(it) } })

    companion object {
        fun fromJson(json: JSONObject): BookContextProfile {
            fun terms(key: String): List<ContextTerm> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { ContextTerm.fromJson(array.getJSONObject(it)) }
            }

            fun strings(key: String): List<String> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).map { array.getString(it) }
            }

            return BookContextProfile(
                bookId = json.optString("bookId"),
                bookTitle = json.optString("bookTitle"),
                summary = json.optString("summary"),
                characters = terms("characters"),
                places = terms("places"),
                glossary = terms("glossary"),
                styleNotes = strings("styleNotes")
            )
        }
    }
}

/** Everything sent to the AI layer for one tapped-word lookup. */
data class AiLookupRequest(
    val bookId: String,
    val bookTitle: String,
    val surfaceWord: String,
    val headword: String,
    val sentence: String,
    val paragraph: String,
    val localSenses: List<String>,
    val localDefinitions: List<String>,
    val matchedPhrase: String?,
    val glossary: List<GlossaryEntry> = emptyList()
)

/** Contextual answer shown as an enhancement above the local dictionary. */
data class AiLookupResult(
    val headword: String,
    val contextualMeaning: String,
    val explanation: String = "",
    val phrase: String? = null,
    val source: String
)

/** Provider-agnostic AI settings; stored locally, never in source control. */
data class AiSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val azureTranslationEnabled: Boolean = false,
    val azureKey: String = "",
    val azureRegion: String = "",
    val azureEndpoint: String = "https://api.cognitive.microsofttranslator.com"
) {
    /** Remote AI is only used when the user enabled it and supplied a key. */
    val remoteReady: Boolean get() = enabled && apiKey.isNotBlank()

    /** Azure sentence translation is independent from the DeepSeek profile. */
    val azureReady: Boolean get() = azureTranslationEnabled && azureKey.isNotBlank()
}

/** Per-book generation status shown in the UI. */
data class AiBookStatus(
    val generating: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null
)

/** Thrown when the remote AI provider fails. */
class AiRequestException(message: String) : Exception(message)

/** One book-specific glossary entry, editable by the user. */
data class GlossaryEntry(
    val term: String,
    val translation: String = "",
    val kind: String = "custom",
    val note: String = "",
    val enabled: Boolean = true,
    val origin: String = "manual",
    val updatedAt: Long = 0L
) {
    /** Stable key: one entry per lowercase term. */
    val key: String get() = term.lowercase()

    fun toJson(): JSONObject = JSONObject()
        .put("term", term)
        .put("translation", translation)
        .put("kind", kind)
        .put("note", note)
        .put("enabled", enabled)
        .put("origin", origin)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject) = GlossaryEntry(
            term = json.optString("term"),
            translation = json.optString("translation"),
            kind = json.optString("kind", "custom"),
            note = json.optString("note"),
            enabled = json.optBoolean("enabled", true),
            origin = json.optString("origin", "manual"),
            updatedAt = json.optLong("updatedAt")
        )
    }
}

/** Per-book glossary, stored separately from the AI context profile. */
data class BookGlossary(
    val bookId: String,
    val entries: List<GlossaryEntry> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId)
        .put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): BookGlossary {
            val array = json.optJSONArray("entries") ?: return BookGlossary(json.optString("bookId"))
            return BookGlossary(
                bookId = json.optString("bookId"),
                entries = (0 until array.length()).map { GlossaryEntry.fromJson(array.getJSONObject(it)) }
            )
        }
    }
}

/** A glossary term found inside a sentence, with its original surface text. */
data class GlossaryMatch(
    val entry: GlossaryEntry,
    val text: String,
    val start: Int,
    val endExclusive: Int
)

/**
 * Longest-first, non-overlapping matches of enabled glossary entries.
 *
 * Azure's dynamic dictionary markup is case-sensitive, so we keep the exact
 * surface text from the sentence while matching case-insensitively.
 */
fun BookGlossary.matchesIn(sentence: String): List<GlossaryMatch> {
    val lower = sentence.lowercase()
    val occupied = mutableListOf<IntRange>()
    return entries.asSequence()
        .filter { it.enabled && it.term.isNotBlank() }
        .sortedByDescending { it.term.length }
        .mapNotNull { entry ->
            val termLower = entry.term.lowercase()
            var searchFrom = 0
            while (true) {
                val index = lower.indexOf(termLower, searchFrom)
                if (index < 0) break
                val end = index + termLower.length
                val range = index until end
                if (occupied.none { index < it.last + 1 && end > it.first } &&
                    boundaryOk(sentence, index, end)
                ) {
                    occupied += range
                    return@mapNotNull GlossaryMatch(
                        entry = entry,
                        text = sentence.substring(index, end),
                        start = index,
                        endExclusive = end
                    )
                }
                searchFrom = index + 1
            }
            null
        }
        .toList()
}

private fun boundaryOk(sentence: String, start: Int, end: Int): Boolean {
    val before = if (start == 0) ' ' else sentence[start - 1]
    val after = if (end >= sentence.length) ' ' else sentence[end]
    return !before.isLetter() && !after.isLetter()
}
