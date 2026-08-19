package com.linguareader.app.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TermLexiconLearnerTest {

    private fun pair(en: String, zh: String) = AlignedSentencePair(
        enChapter = 0, zhChapter = 0,
        enParagraph = en, zhParagraph = zh,
        enSentence = en, zhSentence = zh,
        confidence = 0.9f
    )

    @Test
    fun learnsProperNounCoOccurringAcrossSentences() {
        val terms = TermLexiconLearner.learn(
            listOf(
                pair("Winston sat alone and thought.", "温斯顿独自坐着沉思。"),
                pair("Winston took the lift to his flat.", "温斯顿乘电梯回到住处。"),
                pair("A party member saw Winston.", "一个党员看见了温斯顿。")
            )
        )
        val t = terms.firstOrNull { it.enWord == "winston" }
        assertNotNull(t)
        assertEquals("温斯顿", t.zhTerm)
        assertTrue(t.count >= 2)
        assertTrue(t.confidence >= 0.5f)
    }

    @Test
    fun singleSentenceWithoutSeedLearnsNothing() {
        val terms = TermLexiconLearner.learn(
            listOf(pair("He walked along the river bank.", "他沿着河岸散步。"))
        )
        // 单句、无种子：n-gram 候选全部 count==1 且非种子 → 保守过滤，不产出噪声。
        assertTrue(terms.isEmpty())
    }

    @Test
    fun seedTermIsLearnedFromSingleOccurrence() {
        val terms = TermLexiconLearner.learn(
            listOf(pair("Winston looked up.", "温斯顿抬起头。")),
            seeds = mapOf("winston" to listOf("n. 温斯顿"))
        )
        val t = terms.firstOrNull { it.enWord == "winston" }
        assertNotNull(t)
        assertEquals("温斯顿", t.zhTerm)
        assertEquals(1, t.count)
    }

    @Test
    fun learnsCommonNounTranslationByPosition() {
        val terms = TermLexiconLearner.learn(
            listOf(
                pair("The quick brown fox jumped over the lazy dog.", "敏捷的棕色狐狸跳过那只懒狗。"),
                pair("The fox hid behind the barn.", "狐狸躲在谷仓后面。"),
                pair("A fox crossed the road.", "一只狐狸穿过了马路。")
            )
        )
        val t = terms.firstOrNull { it.enWord == "fox" }
        assertNotNull(t)
        assertEquals("狐狸", t.zhTerm)
        assertTrue(t.count >= 2)
    }
}
