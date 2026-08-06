package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryRepository(private val context: Context) {
    // v1 stored every book inside a single library.json; v2 keeps one
    // metadata.json per book so progress writes touch one small file only.
    private val legacyMetadataFile = File(context.filesDir, "library.json")
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun loadBooks(): List<Book> = withContext(Dispatchers.IO) {
        mutex.withLock { readBooks() }
    }

    private fun readBooks(): List<Book> {
        migrateLegacyMetadata()
        return booksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { directory ->
                val metadata = File(directory, "metadata.json")
                if (!metadata.isFile) null
                else runCatching { Book.fromJson(JSONObject(metadata.readText())) }.getOrNull()
            }
            ?.filter { File(it.extractedDir).isDirectory && it.chapters.isNotEmpty() }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()
    }

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val importer = BookImporter(context, booksDir)
        val imported = importer.import(uri)
        mutex.withLock { writeMetadata(imported) }
        imported
    }

    suspend fun saveProgress(book: Book, chapterIndex: Int, pageIndex: Int, progress: Float) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeMetadata(
                    book.copy(
                        chapterIndex = chapterIndex,
                        pageIndex = pageIndex,
                        progress = progress.coerceIn(0f, 1f)
                    )
                )
            }
        }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // The per-book directory holds both the extracted content and its
            // metadata.json, so removing it atomically drops metadata too.
            val root = File(book.extractedDir)
            if (root.canonicalPath.startsWith(booksDir.canonicalPath + File.separator)) {
                root.deleteRecursively()
            }
        }
    }

    private fun writeMetadata(book: Book) {
        val metadata = File(File(booksDir, book.id), "metadata.json")
        metadata.parentFile?.mkdirs()
        val temp = File(metadata.parentFile, "${metadata.name}.tmp")
        temp.writeText(book.toJson().toString())
        if (!temp.renameTo(metadata)) {
            metadata.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun migrateLegacyMetadata() {
        if (!legacyMetadataFile.isFile) return
        runCatching {
            val array = JSONArray(legacyMetadataFile.readText())
            (0 until array.length())
                .map { Book.fromJson(array.getJSONObject(it)) }
                .forEach { writeMetadata(it) }
            legacyMetadataFile.delete()
        }
    }
}
