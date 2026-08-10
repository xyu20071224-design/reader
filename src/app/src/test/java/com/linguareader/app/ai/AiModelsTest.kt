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
            styleNotes = listOf("口语化叙述")
        )

        val restored = BookContextProfile.fromJson(JSONObject(profile.toJson().toString()))

        assertEquals(profile, restored)
    }
}
