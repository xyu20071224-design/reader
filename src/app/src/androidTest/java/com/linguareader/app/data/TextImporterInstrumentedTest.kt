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
class TextImporterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "txt-import-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun importsTxtWithChaptersIntoBookModel() {
        val txt = File(root, "My Story.txt")
        txt.writeText("Chapter 1: Beginning\nHello reader.\n\nChapter 2: End\nGoodbye.\n", Charsets.UTF_8)

        val book = TextImporter(context, File(root, "library")).import(Uri.fromFile(txt))

        assertEquals("My Story", book.title)
        assertEquals("txt", book.sourceFormat)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].title.startsWith("Chapter 1"))

        val first = File(book.extractedDir, book.chapters[0].relativePath)
        assertTrue(first.readText().contains("Hello reader"))
    }
}