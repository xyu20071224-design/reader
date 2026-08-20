package com.linguareader.app.translation

import com.linguareader.app.tts.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAlignerTest {

    @Test
    fun `aligns sentences one to one inside a matching chapter`() {
        val en = listOf(listOf("The hobbit lived in a hole. It was a comfortable hole."))
        val zh = listOf(listOf("哈比人住在洞里。那是个舒服的洞。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(2, pairs.size)
        assertEquals(0, pairs[0].enChapter)
        assertEquals(0, pairs[0].zhChapter)
        assertTrue(pairs[0].enSentence.contains("hobbit"))
        assertEquals("哈比人住在洞里。", pairs[0].zhSentence)
        assertTrue(pairs[1].enSentence.contains("comfortable"))
        assertEquals("那是个舒服的洞。", pairs[1].zhSentence)
        assertTrue(pairs[0].confidence > TranslationAligner.MIN_CONFIDENCE)
    }

    @Test
    fun `all sentence pairs of one paragraph share the same paragraph instance`() {
        val en = listOf(listOf("First one here. Second one here."))
        val zh = listOf(listOf("第一句在这里。第二句在这里。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(2, pairs.size)
        // 段落是引用共享的，内存里不按句复制整段文本。
        assertTrue(pairs[0].enParagraph === pairs[1].enParagraph)
        assertTrue(pairs[0].zhParagraph === pairs[1].zhParagraph)
    }

    @Test
    fun `maps chapter indices from the dp path even when two chapters are identical`() {
        // 回归：旧实现用 enTexts.indexOf(text) 回查下标，两章正文完全相同时
        // 会把它们全部映射到第一处，导致整章错配。
        val duplicate = listOf("Chapter text repeated word for word.")
        val en = listOf(
            duplicate,
            duplicate,
            listOf("A third chapter with entirely different wording inside.")
        )
        val zh = listOf(listOf("重复的章节文字。"), listOf("重复的章节文字。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(listOf(0, 1), pairs.map { it.enChapter }.distinct().sorted())
        assertEquals(listOf(0, 1), pairs.map { it.zhChapter }.distinct().sorted())
    }

    @Test
    fun `empty input yields no pairs`() {
        assertTrue(TranslationAligner.align(emptyList(), listOf(listOf("中文"))).isEmpty())
        assertTrue(TranslationAligner.align(listOf(listOf("English")), emptyList()).isEmpty())
    }

    @Test
    fun `spaced initials do not end an english sentence`() {
        // 对齐质量依赖分句：J. R. R. 这类带空格缩写必须整体保留在同一句里。
        val sentences = SentenceSplitter.split("J. R. R. Tolkien wrote it. Then he slept.")

        assertEquals(2, sentences.size)
        assertTrue(sentences[0].trim().startsWith("J. R. R. Tolkien"))
    }
}
