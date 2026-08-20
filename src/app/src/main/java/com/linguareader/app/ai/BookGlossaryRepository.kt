package com.linguareader.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Per-book glossary storage.
 *
 * Kept separate from [BookContextProfile] so regenerating the AI profile never
 * overwrites user-edited entries. Manual entries always win over auto-imported
 * ones during [importFromProfile].
 */
class BookGlossaryRepository(private val context: Context) {
    private val glossaryDir = File(context.filesDir, "ai/glossary").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun load(bookId: String): BookGlossary = withContext(Dispatchers.IO) {
        mutex.withLock { read(bookId) }
    }

    suspend fun addOrUpdate(
        bookId: String,
        term: String,
        translation: String,
        kind: String = "custom"
    ): BookGlossary = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(bookId)
            val cleanTerm = term.trim()
            if (cleanTerm.isBlank()) return@withLock current
            val existing = current.entries.firstOrNull { it.key == cleanTerm.lowercase() }
            val updated = current.entries.filterNot { it.key == cleanTerm.lowercase() } + GlossaryEntry(
                term = cleanTerm,
                translation = translation.trim(),
                kind = kind,
                note = existing?.note.orEmpty(),
                enabled = existing?.enabled ?: true,
                origin = "manual",
                updatedAt = System.currentTimeMillis()
            )
            val glossary = BookGlossary(bookId, updated)
            write(bookId, glossary)
            glossary
        }
    }

    suspend fun update(bookId: String, entry: GlossaryEntry): BookGlossary =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(bookId)
                val key = entry.key
                val updated = current.entries.map {
                    if (it.key == key) entry.copy(updatedAt = System.currentTimeMillis()) else it
                }
                val glossary = BookGlossary(bookId, updated)
                write(bookId, glossary)
                glossary
            }
        }

    suspend fun remove(bookId: String, term: String): BookGlossary =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(bookId)
                val key = term.trim().lowercase()
                val glossary = BookGlossary(bookId, current.entries.filterNot { it.key == key })
                write(bookId, glossary)
                glossary
            }
        }

    /**
     * Imports auto-generated profile terms. Existing manual entries keep their
     * translation; existing auto/local entries gain a translation only when
     * they had none and the profile now provides one.
     */
    suspend fun importFromProfile(bookId: String, profile: BookContextProfile) {
        if (bookId.isBlank()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(bookId)
                val merged = linkedMapOf<String, GlossaryEntry>()
                current.entries.forEach { merged[it.key] = it }

                fun import(term: ContextTerm, kind: String) {
                    if (term.term.isBlank()) return
                    val entry = GlossaryEntry(
                        term = term.term,
                        translation = term.translation.trim(),
                        kind = kind,
                        note = term.note,
                        enabled = true,
                        origin = if (term.translation.isBlank()) "local" else "auto",
                        updatedAt = System.currentTimeMillis()
                    )
                    val existing = merged[entry.key]
                    when {
                        existing == null -> merged[entry.key] = entry
                        existing.origin == "manual" -> Unit
                        existing.translation.isBlank() && entry.translation.isNotBlank() ->
                            merged[entry.key] = existing.copy(
                                translation = entry.translation,
                                origin = "auto",
                                updatedAt = entry.updatedAt
                            )
                    }
                }

                profile.characters.forEach { import(it, GlossaryEntry.KIND_CHARACTER) }
                profile.places.forEach { import(it, "place") }
                profile.glossary.forEach { import(it, "glossary") }

                // Multi-voice M2 (PLAN-MULTI-VOICE §7): the profile also carries
                // voice-facing character attributes (aliases / gender / age /
                // style / importance). They are merged onto the very same
                // character entries, so the roster the speaker tagger validates
                // against is the glossary the user can edit - and manual values
                // are never overwritten.
                profile.characterProfiles.forEach { characterProfile ->
                    val name = characterProfile.name.trim()
                    if (name.isBlank()) return@forEach
                    val key = name.lowercase()
                    val existing = merged[key]
                    // Never re-kind a place/glossary entry into a character.
                    if (existing != null && existing.kind != GlossaryEntry.KIND_CHARACTER) {
                        return@forEach
                    }
                    val base = existing ?: GlossaryEntry(
                        term = name,
                        kind = GlossaryEntry.KIND_CHARACTER,
                        origin = "auto"
                    )
                    val updated = base.mergeProfile(characterProfile)
                    if (updated != existing) {
                        merged[key] = updated.copy(updatedAt = System.currentTimeMillis())
                    }
                }
                write(bookId, BookGlossary(bookId, merged.values.toList()))
            }
        }
    }

    fun delete(bookId: String) {
        glossaryFile(bookId).delete()
    }

    private fun read(bookId: String): BookGlossary {
        val file = glossaryFile(bookId)
        if (!file.isFile) return BookGlossary(bookId)
        return runCatching { BookGlossary.fromJson(JSONObject(file.readText())) }
            .getOrDefault(BookGlossary(bookId))
    }

    private fun write(bookId: String, glossary: BookGlossary) {
        val file = glossaryFile(bookId)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(glossary.toJson().toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun glossaryFile(bookId: String): File = File(glossaryDir, "$bookId.json")
}
