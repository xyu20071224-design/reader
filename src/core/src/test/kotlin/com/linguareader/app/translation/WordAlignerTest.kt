package com.linguareader.app.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordAlignerTest {

    @Test
    fun digitAnchorIsMatchedDirectly() {
        val r = WordAligner.align("1984", "It was 1984.", "那是 1984 年。", emptyList())
        assertNotNull(r)
        assertEquals(WordAlignmentSource.ANCHOR, r.source)
        assertEquals("1984", r.word)
    }

    @Test
    fun latinProperNounAnchorIsMatchedDirectly() {
        val r = WordAligner.align("iPhone", "He bought an iPhone.", "他买了一部 iPhone。", emptyList())
        assertNotNull(r)
        assertEquals(WordAlignmentSource.ANCHOR, r.source)
        assertEquals("iPhone", r.word)
    }

    @Test
    fun translatedProperNounIsMatchedViaDictionary() {
        val r = WordAligner.align(
            enWord = "Winston",
            enSentence = "Winston sat alone.",
            zhSentence = "温斯顿独自坐着。",
            candidates = listOf("n. 温斯顿")
        )
        assertNotNull(r)
        assertEquals(WordAlignmentSource.DICTIONARY, r.source)
        assertEquals("温斯顿", r.word)
    }

    @Test
    fun dictionaryCandidateIsMatchedByPosition() {
        val r = WordAligner.align(
            enWord = "fox",
            enSentence = "The quick brown fox jumps.",
            zhSentence = "敏捷的狐狸跳了起来。",
            candidates = listOf("n. 狐狸", "v. 欺骗")
        )
        assertNotNull(r)
        assertEquals(WordAlignmentSource.DICTIONARY, r.source)
        assertEquals("狐狸", r.word)
        assertEquals("敏捷的狐狸跳了起来。".indexOf("狐狸"), r.start)
    }

    @Test
    fun prefersLongerCandidateOverSingleCharPart() {
        val r = WordAligner.align(
            enWord = "bank",
            enSentence = "He sat by the bank.",
            zhSentence = "他坐在河岸旁。",
            candidates = listOf("n. 银行；河岸")
        )
        assertNotNull(r)
        // 应按最长匹配优先命中「河岸」而非「银行」（后者不在句中）。
        assertEquals("河岸", r.word)
    }

    @Test
    fun noMatchReturnsNull() {
        val r = WordAligner.align(
            enWord = "do",
            enSentence = "How do you do?",
            zhSentence = "你好。",
            candidates = listOf("v. 做")
        )
        assertNull(r)
    }

    @Test
    fun preferredBookTermIsSelectedWithoutDictionarySenses() {
        // 词典里没有“佛罗多”，但本书术语表偏好它 → 仍能选中。
        val r = WordAligner.align(
            enWord = "frodo",
            enSentence = "Frodo drew his sword.",
            zhSentence = "佛罗多拔出了剑。",
            candidates = emptyList(),
            prefer = mapOf("佛罗多" to 0.5f)
        )
        assertNotNull(r)
        assertEquals(WordAlignmentSource.DICTIONARY, r?.source)
        assertEquals("佛罗多", r?.word)
        assertTrue((r?.confidence ?: 0f) >= WordAligner.MIN_CONFIDENCE)
    }

    @Test
    fun preferBonusLiftsMediocrePositionAboveThreshold() {
        // 无偏好时位置偏差把置信压到阈值下；偏好加成后应可选中。
        val r = WordAligner.align(
            enWord = "gandalf",
            enSentence = "Gandalf was gone from the party.",
            zhSentence = "很久以前就离开了宴会。甘道夫。",
            candidates = emptyList(),
            prefer = mapOf("甘道夫" to 0.5f)
        )
        assertNotNull(r)
        assertEquals("甘道夫", r?.word)
    }
}
