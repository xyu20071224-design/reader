package com.linguareader.shared.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 句级边界提取的单测：**保守回退**是这里的核心契约。
 *
 * 提取器只有拿到「行数 == 英文句数」这条构造性证据才产出条目；任何不确定的情况都
 * 必须**静默不产出**，让对齐器回退到中文分句器。这一点错了不会报错，只会让 P3
 * 悄悄失效或错切，所以逐条钉住。
 */
class AiBookTranslatorSentenceBoundaryTest {

    private fun batchOf(vararg paragraphs: String) = TranslationBatch(
        chapterIndex = 0,
        batchIndex = 0,
        paragraphIndices = paragraphs.indices.toList(),
        paragraphs = paragraphs.toList()
    )

    @Test
    fun extractsLinesWhenCountMatchesSentences() {
        val en = "He left. She stayed."
        val zh = "他走了。\n她留下了。"
        val map = AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh))
        assertEquals(1, map.size)
        val lines = map.values.first()
        assertEquals(listOf("他走了。", "她留下了。"), lines)
    }

    @Test
    fun keyIsUsableByTtsNormalization() {
        // TtsTextExtractor 的归一化：空白折叠成单空格 + trim。
        // 键必须与之同形，否则对齐器查表永远落空（P3 会静默失效）。
        val en = "He left. She stayed."
        val zh = "他走了。\n  她留下了。  "
        val map = AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh))
        val expectedKey = "他走了。 她留下了。"
        assertTrue("键应为折叠空白后的形态，实际=${map.keys}", map.containsKey(expectedKey))
        assertEquals(expectedKey, AiBookTranslator.normalizeParagraphKey(zh))
    }

    @Test
    fun skipsWhenLineCountDiffersFromSentenceCount() {
        val en = "He left. She stayed."
        // 三行对两句 → 模型没按句返回，必须不产出（回退分句器）
        val zh = "他走了。\n她留下了。\n多出来的一行。"
        assertTrue(AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh)).isEmpty())
    }

    @Test
    fun skipsParagraphWithoutNewline() {
        // 旧检查点 / 人译本 / 模型没按句返回 → 没有换行 → 不产出
        val en = "He left. She stayed."
        val zh = "他走了。她留下了。"
        assertTrue(AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh)).isEmpty())
    }

    @Test
    fun skipsSingleLine() {
        val en = "He left."
        val zh = "他走了。"
        assertTrue(AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh)).isEmpty())
    }

    @Test
    fun skipsWhenSizesDiffer() {
        assertTrue(
            AiBookTranslator.sentenceBoundaries(listOf("a", "b"), listOf("甲\n乙")).isEmpty()
        )
    }

    @Test
    fun sentenceCountMismatchesIsSoftAndReportsIndices() {
        // 绝不抛异常，只报编号——句级结构做成硬校验会让整本书翻译中止。
        val batch = batchOf("He left. She stayed.", "One. Two. Three.")
        val translations = listOf(
            "他走了。\n她留下了。",   // 2 行 vs 2 句 → 一致
            "一。\n二。\n三。"         // 3 行 vs 3 句 → 一致
        )
        assertTrue(AiBookTranslator.sentenceCountMismatches(batch, translations).isEmpty())

        val bad = listOf("他走了。\n她留下了。", "一。\n二。")  // 第二段 2 行 vs 3 句
        assertEquals(listOf(1), AiBookTranslator.sentenceCountMismatches(batch, bad))
    }

    @Test
    fun emptyLinesAreIgnoredWhenSplitting() {
        val en = "He left. She stayed."
        val zh = "他走了。\n\n她留下了。\n"
        val map = AiBookTranslator.sentenceBoundaries(listOf(en), listOf(zh))
        assertEquals(listOf("他走了。", "她留下了。"), map.values.first())
    }
}
