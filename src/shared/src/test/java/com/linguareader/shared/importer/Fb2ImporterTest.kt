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

    @Test
    fun missingBodyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            parseFb2("<FictionBook><description/></FictionBook>", "x")
        }
    }
}