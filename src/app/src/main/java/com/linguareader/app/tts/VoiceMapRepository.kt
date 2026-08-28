package com.linguareader.app.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Owns the per-book [BookVoiceMap] (PLAN-MULTI-VOICE §3.3/§5.3).
 *
 * Storage is `files/voice_maps/<bookId>.json`, written atomically behind a
 * [Mutex] like the other repositories. The mapping is deliberately sticky:
 *
 * - a book keeps its mapping across chapters and sessions;
 * - new characters are assigned incrementally without moving existing ones;
 * - switching engine (different voice library) recomputes everything **except**
 *   the entries the user locked.
 *
 * The character list and the co-occurrence statistics are injected as suspend
 * providers, so this class stays free of AI-layer types and is easy to test.
 */
class VoiceMapRepository(
    context: Context,
    private val charactersProvider: suspend (String) -> List<VoiceCharacter>,
    private val cooccurrenceProvider: suspend (String) -> Map<Pair<String, String>, Int> =
        { emptyMap() }
) {
    private val appContext = context.applicationContext
    private val mapsDir = File(appContext.filesDir, "voice_maps")
    private val mutex = Mutex()

    suspend fun load(bookId: String): BookVoiceMap? = withContext(Dispatchers.IO) {
        if (bookId.isBlank()) return@withContext null
        mutex.withLock { read(bookId) }
    }

    /**
     * The mapping to play [bookId] with: loaded, extended for new characters and
     * recomputed when the engine changed. Returns null when the engine offers no
     * voice library at all (D2: 系统语音).
     */
    suspend fun ensureFor(
        bookId: String,
        library: VoiceLibrary,
        narratorLanguages: List<String> = listOf(TtsLanguage.ENGLISH, TtsLanguage.CHINESE),
        reserved: Set<String> = emptySet()
    ): BookVoiceMap? {
        if (bookId.isBlank() || library.isEmpty) return load(bookId)
        val existing = load(bookId)
        val characters = charactersProvider(bookId)
        val cooccurrence = if (characters.isEmpty()) emptyMap() else cooccurrenceProvider(bookId)
        val assigned = VoiceAssigner.assign(
            bookId = bookId,
            characters = characters,
            library = library,
            cooccurrence = cooccurrence,
            existing = existing,
            narratorLanguages = narratorLanguages,
            reserved = reserved
        )
        if (assigned != existing) store(assigned)
        return assigned
    }

    /** Pins one speaker to a voice (user override, never auto-reassigned). */
    suspend fun lock(bookId: String, speaker: String, voiceId: String): BookVoiceMap {
        val current = load(bookId) ?: BookVoiceMap(bookId)
        val updated = current.copy(bookId = bookId).lock(speaker, voiceId)
        if (updated != current) store(updated)
        return updated
    }

    /** Pins the narration voice of one language. */
    suspend fun lockNarrator(bookId: String, language: String, voiceId: String): BookVoiceMap {
        val current = load(bookId) ?: BookVoiceMap(bookId)
        val updated = current.copy(bookId = bookId).lockNarrator(language, voiceId)
        if (updated != current) store(updated)
        return updated
    }

    /** Releases a pin so automatic assignment may move that speaker again. */
    suspend fun unlock(bookId: String, speaker: String): BookVoiceMap {
        val current = load(bookId) ?: BookVoiceMap(bookId)
        val updated = current.unlock(speaker)
        if (updated != current) store(updated)
        return updated
    }

    /** Removes a character's pin/mapping (roster entry was deleted). */
    suspend fun removeCharacter(bookId: String, speaker: String): BookVoiceMap {
        val current = load(bookId) ?: BookVoiceMap(bookId)
        val updated = current.copy(bookId = bookId).removeCharacter(speaker)
        if (updated != current) store(updated)
        return updated
    }

    fun delete(bookId: String) {
        if (bookId.isBlank()) return
        mapFile(bookId).delete()
    }

    private fun read(bookId: String): BookVoiceMap? {
        val file = mapFile(bookId)
        if (!file.isFile) return null
        return runCatching { BookVoiceMap.fromJson(JSONObject(file.readText())) }
            .getOrNull()
            ?.copy(bookId = bookId)
    }

    private suspend fun store(map: BookVoiceMap) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = mapFile(map.bookId)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(map.toJson().toString())
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }
        }
    }

    private fun mapFile(bookId: String): File = File(mapsDir, bookId + ".json")
}
