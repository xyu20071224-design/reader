package com.linguareader.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- 点词查词的降级信号（2026-08-24 #3：失败不能静默吞掉） ------------------

    private val lookupRequest = AiLookupRequest(
        bookId = "b",
        bookTitle = "Test Book",
        surfaceWord = "harry",
        headword = "harry",
        sentence = "Harry Potter opened the door.",
        paragraph = "",
        localSenses = emptyList(),
        localDefinitions = emptyList(),
        matchedPhrase = null
    )

    @Test
    fun `lookup remote failure is reported even when local fallback has nothing`() = runBlocking {
        val profile = LocalGlossaryTranslator().buildBookContext("Test Book", chapters)

        val outcome = lookupWithContextFallback(
            translator = failingLookupRemote(),
            profile = profile,
            request = lookupRequest
        )

        // 远程抛错、本地兜底也没结果：result 为 null，但失败必须可见。
        assertTrue(outcome.remoteFailed)
    }

    @Test
    fun `lookup remote failure still serves the local fallback result`() = runBlocking {
        val profile = LocalGlossaryTranslator().buildBookContext("Test Book", chapters)
        val request = lookupRequest.copy(surfaceWord = "harry potter", headword = "harry potter")

        val outcome = lookupWithContextFallback(
            translator = failingLookupRemote(),
            profile = profile,
            request = request
        )

        assertTrue(outcome.remoteFailed)
        assertEquals("本地轻量语境", outcome.result?.source)
    }

    @Test
    fun `lookup remote success reports no failure`() = runBlocking {
        val profile = LocalGlossaryTranslator().buildBookContext("Test Book", chapters)
        val remote = object : AiTranslator {
            override val id = "remote"
            override val displayName = "Remote"
            override val offline = false

            override suspend fun buildBookContext(
                bookTitle: String,
                chapters: List<ChapterText>
            ): BookContextProfile = throw UnsupportedOperationException()

            override suspend fun translate(
                profile: BookContextProfile,
                request: AiLookupRequest
            ): AiLookupResult = AiLookupResult(
                headword = request.headword,
                contextualMeaning = "ok",
                source = displayName
            )
        }

        val outcome = lookupWithContextFallback(remote, profile, lookupRequest)

        assertFalse(outcome.remoteFailed)
        assertEquals("ok", outcome.result?.contextualMeaning)
    }

    @Test
    fun `lookup offline translator failure is not swallowed`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking<Unit> {
                lookupWithContextFallback(
                    translator = object : AiTranslator {
                        override val id = "local"
                        override val displayName = "Local"
                        override val offline = true

                        override suspend fun buildBookContext(
                            bookTitle: String,
                            chapters: List<ChapterText>
                        ): BookContextProfile = throw UnsupportedOperationException()

                        override suspend fun translate(
                            profile: BookContextProfile,
                            request: AiLookupRequest
                        ): AiLookupResult? = throw IllegalStateException("local broken")
                    },
                    profile = BookContextProfile(bookId = "b", bookTitle = "Test Book"),
                    request = lookupRequest
                )
                Unit
            }
        }

        assertEquals("local broken", error.message)
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

    /** 点词查词场景的坏后端：translate 本身抛错（如 Key/端点配错）。 */
    private fun failingLookupRemote(): AiTranslator = object : AiTranslator {
        override val id = "broken-lookup-remote"
        override val displayName = "Broken"
        override val offline = false

        override suspend fun buildBookContext(
            bookTitle: String,
            chapters: List<ChapterText>
        ): BookContextProfile = throw AiRequestException("boom")

        override suspend fun translate(
            profile: BookContextProfile,
            request: AiLookupRequest
        ): AiLookupResult? = throw AiRequestException("401")
    }
}
