package com.linguareader.shared.importer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EPUB 导入的 JVM 覆盖（原先只有仪器测试）。
 *
 * 存在的理由：解压护栏 2026-09-08 抽到 `SafeZip` 后，EpubImporter 只剩一行委托；
 * 这条路径必须有一个能在 CI 上跑的测试守着，否则「抽公共代码」就成了没保险的搬家。
 */
class EpubImporterTest {

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private val opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>The Lantern</dc:title>
            <dc:creator>Someone</dc:creator>
          </metadata>
          <manifest>
            <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine><itemref idref="c1"/></spine>
        </package>
    """.trimIndent()

    private val chapter = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter One</title></head>
        <body><h1>Chapter One</h1><p>Hello <b>world</b>.</p>
        <script>alert('x')</script></body></html>
    """.trimIndent()

    private fun epub(vararg extra: Pair<String, String>): File {
        val file = File.createTempFile("book-", ".epub")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { out ->
            fun put(name: String, text: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/chapter1.xhtml", chapter)
            extra.forEach { (name, text) -> put(name, text) }
        }
        return file
    }

    @Test
    fun `imports chapters and strips scripts`() {
        val booksDir = File.createTempFile("books-", "").let { it.delete(); it.mkdirs(); it }
        val book = EpubImporter(booksDir).import(epub())

        assertEquals("The Lantern", book.title)
        assertEquals("Someone", book.author)
        assertEquals(1, book.chapters.size)
        assertEquals("Chapter One", book.chapters.first().title)

        val html = File(book.extractedDir, book.chapters.first().relativePath).readText()
        assertTrue("alert" !in html, "script 应被清掉：$html")
        assertTrue("Hello" in html)
    }

    @Test
    fun `path traversal inside epub is rejected`() {
        val booksDir = File.createTempFile("books-", "").let { it.delete(); it.mkdirs(); it }
        val error = assertFailsWith<IllegalArgumentException> {
            EpubImporter(booksDir).import(epub("../escape.txt" to "nope"))
        }
        assertTrue(error.message.orEmpty().contains("EPUB"), error.message.orEmpty())
        assertTrue(!File(booksDir.parentFile, "escape.txt").exists())
    }
}
