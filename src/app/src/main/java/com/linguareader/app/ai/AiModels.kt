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
    val matchedPhrase: String?
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
    val model: String = "deepseek-chat"
) {
    /** Remote AI is only used when the user enabled it and supplied a key. */
    val remoteReady: Boolean get() = enabled && apiKey.isNotBlank()
}

/** Per-book generation status shown in the UI. */
data class AiBookStatus(
    val generating: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null
)

/** Thrown when the remote AI provider fails. */
class AiRequestException(message: String) : Exception(message)
