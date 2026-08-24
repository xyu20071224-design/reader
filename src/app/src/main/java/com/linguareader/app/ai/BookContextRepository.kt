package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.data.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Builds a book profile, degrading to the offline glossary when the remote
 * backend fails (F-126: AI 请求失败/超时自动降级).
 */
internal suspend fun buildContextProfile(
    translator: AiTranslator,
    bookTitle: String,
    chapters: List<ChapterText>
): BookContextProfile {
    if (chapters.isEmpty()) return BookContextProfile(bookId = "", bookTitle = bookTitle)
    return try {
        translator.buildBookContext(bookTitle, chapters)
    } catch (failure: Throwable) {
        if (failure is CancellationException || translator.offline) throw failure
        LocalGlossaryTranslator().buildBookContext(bookTitle, chapters)
    }
}

/**
 * Word-lookup counterpart of [buildContextProfile]: a remote failure degrades
 * to the offline glossary, but — unlike profile generation — the degradation is
 * reported via [AiLookupOutcome.remoteFailed] so the UI can tell the user
 * (silently swallowing it was defect #3: a wrong key/endpoint made every tap
 * look like "nothing happened").
 */
internal suspend fun lookupWithContextFallback(
    translator: AiTranslator,
    profile: BookContextProfile,
    request: AiLookupRequest
): AiLookupOutcome = try {
    AiLookupOutcome(translator.translate(profile, request))
} catch (failure: Throwable) {
    if (failure is CancellationException || translator.offline) throw failure
    AiLookupOutcome(
        result = LocalGlossaryTranslator().translate(profile, request),
        remoteFailed = true
    )
}

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

    /**
     * Whether a profile file exists on disk. Feeds the shelf's ready label
     * after process death without parsing JSON (refresh checks every book).
     */
    suspend fun hasProfile(bookId: String): Boolean = withContext(Dispatchers.IO) {
        profileFile(bookId).isFile
    }

    suspend fun generate(book: Book, force: Boolean = false): BookContextProfile {
        val existing = if (force) null else profileFor(book)
        if (existing != null) return existing

        val profile = withContext(Dispatchers.IO) {
            val chapters = ChapterTextExtractor().extract(book)
            if (chapters.isEmpty()) {
                BookContextProfile(bookId = book.id, bookTitle = book.title)
            } else {
                buildContextProfile(chooseTranslator(), book.title, chapters)
                    .copy(bookId = book.id, bookTitle = book.title)
            }
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

    /**
     * Returns an outcome whose result is null when no profile is ready or the
     * backend has nothing to add. A remote failure is reported through
     * [AiLookupOutcome.remoteFailed] instead of being swallowed silently.
     */
    suspend fun translate(
        book: Book,
        request: AiLookupRequest
    ): AiLookupOutcome {
        val profile = profileFor(book) ?: return AiLookupOutcome(result = null)
        val settings = settingsStore.load()
        val translator = if (settings.powerEnabled && profile.source == "deepseek" && settings.remoteReady) {
            DeepSeekTranslator(settings)
        } else {
            LocalGlossaryTranslator()
        }
        return lookupWithContextFallback(translator, profile, request)
    }

    fun delete(bookId: String) {
        profileFile(bookId).delete()
    }

    private fun chooseTranslator(): AiTranslator {
        val settings = settingsStore.load()
        return if (settings.powerEnabled && settings.remoteReady) DeepSeekTranslator(settings)
        else LocalGlossaryTranslator()
    }

    private fun profileFile(bookId: String): File = File(profilesDir, "$bookId.json")
}
