package com.linguareader.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.R
import com.linguareader.app.data.WordLookup
import com.linguareader.shared.ai.ManualTranslationIo
import com.linguareader.app.translation.TranslationMemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 手动 AI 全书翻译编排层：导出任务文件（批次/指纹/hasCheckpoint 标记）、导入
 * 结果文件（逐批校验落检查点、拒绝项不影响其余批次）、以及「齐批 → 零出网
 * 组装 → attachGenerated 对齐」端到端。
 */
@RunWith(RobolectricTestRunner::class)
class AiTranslationRepositoryManualTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sourceBookId = "src-manual-test"

    private class FakeChat : com.linguareader.shared.ai.AiTranslationChatClient {
        override suspend fun translateSegments(system: String, user: String): JSONObject {
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

    private fun repository() =
        AiTranslationRepository(context, AiSettingsStore(context), BookGlossaryRepository(context)) { FakeChat() }

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
            title = "Manual Test Book",
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

    /** 模拟外部 agent：读任务文件，逐段产出「译N：段落」；[onlyKeys] 空则翻全部批次。 */
    private fun agentResult(task: JSONObject, onlyKeys: Set<String> = emptySet()): String {
        val translations = JSONArray()
        val batches = task.optJSONArray("batches")!!
        for (i in 0 until batches.length()) {
            val batch = batches.getJSONObject(i)
            val key = "${batch.optInt("chapterIndex")}-${batch.optInt("batchIndex")}"
            if (onlyKeys.isNotEmpty() && key !in onlyKeys) continue
            val indices = batch.optJSONArray("paragraphIndices")!!
            val paragraphs = batch.optJSONArray("paragraphs")!!
            val segments = JSONArray()
            for (p in 0 until paragraphs.length()) {
                segments.put(
                    JSONObject()
                        .put("i", indices.optInt(p))
                        .put("t", "译${indices.optInt(p)}：${paragraphs.optString(p)}")
                )
            }
            translations.put(
                JSONObject()
                    .put("chapterIndex", batch.optInt("chapterIndex"))
                    .put("batchIndex", batch.optInt("batchIndex"))
                    .put("sourceHash", batch.optString("sourceHash"))
                    .put("segments", segments)
            )
        }
        return JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", ManualTranslationIo.FORMAT_VERSION)
            .put("bookId", task.optString("bookId"))
            .put("translations", translations)
            .toString()
    }

    @Test
    fun `exported task matches the batch plan and flags finished batches`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()

        // 全新导出：全部批次无检查点标记，指纹与本地批次规划一一对应。
        val fresh = repo.buildManualTask(book, styleNotes = "对话用「」")
        val freshBatches = fresh.optJSONArray("batches")!!
        assertEquals(2, freshBatches.length())
        freshBatches.let { batches ->
            for (i in 0 until batches.length()) {
                val batch = batches.getJSONObject(i)
                assertEquals(false, batch.optBoolean("hasCheckpoint"))
                // 指纹必须按任务内自带段落计算——导入侧拿它做防错位校验。
                val paragraphs = batch.optJSONArray("paragraphs")!!
                val hash = AiBookTranslator.sourceHash(
                    (0 until paragraphs.length()).map { paragraphs.optString(it) }
                )
                assertEquals(hash, batch.optString("sourceHash"))
            }
        }
        assertEquals("对话用「」", fresh.optString("styleNotes"))
        assertTrue(fresh.optString("instructions").contains(ManualTranslationIo.RESULT_KIND))

        // 在线翻完后再导出：全部批次打 hasCheckpoint 标，agent 只需跳过。
        repo.translateBook(book) { _ -> }
        val after = repo.buildManualTask(book, styleNotes = null)
        val afterBatches = after.optJSONArray("batches")!!
        for (i in 0 until afterBatches.length()) {
            assertEquals(true, afterBatches.getJSONObject(i).optBoolean("hasCheckpoint"))
        }
    }

    @Test
    fun `import writes checkpoints and reaches full coverage`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        val task = repo.buildManualTask(book, styleNotes = null)

        val report = repo.importManualResults(book, listOf(agentResult(task)))

        assertEquals(2, report.acceptedBatches)
        assertEquals(emptyList<String>(), report.rejected)
        assertTrue(report.coverage.complete)
        assertEquals(2, report.coverage.coveredBatches)
        // 每批一个检查点，mode = manual（与在线批次可区分的溯源标记）。
        val checkpoint = File(context.filesDir, "ai/ai-translations/$sourceBookId/0-0.json")
        assertTrue(checkpoint.isFile)
        assertEquals(
            ManualTranslationIo.MODE_MANUAL,
            JSONObject(checkpoint.readText()).optString("mode")
        )
    }

    @Test
    fun `partial imports accumulate across files until coverage is complete`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        val task = repo.buildManualTask(book, styleNotes = null)

        // 第一份结果只翻第一章的批次；kind 不对的文件整体拒绝但不拖累别的文件。
        val report1 = repo.importManualResults(
            book,
            listOf("{\"kind\":\"something-else\"}", agentResult(task, onlyKeys = setOf("0-0")))
        )
        assertEquals(1, report1.acceptedBatches)
        assertEquals(1, report1.rejected.size)
        assertTrue("文件 1" in report1.rejected[0])
        assertEquals(1, report1.coverage.coveredBatches)
        assertEquals(false, report1.coverage.complete)

        // 第二份补齐第二章 → 覆盖 100%。
        val report2 = repo.importManualResults(book, listOf(agentResult(task, onlyKeys = setOf("1-0"))))
        assertEquals(1, report2.acceptedBatches)
        assertTrue(report2.coverage.complete)
    }

    @Test
    fun `import rejects stale hashes and unknown batches without touching the rest`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        val task = repo.buildManualTask(book, styleNotes = null)

        // 第一章批次有效；第二章批次带旧指纹；另带一个不属于本书的批次。
        val valid = agentResult(task, onlyKeys = setOf("0-0"))
        val tamperedJson = JSONObject(agentResult(task, onlyKeys = setOf("1-0")))
        tamperedJson.optJSONArray("translations")!!.getJSONObject(0).put("sourceHash", "outdated")
        val unknown = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", 1)
            .put(
                "translations",
                JSONArray().put(
                    JSONObject()
                        .put("chapterIndex", 99)
                        .put("batchIndex", 0)
                        .put("segments", JSONArray().put(JSONObject().put("i", 0).put("t", "译文")))
                )
            )
            .toString()

        val report = repo.importManualResults(book, listOf(valid, tamperedJson.toString(), unknown))

        assertEquals(1, report.acceptedBatches)
        assertEquals(2, report.rejected.size)
        assertTrue(report.rejected.any { "指纹不符" in it })
        assertTrue(report.rejected.any { "不属于这本书" in it })
        // 拒绝不影响有效批次落检查点。
        assertEquals(1, report.coverage.coveredBatches)
    }

    @Test
    fun `completeFromCheckpoints assembles the book offline and aligns end to end`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        val task = repo.buildManualTask(book, styleNotes = null)
        repo.importManualResults(book, listOf(agentResult(task)))

        val translation = repo.completeFromCheckpoints(book, providerName = "手动 AI")
        assertEquals(AiTranslationRepository.translationId(sourceBookId), translation.id)
        assertEquals(context.getString(R.string.translation_manual_title), translation.title)
        assertEquals("手动 AI", translation.author)
        // 手动译文正确落进章节 XHTML（agent 的「译N：」前缀原样出现在 <p> 里）。
        val chapter0 = File(translation.extractedDir, "chapter_000.xhtml").readText()
        assertTrue("<p>译0：Hello world.</p>" in chapter0)
        assertTrue("<p>译1：In 1926 he left the town.</p>" in chapter0)

        // 与在线路径同一对齐管线：句级对照可用。
        val memoryRepo = TranslationMemoryRepository(ApplicationProvider.getApplicationContext())
        val result = memoryRepo.attachGenerated(book, translation)
        assertTrue(result.memory.pairs.isNotEmpty())
        val lookup = memoryRepo.lookup(
            book, 0,
            WordLookup("left", "In 1926 he left the town.", "In 1926 he left the town.", 12, 0f, 0f)
        )
        assertNotNull(lookup)
        assertEquals("译1：In 1926 he left the town.", lookup?.chinese)
    }

    @Test
    fun `completeFromCheckpoints refuses to run with missing batches`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        // 一个检查点都没有：离线组装必须立刻拒绝，绝不偷偷出网。
        val error = assertThrows(ManualTranslationIncompleteException::class.java) {
            runBlocking { repo.completeFromCheckpoints(book, providerName = "手动 AI") }
        }
        assertTrue("0-0" in error.message!!)
    }

    @Test
    fun `changed source invalidates only the stale batches via source hash`() = runBlocking {
        val book = makeSourceBook()
        val repo = repository()
        val task = repo.buildManualTask(book, styleNotes = null)
        repo.importManualResults(book, listOf(agentResult(task)))

        // 第一章内容变了（书重新导入过）：旧结果对 0-0 指纹不符被拒绝；第二章
        // 内容没变，指纹仍然吻合，同一份结果文件里的 1-0 译文照常收下——
        // 指纹校验是逐批的，只拦真正错位的批次。
        File(File(book.extractedDir), "chapter_000.xhtml").writeText(
            "<html><body><p>Changed content entirely.</p><p>In 1926 he left the town.</p></body></html>"
        )
        val report = repo.importManualResults(book, listOf(agentResult(task)))
        assertEquals(1, report.acceptedBatches)
        assertEquals(1, report.rejected.size)
        assertTrue("指纹不符" in report.rejected[0])
    }
}
