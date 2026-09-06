package com.linguareader.shared.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动 AI 全书翻译的文件协议：任务文件组装（批次/术语表/说明齐全、术语口径与
 * prompt 注入一致）与结果文件解析（宽容围栏、kind/version 门禁、缺字段报错）。
 */
class ManualTranslationIoTest {

    private fun entry(
        term: String,
        translation: String = "译法",
        note: String = "",
        enabled: Boolean = true,
        origin: String = "manual"
    ) = GlossaryEntry(term = term, translation = translation, note = note, enabled = enabled, origin = origin)

    private fun taskBatch(
        chapterIndex: Int,
        batchIndex: Int,
        paragraphs: List<String>,
        hasCheckpoint: Boolean = false
    ) = ManualTranslationIo.ManualTaskBatch(
        chapterTitle = "第 ${chapterIndex + 1} 章",
        sourceHash = "hash-$chapterIndex-$batchIndex",
        batch = TranslationBatch(chapterIndex, batchIndex, paragraphs.indices.toList(), paragraphs),
        hasCheckpoint = hasCheckpoint
    )

    @Test
    fun `task carries batches, glossary, style notes and instructions`() {
        val task = ManualTranslationIo.buildTask(
            bookId = "book-1",
            bookTitle = "Test Book",
            glossary = listOf(entry("Frodo", "弗罗多", "主角"), entry("Hogwarts", "", note = "地名")),
            styleNotes = " 对话用「」 ",
            batches = listOf(
                taskBatch(0, 0, listOf("Hello world.", "In 1926 he left.")),
                taskBatch(0, 1, listOf("Second batch."), hasCheckpoint = true)
            )
        )

        assertEquals(ManualTranslationIo.TASK_KIND, task.optString("kind"))
        assertEquals(ManualTranslationIo.FORMAT_VERSION, task.optInt("version"))
        assertEquals("book-1", task.optString("bookId"))
        assertEquals("Test Book", task.optString("bookTitle"))
        assertEquals("对话用「」", task.optString("styleNotes"))
        // 给 agent 的说明必须就位，否则 agent 不知道结果文件长什么样。
        assertEquals(ManualTranslationIo.INSTRUCTIONS, task.optString("instructions"))

        val glossary = task.optJSONArray("glossary")!!
        assertEquals(2, glossary.length())
        assertEquals("Frodo", glossary.getJSONObject(0).optString("term"))
        assertEquals("弗罗多", glossary.getJSONObject(0).optString("translation"))
        // 译法为空的词条原样导出（说明里约定按「保留原文」处理）。
        assertEquals("", glossary.getJSONObject(1).optString("translation"))

        val batches = task.optJSONArray("batches")!!
        assertEquals(2, batches.length())
        val first = batches.getJSONObject(0)
        assertEquals(0, first.optInt("chapterIndex"))
        assertEquals(0, first.optInt("batchIndex"))
        assertEquals("hash-0-0", first.optString("sourceHash"))
        assertEquals(false, first.optBoolean("hasCheckpoint"))
        // 编号是章内段落号，原样导出——导入校验与在线 extractValidated 依赖同一契约。
        val indices = first.optJSONArray("paragraphIndices")!!
        val paragraphs = first.optJSONArray("paragraphs")!!
        assertEquals(listOf(0, 1), indices.toList())
        assertEquals(listOf("Hello world.", "In 1926 he left."), paragraphs.toList())
        // 已有检查点的批次要打标，agent 才知道跳过。
        assertTrue(batches.getJSONObject(1).optBoolean("hasCheckpoint"))
    }

    @Test
    fun `task glossary uses the same pipeline as prompt injection`() {
        // 91 条启用词条 + 1 条停用 + 1 条手动：注入口径 = 手动优先、上限 80。
        val glossary = buildList {
            add(entry("ManualTerm", origin = "manual"))
            repeat(90) { add(entry("auto-$it", origin = "auto")) }
            add(entry("Disabled", enabled = false))
        }
        val task = ManualTranslationIo.buildTask("b", "t", glossary, null, emptyList())
        assertEquals(80, task.optJSONArray("glossary")!!.length())
        assertEquals("ManualTerm", task.optJSONArray("glossary")!!.getJSONObject(0).optString("term"))
        // 与 AiBookTranslator 注入口径严格同数。
        assertEquals(
            AiBookTranslator.injectedGlossaryCount(glossary),
            task.optJSONArray("glossary")!!.length()
        )
    }

    @Test
    fun `parseResults accepts segments array and object, tolerates fences and prose`() {
        val result = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", 1)
            .put("bookId", "book-1")
            .put(
                "translations",
                JSONArray().put(
                    JSONObject()
                        .put("chapterIndex", 0)
                        .put("batchIndex", 1)
                        .put("sourceHash", "hash-0-1")
                        .put(
                            "segments",
                            JSONArray()
                                .put(JSONObject().put("i", 3).put("t", "译文三"))
                                .put(JSONObject().put("i", 7).put("t", "译文七"))
                        )
                )
            )

        // 原样解析（说明约定的形状：segments 直接是数组）。
        val direct = ManualTranslationIo.parseResults(result.toString())
        assertEquals(1, direct.size)
        assertEquals(0, direct[0].chapterIndex)
        assertEquals(1, direct[0].batchIndex)
        assertEquals("hash-0-1", direct[0].sourceHash)
        assertEquals(2, direct[0].segments.optJSONArray("segments")!!.length())

        // agent 常见姿势：markdown 围栏 + 前后说明文字。
        val fenced = "好的，这是翻译结果：\n```json\n${result}\n```\n请查收。"
        val lenient = ManualTranslationIo.parseResults(fenced)
        assertEquals(1, lenient.size)

        // agent 直接贴在线响应对象 {"segments":[…]} 也收下。
        val onlineShape = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", 1)
            .put(
                "translations",
                JSONArray().put(
                    JSONObject()
                        .put("chapterIndex", 2)
                        .put("batchIndex", 0)
                        .put(
                            "segments",
                            JSONObject().put(
                                "segments",
                                JSONArray().put(JSONObject().put("i", 0).put("t", "译文"))
                            )
                        )
                )
            )
        val wrapped = ManualTranslationIo.parseResults(onlineShape.toString())
        assertEquals(1, wrapped.size)
        assertEquals(1, wrapped[0].segments.optJSONArray("segments")!!.length())

        // sourceHash 可省略（导入侧退化为按 (章, 批) 定位）。
        result.optJSONArray("translations")!!.getJSONObject(0).remove("sourceHash")
        assertEquals(
            null,
            ManualTranslationIo.parseResults(result.toString())[0].sourceHash
        )
    }

    @Test
    fun `parseResults rejects wrong kind, newer version and broken entries`() {
        val badKind = JSONObject().put("kind", ManualTranslationIo.TASK_KIND).put("version", 1)
        val badKindError = assertThrows(IllegalArgumentException::class.java) {
            ManualTranslationIo.parseResults(badKind.toString())
        }
        assertTrue("结果文件" in badKindError.message!!)

        val newer = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", ManualTranslationIo.FORMAT_VERSION + 1)
        assertThrows(IllegalArgumentException::class.java) {
            ManualTranslationIo.parseResults(newer.toString())
        }

        // 非 JSON 文本 → 明确报错而不是崩溃。
        val notJsonError = assertThrows(IllegalArgumentException::class.java) {
            ManualTranslationIo.parseResults("这不是 JSON")
        }
        assertTrue("JSON" in notJsonError.message!!)

        val missingSegments = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", 1)
            .put(
                "translations",
                JSONArray().put(JSONObject().put("chapterIndex", 0).put("batchIndex", 0))
            )
        val segmentsError = assertThrows(IllegalArgumentException::class.java) {
            ManualTranslationIo.parseResults(missingSegments.toString())
        }
        assertTrue("segments" in segmentsError.message!!)

        val missingLocator = JSONObject()
            .put("kind", ManualTranslationIo.RESULT_KIND)
            .put("version", 1)
            .put("translations", JSONArray().put(JSONObject().put("segments", JSONArray())))
        val locatorError = assertThrows(IllegalArgumentException::class.java) {
            ManualTranslationIo.parseResults(missingLocator.toString())
        }
        assertTrue("chapterIndex" in locatorError.message!!)
    }
}
