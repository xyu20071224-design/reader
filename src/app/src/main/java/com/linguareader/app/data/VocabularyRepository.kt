package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import com.linguareader.shared.ai.AiLookupResult
import com.linguareader.shared.data.VocabularyRepository as SharedVocabularyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生词本仓库的 Android 壳（桌面迁移 M2 刀4）。
 *
 * 增删改查/复习调度的真相已迁入 `com.linguareader.shared.data.VocabularyRepository`
 * （构造器收 `AppContext`，JSON 文件落在 `appContext.filesDir`）；本类只保留平台侧
 * 一件事：CSV 导出走 SAF（`contentResolver.openOutputStream`）。
 */
class VocabularyRepository(context: Context) : BookScopedStore {
    private val shared = com.linguareader.shared.data.VocabularyRepository(AndroidAppContext(context))
    private val appContext = context.applicationContext

    suspend fun load(): List<SavedWord> = shared.load()

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        mode: ReviewMode = ReviewMode.GENTLE,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = shared.save(book, chapterTitle, lookup, entry, mode, aiResult)

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        pace: ReviewPace,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = shared.save(book, chapterTitle, lookup, entry, pace, aiResult)

    override val storeId: String get() = shared.storeId

    override fun storageRoots(): List<File> = shared.storageRoots()

    override suspend fun deleteBookData(book: Book) { shared.deleteBookData(book) }

    override fun orphans(books: List<Book>): List<File> = shared.orphans(books)

    suspend fun removeByBook(bookId: String): List<SavedWord> = shared.removeByBook(bookId)

    suspend fun remove(id: String): List<SavedWord> = shared.remove(id)

    suspend fun review(id: String, remembered: Boolean, pace: ReviewPace): List<SavedWord> =
        shared.review(id, remembered, pace)

    suspend fun export(uri: Uri, words: List<SavedWord>) = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
            it.write(SharedVocabularyRepository.csv(words))
        } ?: error("无法打开导出文件")
    }
}
