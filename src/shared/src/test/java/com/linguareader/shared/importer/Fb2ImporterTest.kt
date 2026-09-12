package com.linguareader.shared.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Fb2ImporterTest {
    private val sample = """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
  <description>
    <title-info>
      <book-title>Sample FB2</book-title>
      <author><first-name>Ada</first-name><last-name>Reader</last-name></author>
      <coverpage><image l:href="#cover.jpg"/></coverpage>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>Chapter One</p></title>
      <p>Hello &amp; welcome.</p>
      <section>
        <title><p>Nested Part</p></title>
        <p>Nested body.</p>
      </section>
    </section>
    <section>
      <title><p>Chapter Two</p></title>
      <p>Second body.</p>
    </section>
  </body>
  <binary id="cover.jpg" content-type="image/jpeg">AAAA</binary>
</FictionBook>"""

    @Test
    fun parsesMetadataCoverAndChapters() {
        val parsed = parseFb2(sample, "Fallback Title")

        assertEquals("Sample FB2", parsed.title)
        assertEquals("Ada Reader", parsed.author)
        assertNotNull(parsed.cover)
        assertEquals("image/jpeg", parsed.cover!!.contentType)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter One", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].bodyHtml.contains("Hello &amp; welcome."))
        assertTrue(parsed.chapters[0].bodyHtml.contains("<h1>Chapter One</h1>"))
        assertTrue(parsed.chapters[0].bodyHtml.contains("<h2>Nested Part</h2>"))
        assertEquals("Chapter Two", parsed.chapters[1].title)
    }

    /**
     * 第四轮审查 4-4：FB2 的 `<emphasis>`/`<strong>`/`<table>` 此前全丢（em/strong/
     * table 计数为 0），富文本塌成纯文本、表格拍成"表格单元甲表格单元乙"无分隔。
     */
    @Test
    fun keepsInlineFormattingAndTables() {
        val fb2 = """
            <FictionBook><body><section>
              <title><p>Rich</p></title>
              <p>Plain <emphasis>italic</emphasis> and <strong>bold</strong> text.</p>
              <table>
                <tr><th>Head A</th><th>Head B</th></tr>
                <tr><td>Cell A</td><td>Cell B</td></tr>
              </table>
            </section></body></FictionBook>
        """.trimIndent()

        val body = parseFb2(fb2, "x").chapters[0].bodyHtml
        assertTrue(body.contains("<em>italic</em>"), "emphasis 应转成 <em>，实际：$body")
        assertTrue(body.contains("<strong>bold</strong>"), "strong 应转成 <strong>，实际：$body")
        assertTrue(body.contains("<table>"), "表格应保留 <table>，实际：$body")
        assertTrue(body.contains("<th>Head A</th>"), "表头应保留 <th>，实际：$body")
        assertTrue(body.contains("<td>Cell A</td>"), "单元格应保留 <td>，实际：$body")
        assertTrue(body.contains("</td><td>"), "单元格之间必须有分隔，不能拍平成一串：$body")
    }

    @Test
    fun missingBodyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            parseFb2("<FictionBook><description/></FictionBook>", "x")
        }
    }
}