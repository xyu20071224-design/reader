package com.linguareader.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `sentence start function words are not recorded`() = runBlocking {
        val chapters = listOf(
            ChapterText(
                index = 0,
                title = "One",
                text = "The door opened. Harry entered the room. He smiled."
            ),
            ChapterText(
                index = 1,
                title = "Two",
                text = "The night was quiet. Harry walked in. She left."
            )
        )

        val profile = translator.buildBookContext("Test", chapters)
        val terms = (profile.characters + profile.places + profile.glossary)
            .map { it.term.lowercase() }

        assertTrue(terms.contains("harry"))
        assertFalse(terms.contains("the"))
        assertFalse(terms.contains("he"))
        assertFalse(terms.contains("she"))
    }

    @Test
    fun `builds glossary from repeated cjk names in chinese books`() = runBlocking {
        val chapters = listOf(
            ChapterText(
                index = 0,
                title = "一",
                text = "哈利波特走进了霍格沃茨。哈利波特很喜欢霍格沃茨。"
            ),
            ChapterText(
                index = 1,
                title = "二",
                text = "霍格沃茨就在前方。哈利波特回到了霍格沃茨。"
            )
        )

        val profile = translator.buildBookContext("中文书", chapters)
        val terms = (profile.characters + profile.places + profile.glossary).map { it.term }

        assertTrue(terms.contains("哈利波特"))
        assertTrue(terms.contains("霍格沃茨"))
        // N-gram 分裂出来的子串不应混入（"哈利"、"沃茨" 等）。
        assertTrue(terms.none { it.length < 4 })
    }

    @Test
    fun `translate matches a cjk term prefix in chinese books`() = runBlocking {
        val profile = BookContextProfile(
            bookId = "book-zh",
            bookTitle = "中文书",
            glossary = listOf(
                ContextTerm(
                    term = "哈利波特",
                    note = "本书出现 3 次；首次见：哈利波特走进了霍格沃茨。"
                )
            )
        )
        val request = AiLookupRequest(
            bookId = "book-zh",
            bookTitle = "中文书",
            surfaceWord = "哈利",
            headword = "哈利",
            sentence = "哈利波特走进了霍格沃茨。",
            paragraph = "哈利波特走进了霍格沃茨。",
            localSenses = emptyList(),
            localDefinitions = emptyList(),
            matchedPhrase = null
        )

        val hit = translator.translate(profile, request)
        assertNotNull(hit)
        assertEquals("本地轻量语境", hit!!.source)

        val miss = translator.translate(profile, request.copy(surfaceWord = "霍格", headword = "霍格"))
        assertNull(miss)
    }
}
