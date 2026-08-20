package com.linguareader.app.tts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-book character → voice mapping (PLAN-MULTI-VOICE §3.3), persisted so a
 * character never changes voice between chapters or sessions (§5.3
 * 「一致性 > 单次正确」).
 *
 * - [narrator] is keyed by language ("en"/"zh") because a mixed book needs one
 *   narration voice per language;
 * - [characterVoice] is keyed by the canonical character name (lookups are
 *   case-insensitive);
 * - [userLocked] holds keys the user pinned by hand ([keyOf] / [narratorKey]);
 *   automatic assignment must never touch them;
 * - [engine] records which engine the map was computed for, so switching
 *   engines (different voice library) triggers a re-assignment that still
 *   keeps the locked entries.
 */
data class BookVoiceMap(
    val bookId: String,
    val narrator: Map<String, String> = emptyMap(),
    val characterVoice: Map<String, String> = emptyMap(),
    val userLocked: Set<String> = emptySet(),
    val engine: String = ""
) {
    val isEmpty: Boolean get() = narrator.isEmpty() && characterVoice.isEmpty()

    /**
     * Voice for a speaker tag, or null when the caller should fall back to its
     * own default (the unattributed "dialogue" marker always lands here).
     */
    fun voiceFor(speaker: String, language: String = TtsLanguage.ENGLISH): String? {
        val name = speaker.trim()
        if (name.isEmpty()) return null
        if (name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) return narratorFor(language)
        return characterVoice.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    /** Narration voice for [language], degrading to any configured narrator. */
    fun narratorFor(language: String): String? =
        (narrator[language] ?: narrator[language.lowercase()] ?: narrator[TtsLanguage.ENGLISH])
            ?.takeIf { it.isNotBlank() }
            ?: narrator.values.firstOrNull { it.isNotBlank() }

    fun isLocked(key: String): Boolean = userLocked.any { it.equals(key, ignoreCase = true) }

    /** Pins one speaker to a voice; user choices survive every re-assignment. */
    fun lock(speaker: String, voiceId: String): BookVoiceMap {
        val name = speaker.trim()
        if (name.isEmpty() || voiceId.isBlank()) return this
        if (name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
            return lockNarrator(TtsLanguage.ENGLISH, voiceId)
        }
        val cleaned = characterVoice.filterKeys { !it.equals(name, ignoreCase = true) }
        return copy(
            characterVoice = cleaned + (name to voiceId),
            userLocked = userLocked + keyOf(name)
        )
    }

    fun lockNarrator(language: String, voiceId: String): BookVoiceMap {
        if (voiceId.isBlank()) return this
        return copy(
            narrator = narrator + (language to voiceId),
            userLocked = userLocked + narratorKey(language)
        )
    }

    /** Releases a pin so the assigner may move that speaker again. */
    fun unlock(speaker: String): BookVoiceMap {
        val name = speaker.trim()
        if (name.isEmpty()) return this
        val key = if (name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
            narratorKey(TtsLanguage.ENGLISH)
        } else {
            keyOf(name)
        }
        return copy(userLocked = userLocked.filterNot { it.equals(key, ignoreCase = true) }.toSet())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId)
        .put("narrator", JSONObject().apply { narrator.forEach { (k, v) -> put(k, v) } })
        .put("characterVoice", JSONObject().apply { characterVoice.forEach { (k, v) -> put(k, v) } })
        .put("userLocked", JSONArray().apply { userLocked.forEach { put(it) } })
        .put("engine", engine)

    companion object {
        /** Lock/lookup key of a character. */
        fun keyOf(name: String): String = name.trim().lowercase()

        /** Lock key of the narration voice of one language. */
        fun narratorKey(language: String): String = "narrator:" + language.trim().lowercase()

        fun fromJson(json: JSONObject): BookVoiceMap {
            fun stringMap(key: String): Map<String, String> {
                val obj = json.optJSONObject(key) ?: return emptyMap()
                val out = linkedMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = obj.optString(name)
                    if (name.isNotBlank() && value.isNotBlank()) out[name] = value
                }
                return out
            }

            val locked = json.optJSONArray("userLocked")
            return BookVoiceMap(
                bookId = json.optString("bookId"),
                narrator = stringMap("narrator"),
                characterVoice = stringMap("characterVoice"),
                userLocked = if (locked == null) emptySet() else (0 until locked.length())
                    .mapNotNull { locked.optString(it).trim().takeIf(String::isNotBlank) }
                    .toSet(),
                engine = json.optString("engine")
            )
        }
    }
}
