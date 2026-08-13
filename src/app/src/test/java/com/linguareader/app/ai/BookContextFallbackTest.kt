package com.linguareader.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookContextFallbackTest {
    private val chapters = listOf(
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

    @Test
    fun `remote failure falls back to local glossary`() = runBlocking {
        val profile = buildContextProfile(
            translator = failingRemote(),
            bookTitle = "Test Book",
            chapters = chapters
        )

        assertEquals("local", profile.source)
        val terms = (profile.characters + profile.places + profile.glossary)
            .map { it.term.lowercase() }
        assertTrue(terms.contains("harry potter"))
        assertTrue(terms.contains("hogwarts"))
    }

    @Test
    fun `remote success keeps remote source`() = runBlocking {
        val profile = buildContextProfile(
            translator = object : AiTranslator {
                override val id = "remote"
                override val displayName = "Remote"
                override val offline = false

                override suspend fun buildBookContext(
                    bookTitle: String,
                    chapters: List<ChapterText>
                ): BookContextProfile = BookContextProfile(
                    bookId = "",
                    bookTitle = bookTitle,
                    summary = "ok",
                    source = "deepseek"
                )

                override suspend fun translate(
                    profile: BookContextProfile,
                    request: AiLookupRequest
                ): AiLookupResult? = null
            },
            bookTitle = "Test Book",
            chapters = chapters
        )

        assertEquals("deepseek", profile.source)
        assertEquals("ok", profile.summary)
    }

    @Test
    fun `offline failure is not swallowed`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking<Unit> {
                buildContextProfile(
                    translator = object : AiTranslator {
                        override val id = "local"
                        override val displayName = "Local"
                        override val offline = true

                        override suspend fun buildBookContext(
                            bookTitle: String,
                            chapters: List<ChapterText>
                        ): BookContextProfile = throw IllegalStateException("local broken")

                        override suspend fun translate(
                            profile: BookContextProfile,
                            request: AiLookupRequest
                        ): AiLookupResult? = null
                    },
                    bookTitle = "Test Book",
                    chapters = chapters
                )
                Unit
            }
        }

        assertEquals("local broken", error.message)
    }

    @Test
    fun `empty chapters return empty profile without calling translator`() = runBlocking {
        val profile = buildContextProfile(
            translator = failingRemote(),
            bookTitle = "Test Book",
            chapters = emptyList()
        )

        assertEquals("Test Book", profile.bookTitle)
    }

    private fun failingRemote(): AiTranslator = object : AiTranslator {
        override val id = "broken-remote"
        override val displayName = "Broken"
        override val offline = false

        override suspend fun buildBookContext(
            bookTitle: String,
            chapters: List<ChapterText>
        ): BookContextProfile = throw AiRequestException("boom")

        override suspend fun translate(
            profile: BookContextProfile,
            request: AiLookupRequest
        ): AiLookupResult? = null
    }
}
