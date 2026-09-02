package com.linguareader.app.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun treatsTerminatorRunsAsOneBoundary() {
        val sentences = SentenceSplitter.split("Really!! I can't believe it. Wait… what?")

        assertEquals(3, sentences.size)
        assertEquals("Really!!", sentences[0])
        assertEquals("I can't believe it.", sentences[1])
        assertEquals("Wait… what?", sentences[2])
    }

    @Test
    fun `keepsCaptionFragmentWithItsQuoteWhenItStartsWithLowercase`() {
        // R3：句末标点后跟小写字母 = 说话人引导语延续，不是新句。
        val sentences = SentenceSplitter.split(
            "\"I am sorry, Frodo!\" he cried, full of concern. \"So much has happened this day.\""
        )

        assertEquals(2, sentences.size)
        assertEquals("\"I am sorry, Frodo!\" he cried, full of concern.", sentences[0].trim())
        assertEquals("\"So much has happened this day.\"", sentences[1].trim())
    }

    @Test
    fun `curlyQuoteCaptionMergesButAFreshQuoteStartsANewSentence`() {
        // 收紧版 R3：只有闭合引号归属后才可能合并；新引号开句仍切。
        val sentences = SentenceSplitter.split(
            "‘I am sorry, Frodo!’ he cried, full of concern. ‘So much has happened this day.’"
        )

        assertEquals(2, sentences.size)
        assertTrue(sentences[0].trim().startsWith("‘I am sorry"))
        assertTrue(sentences[1].trim().startsWith("‘So much"))
    }

    @Test
    fun `noMergeWithoutClosingQuoteAttribution`() {
        // 无引号归属的小写延续不被合并（保守：只修引语引导语，不吞整句对话）。
        val sentences = SentenceSplitter.split("This is odd. he said nothing.")
        assertEquals(2, sentences.size)
    }

    @Test
    fun `splitsWhenTerminatorIsFollowedByACapitalOrAQuote`() {
        // R3 不能误伤正常句界：句末后大写字母或新引号开句仍要切。
        val sentences = SentenceSplitter.split(
            "She asked, \"Is it true?\" He nodded. \"Then leave,\" she added."
        )

        assertEquals(3, sentences.size)
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
