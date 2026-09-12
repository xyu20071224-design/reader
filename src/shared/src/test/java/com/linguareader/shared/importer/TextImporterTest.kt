package com.linguareader.shared.importer

import java.io.File
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * 第四轮审查 4-5：PDF 行末排印断词 `exam-` + 换行应在转 XHTML 时合并成一个词，
     * 而不是留下 `exam- ple`（朗读与点词都会看错）。
     */
    @Test
    fun joinsHyphenatedLineBreaksButKeepsRealHyphens() {
        val html = textToXhtml(
            "t",
            "This is an exam-\nple of a broken word.\n\nKeep the dash in C-3PO and a -- separator."
        )

        assertTrue(html.contains("example of a broken word"), "断词应合并：$html")
        assertFalse(html.contains("exam- ple"), "不应残留 `exam- ple`：$html")
        assertTrue(html.contains("C-3PO"), "正常连字符应保留：$html")
        assertTrue(html.contains("-- separator"), "`--` 分隔线应保留：$html")
    }

    /**
     * 第四轮审查 4-7：TXT 正文此前零标题标签（章名只在 `<title>` 与书库章名），
     * 滚动/朗读时看不到章内大标题。识别出的章标题应在正文里落成 `<h1>`，
     * 且数量等于识别出的章节数。
     */
    @Test
    fun chapterTitlesBecomeHeadingsInBody() {
        val text = "第一章 起点\n正文一。\n\n第二章 转折\n正文二。\n"
        val chapters = splitChapters(text)
        assertEquals(2, chapters.size, "应识别出 2 章")

        val htmls = chapters.map { (title, body) -> textToXhtml(title, body) }
        htmls.forEachIndexed { index, html ->
            assertTrue(html.contains("<h1>"), "第 ${index + 1} 章正文应有 <h1>：$html")
        }
        assertEquals(2, htmls.count { it.contains("<h1>") }, "标题标签数应等于章节数")
        assertTrue(htmls[0].contains("<h1>第一章 起点</h1>"), "标题文字应在 <h1> 内：${htmls[0]}")
    }

    /** 无标题的 TXT（整篇一章、章名为空）不应凭空生出 <h1>。 */
    @Test
    fun untitledChapterGetsNoHeading() {
        val html = textToXhtml("", "只有正文，没有标题行。")
        assertFalse(html.contains("<h1>"), "无标题章节不应有 <h1>：$html")
        assertTrue(html.contains("<p>只有正文，没有标题行。</p>"))
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