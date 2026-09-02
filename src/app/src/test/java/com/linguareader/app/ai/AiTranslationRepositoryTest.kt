package com.linguareader.app.ai

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.WordLookup
import com.linguareader.app.translation.TranslationMatchLevel
import com.linguareader.app.translation.TranslationMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
        val systems = mutableListOf<String>()
        var failNextCall = false
        /** 句级重翻（system 命中 RETRANSLATE_SYSTEM_PROMPT）时返回的译文。 */
        var retranslateReply: String? = null

        override suspend fun translateSegments(system: String, user: String): JSONObject {
            calls++
            prompts += user
            systems += system
            if (failNextCall) {
                failNextCall = false
                throw AiRequestException("网络断了")
            }
            if (system == AiBookTranslator.RETRANSLATE_SYSTEM_PROMPT) {
                return JSONObject().put("translation", retranslateReply.orEmpty())
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

    /** 记录退避时长而不真的睡，单测才跑得快。 */
    private fun repository(
        chat: AiTranslationChatClient,
        delays: MutableList<Long>
    ): AiTranslationRepository =
        AiTranslationRepository(
            context,
            AiSettingsStore(context),
            BookGlossaryRepository(context),
            retryDelay = { delays += it }
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
    fun `a batch that exhausts retries keeps source text instead of aborting the book`() = runBlocking {
        val book = makeSourceBook()
        val delays = mutableListOf<Long>()
        // 前两次调用（第一批的初翻 + 带原因重试）都失败，之后恢复正常。
        val chat = object : AiTranslationChatClient {
            var calls = 0
            override suspend fun translateSegments(system: String, user: String): JSONObject {
                calls++
                if (calls <= 2) throw AiRequestException("AI 接口返回 HTTP 503：busy")
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
        val failedParagraphs = mutableListOf<Int>()

        val translation = repository(chat, delays).translateBook(
            book,
            onBatchFailed = { batch, _ -> failedParagraphs += batch.paragraphs.size }
        ) { _ -> }

        // 整本书没有因为一批失败而中止：第二章照常翻完。
        assertEquals(listOf(2), failedParagraphs)
        val first = File(translation.extractedDir, "chapter_000.xhtml").readText()
        val second = File(translation.extractedDir, "chapter_001.xhtml").readText()
        // 失败批保留英文原文占位，段数与英文侧仍严格 1:1，否则整章对照错位。
        assertTrue("Hello world." in first)
        assertTrue("In 1926 he left the town." in first)
        assertEquals(2, Regex("<p>").findAll(first).count())
        assertTrue("译0：Harry walked to Hogwarts alone." in second)
        // 重试前退避过，且 5xx 走的是较长那档。
        assertTrue(delays.isNotEmpty())
        assertTrue(delays.all { it >= 4_000L })
    }

    @Test
    fun `polish mode runs a second pass per batch and records mode in checkpoint`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        val repo = repository(chat)
        repo.translateBook(book, mode = AiTranslationRepository.MODE_POLISH) { _ -> }

        // 每批两遍：初翻 → 精修，且系统提示词按遍切换。
        assertEquals(4, chat.calls)
        assertEquals(AiBookTranslator.TRANSLATION_SYSTEM_PROMPT, chat.systems[0])
        assertEquals(AiBookTranslator.POLISH_SYSTEM_PROMPT, chat.systems[1])
        assertEquals(AiBookTranslator.TRANSLATION_SYSTEM_PROMPT, chat.systems[2])
        assertEquals(AiBookTranslator.POLISH_SYSTEM_PROMPT, chat.systems[3])
        // 精修请求能拿到初稿。
        assertTrue("初稿：" in chat.prompts[1])

        // 检查点带 mode；精译检查点在精译模式下重跑零出网。
        val checkpoint = File(context.filesDir, "ai/ai-translations/$sourceBookId/0-0.json")
        assertEquals("polish", JSONObject(checkpoint.readText()).optString("mode"))
        val second = FakeChat()
        repository(second).translateBook(book, mode = AiTranslationRepository.MODE_POLISH) { _ -> }
        assertEquals(0, second.calls)
    }

    @Test
    fun `style notes persist and round trip`() = runBlocking {
        val repo = repository(FakeChat())
        assertEquals(null, repo.loadStyle(sourceBookId))
        repo.saveStyle(sourceBookId, "对话用『』")
        assertEquals("对话用『』", repo.loadStyle(sourceBookId))
        // 空白说明读回为 null（= 不注入）。
        repo.saveStyle(sourceBookId, "   ")
        assertEquals(null, repo.loadStyle(sourceBookId))
    }

    @Test
    fun `retranslate replaces the sentence pair and lookups return the new text`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        val repo = repository(chat)
        val translation = repo.translateBook(book) { _ -> }
        val memoryRepo = TranslationMemoryRepository(ApplicationProvider.getApplicationContext())
        memoryRepo.attachGenerated(book, translation)
        val hit = memoryRepo.lookup(
            book, 0,
            WordLookup("left", "In 1926 he left the town.", "In 1926 he left the town.", 12, 0f, 0f)
        )!!
        assertEquals(TranslationMatchLevel.SENTENCE, hit.matchLevel)
        assertTrue(hit.pairIndex >= 0)

        chat.retranslateReply = "1926年，他离开了那座小镇。"
        val newZh = repo.retranslateText(
            book, hit.english, hit.englishParagraph, hit.chinese, feedback = "用「那座小镇」"
        )
        assertEquals("1926年，他离开了那座小镇。", newZh)
        // 重翻请求带反馈与段落上下文。
        assertTrue("用「那座小镇」" in chat.prompts.last())
        assertTrue(hit.englishParagraph in chat.prompts.last())

        assertTrue(memoryRepo.replaceSentenceTranslation(book.id, hit.pairIndex, newZh) != null)
        val after = memoryRepo.lookup(
            book, 0,
            WordLookup("left", "In 1926 he left the town.", "In 1926 he left the town.", 12, 0f, 0f)
        )!!
        assertEquals("1926年，他离开了那座小镇。", after.chinese)
        assertEquals(hit.pairIndex, after.pairIndex)
        // 同段其它句对不受影响（仍是初翻产物）。
        val neighbour = memoryRepo.lookup(
            book, 0,
            WordLookup("Hello", "Hello world.", "Hello world.", 0, 0f, 0f)
        )
        assertEquals("译0：Hello world.", neighbour?.chinese)
    }

    @Test
    fun `retranslate validation failure throws and keeps archive untouched`() = runBlocking {
        val book = makeSourceBook()
        val chat = FakeChat()
        val repo = repository(chat)
        val translation = repo.translateBook(book) { _ -> }
        val memoryRepo = TranslationMemoryRepository(ApplicationProvider.getApplicationContext())
        memoryRepo.attachGenerated(book, translation)
        val hit = memoryRepo.lookup(
            book, 0,
            WordLookup("left", "In 1926 he left the town.", "In 1926 he left the town.", 12, 0f, 0f)
        )!!

        // 空回复 → 自检失败抛异常。
        chat.retranslateReply = ""
        assertThrows(AiRequestException::class.java) {
            runBlocking {
                repo.retranslateText(book, hit.english, hit.englishParagraph, hit.chinese, feedback = null)
            }
        }
        // 数字锚点丢失同样拒绝。
        chat.retranslateReply = "他离开了小镇。"
        assertThrows(AiRequestException::class.java) {
            runBlocking {
                repo.retranslateText(book, hit.english, hit.englishParagraph, hit.chinese, feedback = null)
            }
        }
        // 档案未被破坏：重查还是旧译文。
        val after = memoryRepo.lookup(
            book, 0,
            WordLookup("left", "In 1926 he left the town.", "In 1926 he left the town.", 12, 0f, 0f)
        )!!
        assertEquals(hit.chinese, after.chinese)
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

    @Test
    fun `synthetic translation title and author follow the active provider`() = runBlocking {
        val book = makeSourceBook()
        // 默认配置（无服务商列表）回退 DeepSeek。
        val fallback = repository(FakeChat()).translateBook(book) { _ -> }
        assertEquals("DeepSeek", fallback.author)

        // 换生效服务商后重新生成：检查点全部复用（零出网），标题与作者跟着换。
        AiSettingsStore(context).save(
            AiSettings(
                enabled = true,
                apiKey = "key",
                providers = listOf(
                    AiProviderProfile(
                        id = "p1", name = "Kimi",
                        baseUrl = "https://example.com", apiKey = "key", model = "m1"
                    )
                ),
                activeProviderId = "p1"
            )
        )
        val chat = FakeChat()
        val regen = repository(chat).translateBook(book) { _ -> }
        assertEquals(0, chat.calls)
        assertEquals("Kimi", regen.author)
        // 标题以服务商名开头（zh「Kimi AI 译本」/ en「Kimi AI translation」通吃）。
        assertTrue(regen.title.startsWith("Kimi"))
    }
}
