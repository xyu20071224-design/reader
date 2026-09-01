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

    /**
     * 盘上这本书的最新记录；文件不存在或解析失败时返回 null。
     *
     * 所有写入都必须以它为基底，**不能信任调用方传进来的 Book 快照**：
     * 阅读页每 800ms 回写一次，用的是打开书那一刻的快照（其中 tts 两个字段是旧值）；
     * 听书服务回写用的是起播那一刻的快照（其中阅读三个字段是旧值）。
     * 两边各自整份覆盖 metadata.json，边听边读就会互相把对方的进度打回旧值——
     * 而续听正是靠 ttsChapterIndex/ttsSentenceIndex，下次就从头开始了。
     */
    private fun readMetadata(bookId: String): Book? {
        val metadata = File(File(booksDir, bookId), "metadata.json")
        if (!metadata.isFile) return null
        return runCatching { Book.fromJson(JSONObject(metadata.readText())) }.getOrNull()
    }

    /**
     * 阅读进度落盘。
     *
     * [locusBlockIndex] 是位置真相（章内块下标），页码只是派生展示量、迁移期
     * 兜底：它随字号/旋转/分栏变化，同一个页码在不同排版下指着不同的文字。
     * 传 [Book.NO_LOCUS] 表示这次还没取到锚点——此时**保留盘上已有的锚点**，
     * 不要用「没有」覆盖掉一个有效锚点。
     */
    suspend fun saveProgress(
        book: Book,
        chapterIndex: Int,
        pageIndex: Int,
        progress: Float,
        locusBlockIndex: Int = Book.NO_LOCUS,
        locusCharOffset: Int = 0,
        locusAnchor: String = Book.ANCHOR_EXACT
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val latest = readMetadata(book.id) ?: book
            val hasNewLocus = locusBlockIndex >= 0 || locusAnchor != Book.ANCHOR_EXACT
            // 换章时锚点会先变成 chapter-start/end 再落到具体块：章号变了就必须
            // 接受新锚点，否则会拿上一章的块下标去定位新章。
            val chapterChanged = latest.chapterIndex != chapterIndex
            writeMetadata(
                latest.copy(
                    chapterIndex = chapterIndex,
                    pageIndex = pageIndex,
                    progress = progress.coerceIn(0f, 1f),
                    locusBlockIndex = if (hasNewLocus || chapterChanged) locusBlockIndex else latest.locusBlockIndex,
                    locusCharOffset = if (hasNewLocus || chapterChanged) locusCharOffset.coerceAtLeast(0) else latest.locusCharOffset,
                    locusAnchor = if (hasNewLocus || chapterChanged) locusAnchor else latest.locusAnchor
                )
            )
        }
    }

    suspend fun saveListeningProgress(book: Book, chapterIndex: Int, sentenceIndex: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val latest = readMetadata(book.id) ?: book
                writeMetadata(
                    latest.copy(
                        ttsChapterIndex = chapterIndex.coerceAtLeast(0),
                        ttsSentenceIndex = sentenceIndex.coerceAtLeast(0)
                    )
                )
            }
        }

    /** 记录（或清空）这本书配的中文译本；清空时三个字段传空值。 */
    suspend fun saveTranslation(
        book: Book,
        translationBookId: String,
        translationTitle: String,
        alignedAt: Long
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 整本对齐/AI 翻译要跑几十秒到几分钟，期间用户多半在读；
            // 用旧快照回写会把这段时间的阅读进度整体抹掉。
            val latest = readMetadata(book.id) ?: book
            writeMetadata(
                latest.copy(
                    translationBookId = translationBookId,
                    translationTitle = translationTitle,
                    translationAlignedAt = alignedAt
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
        if (temp.renameTo(metadata)) return
        // rename 失败时不能再去直写目标文件：那是非原子的，中途崩溃/掉电会留下
        // 截断的 metadata.json，而 readBooks() 解析失败会把这本书静默从书架抹掉。
        // 先删目标再 rename（rename 本身是原子的）；仍不行就保留 .tmp 原地不动，
        // 宁可这次进度没存上，也不能把已有记录写坏。
        // 这里刻意不抛异常：本函数在阅读页 800ms 一次的回写路径上，
        // 抛出去会让调用方的 launch 直接崩掉。
        if (metadata.delete()) temp.renameTo(metadata)
    }

    /**
     * v1 的单文件 library.json 迁到 v2 的每书一个 metadata.json。
     *
     * 逐条迁移：过去是急切 `map` + 整体 `runCatching`，v1 库里只要有一条坏记录，
     * 整批迁移全部放弃、异常被空 catch 吞掉、旧文件还不删 —— 于是每次 readBooks()
     * 都重试一遍再失败，老用户的书架会永久为空且没有任何提示。
     */
    private fun migrateLegacyMetadata() {
        if (!legacyMetadataFile.isFile) return
        val array = runCatching { JSONArray(legacyMetadataFile.readText()) }.getOrNull() ?: return
        var migrated = 0
        var failed = 0
        for (index in 0 until array.length()) {
            val book = runCatching { Book.fromJson(array.getJSONObject(index)) }.getOrNull()
            if (book == null) {
                failed++
                continue
            }
            if (runCatching { writeMetadata(book) }.isSuccess) migrated++ else failed++
        }
        // 只要迁出过东西就删旧文件（剩下的坏记录本来也读不出来）；
        // 一条都没迁成功时保留原始文件，留给日后人工恢复。
        if (migrated > 0 || failed == 0) legacyMetadataFile.delete()
    }
}
