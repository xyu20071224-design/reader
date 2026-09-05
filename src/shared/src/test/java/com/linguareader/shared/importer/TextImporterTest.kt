package com.linguareader.shared.importer

import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextImporterTest {
    @Test
    fun splitChaptersRecognizesCommonTitlePatterns() {
        val text = "Chapter 1: The Start\nHello world.\n\n第一章 新的开始\n第二段。\n\n3. Third Chapter\nThird body."
        val chapters = splitChapters(text)

        assertEquals(3, chapters.size)
        assertTrue(chapters[0].first.startsWith("Chapter 1"))
        assertTrue(chapters[1].first.startsWith("第一章"))
        assertTrue(chapters[2].first.startsWith("3."))
        assertTrue(chapters[0].second.contains("Hello world."))
    }

    @Test
    fun textWithoutTitlesBecomesSingleChapter() {
        val chapters = splitChapters("Just a plain story.\nNo titles here.")

        assertEquals(1, chapters.size)
        assertEquals("", chapters[0].first)
    }

    @Test
    fun xhtmlEscapesMarkupAndKeepsParagraphs() {
        val html = textToXhtml("A & B", "First <p>.\n\nSecond \"quoted\".")

        assertTrue(html.contains("A &amp; B"))
        assertTrue(html.contains("&lt;p&gt;"))
        assertTrue(html.contains("&quot;quoted&quot;"))
        assertEquals(2, Regex("<p>").findAll(html).count())
    }

    @Test
    fun decodesUtf8AndGbkText() {
        val utf8 = File.createTempFile("utf8", ".txt")
        utf8.writeText("Hello 你好", Charsets.UTF_8)
        assertEquals("Hello 你好", decodeTextFile(utf8).trim())
        utf8.delete()

        val gbk = File.createTempFile("gbk", ".txt")
        gbk.writeBytes("中文内容".toByteArray(Charset.forName("GBK")))
        assertEquals("中文内容", decodeTextFile(gbk).trim())
        gbk.delete()
    }
}