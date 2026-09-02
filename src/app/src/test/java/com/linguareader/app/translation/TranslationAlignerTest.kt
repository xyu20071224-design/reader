package com.linguareader.app.translation

import com.linguareader.app.tts.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `paragraphs the dp had to skip fall back to the nearest translated paragraph`() {
        // 段落级 DP 只能 1:1 / 2:1 / 1:2，5 段英文对 2 段中文必然要合并 + 跳过；
        // 无论走哪条路，每个源段落都必须能查到对照（合并成分条目 / 邻近段落兜底）。
        val en = listOf(
            listOf(
                "Alpha one here.",
                "Beta two here.",
                "Gamma three here.",
                "Delta four here.",
                "Epsilon five here."
            )
        )
        val zh = listOf(listOf("中文第一段。", "中文第二段。"))

        val pairs = TranslationAligner.align(en, zh)
        val index = TranslationMemoryIndex(
            TranslationMemory(
                sourceBookId = "s",
                sourceTitle = "t",
                translationBookId = "z",
                translationTitle = "译本",
                alignedAt = 0L,
                pairs = pairs
            )
        )

        en[0].forEach { paragraph ->
            val hit = index.lookup(0, paragraph, paragraph)
            assertNotNull("段落查不到任何对照：$paragraph", hit)
            assertTrue("对照必须落在真实中文段落上：${hit!!.chinese}", hit.chinese.startsWith("中文"))
        }

        val paragraphLevel = pairs.filter { it.enSentence.isBlank() }
        assertTrue("应产出段级条目（合并成分或邻近兜底）", paragraphLevel.isNotEmpty())
        assertTrue(
            "段级条目不得低于查询门槛，否则落盘白占体积",
            paragraphLevel.all { it.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE }
        )
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
    @Test
    fun `closing quotes after a chinese terminator are never orphaned`() {
        // 整段引文以「。」+「」结尾：旧分句会切出纯「』」残渣（魔戒真档 734 条 zs=「」）；
        // 修复后整段引文并为一句，不再产生脏句对。
        val en = listOf(listOf("He said hello. Then he left."))
        val zh = listOf(listOf("「他說：『你好。』」然後他走了。"))

        val pairs = TranslationAligner.align(en, zh)

        assertTrue(pairs.isNotEmpty())
        assertTrue(
            "不得出现纯引号残渣句对：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.isNotBlank() && it.zhSentence.none { c -> c.isLetterOrDigit() } }
        )
        val joined = pairs.map { it.zhSentence }.joinToString("")
        assertTrue(joined.contains("然後他走了"))
    }

    @Test
    fun `a chinese quote-led caption merges into the previous sentence`() {
        // 「。」后紧跟闭合引号 + 引导语：』他大喊：「……」 旧规则切成独立片段；
        // 修复后与引文同句。
        val en = listOf(listOf("\"You are crazy!\" he shouted. \"Go! Go!\""))
        val zh = listOf(listOf("「你有病啊！」他大喊：「快走啊！」"))

        val pairs = TranslationAligner.align(en, zh)

        assertTrue(pairs.isNotEmpty())
        assertTrue(
            "引导语残句必须并入引文：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.trim().startsWith("」") || it.zhSentence.trim().startsWith("』") }
        )
    }
}
