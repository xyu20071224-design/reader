package com.linguareader.app.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationMemorySearchTest {

    private fun memory() = TranslationMemory(
        sourceBookId = "src",
        sourceTitle = "Source",
        translationBookId = "zh",
        translationTitle = "中文译本",
        alignedAt = 0L,
        pairs = listOf(
            AlignedSentencePair(
                enChapter = 0, zhChapter = 0,
                enParagraph = "Para one. Second sentence.",
                zhParagraph = "段落一。第二句。",
                enSentence = "Second sentence.",
                zhSentence = "第二句。",
                confidence = 0.9f
            )
        )
    )

    @Test
    fun exactSentenceMatchReturnsSentenceLevel() {
        val result = TranslationMemorySearch.lookup(memory(), 0, "Second sentence.", "Para one. Second sentence.")
        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals("第二句。", result?.chinese)
        assertEquals(0.9f, result?.confidence)
    }

    @Test
    fun paragraphFallbackReturnsParagraphLevel() {
        val result = TranslationMemorySearch.lookup(memory(), 0, "Unmatched sentence.", "Para one. Second sentence.")
        assertEquals(TranslationMatchLevel.PARAGRAPH, result?.matchLevel)
        assertEquals("段落一。第二句。", result?.chineseParagraph)
    }

    @Test
    fun missingChapterReturnsNull() {
        assertNull(TranslationMemorySearch.lookup(memory(), 3, "Second sentence.", "Para one. Second sentence."))
    }

    private fun memoryOf(pairs: List<AlignedSentencePair>) = TranslationMemory(
        sourceBookId = "src", sourceTitle = "Source",
        translationBookId = "zh", translationTitle = "中文译本",
        alignedAt = 0L, pairs = pairs
    )

    private fun quoteMemory() = memoryOf(
        listOf(
            AlignedSentencePair(
                enChapter = 0, zhChapter = 0,
                enParagraph = "“Hello,”—said Winston. Why?",
                zhParagraph = "段落。",
                enSentence = "“Hello,”—said Winston.",
                zhSentence = "“你好，”温斯顿说道。",
                confidence = 0.9f
            )
        )
    )

    @Test
    fun normalizationUnifiesQuotesAndDashes() {
        // 存储端弯引号+em dash；WebView 端直引号+连字符 → 归一化后应句级命中。
        val r = TranslationMemorySearch.lookup(quoteMemory(), 0,
            sentence = "\"Hello,\"-said Winston.",
            paragraph = "\"Hello,\"-said Winston. Why?")
        assertEquals(TranslationMatchLevel.SENTENCE, r?.matchLevel)
        assertEquals("“你好，”温斯顿说道。", r?.chinese)
    }

    @Test
    fun missingTerminalPeriodStillMatchesAfterNormalization() {
        val r = TranslationMemorySearch.lookup(quoteMemory(), 0,
            sentence = "“Hello,”—said Winston",
            paragraph = "“Hello,”—said Winston. Why?")
        assertEquals(TranslationMatchLevel.SENTENCE, r?.matchLevel)
    }

    private fun riverMemory() = memoryOf(
        listOf(
            AlignedSentencePair(
                enChapter = 0, zhChapter = 0,
                enParagraph = "He walked to the river bank at dusk.",
                zhParagraph = "段落。",
                enSentence = "He walked to the river bank at dusk.",
                zhSentence = "他黄昏时走到河岸。",
                confidence = 0.9f
            )
        )
    )

    @Test
    fun fuzzySimilarSentenceFallsBackToSentenceLevel() {
        // 去除一个冠词仍高度相似（>=0.85）→ 句子级，而不是掉到段落级。
        val r = TranslationMemorySearch.lookup(riverMemory(), 0,
            sentence = "He walked to river bank at dusk.",
            paragraph = "He walked to river bank at dusk.")
        assertEquals(TranslationMatchLevel.SENTENCE, r?.matchLevel)
        assertEquals("他黄昏时走到河岸。", r?.chinese)
    }

    @Test
    fun dissimilarSentenceIsNotFuzzyMatched() {
        val r = TranslationMemorySearch.lookup(riverMemory(), 0,
            sentence = "He walked home quickly.",
            paragraph = "He walked home quickly.")
        assertNull(r)
    }
}
