package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGlossaryTest {
    @Test
    fun `glossary json round trip`() {
        val glossary = BookGlossary(
            bookId = "book-1",
            entries = listOf(
                GlossaryEntry(
                    term = "Harry Potter",
                    translation = "哈利·波特",
                    kind = "character",
                    origin = "auto"
                ),
                GlossaryEntry(
                    term = "Hermione",
                    translation = "",
                    kind = "character",
                    origin = "manual",
                    enabled = false
                )
            )
        )

        assertEquals(glossary, BookGlossary.fromJson(JSONObject(glossary.toJson().toString())))
    }

    @Test
    fun `matchesIn prefers longest term and avoids overlap`() {
        val glossary = BookGlossary(
            bookId = "book-1",
            entries = listOf(
                GlossaryEntry(term = "Ministry", translation = "部"),
                GlossaryEntry(term = "Ministry of Magic", translation = "魔法部"),
                GlossaryEntry(term = "Hogwarts", translation = "霍格沃茨")
            )
        )

        val matches = glossary.matchesIn("The Ministry of Magic is near Hogwarts.")
        val matched = matches.map { it.entry.term }

        assertTrue(matched.contains("Ministry of Magic"))
        assertTrue(!matched.contains("Ministry"))
        assertTrue(matched.contains("Hogwarts"))
    }

    @Test
    fun `matchesIn keeps original surface casing`() {
        val glossary = BookGlossary(
            bookId = "book-1",
            entries = listOf(GlossaryEntry(term = "Harry Potter", translation = "哈利·波特"))
        )

        val match = glossary.matchesIn("harry potter opened the door").single()

        assertEquals("harry potter", match.text)
    }

    @Test
    fun `disabled entries are ignored`() {
        val glossary = BookGlossary(
            bookId = "book-1",
            entries = listOf(
                GlossaryEntry(term = "Hogwarts", translation = "霍格沃茨", enabled = false)
            )
        )

        assertTrue(glossary.matchesIn("Hogwarts is far away.").isEmpty())
    }
}
