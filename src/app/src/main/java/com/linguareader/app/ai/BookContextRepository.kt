package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Owns per-book context profiles.
 *
 * Profile generation chooses the configured backend: DeepSeek when the user
 * supplied a key, otherwise the offline local glossary. Lookup never blocks
 * on network when no profile exists yet.
 */
class BookContextRepository(
    private val context: Context,
    private val settingsStore: AiSettingsStore
) {
    private val profilesDir = File(context.filesDir, "ai/book-context").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun profileFor(book: Book): BookContextProfile? = withContext(Dispatchers.IO) {
        val file = profileFile(book.id)
        if (!file.isFile) return@withContext null
        runCatching { BookContextProfile.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    suspend fun generate(book: Book, force: Boolean = false): BookContextProfile {
        val existing = if (force) null else profileFor(book)
        if (existing != null) return existing

        val translator = chooseTranslator()
        val profile = withContext(Dispatchers.IO) {
            val chapters = ChapterTextExtractor().extract(book)
            translator.buildBookContext(book.title, chapters)
                .copy(bookId = book.id, bookTitle = book.title)
        }

        mutex.withLock {
            withContext(Dispatchers.IO) {
                val temp = File(profilesDir, "${book.id}.json.tmp")
                temp.writeText(profile.toJson().toString())
                if (!temp.renameTo(profileFile(book.id))) {
                    profileFile(book.id).writeText(temp.readText())
                    temp.delete()
                }
            }
        }
        return profile
    }

    /** Returns null when no profile is ready or the backend has nothing to add. */
    suspend fun translate(
        book: Book,
        request: AiLookupRequest
    ): AiLookupResult? {
        val profile = profileFor(book) ?: return null
        return chooseTranslator().translate(profile, request)
    }

    fun delete(bookId: String) {
        profileFile(bookId).delete()
    }

    private fun chooseTranslator(): AiTranslator {
        val settings = settingsStore.load()
        return if (settings.remoteReady) DeepSeekTranslator(settings)
        else LocalGlossaryTranslator()
    }

    private fun profileFile(bookId: String): File = File(profilesDir, "$bookId.json")
}
