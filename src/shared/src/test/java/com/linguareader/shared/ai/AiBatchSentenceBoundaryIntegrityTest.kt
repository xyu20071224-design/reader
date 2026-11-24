package com.linguareader.shared.ai

import com.linguareader.shared.tts.SentenceSplitter
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自含语料：不依赖 gitignored 的整书素材，直接重放生产拆批/合并/句界步骤。 */
class AiBatchSentenceBoundaryIntegrityTest {
    private val sourceSentences = listOf(
        "The first small bird flew away.",
        "The second small bird stayed here.",
        "The third small bird found its home.",
        "The fourth small bird sang again."
    )
    private val chinese = listOf(
        "第一只小鸟飞走了。", "第二只小鸟留在这里。", "第三只小鸟找到了家。", "第四只小鸟再次唱歌。"
    )
    private val source = sourceSentences.joinToString(" ")

    private fun translations(batches: List<TranslationBatch>): List<List<String>> = batches.map { batch ->
        batch.paragraphs.map { chunk ->
            SentenceSplitter.split(chunk).joinToString("\n") { sentence ->
                chinese[sourceSentences.indexOf(sentence)]
            }
        }
    }

    @Test
    fun `sentence boundaries survive a source paragraph spanning multiple batches`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source), 70)
        assertTrue(batches.size > 1)
        val replies = translations(batches)
        val merged = AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(source))
        val lines = AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source))

        assertEquals(1, merged.size)
        assertEquals(chinese, lines[AiBookTranslator.normalizeParagraphKey(merged.single())])
        // 修复只保留句界侧车，不改变已经约定的中文正文空串拼接。
        assertEquals(replies.flatten().joinToString(""), merged.single())
    }

    @Test
    fun `one sentence per chunk still provides a complete paragraph sidecar`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source), 36)
        val replies = translations(batches)
        assertTrue(replies.flatten().none { it.contains('\n') })
        val merged = AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(source)).single()
        val lines = AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source))

        assertEquals(chinese, lines[AiBookTranslator.normalizeParagraphKey(merged)])
    }

    @Test
    fun `a failed chunk never produces a sentence sidecar for the source paragraph`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source), 70)
        val replies: List<List<String>?> = translations(batches).mapIndexed { index, reply ->
            if (index == 1) null else reply
        }
        assertTrue(AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source)).isEmpty())
        assertEquals(listOf(source), AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(source)))
    }

    @Test
    fun `a hard cut inside one original sentence cannot invent sentence boundaries`() {
        val longSentence = "This single long sentence has enough ordinary words to cross a tiny batch limit."
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(longSentence), 25)
        assertTrue(batches.size > 1)
        val replies = batches.map { batch -> batch.paragraphs.map { "某一部分译文。" } }
        assertFalse(replies.isEmpty())
        assertTrue(AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(longSentence)).isEmpty())
    }

    @Test
    fun `opposite line count errors in separate chunks cannot cancel each other`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source), 70)
        assertEquals(2, batches.size)
        val replies = listOf(
            listOf(chinese.take(2).joinToString("")),
            listOf("第三只小鸟\n找到了家。\n${chinese.last()}")
        )
        assertTrue(AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source)).isEmpty())
    }

    @Test
    fun `unsplit paragraphs retain the existing sentence boundary contract`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source))
        val replies = translations(batches)
        val merged = AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(source))
        assertEquals(
            AiBookTranslator.sentenceBoundaries(listOf(source), merged),
            AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source))
        )
    }

    @Test
    fun `stored split paragraph boundaries reach the production aligner`() {
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(source), 70)
        val replies = translations(batches)
        val merged = AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(source)).single()
        val boundaries = AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(source))
        val json = JSONObject(JSONObject(boundaries).toString())
        val restored = json.keys().asSequence().associateWith { key ->
            val array = json.getJSONArray(key)
            (0 until array.length()).map { array.getString(it) }
        }
        val pairs = TranslationAligner.align(
            listOf(listOf(source)),
            listOf(listOf(AiBookTranslator.normalizeParagraphKey(merged))),
            null,
            TranslationAligner.ZhSplitPolicy.withKnownSentences(restored)
        )
        assertEquals(sourceSentences.zip(chinese), pairs.map { it.enSentence to it.zhSentence })
    }

    @Test
    fun `restoring split chunk boundaries fixes the target one to many translation fixture`() {
        val english = listOf(
            "The first small bird flew away and crossed the forest.",
            "The second small bird stayed here and did not leave.",
            "The third small bird found its home and was very happy.",
            "The fourth small bird sang again and its song reached far away."
        )
        val chinese = listOf(
            "第一只小鸟飞走了。它穿过了树林。",
            "第二只小鸟留在这里。它没有离开。",
            "第三只小鸟找到了家。它十分开心。",
            "第四只小鸟再次唱歌。歌声传到了远方。"
        )
        val paragraph = english.joinToString(" ")
        val batches = AiBookTranslator.groupIntoBatches(0, listOf(paragraph), 80)
        val replies = batches.map { batch -> batch.paragraphs.map { chinese[english.indexOf(it)] } }
        val merged = AiBookTranslator.mergeBatchTranslations(batches, replies, listOf(paragraph))
        val zhParagraph = AiBookTranslator.normalizeParagraphKey(merged.single())
        val legacy = AiBookTranslator.sentenceBoundaries(listOf(paragraph), merged)
        val restored = AiBookTranslator.sentenceBoundariesFromBatches(batches, replies, listOf(paragraph))
        val expected = english.zip(chinese).toMap()
        fun correct(known: Map<String, List<String>>) = TranslationAligner.align(
            listOf(listOf(paragraph)), listOf(listOf(zhParagraph)), null,
            TranslationAligner.ZhSplitPolicy.withKnownSentences(known)
        ).count { pair -> expected[pair.enSentence] == pair.zhSentence }

        val before = correct(legacy)
        val after = correct(restored)
        assertEquals(4, after)
        assertTrue("目标夹具应实际改善，旧=$before，新=$after", before < after)
        println("[q1-t01 fixed fixture] correct sentence pairs: $before/4 -> $after/4 (synthetic, no meaning index)")
    }
}
