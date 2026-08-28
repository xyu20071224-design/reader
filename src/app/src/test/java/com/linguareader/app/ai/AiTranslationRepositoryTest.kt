package com.linguareader.app.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.WordLookup
import com.linguareader.app.translation.TranslationMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * AI 整本翻译编排层：批次翻译落检查点、续跑复用、指纹失效重翻、
 * 失败带原因重试一次，以及「译文 → 合成译本 → attachGenerated 对齐」端到端。
 */
@RunWith(RobolectricTestRunner::class)
class AiTranslationRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sourceBookId = "src-ai-test"

    /** 从 prompt 里抽编号段落并「翻译」（原文前加 译N：），保证数字锚点保留。 */
    private class FakeChat : AiTranslationChatClient {
        var calls = 0
        val prompts = mutableListOf<String>()
        var failNextCall = false

        override suspend fun translateSegments(system: String, user: String): JSONObject {
            calls++
            prompts += user
            if (failNextCall) {
                failNextCall = false
                throw AiRequestException("网络断了")
            }
            val array = JSONArray()
            Regex("\\[(\\d+)] (.+)").findAll(user).forEach { match ->
                array.put(
                    JSONObject()
                        .put("i", match.groupValues[1].toInt())
                        .put("t", "译${match.groupValues[1]}：${match.groupValues[2]}")
                )
            }
            return JSONObject().put("segments", array)
        }
    }

    private fun makeSourceBook(): Book {
        val dir = File(context.filesDir, "books-src/$sourceBookId")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "chapter_000.xhtml").writeText(
            "<html><body><p>Hello world.</p><p>In 1926 he left the town.</p></body></html>"
        )
        File(dir, "chapter_001.xhtml").writeText(
            "<html><body><p>Harry walked to Hogwarts alone.</p></body></html>"
        )
        return Book(
            id = sourceBookId,
            title = "Test Book",
            author = "Author",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(
                Chapter(title = "One", relativePath = "chapter_000.xhtml"),
                Chapter(title = "Two", relativePath = "chapter_001.xhtml")
            ),
            addedAt = 0L
        )
    }

    private fun repository(chat: AiTranslationChatClient): AiTranslationRepository =
        AiTranslationRepository(
            context,
            AiSettingsStore(context),
            BookGlossaryRepository(context)
        ) { chat }

    @Test
    fun `translates all batches, writes chapters and checkpoints`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        val repo = repository(chat)
        val percents = mutableListOf<Int>()

        val translation = repo.translateBook(book) { percent -> percents += percent }

        // 两个短章各 1 批。
        assertEquals(2, chat.calls)
        assertEquals(listOf(50, 100), percents)
        assertEquals(AiTranslationRepository.translationId(sourceBookId), translation.id)
        // 译文目录与合成章节就位，每段一个 <p>。
        val dir = File(context.filesDir, "translations/ai-$sourceBookId")
        val chapter0 = File(dir, "chapter_000.xhtml").readText()
        assertTrue("<p>译0：Hello world.</p>" in chapter0)
        assertTrue("<p>译1：In 1926 he left the town.</p>" in chapter0)
        assertEquals(2, translation.chapters.size)
        // 每批一个检查点文件。
        assertTrue(File(context.filesDir, "ai/ai-translations/$sourceBookId/0-0.json").isFile)
        assertTrue(File(context.filesDir, "ai/ai-translations/$sourceBookId/1-0.json").isFile)
    }

    @Test
    fun `rerun reuses checkpoints without any request`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        val repo = repository(chat)
        repo.translateBook(book) { _ -> }
        val callsAfterFirst = chat.calls

        // 同一本书重跑：全部批次命中检查点，零出网。
        repo.translateBook(book) { _ -> }
        assertEquals(callsAfterFirst, chat.calls)
    }

    @Test
    fun `stale checkpoint hash triggers retranslation`() = runBlocking {
        val book = makeSourceBook()
        repository(FakeChat()).translateBook(book) { _ -> }

        // 篡改一个检查点的指纹 → 该批作废重翻，另一批仍命中。
        val stale = File(context.filesDir, "ai/ai-translations/$sourceBookId/0-0.json")
        val json = JSONObject(stale.readText()).put("sourceHash", "outdated")
        stale.writeText(json.toString())
        val second = FakeChat()
        repository(second).translateBook(book) { _ -> }
        assertEquals(1, second.calls)
    }

    @Test
    fun `batch failure retries once with the reason then succeeds`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        chat.failNextCall = true
        val repo = repository(chat)

        repo.translateBook(book) { _ -> }

        assertEquals(3, chat.calls) // 失败 1 次 + 带原因重试 1 次 + 第二批 1 次
        assertTrue("请求失败：网络断了" in chat.prompts[1])
    }

    @Test
    fun `wrong segment count in reply is retried with feedback`() = runBlocking {
        val book = makeSourceBook()
        val chat = object : AiTranslationChatClient {
            var calls = 0
            val prompts = mutableListOf<String>()
            override suspend fun translateSegments(system: String, user: String): JSONObject {
                calls++
                prompts += user
                if (calls == 1) {
                    // 少回一段：缺编号 1。
                    return JSONObject().put(
                        "segments",
                        JSONArray().put(JSONObject().put("i", 0).put("t", "译0：Hello world."))
                    )
                }
                val array = JSONArray()
                Regex("\\[(\\d+)] (.+)").findAll(user).forEach { match ->
                    array.put(
                        JSONObject()
                            .put("i", match.groupValues[1].toInt())
                            .put("t", "译${match.groupValues[1]}：${match.groupValues[2]}")
                    )
                }
                return JSONObject().put("segments", array)
            }
        }
        repository(chat).translateBook(book) { _ -> }
        // 第 1 批坏响应 1 次 + 带原因重试 1 次 + 第二章 1 次。
        assertEquals(3, chat.calls)
        assertTrue("缺少编号" in chat.prompts[1])
    }

    @Test
    fun `generated translation attaches and aligns end to end`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository(FakeChat())
        val translation = repo.translateBook(book) { _ -> }

        val memoryRepo = TranslationMemoryRepository(ApplicationProvider.getApplicationContext())
        val result = memoryRepo.attachGenerated(book, translation)

        assertTrue(result.memory.pairs.isNotEmpty())
        assertEquals(book.id, result.memory.sourceBookId)
        assertTrue(memoryRepo.hasMemory(book.id))
        // 查询侧能命中句级对照。
        val lookup = memoryRepo.lookup(
            book,
            chapterIndex = 0,
            lookup = WordLookup(
                word = "left",
                sentence = "In 1926 he left the town.",
                paragraph = "In 1926 he left the town.",
                sentenceOffset = 12,
                x = 0f,
                y = 0f
            )
        )
        assertNotNull(lookup)
        assertNotEquals("", result.translationBook.title)
    }
}
