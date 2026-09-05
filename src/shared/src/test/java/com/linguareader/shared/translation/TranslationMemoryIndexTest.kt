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
    fun `paragraph fallback recovers a similar sentence inside the paragraph`() {
        // 与「He was late.」token 重叠 0.75：低于第 4 级章内阈值 0.85，但在段内
        // 找回阈值 0.60 之上；特意把语序打断（不含任何存储句的子串），确保走的是
        // 段落兜底内的句级找回而不是第 3 级「句子包含」。找回后升级句子级，
        // 词级高亮与句级重翻随之可用，段落仍在上下文里。
        val result = index.lookup(0, "He was, that morning, late.", paragraph)

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
        assertEquals("他迟到了。", result.chinese)
        assertEquals(0, result.pairIndex)
        assertEquals(paragraph, result.englishParagraph)
        assertEquals(zhParagraph, result.chineseParagraph)
    }

    @Test
    fun `paragraph recovery respects the confidence floor`() {
        // 段内最像的句对自身置信度低于门槛时不找回（宁可整段，不可错标残句），
        // 降级到段落级展示同段高置信句对的完整段落。
        val lowConfidenceFirst = TranslationMemory(
            sourceBookId = "s",
            sourceTitle = "Source",
            translationBookId = "z",
            translationTitle = "译本",
            alignedAt = 0L,
            pairs = listOf(
                AlignedSentencePair(
                    0, 0, paragraph, zhParagraph, "He was late.", "他迟到了。", 0.25f
                ),
                AlignedSentencePair(
                    0, 0, paragraph, zhParagraph, "Then he ran.", "然后他跑了起来。", 0.9f
                )
            )
        )

        val result = TranslationMemoryIndex(lowConfidenceFirst)
            .lookup(0, "He was, that morning, late.", paragraph)

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
        assertEquals(zhParagraph, result.chinese)
    }

    @Test
    fun `paragraph recovery by word sense finds the sentence when tokens drift`() {
        // 意译场景：点击句与段内任何英文句 token 重叠都远低于 0.60，重叠找回
        // 失败；但被点词 softly 的释义词「低声」出现在第一句中译里 → 释义找回
        // 升级句子级。段落上下文保持不变。
        val memory = TranslationMemory(
            sourceBookId = "s",
            sourceTitle = "Source",
            translationBookId = "z",
            translationTitle = "译本",
            alignedAt = 0L,
            pairs = listOf(
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran.", "他低声道。然后他跑了起来。",
                    "He murmured softly.", "他低声道。", 0.9f
                ),
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran.", "他低声道。然后他跑了起来。",
                    "Then he ran.", "然后他跑了起来。", 0.85f
                )
            )
        )

        val result = TranslationMemoryIndex(memory)
            .lookup(0, "He spoke in a low voice.", "He murmured softly. Then he ran.", enWord = "softly") {
                listOf("低声", "轻声")
            }

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
        assertEquals("他低声道。", result.chinese)
        assertEquals(0, result.pairIndex)
        assertEquals("他低声道。然后他跑了起来。", result.chineseParagraph)
    }

    @Test
    fun `sense recovery ranks multiple candidates by confidence and overlap`() {
        // 两句中译都含释义词（低声 / 轻声）：第一句词级置信度更高（位置更接近
        // 英文词的相对位置），综合分胜出。
        val memory = TranslationMemory(
            sourceBookId = "s",
            sourceTitle = "Source",
            translationBookId = "z",
            translationTitle = "译本",
            alignedAt = 0L,
            pairs = listOf(
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran quietly.", "他低声道。然后他轻声跑开了。",
                    "He murmured softly.", "他低声道。", 0.9f
                ),
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran quietly.", "他低声道。然后他轻声跑开了。",
                    "Then he ran quietly.", "然后他轻声跑开了。", 0.85f
                )
            )
        )

        val result = TranslationMemoryIndex(memory)
            .lookup(0, "He spoke in a low voice.", "He murmured softly. Then he ran quietly.", enWord = "softly") {
                listOf("低声", "轻声")
            }

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
        assertEquals("他低声道。", result.chinese)
    }

    @Test
    fun `sense recovery skips pairs below the confidence floor`() {
        // 段内最像（含释义词）的句对自身置信度不足 → 不找回，降级到段落级。
        val memory = TranslationMemory(
            sourceBookId = "s",
            sourceTitle = "Source",
            translationBookId = "z",
            translationTitle = "译本",
            alignedAt = 0L,
            pairs = listOf(
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran.", "他低声道。然后他跑了起来。",
                    "He murmured softly.", "他低声道。", 0.25f
                ),
                AlignedSentencePair(
                    0, 0, "He murmured softly. Then he ran.", "他低声道。然后他跑了起来。",
                    "Then he ran.", "然后他跑了起来。", 0.9f
                )
            )
        )

        val result = TranslationMemoryIndex(memory)
            .lookup(0, "He spoke in a low voice.", "He murmured softly. Then he ran.", enWord = "softly") {
                listOf("低声")
            }

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
        assertEquals("他低声道。然后他跑了起来。", result.chinese)
    }

    @Test
    fun `overlap recovery takes precedence over sense recovery`() {
        // 点击句与第一句 token 重叠 0.75（重叠找回可达），同时释义词「跑」只在
        // 第二句中译里：强证据优先，必须走重叠找回而不是释义找回。
        val result = index.lookup(
            0, "He was, that morning, late.", paragraph, enWord = "late"
        ) { listOf("跑") }

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
        assertEquals("他迟到了。", result.chinese)
        assertEquals(0, result.pairIndex)
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
