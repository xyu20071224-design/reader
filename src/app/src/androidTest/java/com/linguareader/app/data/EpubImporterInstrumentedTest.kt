package com.linguareader.app.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubImporterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "epub-import-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun importsSpineMetadataAndSanitizesScripts() {
        val epub = File(root, "sample.epub")
        createSampleEpub(epub)

        val imported = EpubImporter(context, File(root, "library")).import(Uri.fromFile(epub))

        assertEquals("A Small English Book", imported.title)
        assertEquals("Test Author", imported.author)
        assertEquals(2, imported.chapters.size)
        assertEquals("The Beginning", imported.chapters.first().title)

        val firstChapter = File(imported.extractedDir, imported.chapters.first().relativePath)
        val sanitized = firstChapter.readText()
        assertFalse(sanitized.contains("<script", ignoreCase = true))
        assertFalse(sanitized.contains("onclick=", ignoreCase = true))
        assertTrue(sanitized.contains("Reader"))
    }

    private fun createSampleEpub(target: File) {
        ZipOutputStream(target.outputStream()).use { zip ->
            fun add(path: String, contents: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.trimIndent().toByteArray())
                zip.closeEntry()
            }

            add("mimetype", "application/epub+zip")
            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OPS/content.opf"
                      media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """
            )
            add(
                "OPS/content.opf",
                """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>A Small English Book</dc:title>
                    <dc:creator>Test Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="one"/>
                    <itemref idref="two"/>
                  </spine>
                </package>
                """
            )
            add(
                "OPS/one.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>The Beginning</title><script>alert('bad')</script></head>
                  <body><h1>The Beginning</h1><p onclick="bad()">The Reader opened a book.</p></body>
                </html>
                """
            )
            add(
                "OPS/two.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>The Next Chapter</title></head>
                  <body><h1>The Next Chapter</h1><p>Learning continued.</p></body>
                </html>
                """
            )
        }
    }
}
