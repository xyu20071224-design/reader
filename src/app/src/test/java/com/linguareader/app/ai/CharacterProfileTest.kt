package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 model tests (PLAN-MULTI-VOICE §9): character-profile JSON round trips,
 * the manual-wins merge into the glossary roster, and the chapter speaker-tag
 * cache format.
 */
class CharacterProfileTest {

    private val gandalf = CharacterProfile(
        name = "Gandalf",
        aliases = listOf("Mithrandir"),
        gender = "male",
        ageGroup = "elderly",
        style = listOf("deep", "calm"),
        importance = CharacterProfile.IMPORTANCE_MAJOR,
        language = "en",
        confidence = 0.9f,
        origin = "auto"
    )

    @Test
    fun `character profile json round trip`() {
        assertEquals(gandalf, CharacterProfile.fromJson(JSONObject(gandalf.toJson().toString())))
    }

    @Test
    fun `character profile defaults are stable`() {
        val bare = CharacterProfile.fromJson(JSONObject("{\"name\":\"Frodo\"}"))
        assertEquals("Frodo", bare.name)
        assertEquals(CharacterProfile.IMPORTANCE_MINOR, bare.importance)
        assertEquals("en", bare.language)
        assertEquals("auto", bare.origin)
        assertEquals(1, bare.importanceRank)
        assertEquals(3, gandalf.importanceRank)
    }

    @Test
    fun `book context profile carries character profiles`() {
        val profile = BookContextProfile(
            bookId = "book-1",
            bookTitle = "LOTR",
            characters = listOf(ContextTerm("Gandalf", "甘道夫")),
            characterProfiles = listOf(gandalf),
            source = "deepseek"
        )
        val restored = BookContextProfile.fromJson(JSONObject(profile.toJson().toString()))
        assertEquals(profile, restored)
        assertEquals(listOf(gandalf), restored.characterProfiles)
    }

    @Test
    fun `nameless profiles are dropped when parsing`() {
        val json = JSONObject(
            "{\"bookId\":\"b\",\"bookTitle\":\"t\"," +
                "\"characterProfiles\":[{\"name\":\"\"},{\"name\":\"Frodo\"}]}"
        )
        val restored = BookContextProfile.fromJson(json)
        assertEquals(listOf("Frodo"), restored.characterProfiles.map { it.name })
    }

    @Test
    fun `glossary entry round trips character fields`() {
        val entry = GlossaryEntry(
            term = "Gandalf",
            translation = "甘道夫",
            kind = GlossaryEntry.KIND_CHARACTER,
            origin = "auto",
            aliases = listOf("Mithrandir"),
            gender = "male",
            ageGroup = "elderly",
            style = listOf("deep"),
            importance = "major"
        )
        assertEquals(entry, GlossaryEntry.fromJson(JSONObject(entry.toJson().toString())))
    }

    @Test
    fun `auto entries take the whole profile`() {
        val entry = GlossaryEntry(term = "Gandalf", kind = "character", origin = "auto")
        val merged = entry.mergeProfile(gandalf)
        assertEquals("male", merged.gender)
        assertEquals("elderly", merged.ageGroup)
        assertEquals(listOf("deep", "calm"), merged.style)
        assertEquals("major", merged.importance)
        assertEquals(listOf("Mithrandir"), merged.aliases)
    }

    @Test
    fun `manual entries keep their own values and only fill blanks`() {
        val manual = GlossaryEntry(
            term = "Gandalf",
            kind = GlossaryEntry.KIND_CHARACTER,
            origin = "manual",
            gender = "female",
            style = listOf("bright"),
            aliases = listOf("Grey Pilgrim")
        )
        val merged = manual.mergeProfile(gandalf)
        // User-chosen values win …
        assertEquals("female", merged.gender)
        assertEquals(listOf("bright"), merged.style)
        // … blanks are filled from the profile …
        assertEquals("elderly", merged.ageGroup)
        assertEquals("major", merged.importance)
        // … and aliases are unioned so recognition only ever improves.
        assertEquals(listOf("Grey Pilgrim", "Mithrandir"), merged.aliases)
    }

    @Test
    fun `character profile view is limited to character entries`() {
        val character = GlossaryEntry(term = "Frodo", kind = GlossaryEntry.KIND_CHARACTER)
        assertEquals("Frodo", character.characterProfile()?.name)
        assertEquals(CharacterProfile.IMPORTANCE_MINOR, character.characterProfile()?.importance)
        assertNull(GlossaryEntry(term = "Shire", kind = "place").characterProfile())
    }

    @Test
    fun `profiles from several segments merge into one`() {
        val first = CharacterProfile(name = "Gandalf", gender = "male", importance = "minor")
        val second = CharacterProfile(
            name = "Gandalf",
            aliases = listOf("Mithrandir"),
            ageGroup = "elderly",
            style = listOf("deep"),
            importance = "major",
            confidence = 0.8f
        )
        val merged = first.mergedWith(second)
        assertEquals("male", merged.gender)
        assertEquals("elderly", merged.ageGroup)
        assertEquals(listOf("Mithrandir"), merged.aliases)
        assertEquals(listOf("deep"), merged.style)
        assertEquals("major", merged.importance)
        assertEquals(0.8f, merged.confidence)
    }

    @Test
    fun `chapter speaker tags json round trip`() {
        val tags = ChapterSpeakerTags(
            chapterIndex = 3,
            speakers = listOf("narrator", "Gandalf", "dialogue"),
            source = ChapterSpeakerTags.SOURCE_LLM,
            updatedAt = 1_700_000_000_000L
        )
        assertEquals(tags, ChapterSpeakerTags.fromJson(JSONObject(tags.toJson().toString())))
    }

    @Test
    fun `broken tag json degrades to an empty rule entry`() {
        val tags = ChapterSpeakerTags.fromJson(JSONObject("{}"))
        assertEquals(-1, tags.chapterIndex)
        assertTrue(tags.speakers.isEmpty())
        assertEquals(ChapterSpeakerTags.SOURCE_RULE, tags.source)
    }
}
