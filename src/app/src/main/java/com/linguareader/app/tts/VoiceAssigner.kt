package com.linguareader.app.tts

/**
 * Character attributes the assigner needs, mapped from the AI character
 * profile at the boundary so this algorithm stays free of AI types.
 */
data class VoiceCharacter(
    val name: String,
    val gender: String = "",
    val ageGroup: String = "",
    val style: List<String> = emptyList(),
    val importance: String = "minor",
    val language: String = TtsLanguage.ENGLISH
) {
    val key: String get() = BookVoiceMap.keyOf(name)

    val importanceRank: Int
        get() = when (importance.trim().lowercase()) {
            "major" -> 3
            "medium" -> 2
            else -> 1
        }
}

/**
 * Character → voice assignment (PLAN-MULTI-VOICE §5.2).
 *
 * Deterministic and pure, so the whole policy is unit-testable:
 *
 * 1. **hard filter** - language must match (a blank language is multilingual)
 *    and a known gender must not conflict;
 * 2. **soft score** - gender / age / style similarity plus an importance prize
 *    on voice quality, so major characters get the best voices;
 * 3. **greedy assignment** in (importance, co-occurrence degree) order with a
 *    distinctness penalty λ·Σ sim(v, voice of a co-occurring neighbour);
 * 4. one **swap** attempt when nothing unused is left, then **reuse limited to
 *    non-co-occurring characters**, and the narration voice as the last resort;
 * 5. **narrator** is picked first (most neutral voice) and reserved, which is
 *    what keeps narration audibly apart from every character;
 * 6. **locked** entries and - unless the engine changed - previously assigned
 *    ones are kept untouched, so adding characters later is incremental (§5.3).
 */
object VoiceAssigner {
    const val DEFAULT_LAMBDA = 0.6f

    private const val W_GENDER = 0.40f
    private const val W_AGE = 0.15f
    private const val W_STYLE = 0.25f
    private const val W_IMPORTANCE = 0.20f

    private val neutralStyles = setOf(
        "calm", "neutral", "narration", "narrator", "news", "documentary",
        "clear", "gentle", "steady"
    )
    private val unsuitableNarratorStyles = setOf("whisper", "whispering", "child", "shouting", "angry")

    fun assign(
        bookId: String,
        characters: List<VoiceCharacter>,
        library: VoiceLibrary,
        cooccurrence: Map<Pair<String, String>, Int> = emptyMap(),
        existing: BookVoiceMap? = null,
        narratorLanguages: List<String> = listOf(TtsLanguage.ENGLISH),
        /** Voice ids the user already spent elsewhere (M1 narrator/dialogue). */
        reserved: Set<String> = emptySet(),
        lambda: Float = DEFAULT_LAMBDA
    ): BookVoiceMap {
        val available = library.voices.filter { it.available }
        val locked = existing?.userLocked.orEmpty()
        if (available.isEmpty()) {
            // Nothing to assign from: keep whatever the user/previous run had.
            return existing?.copy(bookId = bookId) ?: BookVoiceMap(bookId, engine = library.engine)
        }
        val ids = available.map { it.id }.toSet()
        val engineChanged = existing != null &&
            existing.engine.isNotBlank() &&
            existing.engine != library.engine

        // ⑤ narrator first, so characters are scored against it.
        val narrator = linkedMapOf<String, String>()
        val takenIds = mutableSetOf<String>()
        takenIds += reserved.filter { it.isNotBlank() }
        for (language in narratorLanguages.map { it.trim().lowercase() }.distinct()) {
            val previous = existing?.narrator?.get(language)?.takeIf { it in ids }
            val keepPrevious = previous != null &&
                (existing.isLocked(BookVoiceMap.narratorKey(language)) || !engineChanged)
            val chosen = if (keepPrevious) previous else pickNarrator(available, language, takenIds)
            if (chosen != null) {
                narrator[language] = chosen
                takenIds += chosen
            }
        }

        val roster = characters
            .filter { it.name.isNotBlank() }
            .distinctBy { it.key }
            .sortedWith(
                compareByDescending<VoiceCharacter> { it.importanceRank }
                    .thenByDescending { SpeakerCooccurrence.degree(cooccurrence, it.key) }
                    .thenBy { it.key }
            )
        val previousByKey = existing?.characterVoice.orEmpty()
            .entries
            .associate { BookVoiceMap.keyOf(it.key) to it.value }

        val assigned = linkedMapOf<String, String>()
        val ownerByVoice = mutableMapOf<String, String>()

        fun take(character: VoiceCharacter, voiceId: String) {
            assigned[character.name] = voiceId
            takenIds += voiceId
            ownerByVoice[voiceId] = character.key
        }

        // ⑥ keep locked entries, and previous ones while the engine is stable.
        for (character in roster) {
            val previous = previousByKey[character.key]?.takeIf { it in ids } ?: continue
            val keep = locked.any { it.equals(character.key, ignoreCase = true) } || !engineChanged
            if (keep) take(character, previous)
        }

        for (character in roster) {
            if (assigned.containsKey(character.name)) continue
            val candidates = hardFilter(character, available)
            if (candidates.isEmpty()) {
                narratorFor(character, narrator)?.let { take(character, it) }
                continue
            }
            val unused = candidates.filter { it.id !in takenIds }
            val best = pickBest(unused, character, assigned, cooccurrence, library, lambda)
            if (best != null) {
                take(character, best.id)
                continue
            }
            val swapped = trySwap(character, candidates, roster, assigned, takenIds, ownerByVoice, available)
            if (swapped != null) {
                take(character, swapped)
                continue
            }
            val reuse = pickReuse(candidates, character, assigned, cooccurrence, library, lambda, ownerByVoice)
            if (reuse != null) {
                // Shared voice: only ever between characters that never speak
                // next to each other.
                assigned[character.name] = reuse.id
                continue
            }
            narratorFor(character, narrator)?.let { assigned[character.name] = it }
        }

        return BookVoiceMap(
            bookId = bookId,
            narrator = narrator,
            characterVoice = assigned,
            userLocked = locked,
            engine = library.engine
        )
    }

    // ① hard filter: language and non-conflicting gender.
    internal fun hardFilter(character: VoiceCharacter, voices: List<VoiceInfo>): List<VoiceInfo> {
        val strict = voices.filter { voice ->
            voice.speaks(character.language) && genderCompatible(character.gender, voice.gender)
        }
        if (strict.isNotEmpty()) return strict
        // Relax gender before language: a wrong-language voice is unusable,
        // a wrong-gender one is merely a bad match.
        val sameLanguage = voices.filter { it.speaks(character.language) }
        return if (sameLanguage.isNotEmpty()) sameLanguage else voices
    }

    private fun genderCompatible(characterGender: String, voiceGender: String): Boolean =
        characterGender.isBlank() || voiceGender.isBlank() ||
            characterGender.equals(voiceGender, ignoreCase = true)

    // ② soft score.
    internal fun score(character: VoiceCharacter, voice: VoiceInfo): Float {
        val gender = when {
            character.gender.isBlank() || voice.gender.isBlank() -> 0.5f
            character.gender.equals(voice.gender, ignoreCase = true) -> 1f
            else -> 0f
        }
        val age = when {
            character.ageGroup.isBlank() || voice.ageGroup.isBlank() -> 0.5f
            character.ageGroup.equals(voice.ageGroup, ignoreCase = true) -> 1f
            else -> 0.2f
        }
        val style = if (character.style.isEmpty() || voice.style.isEmpty()) {
            0.4f
        } else {
            VoiceLibrary.jaccard(character.style, voice.style)
        }
        val prize = voice.quality * (character.importanceRank / 3f)
        return W_GENDER * gender + W_AGE * age + W_STYLE * style + W_IMPORTANCE * prize
    }

    // ③ distinctness penalty against already-assigned co-occurring neighbours.
    internal fun penalty(
        voice: VoiceInfo,
        character: VoiceCharacter,
        assigned: Map<String, String>,
        cooccurrence: Map<Pair<String, String>, Int>,
        library: VoiceLibrary,
        lambda: Float
    ): Float {
        var sum = 0f
        for ((name, voiceId) in assigned) {
            val shared = SpeakerCooccurrence.count(cooccurrence, character.key, name)
            if (shared <= 0) continue
            val other = library.byId(voiceId) ?: continue
            val weight = minOf(1f, shared / 5f)
            sum += weight * VoiceLibrary.similarity(voice, other)
        }
        return lambda * sum
    }

    private fun pickBest(
        candidates: List<VoiceInfo>,
        character: VoiceCharacter,
        assigned: Map<String, String>,
        cooccurrence: Map<Pair<String, String>, Int>,
        library: VoiceLibrary,
        lambda: Float
    ): VoiceInfo? = candidates
        .sortedWith(
            compareByDescending<VoiceInfo> {
                score(character, it) - penalty(it, character, assigned, cooccurrence, library, lambda)
            }.thenBy { it.id }
        )
        .firstOrNull()

    /**
     * ④ One swap attempt: hand a less important character's voice to [character]
     * when that character can still move to an unused voice of its own.
     */
    private fun trySwap(
        character: VoiceCharacter,
        candidates: List<VoiceInfo>,
        roster: List<VoiceCharacter>,
        assigned: MutableMap<String, String>,
        takenIds: MutableSet<String>,
        ownerByVoice: MutableMap<String, String>,
        available: List<VoiceInfo>
    ): String? {
        val candidateIds = candidates.map { it.id }.toSet()
        val byKey = roster.associateBy { it.key }
        for ((voiceId, ownerKey) in ownerByVoice.entries.sortedBy { it.key }) {
            if (voiceId !in candidateIds) continue
            val owner = byKey[ownerKey] ?: continue
            if (owner.importanceRank >= character.importanceRank) continue
            val replacement = hardFilter(owner, available)
                .filter { it.id !in takenIds }
                .sortedWith(compareByDescending<VoiceInfo> { score(owner, it) }.thenBy { it.id })
                .firstOrNull() ?: continue
            assigned[owner.name] = replacement.id
            takenIds += replacement.id
            ownerByVoice.remove(voiceId)
            ownerByVoice[replacement.id] = owner.key
            return voiceId
        }
        return null
    }

    /** Reuse is allowed only between characters that never speak adjacently. */
    private fun pickReuse(
        candidates: List<VoiceInfo>,
        character: VoiceCharacter,
        assigned: Map<String, String>,
        cooccurrence: Map<Pair<String, String>, Int>,
        library: VoiceLibrary,
        lambda: Float,
        ownerByVoice: Map<String, String>
    ): VoiceInfo? {
        val shareable = candidates.filter { voice ->
            val owner = ownerByVoice[voice.id] ?: return@filter false
            SpeakerCooccurrence.count(cooccurrence, character.key, owner) == 0
        }
        return pickBest(shareable, character, assigned, cooccurrence, library, lambda)
    }

    private fun narratorFor(character: VoiceCharacter, narrator: Map<String, String>): String? =
        narrator[character.language] ?: narrator.values.firstOrNull()

    /**
     * ⑤ The narration voice: the most neutral, highest-quality voice of that
     * language that is not already spoken for.
     */
    internal fun pickNarrator(
        voices: List<VoiceInfo>,
        language: String,
        taken: Set<String>
    ): String? {
        val pool = voices.filter { it.speaks(language) && it.id !in taken }
            .ifEmpty { voices.filter { it.speaks(language) } }
            .ifEmpty { voices }
        return pool
            .sortedWith(compareByDescending<VoiceInfo> { narratorScore(it) }.thenBy { it.id })
            .firstOrNull()
            ?.id
    }

    private fun narratorScore(voice: VoiceInfo): Float {
        val styles = voice.style.map { it.lowercase() }
        var score = voice.quality
        if (styles.any { it in neutralStyles }) score += 0.4f
        if (styles.any { it in unsuitableNarratorStyles }) score -= 0.5f
        if (voice.ageGroup.equals("child", ignoreCase = true)) score -= 0.4f
        return score
    }
}
