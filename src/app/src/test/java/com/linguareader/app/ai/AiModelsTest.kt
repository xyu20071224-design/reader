package com.linguareader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class AiModelsTest {
    @Test
    fun `book context profile json round trip`() {
        val profile = BookContextProfile(
            bookId = "book-1",
            bookTitle = "Test Book",
            summary = "A story.",
            characters = listOf(ContextTerm("Harry Potter", "哈利·波特", "主角")),
            places = listOf(ContextTerm("Hogwarts", "霍格沃茨", "魔法学校")),
            glossary = listOf(ContextTerm("wand", "魔杖", "反复出现")),
            styleNotes = listOf("口语化叙述"),
            source = "deepseek"
        )

        val restored = BookContextProfile.fromJson(JSONObject(profile.toJson().toString()))

        assertEquals(profile, restored)
    }

    @Test
    fun `matchesIn collects duplicate term occurrences`() {
        val glossary = BookGlossary(
            bookId = "book-1",
            entries = listOf(GlossaryEntry(term = "Harry Potter", translation = "哈利·波特"))
        )
        val matches = glossary.matchesIn("Harry Potter met Harry Potter.")

        assertEquals(2, matches.size)
        assertEquals(0, matches[0].start)
        assertEquals("Harry Potter", matches[0].text)
        assertEquals(17, matches[1].start)
        assertEquals("Harry Potter", matches[1].text)
    }

    @Test
    fun `profile json without source defaults to local`() {
        val restored = BookContextProfile.fromJson(
            JSONObject()
                .put("bookId", "book-1")
                .put("bookTitle", "Test Book")
        )

        assertEquals("local", restored.source)
    }
}
