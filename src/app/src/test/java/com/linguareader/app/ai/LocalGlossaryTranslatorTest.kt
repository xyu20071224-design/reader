package com.linguareader.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGlossaryTranslatorTest {
    private val translator = LocalGlossaryTranslator()

    @Test
    fun `builds glossary from repeated capitalized names`() = runBlocking {
        val chapters = listOf(
            ChapterText(
                index = 0,
                title = "One",
                text = "Harry Potter opened the door. Hogwarts is far away."
            ),
            ChapterText(
                index = 1,
                title = "Two",
                text = "Harry Potter walked to Hogwarts again."
            )
        )

        val profile = translator.buildBookContext("Test Book", chapters)
        val terms = (profile.characters + profile.places + profile.glossary)
            .map { it.term.lowercase() }

        assertTrue(terms.contains("harry potter"))
        assertTrue(terms.contains("hogwarts"))
    }

    @Test
    fun `translate returns book term hint and null for unknown words`() = runBlocking {
        val profile = BookContextProfile(
            bookId = "book-1",
            bookTitle = "Test Book",
            glossary = listOf(
                ContextTerm(
                    term = "Hogwarts",
                    note = "本书出现 3 次；首次见：Hogwarts is far away."
                )
            )
        )
        val request = AiLookupRequest(
            bookId = "book-1",
            bookTitle = "Test Book",
            surfaceWord = "Hogwarts",
            headword = "hogwarts",
            sentence = "Hogwarts is far away.",
            paragraph = "Hogwarts is far away.",
            localSenses = emptyList(),
            localDefinitions = emptyList(),
            matchedPhrase = null
        )

        val hit = translator.translate(profile, request)
        assertNotNull(hit)
        assertEquals("本地轻量语境", hit!!.source)

        val miss = translator.translate(
            profile,
            request.copy(surfaceWord = "ordinary", headword = "ordinary")
        )
        assertNull(miss)
    }
}
