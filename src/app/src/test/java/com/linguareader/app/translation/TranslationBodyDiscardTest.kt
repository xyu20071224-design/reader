package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D1.8：对齐完成后丢弃**出版译本**的正文，AI 译本保留。
 *
 * 依据（测绘取证）：对齐档案自带译文全文（TranslationModels 的 zhParagraphs 段落表 +
 * 句对的 zs），而 translations/<译本id>/ 的章节文件运行期零读取 —— 只在 attach 时被
 * buildMemory 消费一次。留着就是整本译本白占空间。
 *
 * AI 译本不删：花钱产出、唯一副本，档案损坏就再也拿不回来；出版译本的原文件还在
 * 用户手上，重新导入即可。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationBodyDiscardTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun writeBook(id: String, dir: File, paragraphs: List<String>): Book {
        dir.mkdirs()
        val body = paragraphs.joinToString("") { "<p>" + it + "</p>" }
        File(dir, "chapter_000.xhtml").writeText("<html><body>" + body + "</body></html>")
        return Book(
            id = id,
            title = "Book " + id,
            author = "Author",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "chapter_000.xhtml")),
            addedAt = System.currentTimeMillis()
        )
    }

    private fun source(id: String) = writeBook(
        id,
        File(context.filesDir, "books/" + id),
        listOf("In 1926 he left the town.", "She carried a lantern.")
    )

    private fun translation(id: String) = writeBook(
        id,
        File(context.filesDir, "translations/" + id),
        listOf("1926年他离开了小镇。", "她提着一盏灯。")
    )

    @Test
    fun publishedTranslationBodyIsDiscardedButLookupsStillWork() = runBlocking<Unit> {
        val book = source("src-published")
        // 出版译本的目录名是内容哈希（这里用一个不带 AI 前缀的 id 代表它）。
        val zh = translation("imported-abc123")
        val body = File(zh.extractedDir)
        assertTrue(body.isDirectory)

        val repository = TranslationMemoryRepository(context)
        repository.attachGenerated(book, zh)

        assertFalse(body.exists(), "对齐后出版译本正文应被丢弃")
        assertTrue(File(context.filesDir, "translation-memory/" + book.id + ".json").isFile)
        // 档案自带译文：正文没了，查词照样命中。
        val hit = repository.lookup(
            book, 0,
            WordLookup("lantern", "She carried a lantern.", "She carried a lantern.", 12, 0f, 0f)
        )
        assertNotNull(hit, "正文删掉后译本对照就查不到了——档案没有自带译文")
    }

    @Test
    fun aiTranslationBodyIsKept() = runBlocking<Unit> {
        val book = source("src-ai")
        val zh = translation(Book.AI_TRANSLATION_ID_PREFIX + "src-ai")
        val body = File(zh.extractedDir)

        TranslationMemoryRepository(context).attachGenerated(book, zh)

        assertTrue(body.isDirectory, "AI 译本是花钱产出的唯一副本，不能删")
    }
}