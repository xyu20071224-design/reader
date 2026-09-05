package com.linguareader.shared.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationMemoryFormatTest {

    private val paragraph = "A shared paragraph that would otherwise be repeated for every sentence. "
        .repeat(4)

    private fun memory(pairCount: Int) = TranslationMemory(
        sourceBookId = "source-id",
        sourceTitle = "Source",
        translationBookId = "zh-id",
        translationTitle = "译本",
        alignedAt = 42L,
        pairs = (1..pairCount).map {
            AlignedSentencePair(
                enChapter = 0,
                zhChapter = 0,
                enParagraph = paragraph,
                zhParagraph = "共享的中文段落。",
                enSentence = "Sentence number $it.",
                zhSentence = "第 $it 句。",
                confidence = 0.8f
            )
        }
    )

    @Test
    fun `v2 writes each paragraph once and references it by index`() {
        val json = memory(10).toJson()

        assertEquals(TranslationMemory.FORMAT_VERSION, json.getInt("version"))
        assertEquals(1, json.getJSONArray("enParagraphs").length())
        assertEquals(1, json.getJSONArray("zhParagraphs").length())
        assertEquals(10, json.getJSONArray("pairs").length())
        assertEquals(0, json.getJSONArray("pairs").getJSONObject(7).getInt("ep"))
        assertEquals(0, json.getJSONArray("pairs").getJSONObject(7).getInt("zp"))
        // 整段文本在档案里只出现一次（旧格式是每句一份，单书能到 15 MB）。
        val occurrences = Regex(Regex.escape(paragraph.trim())).findAll(json.toString()).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `round trip restores every field and shares paragraph instances`() {
        val original = memory(6)

        val restored = TranslationMemory.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original.sourceBookId, restored.sourceBookId)
        assertEquals(original.translationTitle, restored.translationTitle)
        assertEquals(original.alignedAt, restored.alignedAt)
        assertEquals(original.pairs, restored.pairs)
        assertSame(restored.pairs[0].enParagraph, restored.pairs[5].enParagraph)
        assertSame(restored.pairs[0].zhParagraph, restored.pairs[5].zhParagraph)
    }

    @Test
    fun `reads legacy v1 archives that inline the paragraph in every pair`() {
        val legacy = JSONObject(
            """
            {"sourceBookId":"src","sourceTitle":"Source","translationBookId":"zh",
             "translationTitle":"译本","alignedAt":7,
             "pairs":[{"enChapter":2,"zhChapter":1,"enParagraph":"P","zhParagraph":"段",
                       "enSentence":"S.","zhSentence":"句。","confidence":0.55}]}
            """.trimIndent()
        )

        val restored = TranslationMemory.fromJson(legacy)

        assertEquals(1, restored.pairs.size)
        assertEquals(2, restored.pairs[0].enChapter)
        assertEquals(1, restored.pairs[0].zhChapter)
        assertEquals("P", restored.pairs[0].enParagraph)
        assertEquals("句。", restored.pairs[0].zhSentence)
        assertEquals(0.55f, restored.pairs[0].confidence, 0.0001f)
        assertTrue(restored.terms.isEmpty())
    }

    @Test
    fun `terms survive a round trip but v1 leaves them empty`() {
        val withTerms = memory(1).copy(
            terms = listOf(BookTerm(enWord = "ring", zhTerm = "魔戒", count = 12, confidence = 0.9f))
        )

        val restored = TranslationMemory.fromJson(JSONObject(withTerms.toJson().toString()))

        assertEquals(1, restored.terms.size)
        assertEquals("魔戒", restored.terms[0].zhTerm)
        assertEquals(12, restored.terms[0].count)
    }
}
