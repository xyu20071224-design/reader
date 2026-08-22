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
 * Voice-facing character profile (PLAN-MULTI-VOICE §3.2).
 *
 * Produced as a by-product of the book context profile (D1: 语境档案书级生成时
 * 顺带产出角色画像表), merged into the per-book glossary so the user can edit
 * it, and consumed by the M2 speaker tagger (roster + aliases) and the M3
 * voice assigner (gender / age / style / importance).
 */
data class CharacterProfile(
    /** Canonical name, e.g. "Gandalf". */
    val name: String,
    /** Alternative names normalised onto [name], e.g. "Mithrandir". */
    val aliases: List<String> = emptyList(),
    /** male / female / unknown (blank = unknown). */
    val gender: String = "",
    /** child / young / adult / elderly / unknown (blank = unknown). */
    val ageGroup: String = "",
    /** Free-form voice hints: calm, deep, lively… */
    val style: List<String> = emptyList(),
    /** major / medium / minor — drives M3 assignment order. */
    val importance: String = IMPORTANCE_MINOR,
    /** Hard constraint for voice assignment ("en" / "zh"). */
    val language: String = "en",
    val confidence: Float = 0f,
    /** auto / local / manual — same vocabulary as [GlossaryEntry.origin]. */
    val origin: String = "auto"
) {
    /** Stable key: one profile per lowercase name. */
    val key: String get() = name.trim().lowercase()

    /** Assignment order for M3: major first, minor last. */
    val importanceRank: Int
        get() = when (importance.lowercase()) {
            IMPORTANCE_MAJOR -> 3
            IMPORTANCE_MEDIUM -> 2
            else -> 1
        }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("aliases", jsonStringArray(aliases))
        .put("gender", gender)
        .put("ageGroup", ageGroup)
        .put("style", jsonStringArray(style))
        .put("importance", importance)
        .put("language", language)
        .put("confidence", confidence.toDouble())
        .put("origin", origin)

    companion object {
        const val IMPORTANCE_MAJOR = "major"
        const val IMPORTANCE_MEDIUM = "medium"
        const val IMPORTANCE_MINOR = "minor"

        fun fromJson(json: JSONObject) = CharacterProfile(
            name = json.optString("name").trim(),
            aliases = json.stringList("aliases"),
            gender = json.optString("gender").trim(),
            ageGroup = json.optString("ageGroup").trim(),
            style = json.stringList("style"),
            importance = json.optString("importance", IMPORTANCE_MINOR).trim()
                .ifBlank { IMPORTANCE_MINOR },
            language = json.optString("language", "en").trim().ifBlank { "en" },
            confidence = json.optDouble("confidence", 0.0).toFloat(),
            origin = json.optString("origin", "auto").trim().ifBlank { "auto" }
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
    /** Voice-facing character profiles (multi-voice M2, D1). */
    val characterProfiles: List<CharacterProfile> = emptyList(),
    val styleNotes: List<String> = emptyList(),
    /** Backend that produced this profile ("deepseek" or "local"). */
    val source: String = "local"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId)
        .put("bookTitle", bookTitle)
        .put("summary", summary)
        .put("characters", JSONArray().apply { characters.forEach { put(it.toJson()) } })
        .put("places", JSONArray().apply { places.forEach { put(it.toJson()) } })
        .put("glossary", JSONArray().apply { glossary.forEach { put(it.toJson()) } })
        .put(
            "characterProfiles",
            JSONArray().apply { characterProfiles.forEach { put(it.toJson()) } }
        )
        .put("styleNotes", JSONArray().apply { styleNotes.forEach { put(it) } })
        .put("source", source)

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
                characterProfiles = json.optJSONArray("characterProfiles")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)
                            ?.let(CharacterProfile::fromJson)
                            ?.takeIf { it.name.isNotBlank() }
                    }
                }.orEmpty(),
                styleNotes = strings("styleNotes"),
                source = json.optString("source", "local")
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
    val azureEndpoint: String = "https://api.cognitive.microsofttranslator.com",
    /** Master switch for all networked AI; when false the app stays fully offline. */
    val powerEnabled: Boolean = true
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
    val updatedAt: Long = 0L,
    // Character-profile fields (multi-voice M2, PLAN-MULTI-VOICE 3.2/7):
    // only meaningful for kind == "character"; the glossary is the single
    // editable home of the speaker roster.
    val aliases: List<String> = emptyList(),
    val gender: String = "",
    val ageGroup: String = "",
    val style: List<String> = emptyList(),
    val importance: String = ""
) {
    /** Stable key: one entry per lowercase term. */
    val key: String get() = term.lowercase()

    /** Character view of this entry; null for non-character entries. */
    fun characterProfile(): CharacterProfile? {
        if (kind != KIND_CHARACTER || term.isBlank()) return null
        return CharacterProfile(
            name = term,
            aliases = aliases,
            gender = gender,
            ageGroup = ageGroup,
            style = style,
            importance = importance.ifBlank { CharacterProfile.IMPORTANCE_MINOR },
            origin = origin
        )
    }

    /**
     * Merges an auto-generated [CharacterProfile] into this entry.
     *
     * Manual entries keep every attribute the user actually filled in
     * (origin=manual wins, the same rule [BookGlossaryRepository] already
     * applies to translations); blank attributes are filled from the profile.
     * Aliases are always unioned - an extra alias only ever helps the speaker
     * tagger recognise a name.
     */
    fun mergeProfile(profile: CharacterProfile): GlossaryEntry {
        val manual = origin == "manual"
        fun pick(mine: String, theirs: String): String =
            if (manual && mine.isNotBlank()) mine else theirs.ifBlank { mine }
        val mergedAliases = (aliases + profile.aliases)
            .map(String::trim)
            .filter { it.isNotBlank() && !it.equals(term, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        return copy(
            kind = KIND_CHARACTER,
            aliases = mergedAliases,
            gender = pick(gender, profile.gender),
            ageGroup = pick(ageGroup, profile.ageGroup),
            style = if (manual && style.isNotEmpty()) style else profile.style.ifEmpty { style },
            importance = pick(importance, profile.importance)
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("term", term)
        .put("translation", translation)
        .put("kind", kind)
        .put("note", note)
        .put("enabled", enabled)
        .put("origin", origin)
        .put("updatedAt", updatedAt)
        .put("aliases", jsonStringArray(aliases))
        .put("gender", gender)
        .put("ageGroup", ageGroup)
        .put("style", jsonStringArray(style))
        .put("importance", importance)

    companion object {
        const val KIND_CHARACTER = "character"

        fun fromJson(json: JSONObject) = GlossaryEntry(
            term = json.optString("term"),
            translation = json.optString("translation"),
            kind = json.optString("kind", "custom"),
            note = json.optString("note"),
            enabled = json.optBoolean("enabled", true),
            origin = json.optString("origin", "manual"),
            updatedAt = json.optLong("updatedAt"),
            aliases = json.stringList("aliases"),
            gender = json.optString("gender").trim(),
            ageGroup = json.optString("ageGroup").trim(),
            style = json.stringList("style"),
            importance = json.optString("importance").trim()
        )
    }
}

/** Per-book glossary, stored separately from the AI context profile. */
data class BookGlossary(
    val bookId: String,
    val entries: List<GlossaryEntry> = emptyList()
) {
    /**
     * 历史脏数据清理：旧版 [LocalGlossaryTranslator] 把句首大写的功能词
     * （And/As/At…）当成了专名，随语境档案导进术语表，在多角色面板里显示为
     * 「角色」。读取时按同一张停用词表过滤；用户手动添加的条目永远保留。
     */
    fun sanitized(): BookGlossary = copy(
        entries = entries.filterNot { entry ->
            entry.origin != "manual" &&
                entry.term.trim().lowercase() in LocalGlossaryTranslator.STOP_WORDS
        }
    )

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

/**
 * Cached per-chapter speaker tags (PLAN-MULTI-VOICE 4.2, incremental cache).
 *
 * One file per (book, chapter); [speakers] is parallel to the chapter sentence
 * list, so a cache whose length no longer matches the extracted chapter is
 * simply ignored (re-tagged on demand) instead of shifting voices.
 */
data class ChapterSpeakerTags(
    val chapterIndex: Int,
    val speakers: List<String>,
    /** Which layer produced the tags: "llm" (DeepSeek) or "rule" (fallback). */
    val source: String = SOURCE_RULE,
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("chapterIndex", chapterIndex)
        .put("speakers", jsonStringArray(speakers))
        .put("source", source)
        .put("updatedAt", updatedAt)

    companion object {
        const val SOURCE_LLM = "llm"
        const val SOURCE_RULE = "rule"

        fun fromJson(json: JSONObject): ChapterSpeakerTags {
            val array = json.optJSONArray("speakers")
            val speakers = if (array == null) emptyList()
            else (0 until array.length()).map { array.optString(it) }
            return ChapterSpeakerTags(
                chapterIndex = json.optInt("chapterIndex", -1),
                speakers = speakers,
                source = json.optString("source", SOURCE_RULE).ifBlank { SOURCE_RULE },
                updatedAt = json.optLong("updatedAt")
            )
        }
    }
}

/**
 * Combines two profiles of the same character coming from different requests
 * (the context profile is built per chapter segment, so the same name shows up
 * several times with partial attributes).
 */
internal fun CharacterProfile.mergedWith(other: CharacterProfile): CharacterProfile =
    copy(
        aliases = (aliases + other.aliases)
            .map(String::trim)
            .filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            .distinctBy { it.lowercase() },
        gender = gender.ifBlank { other.gender },
        ageGroup = ageGroup.ifBlank { other.ageGroup },
        style = (style + other.style).map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase() }.take(4),
        importance = if (other.importanceRank > importanceRank) other.importance else importance,
        language = language.ifBlank { other.language },
        confidence = maxOf(confidence, other.confidence)
    )

// --- small JSON helpers shared by the models above -------------------------

internal fun jsonStringArray(values: List<String>): JSONArray =
    JSONArray().apply { values.forEach { put(it) } }

internal fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length())
        .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
}
