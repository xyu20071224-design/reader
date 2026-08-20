package com.linguareader.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M2 speaker-tag store tests (PLAN-MULTI-VOICE §9): cache read/write, the
 * incremental "only untagged chapters are requested" rule, the roster coming
 * from the glossary (§7), and every degradation path (no key, master switch
 * off, empty roster, backend failure).
 */
@RunWith(RobolectricTestRunner::class)
class SpeakerTagRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    // Two sentences in total: one quoted line (p0/q0) plus one narration line.
    private val blocks = listOf("\"Fly, you fools,\" he shouted.", "The hall fell silent.")

    private class FakeChat(private val answer: String?) : AiChatClient {
        var calls = 0
        override suspend fun chatJson(system: String, user: String): JSONObject {
            calls++
            return answer?.let { JSONObject(it) } ?: throw AiRequestException("HTTP 500")
        }
    }

    private val goodAnswer =
        "{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Gandalf\",\"confidence\":0.95}]}]}"

    private fun settingsStore(
        enabled: Boolean = true,
        apiKey: String = "sk-test",
        powerEnabled: Boolean = true
    ): AiSettingsStore {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .putString("api_key", apiKey.takeIf { it.isNotBlank() })
            .putBoolean("power_enabled", powerEnabled)
            .apply()
        return AiSettingsStore(context)
    }

    private fun seedRoster(bookId: String) = runBlocking {
        BookGlossaryRepository(context).importFromProfile(
            bookId,
            BookContextProfile(
                bookId = bookId,
                bookTitle = "LOTR",
                characters = listOf(ContextTerm("Gandalf", "甘道夫")),
                characterProfiles = listOf(
                    CharacterProfile(name = "Gandalf", aliases = listOf("Mithrandir"))
                ),
                source = "deepseek"
            )
        )
    }

    private fun repository(
        chat: FakeChat,
        store: AiSettingsStore = settingsStore()
    ) = SpeakerTagRepository(
        context,
        store,
        BookGlossaryRepository(context),
        chatClientFactory = { chat }
    )

    @Test
    fun `roster comes from the glossary including aliases`() = runBlocking {
        seedRoster("book-roster")
        val roster = repository(FakeChat(goodAnswer)).roster("book-roster")
        assertEquals(listOf("Gandalf"), roster.names)
        assertEquals("Gandalf", roster.canonical("Mithrandir"))
    }

    @Test
    fun `tags are cached and the next chapter read costs no request`() = runBlocking {
        val bookId = "book-cache"
        seedRoster(bookId)
        val chat = FakeChat(goodAnswer)
        val repository = repository(chat)

        val first = repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2)
        assertEquals(listOf("Gandalf", "narrator"), first)
        assertEquals(1, chat.calls)

        // Incremental: the same chapter is served from the cache …
        assertEquals(first, repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2))
        assertEquals(1, chat.calls)
        assertEquals(first, repository.cachedSpeakers(bookId, 0, 2))

        // … while a chapter that has no tags yet is the only one requested.
        assertNull(repository.cachedSpeakers(bookId, 1, 2))
        repository.tagChapter(bookId, 1, "Chapter 2", blocks, 2)
        assertEquals(2, chat.calls)
    }

    @Test
    fun `a cache of the wrong length is ignored`() = runBlocking {
        val bookId = "book-stale"
        seedRoster(bookId)
        val repository = repository(FakeChat(goodAnswer))
        repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2)

        assertNotNull(repository.cachedSpeakers(bookId, 0, 2))
        // The chapter was re-extracted and now has a different sentence count:
        // the stored tags cannot be trusted to line up any more.
        assertNull(repository.cachedSpeakers(bookId, 0, 5))
    }

    @Test
    fun `no api key degrades without calling the backend`() = runBlocking {
        val bookId = "book-nokey"
        seedRoster(bookId)
        val chat = FakeChat(goodAnswer)
        val repository = repository(chat, settingsStore(apiKey = ""))
        assertNull(repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2))
        assertEquals(0, chat.calls)
    }

    @Test
    fun `master power switch off degrades without calling the backend`() = runBlocking {
        val bookId = "book-power"
        seedRoster(bookId)
        val chat = FakeChat(goodAnswer)
        val repository = repository(chat, settingsStore(powerEnabled = false))
        assertNull(repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2))
        assertEquals(0, chat.calls)
    }

    @Test
    fun `an empty roster degrades without calling the backend`() = runBlocking {
        val chat = FakeChat(goodAnswer)
        val repository = repository(chat)
        assertNull(repository.tagChapter("book-empty", 0, "Chapter 1", blocks, 2))
        assertEquals(0, chat.calls)
    }

    @Test
    fun `a failing backend degrades and is retried later`() = runBlocking {
        val bookId = "book-fail"
        seedRoster(bookId)
        val chat = FakeChat(null)
        val repository = repository(chat)

        assertNull(repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2))
        assertEquals(1, chat.calls)
        // Nothing was cached, so the next read tries again instead of being
        // stuck on a failed chapter.
        assertNull(repository.cachedSpeakers(bookId, 0, 2))
        assertNull(repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2))
        assertEquals(2, chat.calls)
    }

    @Test
    fun `delete drops every cached chapter of a book`() = runBlocking {
        val bookId = "book-delete"
        seedRoster(bookId)
        val repository = repository(FakeChat(goodAnswer))
        repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2)
        assertNotNull(repository.cachedSpeakers(bookId, 0, 2))

        repository.delete(bookId)
        assertNull(repository.cachedSpeakers(bookId, 0, 2))
    }

    @Test
    fun `rejected speakers are never cached as characters`() = runBlocking {
        val bookId = "book-reject"
        seedRoster(bookId)
        val chat = FakeChat(
            "{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Sauron\",\"confidence\":0.99}]}]}"
        )
        val repository = repository(chat)
        // The answer is outside the roster, so the rule layer wins: dialogue.
        assertEquals(
            listOf("dialogue", "narrator"),
            repository.tagChapter(bookId, 0, "Chapter 1", blocks, 2)
        )
    }
}
