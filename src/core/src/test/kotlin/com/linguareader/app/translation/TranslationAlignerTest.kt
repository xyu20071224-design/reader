package com.linguareader.app.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationAlignerTest {

    @Test
    fun alignsSingleChapterParagraphByParagraph() {
        val en = listOf(
            listOf("The quick brown fox jumps over the lazy dog.", "Second paragraph here.")
        )
        val zh = listOf(
            listOf("敏捷的棕色狐狸跳过懒狗。", "这里是第二段。")
        )
        val pairs = TranslationAligner.align(en, zh)
        // 2 个英文段落对齐 2 个中文段落，每段至少产出 1 个句子对。
        assertTrue(pairs.isNotEmpty())
        assertEquals(0, pairs.first().enChapter)
        assertEquals(0, pairs.first().zhChapter)
        assertTrue(pairs.all { it.enParagraph.isNotBlank() && it.zhParagraph.isNotBlank() })
    }

    @Test
    fun anchorsBoostConfidenceAndNumbersAlign() {
        val en = listOf(listOf("Chapter 1 opens in 1984 with the protagonist Winston."))
        val zh = listOf(listOf("第一章从 1984 年的主人公温斯顿开始。"))
        val pairs = TranslationAligner.align(en, zh)
        assertTrue(pairs.isNotEmpty())
        // 数字锚点 1984 命中，置信度应显著高于最低阈值。
        assertTrue(pairs.first().confidence > TranslationAligner.MIN_CONFIDENCE)
        assertTrue(pairs.first().enSentence.contains("1984"))
    }

    @Test
    fun unequalChapterCountsStillAlign() {
        val en = listOf(listOf("First chapter text."), listOf("Second chapter text."))
        val zh = listOf(listOf("第一章文本。"))
        val pairs = TranslationAligner.align(en, zh)
        // 章节数不一致也能产出对齐结果，不抛异常。
        assertTrue(pairs.all { it.enChapter in 0..1 && it.zhChapter == 0 })
    }
}
