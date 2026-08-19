package com.linguareader.app.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSplitterTest {
    @Test
    fun splitsEnglishSentences() {
        val sentences = SentenceSplitter.split(
            "The quick brown fox jumps over the lazy dog. He ran away!"
        )

        assertEquals(2, sentences.size)
        assertEquals("The quick brown fox jumps over the lazy dog.", sentences[0])
        assertEquals("He ran away!", sentences[1])
    }

    @Test
    fun keepsCommonAbbreviationsTogether() {
        val sentences = SentenceSplitter.split(
            "Dr. Smith arrived early. Prof. Lee agreed with the plan."
        )

        assertEquals(2, sentences.size)
        assertEquals("Dr. Smith arrived early.", sentences[0])
        assertEquals("Prof. Lee agreed with the plan.", sentences[1])
    }

    @Test
    fun keepsInitialsInsideSentencesButSplitsAtSentenceEnd() {
        val sentences = SentenceSplitter.split(
            "He works for the U.S. government. Next year he retires."
        )

        assertEquals(2, sentences.size)
        assertEquals("He works for the U.S. government.", sentences[0])
        assertEquals("Next year he retires.", sentences[1])
    }

    @Test
    fun splitsChineseSentences() {
        val sentences = SentenceSplitter.split("你好。世界很大！我们走吧？")

        assertEquals(listOf("你好。", "世界很大！", "我们走吧？"), sentences)
    }

    @Test
    fun splitsMixedEnglishAndChinese() {
        val sentences = SentenceSplitter.split("He said \"Hello.\" 然后他走了。")

        assertEquals(2, sentences.size)
        assertEquals("He said \"Hello.\"", sentences[0])
        assertEquals("然后他走了。", sentences[1])
    }

    @Test
    fun keepsClosingQuotesWithFinishedSentence() {
        val sentences = SentenceSplitter.split(
            "She asked, \"Is it true?\" He nodded."
        )

        assertEquals(2, sentences.size)
        assertEquals("She asked, \"Is it true?\"", sentences[0])
        assertEquals("He nodded.", sentences[1])
    }

    @Test
    fun keepsSpacedInitialsTogether() {
        val sentences = SentenceSplitter.split(
            "J. K. Rowling walked in. George R. R. Martin wrote the book."
        )

        assertEquals(2, sentences.size)
        assertEquals("J. K. Rowling walked in.", sentences[0])
        assertEquals("George R. R. Martin wrote the book.", sentences[1])
    }

    @Test
    fun treatsTerminatorRunsAsOneBoundary() {
        val sentences = SentenceSplitter.split("Really!! I can't believe it. Wait… what?")

        assertEquals(3, sentences.size)
        assertEquals("Really!!", sentences[0])
        assertEquals("I can't believe it.", sentences[1])
        assertEquals("Wait… what?", sentences[2])
    }

    @Test
    fun returnsWholeTextWhenNoTerminatorExists() {
        val sentences = SentenceSplitter.split("This is one long unbroken sentence")

        assertEquals(listOf("This is one long unbroken sentence"), sentences)
    }

    @Test
    fun returnsEmptyListForBlankText() {
        assertEquals(emptyList<String>(), SentenceSplitter.split("   "))
        assertEquals(emptyList<String>(), SentenceSplitter.split(""))
    }
}
