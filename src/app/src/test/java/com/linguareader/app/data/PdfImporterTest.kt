package com.linguareader.app.data

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfImporterTest {

    @Before
    fun initPdfBoxResources() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun extractsTextAndSplitsByBookmarks() {
        val file = createPdf(
            pages = listOf(
                "Chapter One\nFirst page body.\nHello world.",
                "Second page body.",
                "Chapter Two\nFinal page body."
            ),
            bookmarks = mapOf("Chapter One" to 0, "Chapter Two" to 2),
            title = "Sample PDF",
            author = "Ada Reader"
        )

        val parsed = extractPdf(file)

        assertEquals("Sample PDF", parsed.title)
        assertEquals("Ada Reader", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter One", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].body.contains("First page body."))
        assertTrue(parsed.chapters[0].body.contains("Second page body."))
        assertTrue(!parsed.chapters[0].body.contains("Final page body."))
        assertEquals("Chapter Two", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].body.contains("Final page body."))
        file.delete()
    }

    @Test
    fun splitsByTitleLinesWhenNoBookmarks() {
        val file = createPdf(
            pages = listOf(
                "Chapter 1: The Start\nHello there.",
                "More text on page two.",
                "Chapter 2: The End\nGoodbye."
            )
        )

        val parsed = extractPdf(file)

        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1: The Start", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].body.contains("Hello there."))
        assertTrue(!parsed.chapters[0].body.contains("Goodbye."))
        assertEquals("Chapter 2: The End", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].body.contains("Goodbye."))
        file.delete()
    }

    @Test
    fun chunksPagesWhenNoTitlesAreFound() {
        val file = createPdf(
            pages = (1..12).map { "Some content on page $it." }
        )

        val parsed = extractPdf(file)

        assertEquals(2, parsed.chapters.size)
        assertEquals("第 1 部分", parsed.chapters[0].title)
        assertTrue(parsed.chapters[0].body.contains("Some content on page 1."))
        assertEquals("第 2 部分", parsed.chapters[1].title)
        assertTrue(parsed.chapters[1].body.contains("Some content on page 11."))
        file.delete()
    }

    @Test
    fun rejectsPdfWithoutTextLayer() {
        val file = createPdf(pages = listOf("", ""))

        val error = assertFailsWith<IllegalArgumentException> { extractPdf(file) }
        assertTrue(error.message.orEmpty().contains("文字层"))
        file.delete()
    }

    @Test
    fun detectsCommonPdfChapterTitleLines() {
        assertEquals(
            "Chapter 12: The End",
            detectPdfChapterTitle("Chapter 12: The End\nBody text follows.")
        )
        assertEquals("Part II", detectPdfChapterTitle("Part II\nMore text."))
        assertEquals("第一章 开始", detectPdfChapterTitle("第一章 开始\n正文内容。"))
        assertNull(detectPdfChapterTitle("A long ordinary paragraph that keeps going."))
    }

    @Test
    fun pageStartMarkerSplitsPagesInSinglePass() {
        val file = createPdf(pages = listOf("Page one.", "Page two."))

        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            stripper.pageStart = "\u0000PAGE\u0000"
            val text = stripper.getText(document)
            val parts = text.split("\u0000PAGE\u0000").filter { it.isNotBlank() }
            assertEquals(2, parts.size)
            assertTrue(parts[0].contains("Page one."))
            assertTrue(parts[1].contains("Page two."))
        }
        file.delete()
    }

    private fun createPdf(
        pages: List<String>,
        bookmarks: Map<String, Int> = emptyMap(),
        title: String? = null,
        author: String? = null
    ): File {
        val file = File.createTempFile("pdf-test", ".pdf")
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
