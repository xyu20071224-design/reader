package com.linguareader.app.facade

import android.app.Application
import android.net.Uri
import com.linguareader.app.data.Book
import com.linguareader.app.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 书架/图书馆域 facade：导入、删除、进度保存、打开/关闭书。
 *
 * 委托给 [LibraryRepository]；删书时顺带清理 AI 语境档案、术语表与云端 TTS
 * 章节缓存（这三项清理动作由调用方通过 [onBookDeleted] 回调触发，使本类
 * 不直接依赖 ai / tts 包，保持域边界清晰）。
 */
internal class LibraryFacade(
    private val application: Application,
    private val scope: CoroutineScope
) {
    private val library = LibraryRepository(application)

    /** 删书时的附带清理（语境档案 + 术语表），由上层 facade 组合注入。 */
    var onBeforeDelete: suspend (Book) -> Unit = {}

    suspend fun loadBooks(): List<Book> = library.loadBooks()

    suspend fun importBook(uri: Uri): Book = library.importBook(uri)

    suspend fun saveProgress(book: Book, chapterIndex: Int, pageIndex: Int, progress: Float) {
        library.saveProgress(book, chapterIndex, pageIndex, progress)
    }

    fun deleteBook(book: Book) {
        scope.launch {
            library.deleteBook(book)
            onBeforeDelete(book)
            // Cloud TTS chapter audio cache is per book; remove it with the book.
            File(application.filesDir, "tts_cache/${book.id}").deleteRecursively()
        }
    }
}
