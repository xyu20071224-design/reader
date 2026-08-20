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
            progress = .72f,
            ttsChapterIndex = 1,
            ttsSentenceIndex = 4
        )

        val restored = Book.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    @Test
    fun bookJsonRoundTripPreservesPairedTranslation() {
        val original = Book(
            id = "book-2",
            title = "The Fellowship",
            author = "Tolkien",
            extractedDir = "/tmp/book2",
            coverRelativePath = "images/cover.jpg",
            chapters = listOf(Chapter("One", "OPS/one.xhtml")),
            addedAt = 4321L,
            translationBookId = "zh-book",
            translationTitle = "魔戒現身",
            translationAlignedAt = 99L
        )

        val restored = Book.fromJson(original.toJson())

        assertEquals(original, restored)
        assertTrue(restored.hasTranslation)
    }

    @Test
    fun booksWithoutTranslationFieldsStayCompatible() {
        // 旧元数据没有译本字段：必须按「未配译本」读出来，而不是崩或报错。
        val legacy = Book(
            id = "book-3",
            title = "Legacy",
            author = "Nobody",
            extractedDir = "/tmp/book3",
            coverRelativePath = "",
            chapters = listOf(Chapter("One", "OPS/one.xhtml")),
            addedAt = 5678L
        ).toJson()
        legacy.remove("translationBookId")
        legacy.remove("translationTitle")
        legacy.remove("translationAlignedAt")

        val restored = Book.fromJson(legacy)

        assertEquals("", restored.translationBookId)
        assertEquals(0L, restored.translationAlignedAt)
        assertTrue(!restored.hasTranslation)
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

    @Test
    fun bookJsonDefaultsMissingListeningPositionToZero() {
        val book = Book(
            id = "book-3",
            title = "Legacy Book",
            author = "Old Reader",
            extractedDir = "/tmp/legacy",
            coverRelativePath = null,
            chapters = listOf(Chapter("Only", "only.xhtml")),
            addedAt = 1L
        )
        val json = book.toJson()
        json.remove("ttsChapterIndex")
        json.remove("ttsSentenceIndex")

        val restored = Book.fromJson(json)

        assertEquals(0, restored.ttsChapterIndex)
        assertEquals(0, restored.ttsSentenceIndex)
    }
}
