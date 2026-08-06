package com.linguareader.app.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfImporterInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "pdf-import-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun importsTextPdfWithBookmarksIntoBookModel() {
        val pdf = createPdf(
            pages = listOf(
                "Chapter One\nFirst page body.\nHello world.",
                "Second page body.",
                "Chapter Two\nFinal page body."
            ),
            bookmarks = mapOf("Chapter One" to 0, "Chapter Two" to 2),
            title = "PDF Book",
            author = "Ada Reader"
        )

        val book = PdfImporter(context, File(root, "library")).import(Uri.fromFile(pdf))

        assertEquals("PDF Book", book.title)
        assertEquals("Ada Reader", book.author)
        assertEquals("pdf", book.sourceFormat)
        assertEquals(2, book.chapters.size)
        assertEquals("Chapter One", book.chapters[0].title)

        val first = File(book.extractedDir, book.chapters[0].relativePath)
        val html = first.readText()
        assertTrue(html.contains("First page body."))
        assertTrue(html.contains("Second page body."))
    }

    @Test
    fun rejectsPdfWithoutTextLayer() {
        val pdf = createPdf(pages = listOf("", ""))

        val error = runCatching {
            PdfImporter(context, File(root, "library")).import(Uri.fromFile(pdf))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("文字层"))
    }

    @Test
    fun usesFileNameAsTitleWhenMetadataMissing() {
        val pdf = createPdf(
            pages = listOf("Some text on a page."),
            fileName = "Plain.pdf"
        )

        val book = PdfImporter(context, File(root, "library")).import(Uri.fromFile(pdf))

        assertEquals("Plain", book.title)
    }

    private fun createPdf(
        pages: List<String>,
        bookmarks: Map<String, Int> = emptyMap(),
        title: String? = null,
        author: String? = null,
        fileName: String = "sample.pdf"
    ): File {
        val file = File(root, fileName)
        PDDocument().use { document ->
            if (title != null || author != null) {
                val info = document.documentInformation
                info.title = title
                info.author = author
            }
            val pageRefs = pages.map { text ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                if (text.isNotBlank()) {
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(72f, 720f)
                        text.lineSequence().forEach { line ->
                            stream.showText(line)
                            stream.newLineAtOffset(0f, -18f)
                        }
                        stream.endText()
                    }
                }
                page
            }
            if (bookmarks.isNotEmpty()) {
                val outline = PDDocumentOutline()
                document.documentCatalog.documentOutline = outline
                bookmarks.forEach { (bookmarkTitle, pageNumber) ->
                    val item = PDOutlineItem()
                    item.title = bookmarkTitle
                    item.setDestination(pageRefs[pageNumber])
                    outline.addLast(item)
                }
            }
            document.save(file)
        }
        return file
    }
}
