package com.linguareader.app.ai

import com.linguareader.app.data.Book
import com.linguareader.app.data.BookScopedStore
import android.content.Context
import com.linguareader.app.tts.SpeakerLlmTagger
import com.linguareader.app.tts.SpeakerRoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Per-book speaker tag store and the M2 tagging entry point
 * (PLAN-MULTI-VOICE §4.2).
 *
 * Storage is one small file per chapter under
 * `files/ai/speaker-tags/<bookId>/<chapter>.json`, written atomically behind a
 * [Mutex] exactly like [BookGlossaryRepository]. Because the cache is
 * per-chapter, tagging is incremental for free: a chapter that already has
 * tags is never requested again, and a newly read chapter is the only one that
 * costs a request.
 *
 * Degradation (§4.2 「降级」) is expressed as a null result: no master switch, no
 * API key, no character roster, or a failed/rejected request all return null,
 * and the caller simply keeps the rule-layer tags produced by
 * [com.linguareader.app.tts.SpeakerRuleTagger].
 */
class SpeakerTagRepository(
    context: Context,
    private val settingsStore: AiSettingsStore,
    private val glossaryRepository: BookGlossaryRepository,
    /** Chat backend factory (D1: the same client as the profile, protocol-aware). */
    private val chatClientFactory: (AiSettings) -> AiChatClient = { AiTranslators.forSettings(it) }
) : BookScopedStore {
    private val appContext = context.applicationContext
    private val tagsDir = File(appContext.filesDir, "ai/speaker-tags")
    private val mutex = Mutex()
    /** Chapters currently being tagged, so a re-entry cannot double-charge. */
    private val inFlight = mutableSetOf<String>()

    /**
     * Cached LLM tags for one chapter, or null when nothing usable is stored.
     *
     * A cache whose length no longer matches [sentenceCount] belongs to an older
     * extraction of the chapter and is ignored instead of shifting voices.
     */
    suspend fun cachedSpeakers(
        bookId: String,
        chapterIndex: Int,
        sentenceCount: Int
    ): List<String>? = withContext(Dispatchers.IO) {
        if (bookId.isBlank() || sentenceCount <= 0) return@withContext null
        val tags = mutex.withLock { read(bookId, chapterIndex) } ?: return@withContext null
        tags.speakers.takeIf {
            tags.source == ChapterSpeakerTags.SOURCE_LLM && it.size == sentenceCount
        }
    }

    /**
     * Speaker tags for one chapter: the cache when present, otherwise one LLM
     * pass whose result is cached. Returns null whenever the rule-layer tags
     * should be kept (disabled, no key, empty roster, request failed).
     */
    suspend fun tagChapter(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        blocks: List<String>,
        sentenceCount: Int
    ): List<String>? {
        if (bookId.isBlank() || blocks.isEmpty() || sentenceCount <= 0) return null
        cachedSpeakers(bookId, chapterIndex, sentenceCount)?.let { return it }

        val settings = settingsStore.load()
        if (!settings.powerEnabled || !settings.remoteReady) return null
        val roster = roster(bookId)
        if (roster.isEmpty) return null

        val key = bookId + ":" + chapterIndex
        val started = mutex.withLock { inFlight.add(key) }
        if (!started) return null
        try {
            val client = chatClientFactory(settings)
            val tagger = SpeakerLlmTagger(chat = { system, user -> client.chatJson(system, user) })
            val result = tagger.tag(chapterTitle, blocks, roster)
            if (result.source != SpeakerLlmTagger.SOURCE_LLM) return null
            if (result.speakers.size != sentenceCount) return null
            // Only a chapter whose every window came back is cached; a partial
            // answer is used now but retried on the next read.
            if (result.complete) {
                store(
                    bookId,
                    ChapterSpeakerTags(
                        chapterIndex = chapterIndex,
                        speakers = result.speakers,
                        source = ChapterSpeakerTags.SOURCE_LLM,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            return result.speakers
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    /**
     * Character roster from the per-book glossary (§7): the AI profile fills it,
     * the user may edit it, and manual entries win - so the roster is exactly
     * what the reader sees in 本书术语表.
     */
    suspend fun roster(bookId: String): SpeakerRoster {
        val glossary = glossaryRepository.load(bookId)
        return SpeakerRoster.of(
            glossary.entries
                .filter {
                    it.enabled && it.kind == GlossaryEntry.KIND_CHARACTER && it.term.isNotBlank()
                }
                .map { SpeakerRoster.Entry(it.term, it.aliases) }
        )
    }

    /**
     * Every cached chapter tag list of a book, in chapter-file order (M3 input
     * for the adjacent-speaker statistics).
     */
    suspend fun cachedSpeakerLists(bookId: String): List<List<String>> =
        withContext(Dispatchers.IO) {
            if (bookId.isBlank()) return@withContext emptyList()
            mutex.withLock {
                val dir = bookDir(bookId)
                if (!dir.isDirectory) return@withLock emptyList()
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".json") }
                    .sortedBy { it.name }
                    .mapNotNull { file ->
                        runCatching { ChapterSpeakerTags.fromJson(JSONObject(file.readText())) }
                            .getOrNull()
                    }
                    .map { it.speakers }
                    .filter { it.isNotEmpty() }
            }
        }

    /** Drops every cached chapter of a book (book deleted, profile regenerated). */
    override val storeId: String = "ai/speaker-tags"

    override fun storageRoots(): List<File> = listOf(tagsDir)

    override suspend fun deleteBookData(book: Book) { delete(book.id) }

    fun delete(bookId: String) {
        if (bookId.isBlank()) return
        bookDir(bookId).deleteRecursively()
    }

    private fun read(bookId: String, chapterIndex: Int): ChapterSpeakerTags? {
        val file = tagFile(bookId, chapterIndex)
        if (!file.isFile) return null
        return runCatching { ChapterSpeakerTags.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    private suspend fun store(bookId: String, tags: ChapterSpeakerTags) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = tagFile(bookId, tags.chapterIndex)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(tags.toJson().toString())
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }
        }
    }

    private fun bookDir(bookId: String): File = File(tagsDir, bookId)

    private fun tagFile(bookId: String, chapterIndex: Int): File =
        File(bookDir(bookId), chapterIndex.toString() + ".json")
}
