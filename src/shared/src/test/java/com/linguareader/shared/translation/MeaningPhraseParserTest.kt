package com.linguareader.shared.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeaningPhraseParserTest {

    @Test
    fun keepsContentPosLinesAndDropsFunctionWords() {
        val phrases = MeaningPhraseParser.parse(
            "n. 面条, 干面条\na. 干燥的\nprep. 越过\nconj. 当...的时候\nvt. 铺地板"
        )

        assertTrue(phrases.contains("面条"))
        assertTrue(phrases.contains("干燥"))
        assertTrue(phrases.contains("干燥的"))
        assertTrue(phrases.contains("地板"))
        assertTrue(phrases.none { it == "时候" }, "conj. 行应被整行跳过")
        assertTrue(phrases.none { it == "越过" }, "prep. 行应被跳过")
    }

    @Test
    fun removesStopPhrasesButKeepsSpecialisedOnes() {
        val phrases = MeaningPhraseParser.parse("a. 一个重要的发现\\nn. 含义, 意义")

        assertTrue("发现" in phrases)
        assertTrue("含义" in phrases)
        assertTrue("意义" in phrases)
        assertTrue(phrases.none { it == "一个" }, "黑名单短语必须剔除")
    }

    @Test
    fun traditionalCoversKnownPairs() {
        assertEquals("对不起", TraditionalSimplified.toSimplified("對不起"))
        assertEquals("没有什么", TraditionalSimplified.toSimplified("沒有什麼"))
        assertEquals("魔戒", TraditionalSimplified.toSimplified("魔戒"))
    }
}
