package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 assignment tests (PLAN-MULTI-VOICE §9): hard filter, soft score order,
 * the co-occurrence distinctness penalty, locked entries, reuse limited to
 * non-co-occurring characters, and incremental assignment.
 */
class VoiceAssignerTest {

    private val library = VoiceLibrary(
        listOf(
            VoiceInfo("af_maple", "en", "female", quality = 0.6f),
            VoiceInfo("af_sol", "en", "female", quality = 0.5f),
            VoiceInfo("am_onyx", "en", "male", quality = 0.6f),
            VoiceInfo("am_echo", "en", "male", quality = 0.5f),
            VoiceInfo("zf_001", "zh", "female"),
            VoiceInfo("zm_009", "zh", "male")
        ),
        engine = "server:kokoro"
    )

    private fun character(
        name: String,
        gender: String = "",
        importance: String = "minor",
        language: String = "en",
        style: List<String> = emptyList(),
        ageGroup: String = ""
    ) = VoiceCharacter(name, gender, ageGroup, style, importance, language)

    // ── ① hard filter ─────────────────────────────────────────────────────

    @Test
    fun hardFilterKeepsLanguageAndNonConflictingGender() {
        val male = VoiceAssigner.hardFilter(character("Gandalf", "male"), library.voices)
        assertEquals(listOf("am_onyx", "am_echo"), male.map { it.id })

        val chinese = VoiceAssigner.hardFilter(character("小明", "male", language = "zh"), library.voices)
        assertEquals(listOf("zm_009"), chinese.map { it.id })

        // Unknown gender may use every voice of the language.
        val unknown = VoiceAssigner.hardFilter(character("Voice"), library.voices)
        assertEquals(listOf("af_maple", "af_sol", "am_onyx", "am_echo"), unknown.map { it.id })
    }

    @Test
    fun hardFilterRelaxesGenderBeforeLanguage() {
        val onlyFemale = library.voices.filter { it.gender == "female" }
        val filtered = VoiceAssigner.hardFilter(character("Gandalf", "male"), onlyFemale)
        // No male voice exists: same-language female voices are better than a
        // wrong-language male one.
        assertEquals(listOf("af_maple", "af_sol"), filtered.map { it.id })
    }

    @Test
    fun blankLibraryKeepsWhateverExisted() {
        val existing = BookVoiceMap("b", characterVoice = mapOf("Frodo" to "af_sol"))
        val map = VoiceAssigner.assign("b", listOf(character("Frodo")), VoiceLibrary(), existing = existing)
        assertEquals(existing, map)
    }

    // ── ②③⑤ scoring, greedy order and the narrator ────────────────────────

    @Test
    fun majorCharactersGetTheBestMatchingVoicesAndNarratorIsReserved() {
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(
                character("Sam", "male", "minor"),
                character("Gandalf", "male", "major"),
                character("Galadriel", "female", "major")
            ),
            library = library,
            narratorLanguages = listOf("en")
        )
        // Narrator takes the most neutral (highest quality) English voice …
        assertEquals("af_maple", map.narratorFor("en"))
        // … the major male character gets the better remaining male voice …
        assertEquals("am_onyx", map.voiceFor("Gandalf"))
        // … the major female one cannot reuse the narrator voice …
        assertEquals("af_sol", map.voiceFor("Galadriel"))
        // … and the minor character gets what is left.
        assertEquals("am_echo", map.voiceFor("Sam"))
        assertEquals("server:kokoro", map.engine)
    }

    @Test
    fun styleAndAgeRefineTheScore() {
        val styled = VoiceLibrary(
            listOf(
                VoiceInfo("plain", "en", "female"),
                VoiceInfo("lively", "en", "female", style = listOf("lively", "bright"), ageGroup = "young")
            ),
            engine = "e"
        )
        val child = character("Lily", "female", "major", style = listOf("lively"), ageGroup = "young")
        assertTrue(VoiceAssigner.score(child, styled.voices[1]) > VoiceAssigner.score(child, styled.voices[0]))
    }

    @Test
    fun narratorPrefersNeutralStyleAndSkipsReservedVoices() {
        val voices = listOf(
            VoiceInfo("loud", "en", "male", style = listOf("shouting"), quality = 0.9f),
            VoiceInfo("calm", "en", "male", style = listOf("calm"), quality = 0.5f),
            VoiceInfo("plain", "en", "male", quality = 0.6f)
        )
        assertEquals("calm", VoiceAssigner.pickNarrator(voices, "en", emptySet()))
        assertEquals("plain", VoiceAssigner.pickNarrator(voices, "en", setOf("calm")))
    }

    @Test
    fun coOccurringCharactersArePulledApart() {
        val pool = VoiceLibrary(
            listOf(
                VoiceInfo("narr", "en", "", quality = 0.9f),
                VoiceInfo("fem_a", "en", "female"),
                VoiceInfo("fem_b", "en", "female"),
                VoiceInfo("neutral", "en", "")
            ),
            engine = "e"
        )
        val characters = listOf(
            character("Arwen", "female", "major"),
            character("Eowyn", "female", "major")
        )
        val apart = VoiceAssigner.assign(
            bookId = "b",
            characters = characters,
            library = pool,
            cooccurrence = SpeakerCooccurrence.from(
                listOf(List(6) { if (it % 2 == 0) "Arwen" else "Eowyn" })
            ),
            narratorLanguages = listOf("en")
        )
        val together = VoiceAssigner.assign(
            bookId = "b",
            characters = characters,
            library = pool,
            narratorLanguages = listOf("en")
        )
        // Without co-occurrence the second character takes the other female
        // voice (best gender score); when both talk in the same scene the
        // distinctness penalty moves her onto the neutral voice instead.
        assertEquals("fem_a", together.voiceFor("Arwen"))
        assertEquals("fem_b", together.voiceFor("Eowyn"))
        assertEquals("fem_a", apart.voiceFor("Arwen"))
        assertEquals("neutral", apart.voiceFor("Eowyn"))
        assertNotEquals(apart.voiceFor("Arwen"), apart.voiceFor("Eowyn"))
    }

    // ── ④ reuse and swap ─────────────────────────────────────────────────

    @Test
    fun reuseOnlyHappensBetweenCharactersThatNeverSpeakTogether() {
        val tiny = VoiceLibrary(
            listOf(
                VoiceInfo("af_maple", "en", "female"),
                VoiceInfo("am_onyx", "en", "male")
            ),
            engine = "e"
        )
        val cooccurrence = SpeakerCooccurrence.from(
            listOf(List(10) { if (it % 2 == 0) "Gandalf" else "Boromir" })
        )
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(
                character("Gandalf", "male", "major"),
                character("Boromir", "male", "medium"),
                character("Innkeeper", "male", "minor")
            ),
            library = tiny,
            cooccurrence = cooccurrence,
            narratorLanguages = listOf("en")
        )
        assertEquals("af_maple", map.narratorFor("en"))
        // Only one male voice exists and the most important character gets it.
        assertEquals("am_onyx", map.voiceFor("Gandalf"))
        // Boromir speaks next to Gandalf, so sharing his voice is forbidden:
        // he degrades to the narration voice instead.
        assertEquals("af_maple", map.voiceFor("Boromir"))
        // The innkeeper never speaks next to Gandalf, so reuse is allowed.
        assertEquals("am_onyx", map.voiceFor("Innkeeper"))
    }

    @Test
    fun equallyImportantCharactersAreOrderedDeterministically() {
        // Same importance and same co-occurrence degree: the name decides, so a
        // book always produces the same mapping run after run.
        val tiny = VoiceLibrary(
            listOf(
                VoiceInfo("narr", "en", "", style = listOf("calm"), quality = 0.9f),
                VoiceInfo("male_a", "en", "male"),
                VoiceInfo("male_b", "en", "male")
            ),
            engine = "e"
        )
        val characters = listOf(character("Zeta", "male", "major"), character("Alpha", "male", "major"))
        val first = VoiceAssigner.assign("b", characters, tiny, narratorLanguages = listOf("en"))
        val second = VoiceAssigner.assign("b", characters.reversed(), tiny, narratorLanguages = listOf("en"))
        assertEquals(first.characterVoice, second.characterVoice)
        assertEquals("male_a", first.voiceFor("Alpha"))
    }

    @Test
    fun aMoreImportantCharacterMayTakeOverAMinorVoice() {
        val pool = VoiceLibrary(
            listOf(
                VoiceInfo("narr", "en", "", quality = 0.9f),
                VoiceInfo("male_a", "en", "male"),
                VoiceInfo("any_b", "en", "")
            ),
            engine = "e"
        )
        // The minor character is assigned first (alphabetically) only if it has
        // the same importance; here the major one is assigned first, so the swap
        // path is exercised by the *second*, more constrained character.
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(
                character("Aaa", "", "major"),
                character("Zzz", "male", "minor")
            ),
            library = pool,
            narratorLanguages = listOf("en")
        )
        // Every character still ends up with a distinct voice.
        assertEquals(2, map.characterVoice.values.distinct().size)
        assertTrue(map.voiceFor("Zzz") != map.narratorFor("en"))
    }

    // ── ⑥ locking and incremental assignment ─────────────────────────────

    @Test
    fun lockedEntriesAreNeverReassigned() {
        val existing = BookVoiceMap(
            bookId = "b",
            characterVoice = mapOf("Gandalf" to "zf_001"),
            userLocked = setOf("gandalf"),
            engine = "server:kokoro"
        )
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(character("Gandalf", "male", "major")),
            library = library,
            existing = existing,
            narratorLanguages = listOf("en")
        )
        // A Chinese female voice is a terrible match for an English male
        // character - and it stays, because the user chose it.
        assertEquals("zf_001", map.voiceFor("Gandalf"))
        assertTrue(map.isLocked("Gandalf"))
    }

    @Test
    fun newCharactersAreAddedWithoutMovingTheOldOnes() {
        val first = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(character("Gandalf", "male", "major")),
            library = library,
            narratorLanguages = listOf("en")
        )
        val second = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(
                character("Gandalf", "male", "major"),
                character("Frodo", "male", "medium")
            ),
            library = library,
            existing = first,
            narratorLanguages = listOf("en")
        )
        assertEquals(first.voiceFor("Gandalf"), second.voiceFor("Gandalf"))
        assertEquals(first.narratorFor("en"), second.narratorFor("en"))
        assertNotEquals(second.voiceFor("Gandalf"), second.voiceFor("Frodo"))
    }

    @Test
    fun switchingEngineReassignsButKeepsAvailableLockedVoices() {
        val azureMap = BookVoiceMap(
            bookId = "b",
            narrator = mapOf("en" to "en-US-AriaNeural"),
            characterVoice = mapOf("Gandalf" to "en-US-GuyNeural", "Frodo" to "en-US-DavisNeural"),
            userLocked = setOf("frodo"),
            engine = "azure:chinanorth3"
        )
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(
                character("Gandalf", "male", "major"),
                character("Frodo", "male", "medium")
            ),
            library = library,
            existing = azureMap,
            narratorLanguages = listOf("en")
        )
        // Azure ids do not exist on the new engine, so everything is recomputed
        // inside the new library - the lock flag survives for the UI.
        assertTrue(map.characterVoice.values.all { library.byId(it) != null })
        assertEquals("server:kokoro", map.engine)
        assertTrue(map.isLocked("Frodo"))
    }

    @Test
    fun reservedVoicesStayOutOfAutomaticAssignment() {
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = listOf(character("Gandalf", "male", "major")),
            library = library,
            narratorLanguages = listOf("en"),
            reserved = setOf("am_onyx")
        )
        // am_onyx is the best male match but the user spent it on the M1
        // dialogue voice, so the assigner picks the next one.
        assertEquals("am_echo", map.voiceFor("Gandalf"))
        assertNull(map.voiceFor("dialogue"))
    }

    @Test
    fun chineseAndEnglishNarrationGetTheirOwnVoices() {
        val map = VoiceAssigner.assign(
            bookId = "b",
            characters = emptyList(),
            library = library,
            narratorLanguages = listOf("en", "zh")
        )
        assertEquals("af_maple", map.narratorFor("en"))
        assertEquals("zf_001", map.narratorFor("zh"))
        // An unknown language falls back to the English narration voice.
        assertEquals("af_maple", map.narratorFor("fr"))
    }
}
