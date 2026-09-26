package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // 并合句对：档案里 2 句英文并成 1 条（enSentence = "Alpha left. Beta stayed."）。
    private val mergedParagraph = "Alpha left. Beta stayed."
    private val mergedZhParagraph = "阿尔法离开了。贝塔留下了。"
    private val mergedMemory = TranslationMemory(
        sourceBookId = "s",
        sourceTitle = "Source",
        translationBookId = "z",
        translationTitle = "译本",
        alignedAt = 0L,
        pairs = listOf(
            AlignedSentencePair(
                0, 0, mergedParagraph, mergedZhParagraph,
                "Alpha left. Beta stayed.", "阿尔法离开了。贝塔留下了。", 0.9f
            )
        )
    )
    private val mergedIndex = TranslationMemoryIndex(mergedMemory)

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
    fun `a query covering only part of a merged stored sentence is not sentence level`() {
        // 档案里「2 句英文并成 1 条句对」时，点第二句也会命中第 3 级的 contains；
        // 但那条译文是并合句的译文，按句级整条返回就是错标 —— 必须放它继续走
        // 第 5 级，拿整段译文 + 段级标签。
        val result = mergedIndex.lookup(0, "Beta stayed.", mergedParagraph)

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
        assertEquals(mergedZhParagraph, result.chinese)
        assertEquals(mergedZhParagraph, result.chineseParagraph)

        // 任务书里的原样形态：并合句 "A. B."，用户点 B 查 "B."。
        val abIndex = TranslationMemoryIndex(
            TranslationMemory(
                sourceBookId = "s",
                sourceTitle = "Source",
                translationBookId = "z",
                translationTitle = "译本",
                alignedAt = 0L,
                pairs = listOf(
                    AlignedSentencePair(0, 0, "A. B.", "甲。乙。", "A. B.", "甲。乙。", 0.9f)
                )
            )
        )
        val ab = abIndex.lookup(0, "B.", "A. B.")
        assertEquals(TranslationMatchLevel.PARAGRAPH, ab?.matchLevel)
        assertEquals("甲。乙。", ab?.chinese)
    }

    @Test
    fun `the merged sentence itself and single sentence substrings stay sentence level`() {
        // 护栏只管「库中句是并合句、查询句只覆盖其中一部分」这一形态：
        // 并合句整条查仍是句级，单句库里的真子串（库中句只有 1 个句界）也仍是句级。
        val whole = mergedIndex.lookup(0, mergedParagraph, mergedParagraph)
        assertEquals(TranslationMatchLevel.SENTENCE, whole?.matchLevel)
        assertEquals(mergedZhParagraph, whole?.chinese)

        val substring = index.lookup(0, "was late", paragraph)
        assertEquals(TranslationMatchLevel.SENTENCE, substring?.matchLevel)
        assertEquals("他迟到了。", substring?.chinese)
        assertEquals(
            TranslationMatchLevel.SENTENCE,
            index.lookup(0, "Then he", paragraph)?.matchLevel
        )
    }

    @Test
    fun `cross sentence fragment guard boundaries`() {
        // 库中句是并合句、查询只覆盖其中一部分 → 片段。
        assertTrue(TranslationMemorySearch.isCrossSentenceFragment("A. B.", "B."))
        assertTrue(TranslationMemorySearch.isCrossSentenceFragment("A. B.", "A."))
        assertTrue(
            TranslationMemorySearch.isCrossSentenceFragment("Alpha left. Beta stayed.", "left")
        )
        assertTrue(
            TranslationMemorySearch.isCrossSentenceFragment(
                "Alpha left. Beta stayed.", "Beta stayed."
            )
        )
        // 两侧都是同粒度多句 → 不是片段。
        assertFalse(TranslationMemorySearch.isCrossSentenceFragment("A. B.", "A. B."))
        assertFalse(
            TranslationMemorySearch.isCrossSentenceFragment(
                "Alpha left. Beta stayed.", "Beta stayed. Gamma came."
            )
        )
        // 库中句凑不出 ≥2 个句界（空串 / 无标点）→ 永远不是片段。
        assertFalse(TranslationMemorySearch.isCrossSentenceFragment("", ""))
        assertFalse(TranslationMemorySearch.isCrossSentenceFragment("", "B."))
        assertFalse(
            TranslationMemorySearch.isCrossSentenceFragment(
                "no punctuation here", "punctuation"
            )
        )
        // 查询句为空：覆盖的句界（0）比库中句少，按最保守口径判为片段；
        // 调用侧另有「归一化查询非空」门槛，这里只是不给出错标的机会。
        assertTrue(TranslationMemorySearch.isCrossSentenceFragment("A. B.", ""))
        // 库中句本身只有一句 → 真子串不算片段。
        assertFalse(TranslationMemorySearch.isCrossSentenceFragment("He was late.", "was late"))
        // 连续终止符按一个句界算（与 SentenceSplitter 的游程口径一致）。
        assertEquals(1, TranslationMemorySearch.terminatorRuns("Go?!"))
        assertEquals(1, TranslationMemorySearch.terminatorRuns("Wait... what"))
        assertEquals(2, TranslationMemorySearch.terminatorRuns("Wait... what?"))
        assertEquals(2, TranslationMemorySearch.terminatorRuns("Alpha left… Beta stayed."))
    }

    @Test
    fun `aligner version staleness flags archives an older aligner wrote`() {
        // alignerVersion 默认 0：没有该字段的旧档案天然判旧。
        assertTrue(memory.isOutdated())
        assertTrue(memory.copy(alignerVersion = TranslationAligner.VERSION - 1).isOutdated())
        // 版本相等或更新 → 不旧。
        assertFalse(memory.copy(alignerVersion = TranslationAligner.VERSION).isOutdated())
        assertFalse(memory.copy(alignerVersion = TranslationAligner.VERSION + 1).isOutdated())
        // 显式传入当前版本（阶段 2 接线时由调用方决定）。
        assertTrue(memory.copy(alignerVersion = 0).isOutdated(current = 7))
        assertFalse(memory.copy(alignerVersion = 7).isOutdated(current = 7))
        assertFalse(memory.copy(alignerVersion = 8).isOutdated(current = 7))
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
