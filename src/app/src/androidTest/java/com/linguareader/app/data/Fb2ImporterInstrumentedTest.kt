package com.linguareader.app.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Fb2ImporterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "fb2-import-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun importsFb2IntoBookModel() {
        val fb2 = File(root, "Sample.fb2")
        fb2.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description>
    <title-info>
      <book-title>Sample FB2</book-title>
      <author><first-name>Ada</first-name><last-name>Reader</last-name></author>
    </title-info>
  </description>
  <body>
    <section>
      <title><p>One</p></title>
      <p>First body.</p>
    </section>
    <section>
      <title><p>Two</p></title>
      <p>Second body.</p>
    </section>
  </body>
</FictionBook>""".trimIndent(),
            Charsets.UTF_8
        )

        val book = Fb2Importer(context, File(root, "library")).import(Uri.fromFile(fb2))

        assertEquals("Sample FB2", book.title)
        assertEquals("Ada Reader", book.author)
        assertEquals("fb2", book.sourceFormat)
        assertEquals(2, book.chapters.size)
        val first = File(book.extractedDir, book.chapters[0].relativePath)
        assertTrue(first.readText().contains("First body."))
    }

    @Test
    fun usesFileNameAsTitleWhenMetadataMissing() {
        val fb2 = File(root, "Untitled.fb2")
        fb2.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
  <description><title-info/></description>
  <body><section><p>Body text.</p></section></body>
</FictionBook>""".trimIndent(),
            Charsets.UTF_8
        )

        val book = Fb2Importer(context, File(root, "library")).import(Uri.fromFile(fb2))

        assertEquals("Untitled", book.title)
    }
}
