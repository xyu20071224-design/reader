package com.linguareader.shared.data

import com.linguareader.shared.app.AppContext
import com.linguareader.shared.ai.AiLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

class VocabularyRepository(private val appContext: AppContext) : BookScopedStore {
    private val vocabularyFile = File(appContext.filesDir, "vocabulary.json")
    private val mutex = Mutex()

    suspend fun load(): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        mode: ReviewMode = ReviewMode.GENTLE,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = save(book, chapterTitle, lookup, entry, mode.toPace(), aiResult)

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        pace: ReviewPace,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read()
            val headword = entry.matchedPhrase ?: entry.headword
            val id = headword.lowercase(Locale.ROOT)
            val now = System.currentTimeMillis()
            val existing = current.firstOrNull { it.id == id }
            // 同一个词可能以不同形态被查到（study / studied / studying），逐次累积，
            // 供阅读页做整词高亮；原型本身单独传，不必重复存。
            val surfaceForms = (existing?.surfaceForms.orEmpty() + entry.surfaceWord)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals(headword, ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .takeLast(MAX_SURFACE_FORMS)
            val saved = SavedWord(
                id = id,
                headword = headword,
                phonetic = entry.phonetic,
                meaning = entry.senses.take(3).joinToString("\n") { it.text },
                aiMeaning = aiResult?.contextualMeaning.orEmpty(),
                aiSource = aiResult?.source.orEmpty(),
                aiExplanation = aiResult?.explanation.orEmpty(),
                sentence = lookup.sentence.ifBlank { lookup.paragraph },
                bookId = book.id,
                bookTitle = book.title,
                chapterTitle = chapterTitle,
                addedAt = existing?.addedAt ?: now,
                reviewLevel = existing?.reviewLevel ?: 0,
                nextReviewAt = existing?.nextReviewAt ?: now + pace.firstDelayMillis,
                reviewCount = existing?.reviewCount ?: 0,
                surfaceForms = surfaceForms
            )
            val updated = current.filterNot { it.id == id } + saved
            write(updated)
            sorted(updated)
        }
    }

    override val storeId: String = "vocabulary"

    /** 生词本是单个 JSON 文件，不是目录；这里返回文件本身。 */
    override fun storageRoots(): List<File> = listOf(vocabularyFile)

    /**
     * 删书连带删该书生词（2026-09-01 拍板的**行为变更**）。
     *
     * 此前生词完全不随书删除：保存时记了 bookId，却只有按词 id 删的 remove(id)，
     * 于是书从书架消失后，它的生词还留在生词本里，带着一个打不开的书名，且没有
     * 任何入口能按书清掉。现在随书清理 —— 因为不可逆，删除对话框必须显示条数。
     */
    override suspend fun deleteBookData(book: Book) { removeByBook(book.id) }

    /**
     * 生词本是一个文件、不是按书命名的目录，默认的「子项名 = bookId」推断不适用。
     * 这里返回空 —— 但**存量脏数据确实存在**：D1.4 之前删掉的书，其生词至今仍留在
     * 文件里。那是「记录级孤儿」，不是文件级的，要清理得按 bookId 过滤记录，
     * 归 D2.4 的存储占用页面处理。
     */
    override fun orphans(books: List<Book>): List<File> = emptyList()

    suspend fun removeByBook(bookId: String): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read()
            if (bookId.isBlank()) return@withLock sorted(current)
            val remaining = current.filterNot { it.bookId == bookId }
            if (remaining.size != current.size) write(remaining)
            sorted(remaining)
        }
    }

    suspend fun remove(id: String): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = read().filterNot { it.id == id }
            write(updated)
            sorted(updated)
        }
    }

    suspend fun review(id: String, remembered: Boolean, pace: ReviewPace): List<SavedWord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val updated = read().map {
                    if (it.id == id) ReviewScheduler.reviewed(it, remembered, now, pace) else it
                }
                write(updated)
                sorted(updated)
            }
        }

    private fun read(): List<SavedWord> {
        if (!vocabularyFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(vocabularyFile.readText())
            (0 until array.length()).map { SavedWord.fromJson(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    private fun write(words: List<SavedWord>) {
        val temp = File(vocabularyFile.parentFile, "${vocabularyFile.name}.tmp")
        temp.writeText(JSONArray().apply { words.forEach { put(it.toJson()) } }.toString())
        if (!temp.renameTo(vocabularyFile)) {
            vocabularyFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun sorted(words: List<SavedWord>): List<SavedWord> =
        words.sortedWith(compareBy<SavedWord> { it.nextReviewAt }.thenByDescending { it.addedAt })

    companion object {
        /** 每个词最多累积多少个表面形，避免高亮词表无限膨胀。 */
        private const val MAX_SURFACE_FORMS = 8

        fun csv(words: List<SavedWord>): String = buildString {
            appendLine("word,phonetic,meaning,ai_meaning,ai_source,ai_explanation,sentence,book,chapter,review_count,next_review_at")
            words.forEach { word ->
                appendLine(
                    listOf(
                        word.headword,
                        word.phonetic,
                        word.meaning,
                        word.aiMeaning,
                        word.aiSource,
                        word.aiExplanation,
                        word.sentence,
                        word.bookTitle,
                        word.chapterTitle,
                        word.reviewCount.toString(),
                        reviewDate(word.nextReviewAt)
                    ).joinToString(",") { csvCell(it) }
                )
            }
        }

        private fun reviewDate(epochMillis: Long): String =
            if (epochMillis <= 0L) "0"
            else java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(java.util.Date(epochMillis))

        private fun csvCell(value: String): String =
            "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " / ")}\""
    }
}
