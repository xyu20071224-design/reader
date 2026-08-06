package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun bookJsonRoundTripPreservesReadingPosition() {
        val original = Book(
            id = "book-1",
            title = "A Test Book",
            author = "Ada Reader",
            extractedDir = "/tmp/book",
            coverRelativePath = "images/cover.jpg",
            chapters = listOf(
                Chapter("Chapter One", "OPS/one.xhtml"),
                Chapter("Chapter Two", "OPS/two.xhtml")
            ),
            addedAt = 1234L,
            chapterIndex = 1,
            pageIndex = 3,
            progress = .72f
        )

        val restored = Book.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun readerFontsProvideDistinctSystemStacks() {
        val fonts = ReaderFont.entries

        fonts.forEach { font ->
            assertTrue(font.label.isNotBlank(), "${font.name} label must not be blank")
            assertTrue(font.css.isNotBlank(), "${font.name} css must not be blank")
        }
        assertEquals(
            fonts.size,
            fonts.map { it.css }.toSet().size,
            "every ReaderFont must map to a distinct css stack"
        )
        assertEquals(
            fonts.size,
            fonts.map { it.label }.toSet().size,
            "every ReaderFont must have a distinct label"
        )
    }

    @Test
    fun bookJsonDefaultsMissingSourceFormatToEpub() {
        val book = Book(
            id = "book-2",
            title = "Legacy Book",
            author = "Old Reader",
            extractedDir = "/tmp/legacy",
            coverRelativePath = null,
            chapters = listOf(Chapter("Only", "only.xhtml")),
            addedAt = 1L
        )
        val json = book.toJson()
        json.remove("sourceFormat")

        assertEquals("epub", Book.fromJson(json).sourceFormat)
    }
}