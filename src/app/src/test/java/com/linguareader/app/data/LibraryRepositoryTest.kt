package com.linguareader.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 书库元数据读-改-写：听书与阅读两个写入方不得用各自的过期快照互相覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun repository(): LibraryRepository = LibraryRepository(context)

    private fun writeBook(book: Book) {
        val dir = File(context.filesDir, "books/${book.id}")
        dir.mkdirs()
        val extracted = File(context.filesDir, "books/${book.id}/extracted")
        extracted.mkdirs()
        File(dir, "metadata.json").writeText(book.copy(extractedDir = extracted.absolutePath).toJson().toString())
    }

    private fun baseBook(): Book = Book(
        id = "book-lib",
        title = "Test",
        author = "A",
        extractedDir = File(context.filesDir, "books/book-lib/extracted").absolutePath,
        coverRelativePath = null,
        chapters = listOf(Chapter("One", "one.xhtml")),
        addedAt = 1L
    )

    @Test
    fun `listening and reading progress do not overwrite each other`() = runBlocking {
        val book = baseBook()
        writeBook(book)
        val repo = repository()

        // 听书侧写入：快照里阅读进度是旧值（0）。
        repo.saveListeningProgress(book.copy(chapterIndex = 0, pageIndex = 0), chapterIndex = 1, sentenceIndex = 42)
        // 阅读侧随后写入：用的仍是开书那一刻的快照，tts 字段是 0。
        repo.saveProgress(book.copy(ttsChapterIndex = 0, ttsSentenceIndex = 0), chapterIndex = 2, pageIndex = 3, progress = 0.5f)

        val stored = repo.loadBooks().first { it.id == book.id }
        // 阅读侧只该改阅读三个字段，不得回退听书进度。
        assertEquals(2, stored.chapterIndex)
        assertEquals(3, stored.pageIndex)
        assertEquals(1, stored.ttsChapterIndex)
        assertEquals(42, stored.ttsSentenceIndex)
    }

    @Test
    fun `saving without a locus keeps the one already on disk`() = runBlocking {
        // 800ms 节流轮询可能在锚点还没取回来时就落盘。此时传的是「没有锚点」，
        // 绝不能把盘上已有的有效锚点覆盖成 -1 —— 那等于把位置真相删掉，
        // 下次打开又退回按页码还原（旋转/改字号必漂移）。
        File(baseBook().extractedDir).mkdirs()
        writeBook(baseBook().copy(locusBlockIndex = 12, locusCharOffset = 34))
        val repo = repository()

        repo.saveProgress(baseBook(), chapterIndex = 0, pageIndex = 5, progress = .3f)

        val saved = repo.loadBooks().first { it.id == "book-lib" }
        assertEquals(12, saved.locusBlockIndex)
        assertEquals(34, saved.locusCharOffset)
        assertEquals(5, saved.pageIndex)
    }

    @Test
    fun `a new locus replaces the old one`() = runBlocking {
        File(baseBook().extractedDir).mkdirs()
        writeBook(baseBook().copy(locusBlockIndex = 12, locusCharOffset = 34))
        val repo = repository()

        repo.saveProgress(
            baseBook(), chapterIndex = 0, pageIndex = 5, progress = .3f,
            locusBlockIndex = 41, locusCharOffset = 128
        )

        val saved = repo.loadBooks().first { it.id == "book-lib" }
        assertEquals(41, saved.locusBlockIndex)
        assertEquals(128, saved.locusCharOffset)
    }

    @Test
    fun `changing chapter accepts a locus reset instead of keeping the old chapter anchor`() = runBlocking {
        // 换章的一瞬间锚点会先变成 chapter-start/end 再落到具体块。若沿用
        // 「没有锚点就保留旧值」的规则，就会拿上一章的块下标去定位新章。
        File(baseBook().extractedDir).mkdirs()
        writeBook(baseBook().copy(chapterIndex = 0, locusBlockIndex = 12, locusCharOffset = 34))
        val repo = repository()

        repo.saveProgress(baseBook(), chapterIndex = 1, pageIndex = 0, progress = .5f)

        val saved = repo.loadBooks().first { it.id == "book-lib" }
        assertEquals(1, saved.chapterIndex)
        assertEquals(Book.NO_LOCUS, saved.locusBlockIndex)
    }

    @Test
    fun `translation write keeps progress made during the long job`() = runBlocking {
        val book = baseBook()
        writeBook(book)
        val repo = repository()

        // 用户在整本对齐期间读到了第 7 页；对齐完成时传入的是旧快照。
        repo.saveProgress(book.copy(chapterIndex = 7, pageIndex = 7), chapterIndex = 9, pageIndex = 9, progress = 0.9f)
        repo.saveTranslation(book.copy(chapterIndex = 7, pageIndex = 7), "zh-1", "译本", 123L)

        val stored = repo.loadBooks().first { it.id == book.id }
        assertEquals(9, stored.chapterIndex)
        assertEquals("zh-1", stored.translationBookId)
    }

    @Test
    fun `legacy migration tolerates one bad record instead of dropping the whole shelf`() {
        val book = baseBook()
        // loadBooks 会过滤掉 extractedDir 不存在的书，迁移用例必须先落好目录。
        File(book.extractedDir).mkdirs()
        val legacy = File(context.filesDir, "library.json")
        val good = book.toJson()
        val bad = JSONObject().put("id", "broken")
        legacy.writeText(JSONArray().put(good).put(bad).toString())

        val repo = repository()
        val books = runBlocking { repo.loadBooks() }

        // 好记录迁出，坏记录不再毁掉整批，旧文件也已清理。
        assertEquals(listOf(book.id), books.map { it.id })
        assertTrue(!legacy.exists())
        assertNotNull(File(context.filesDir, "books/${book.id}/metadata.json").takeIf { it.isFile })
    }
}
