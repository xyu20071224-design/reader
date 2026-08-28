package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 整本翻译的纯逻辑核心：批次分组、prompt 构建、响应解析与批后自检。
 */
class AiBookTranslatorTest {

    // --- 批次分组 ------------------------------------------------------------

    @Test
    fun `batches split on paragraph boundaries and preserve order`() {
        // 4 段，每段 30 字符，上限 50 → 每批最多 1 段？不：30+30=60>50，所以
        // 每批 1 段（单段不超上限时贪心尽量装满）。
        val paragraphs = (0 until 4).map { "a".repeat(30) }
        val batches = AiBookTranslator.groupIntoBatches(0, paragraphs, maxCharsPerBatch = 50)
        assertEquals(listOf(0, 1, 2, 3), batches.map { it.batchIndex })
        batches.forEach { assertEquals(1, it.paragraphIndices.size) }
    }

    @Test
    fun `batches pack greedily up to the char limit`() {
        val paragraphs = listOf("a".repeat(20), "b".repeat(20), "c".repeat(20), "d".repeat(20))
        val batches = AiBookTranslator.groupIntoBatches(2, paragraphs, maxCharsPerBatch = 45)
        // 20+20=40 ≤ 45，第三段放不下 → [0,1] [2,3]。
        assertEquals(2, batches.size)
        assertEquals(listOf(0, 1), batches[0].paragraphIndices)
        assertEquals(listOf(2, 3), batches[1].paragraphIndices)
        assertEquals(listOf(paragraphs[0], paragraphs[1]), batches[0].paragraphs)
    }

    @Test
    fun `oversized paragraph gets its own batch instead of being cut`() {
        val paragraphs = listOf("x".repeat(200), "short", "y".repeat(10))
        val batches = AiBookTranslator.groupIntoBatches(1, paragraphs, maxCharsPerBatch = 50)
        assertEquals(listOf(0), batches[0].paragraphIndices)
        assertEquals(listOf(1, 2), batches[1].paragraphIndices)
        assertEquals(200, batches[0].charCount)
    }

    @Test
    fun `batch grouping covers every paragraph exactly once`() {
        val paragraphs = (0 until 17).map { "w${it} ".repeat((it % 5) + 1).trim() }
        val batches = AiBookTranslator.groupIntoBatches(3, paragraphs, maxCharsPerBatch = 30)
        assertEquals(paragraphs.indices.toList(), batches.flatMap { it.paragraphIndices })
        assertEquals(paragraphs, batches.flatMap { it.paragraphs })
        assertEquals(batches.indices.toList(), batches.map { it.batchIndex })
    }

    // --- prompt 构建 ----------------------------------------------------------

    @Test
    fun `user prompt numbers paragraphs with their original indices`() {
        val batch = TranslationBatch(4, 0, listOf(5, 6, 7), listOf("First.", "Second.", "Third."))
        val prompt = AiBookTranslator.buildUserPrompt("The Book", "Chapter", emptyList(), null, batch)
        assertTrue("[5] First." in prompt)
        assertTrue("[6] Second." in prompt)
        assertTrue("[7] Third." in prompt)
        assertTrue("{\"segments\":[{\"i\":编号,\"t\":\"中文译文\"}]}" in prompt.replace("\\\"", "\""))
    }

    @Test
    fun `glossary lines mark blank translation as keep original`() {
        val glossary = listOf(
            GlossaryEntry(term = "Frodo", translation = "弗罗多", note = "主角"),
            GlossaryEntry(term = "Hogwarts", translation = "", note = "学校")
        )
        val batch = TranslationBatch(0, 0, listOf(0), listOf("Frodo left Hogwarts."))
        val prompt = AiBookTranslator.buildUserPrompt("T", "C", glossary, null, batch)
        assertTrue("Frodo | 弗罗多 | 主角" in prompt)
        assertTrue("Hogwarts | 保留原文 | 学校" in prompt)
    }

    @Test
    fun `retry feedback is appended when provided`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("Hello."))
        val without = AiBookTranslator.buildUserPrompt("T", "C", emptyList(), null, batch)
        val with = AiBookTranslator.buildUserPrompt("T", "C", emptyList(), null, batch, retryError = "缺段")
        assertTrue("缺段" !in without)
        assertTrue("缺段" in with)
    }

    @Test
    fun `previous tail is included as context`() {
        val batch = TranslationBatch(0, 1, listOf(3), listOf("Next paragraph."))
        val prompt = AiBookTranslator.buildUserPrompt("T", "C", emptyList(), previousTail = "上一段译文。", batch = batch)
        assertTrue("上一段译文。" in prompt)
    }

    @Test
    fun `style notes are injected as a mandatory line`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("Hello."))
        val with = AiBookTranslator.buildUserPrompt("T", "C", emptyList(), null, batch, styleNotes = "对话用『』")
        val blank = AiBookTranslator.buildUserPrompt("T", "C", emptyList(), null, batch, styleNotes = "   ")
        assertTrue("风格说明" in with && "对话用『』" in with)
        assertTrue("风格说明" !in blank)
    }

    // --- 两阶段润色 -----------------------------------------------------------

    @Test
    fun `polish prompt pairs source with draft and keeps segments contract`() {
        val batch = TranslationBatch(2, 0, listOf(0, 1), listOf("In 1926 he left.", "It rained."))
        val prompt = AiBookTranslator.buildPolishUserPrompt(
            "T", "Chapter", emptyList(), batch,
            draftTranslations = listOf("1926年他离开了。", "下雨了。")
        )
        assertTrue("[0] 原文：In 1926 he left." in prompt)
        assertTrue("[0] 初稿：1926年他离开了。" in prompt)
        assertTrue("{\"segments\":[{\"i\":编号,\"t\":\"修订后的中文段落\"}]}" in prompt.replace("\\\"", "\""))
        // 风格说明注入同样生效。
        val styled = AiBookTranslator.buildPolishUserPrompt(
            "T", "Chapter", emptyList(), batch,
            draftTranslations = listOf("a", "b"), styleNotes = "文风口语化"
        )
        assertTrue("文风口语化" in styled)
    }

    // --- 句级重翻 -------------------------------------------------------------

    @Test
    fun `retranslate prompt carries paragraph context feedback and glossary`() {
        val glossary = listOf(GlossaryEntry(term = "Hogwarts", translation = "", note = "学校"))
        val withFeedback = AiBookTranslator.buildRetranslateUserPrompt(
            "Harry walked to Hogwarts.", "Harry walked to Hogwarts. It rained.",
            "哈利走到了魔法学校。", glossary, styleNotes = "口语化", feedback = "语气太书面"
        )
        assertTrue("所在段落" in withFeedback)
        assertTrue("Harry walked to Hogwarts. It rained." in withFeedback)
        assertTrue("哈利走到了魔法学校。" in withFeedback)
        assertTrue("用户对现有译文的反馈" in withFeedback && "语气太书面" in withFeedback)
        assertTrue("Hogwarts | 保留原文" in withFeedback)
        assertTrue("口语化" in withFeedback)

        val withoutFeedback = AiBookTranslator.buildRetranslateUserPrompt(
            "Hello.", "Hello.", "你好。", emptyList(), null, feedback = "  "
        )
        assertTrue("换一种更通顺自然的译法" in withoutFeedback)
    }

    @Test
    fun `retranslate validation rejects blank lost anchors and translated keep-original terms`() {
        val keep = listOf("Hogwarts")
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.validateRetranslation("Hello.", "   ", emptyList())
        }
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.validateRetranslation("The year 1926 passed.", "那一年过去了。", emptyList())
        }
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.validateRetranslation(
                "Harry walked to Hogwarts.", "哈利走到了魔法学校。", keep
            )
        }
        // 通过路径：锚点与术语都在。
        AiBookTranslator.validateRetranslation(
            "In 1926 Harry walked to Hogwarts.", "1926年，哈利走到了 Hogwarts。", keep
        )
    }

    @Test
    fun `retranslate validation rejects absurd length`() {
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.validateRetranslation(
                ("word ".repeat(100)).trim(), "短。", emptyList()
            )
        }
    }

    // --- 解析与自检 -----------------------------------------------------------

    private fun segmentsJson(vararg pairs: Pair<Int, String>): JSONObject {
        val array = org.json.JSONArray()
        pairs.forEach { (i, t) -> array.put(JSONObject().put("i", i).put("t", t)) }
        return JSONObject().put("segments", array)
    }

    @Test
    fun `valid segments pass validation in batch order`() {
        val batch = TranslationBatch(0, 0, listOf(2, 5), listOf("In 1926 he left.", "It rained."))
        val result = AiBookTranslator.extractValidated(
            segmentsJson(5 to "下雨了。", 2 to "1926年，他离开了。"),
            batch,
            keepOriginalTerms = emptyList()
        )
        assertEquals(listOf("1926年，他离开了。", "下雨了。"), result)
    }

    @Test
    fun `missing paragraph index fails validation`() {
        val batch = TranslationBatch(0, 0, listOf(0, 1), listOf("One.", "Two."))
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(segmentsJson(0 to "一。"), batch, emptyList())
        }
    }

    @Test
    fun `blank translation fails validation`() {
        val batch = TranslationBatch(0, 0, listOf(0, 1), listOf("One.", "Two."))
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(
                segmentsJson(0 to "一。", 1 to "  "), batch, emptyList()
            )
        }
    }

    @Test
    fun `missing segments array fails validation`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("One."))
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(JSONObject("{\"segments\":[]}"), batch, emptyList())
        }
    }

    @Test
    fun `dropped digit anchor fails validation`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("The year 1926 changed everything."))
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(
                segmentsJson(0 to "那一年改变了一切。"), batch, emptyList()
            )
        }
        // 数字原样保留则通过。
        assertEquals(
            listOf("1926年改变了一切。"),
            AiBookTranslator.extractValidated(
                segmentsJson(0 to "1926年改变了一切。"), batch, emptyList()
            )
        )
    }

    @Test
    fun `keep original term must survive into the translation`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("Harry walked to Hogwarts."))
        val keep = listOf("Hogwarts")
        // 专名被翻译掉了 → 拒绝。
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(
                segmentsJson(0 to "哈利走到了魔法学校。"), batch, keep
            )
        }
        // 保留原文 → 通过。
        assertEquals(
            listOf("哈利走到了 Hogwarts。"),
            AiBookTranslator.extractValidated(
                segmentsJson(0 to "哈利走到了 Hogwarts。"), batch, keep
            )
        )
    }

    @Test
    fun `absurd length ratio fails validation`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf(("word " .repeat(100)).trim()))
        assertThrows(AiRequestException::class.java) {
            AiBookTranslator.extractValidated(segmentsJson(0 to "短。"), batch, emptyList())
        }
    }

    @Test
    fun `single letter keep original terms are skipped to avoid false alarms`() {
        val batch = TranslationBatch(0, 0, listOf(0), listOf("I am here."))
        // "I" 作为保留原文词条会被任意译文触发误报，必须跳过。
        assertEquals(
            listOf("我在这里。"),
            AiBookTranslator.extractValidated(segmentsJson(0 to "我在这里。"), batch, listOf("I"))
        )
    }

    // --- 检查点指纹 -----------------------------------------------------------

    @Test
    fun `source hash is stable and content sensitive`() {
        val a = listOf("One.", "Two.")
        assertEquals(AiBookTranslator.sourceHash(a), AiBookTranslator.sourceHash(listOf("One.", "Two.")))
        assertNotEquals(AiBookTranslator.sourceHash(a), AiBookTranslator.sourceHash(listOf("One.", "Two ")))
        assertNotEquals(AiBookTranslator.sourceHash(a), AiBookTranslator.sourceHash(listOf("Two.", "One.")))
    }
}
