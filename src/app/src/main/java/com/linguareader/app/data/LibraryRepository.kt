package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书库仓库的 Android 壳（桌面迁移 M2 刀7）。
 *
 * 书架读写/进度落盘/迁移的真相已迁入 `com.linguareader.shared.data.LibraryRepository`
 * （构造器收 `AppContext`）；本类只保留 SAF 导入（`importBook(uri)`）。
 */
class LibraryRepository(private val context: Context) : BookScopedStore {
    private val shared = com.linguareader.shared.data.LibraryRepository(AndroidAppContext(context))

    suspend fun loadBooks(): List<Book> = shared.loadBooks()

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val imported = BookImporter(context, shared.booksDir).import(uri)
        shared.registerImportedBook(imported)
    }

    suspend fun saveProgress(
        book: Book,
        chapterIndex: Int,
        pageIndex: Int,
        progress: Float,
        locusBlockIndex: Int = Book.NO_LOCUS,
        locusCharOffset: Int = 0,
        locusAnchor: String = Book.ANCHOR_EXACT
    ) = shared.saveProgress(book, chapterIndex, pageIndex, progress, locusBlockIndex, locusCharOffset, locusAnchor)

    suspend fun saveListeningProgress(book: Book, chapterIndex: Int, sentenceIndex: Int) =
        shared.saveListeningProgress(book, chapterIndex, sentenceIndex)

    suspend fun saveTranslation(
        book: Book,
        translationBookId: String,
        translationTitle: String,
        alignedAt: Long
    ) = shared.saveTranslation(book, translationBookId, translationTitle, alignedAt)

    override val storeId: String get() = shared.storeId

    override fun storageRoots(): List<File> = shared.storageRoots()

    override suspend fun deleteBookData(book: Book) { shared.deleteBookData(book) }

    override fun orphans(books: List<Book>): List<File> = shared.orphans(books)

    suspend fun deleteBook(book: Book) = shared.deleteBook(book)
}
