package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationMemoryIndexTest {

    private val paragraph = "He was late. Then he ran."
    private val zhParagraph = "他迟到了。然后他跑了起来。"

    private val memory = TranslationMemory(
        sourceBookId = "s",
        sourceTitle = "Source",
        translationBookId = "z",
        translationTitle = "译本",
        alignedAt = 0L,
        pairs = listOf(
            AlignedSentencePair(0, 0, paragraph, zhParagraph, "He was late.", "他迟到了。", 0.9f),
            AlignedSentencePair(0, 0, paragraph, zhParagraph, "Then he ran.", "然后他跑了起来。", 0.85f),
            AlignedSentencePair(3, 2, "Only a paragraph here.", "只有一个段落。", "", "", 0.7f),
            AlignedSentencePair(4, 3, "Low confidence paragraph.", "低置信段落。", "", "", 0.2f)
        )
    )

    private val index = TranslationMemoryIndex(memory)

    @Test
    fun `exact sentence hit is sentence level and carries the translation title`() {
        val result = index.lookup(0, "He was late.", paragraph)

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
        assertEquals("他迟到了。", result.chinese)
        assertEquals(zhParagraph, result.chineseParagraph)
        assertEquals("译本", result.translationTitle)
    }

    @Test
    fun `pair index and english paragraph survive into the lookup result`() {
        // 句级定点重翻靠 pairIndex 精确定位档案条目：必须是 memory.pairs 的
        // 全局下标（跨章不重排），englishParagraph 带上下文。
        val hit = index.lookup(0, "Then he ran.", paragraph)
        assertNotNull(hit)
        assertEquals(1, hit!!.pairIndex)
        assertEquals(paragraph, hit.englishParagraph)

        val other = index.lookup(3, "Only a paragraph here.", "Only a paragraph here.")
        assertNotNull(other)
        assertEquals(2, other!!.pairIndex)
        assertEquals("只有一个段落。", other.chinese)
    }

    @Test
    fun `whitespace and punctuation drift still matches`() {
        // WebView 端给出的句子可能多空白、少标点、带弯引号。
        val result = index.lookup(0, "  He   was late ", paragraph)

        assertEquals("他迟到了。", result?.chinese)
    }

    @Test
    fun `a substring of the stored sentence still matches inside the same paragraph`() {
        val result = index.lookup(0, "was late", paragraph)

        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals("他迟到了。", result?.chinese)
    }

    @Test
    fun `fuzzy match tolerates one extra word`() {
        val result = index.lookup(0, "Then he ran fast.", "A completely different paragraph.")

        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals("然后他跑了起来。", result?.chinese)
    }

    @Test
    fun `falls back to paragraph level when the sentence is unknown`() {
        val result = index.lookup(3, "Nothing like this is in the archive.", "Only a paragraph here.")

        assertEquals(TranslationMatchLevel.PARAGRAPH, result?.matchLevel)
        assertEquals("只有一个段落。", result?.chinese)
    }

    @Test
    fun `paragraph fallback below the confidence floor returns null`() {
        assertNull(index.lookup(4, "Whatever.", "Low confidence paragraph."))
    }

    @Test
    fun `paragraph fallback shows the whole paragraph translation`() {
        // L5 兜底命中的可能是句对条目（同段落），但展示必须是完整 zhParagraph：
        // 段落对不上的前提下，单条句对面临的是错位句，「只翻译了其中一句」
        // 比整段更误导。
        val result = index.lookup(0, "Unknown sentence.", "He was late. Then he ran.")

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
        assertEquals(zhParagraph, result.chinese)
        assertEquals(zhParagraph, result.chineseParagraph)
    }

    @Test
    fun `unknown chapter or unknown text returns null`() {
        assertNull(index.lookup(99, "He was late.", paragraph))
        assertNull(index.lookup(0, "Utterly unrelated wording.", "Utterly unrelated paragraph."))
    }

    @Test
    fun `chapter buckets isolate chapters`() {
        assertEquals(2, index.chapterPairCount(0))
        assertEquals(0, index.chapterPairCount(1))
        assertEquals(1, index.chapterPairCount(3))
        assertEquals(4, index.pairCount)
    }

    @Test
    fun `normalize folds whitespace curly quotes and punctuation`() {
        assertEquals(
            "don't stop - now",
            TranslationMemorySearch.normalize("  Don’t  stop —  now. ")
        )
    }

    @Test
    fun `similarity is one for identical token sets and zero when empty`() {
        assertEquals(1.0, TranslationMemorySearch.sentenceSimilarity("He ran.", "he ran"), 0.0001)
        assertEquals(0.0, TranslationMemorySearch.sentenceSimilarity("", "he ran"), 0.0001)
    }

    @Test
    fun `one shot search helper agrees with the reusable index`() {
        val direct = TranslationMemorySearch.lookup(memory, 0, "He was late.", paragraph)

        assertEquals(index.lookup(0, "He was late.", paragraph), direct)
    }
}
