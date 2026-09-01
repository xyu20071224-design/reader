package com.linguareader.app.ai

import com.linguareader.app.data.Book
import com.linguareader.app.data.BookScopedStore
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
class BookGlossaryRepository(private val context: Context) : BookScopedStore {
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

    /**
     * 给已有角色加别名：别名会进 LLM 标注的角色表提示词与答案校验
     * （[SpeakerRoster]），让「书中其他称呼」也能命中同一角色。
     * 大小写去重；与名字相同、空白或条目不存在时返回 null/原样。
     */
    suspend fun addAlias(bookId: String, name: String, alias: String): BookGlossary? {
        val clean = alias.trim()
        if (bookId.isBlank() || clean.isEmpty()) return null
        return mutateCharacter(bookId, name) { entry ->
            if (clean.equals(entry.term, ignoreCase = true) ||
                entry.aliases.any { it.equals(clean, ignoreCase = true) }
            ) {
                null
            } else {
                entry.copy(aliases = entry.aliases + clean)
            }
        }
    }

    /** 删除角色的一个别名（大小写不敏感）；条目不存在时返回 null。 */
    suspend fun removeAlias(bookId: String, name: String, alias: String): BookGlossary? =
        mutateCharacter(bookId, name) { entry ->
            val remaining = entry.aliases.filterNot { it.equals(alias.trim(), ignoreCase = true) }
            if (remaining.size == entry.aliases.size) null else entry.copy(aliases = remaining)
        }

    /**
     * 对一个角色条目做原地修改的公共骨架：找不到条目返回 null；
     * [mutate] 返回 null 表示无需修改（如重复别名），原数据原样返回。
     */
    private suspend fun mutateCharacter(
        bookId: String,
        name: String,
        mutate: (GlossaryEntry) -> GlossaryEntry?
    ): BookGlossary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(bookId)
            val key = name.trim().lowercase()
            val existing = current.entries.firstOrNull { it.key == key }
                ?: return@withLock null
            val mutated = mutate(existing)
                ?: return@withLock BookGlossary(bookId, current.entries)
            val updated = mutated.copy(updatedAt = System.currentTimeMillis())
            val glossary = BookGlossary(
                bookId,
                current.entries.filterNot { it.key == key } + updated
            )
            write(bookId, glossary)
            glossary
        }
    }

    /**
     * 多角色面板的「添加角色」：把一个名字以手动条目写进角色表。
     *
     * 手动条目（origin=manual）不会被 [BookGlossary.sanitized] 过滤、不会被
     * 档案重导入覆盖；若同名条目已存在（哪怕是 place/glossary），按用户意图
     * 改判为 character 并保留已有翻译/别名。返回 null 表示名字无效。
     */
    suspend fun addManualCharacter(
        bookId: String,
        name: String,
        gender: String = ""
    ): BookGlossary? {
        if (bookId.isBlank()) return null
        val cleanName = name.trim()
        if (cleanName.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(bookId)
                val key = cleanName.lowercase()
                val existing = current.entries.firstOrNull { it.key == key }
                val entry = (existing ?: GlossaryEntry(term = cleanName)).copy(
                    term = cleanName,
                    kind = GlossaryEntry.KIND_CHARACTER,
                    origin = "manual",
                    enabled = true,
                    gender = gender.ifBlank { existing?.gender.orEmpty() },
                    updatedAt = System.currentTimeMillis()
                )
                val glossary = BookGlossary(
                    bookId,
                    current.entries.filterNot { it.key == key } + entry
                )
                write(bookId, glossary)
                glossary
            }
        }
    }

    /**
     * 多角色面板「编辑角色」：更新一个角色的 性别/年龄组/风格/重要性。
     * 不存在的条目返回 null；[name] 仅定位用，不改名。
     */
    suspend fun updateCharacter(
        bookId: String,
        name: String,
        gender: String,
        ageGroup: String,
        style: List<String>,
        importance: String
    ): BookGlossary? = mutateCharacter(bookId, name) { entry ->
        entry.copy(
            kind = GlossaryEntry.KIND_CHARACTER,
            gender = gender,
            ageGroup = ageGroup,
            style = style.map(String::trim).filter(String::isNotBlank),
            importance = importance
        )
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

                // B 兜底：凡被 AI 同时归进 places/glossary 的 term，一律不设角色
                // 身份——即使模型也把它列进了 characters（地名/专名误识别过滤）。
                val characterTerms = profile.characters.map { it.term.trim().lowercase() }.toSet()
                val nonCharacterTerms = buildSet {
                    profile.places.forEach { add(it.term.trim().lowercase()) }
                    profile.glossary.forEach { add(it.term.trim().lowercase()) }
                }
                profile.characters.forEach { term ->
                    if (term.term.trim().lowercase() in nonCharacterTerms) return@forEach
                    import(term, GlossaryEntry.KIND_CHARACTER)
                }
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
                    // B 兜底：name 命中地名/专名（places/glossary），不建角色。
                    if (key in nonCharacterTerms) return@forEach
                    // 陌生名字（既不在 characters 列表、也没有既有条目）视为可疑，不建角色；
                    // 有既有条目则允许合并（别名/属性增量合并，不依赖 characters）。
                    if (key !in characterTerms && existing == null) return@forEach
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

    override val storeId: String = "ai/glossary"

    override fun storageRoots(): List<File> = listOf(glossaryDir)

    override suspend fun deleteBookData(book: Book) { delete(book.id) }

    fun delete(bookId: String) {
        glossaryFile(bookId).delete()
    }

    private fun read(bookId: String): BookGlossary {
        val file = glossaryFile(bookId)
        if (!file.isFile) return BookGlossary(bookId)
        return runCatching { BookGlossary.fromJson(JSONObject(file.readText())) }
            .getOrDefault(BookGlossary(bookId))
            .sanitized()
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
