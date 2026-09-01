package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ── M1 第 1 刀：位置语义锚点 ──────────────────────────────────────────

    @Test
    fun bookJsonRoundTripPreservesReadingLocus() {
        val original = Book(
            id = "book-locus",
            title = "Anchored",
            author = "Ada Reader",
            extractedDir = "/tmp/book",
            coverRelativePath = null,
            chapters = listOf(Chapter("One", "OPS/one.xhtml")),
            addedAt = 1L,
            chapterIndex = 2,
            pageIndex = 7,
            progress = .5f,
            locusBlockIndex = 41,
            locusCharOffset = 128,
            locusAnchor = Book.ANCHOR_CHAPTER_END
        )

        val restored = Book.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(41, restored.locusBlockIndex)
        assertEquals(128, restored.locusCharOffset)
        assertEquals(Book.ANCHOR_CHAPTER_END, restored.locusAnchor)
        assertTrue(restored.hasLocus)
    }

    @Test
    fun booksWithoutLocusFieldsFallBackToPageIndex() {
        // 迁移（方案 §5 的 M1/M2）：老 metadata.json 没有 locus* 三个键，
        // 读出来必须是「尚无锚点」而不是「锚在第 0 块」——后者会把所有老书
        // 的阅读位置一把拽回章首。
        val legacy = Book(
            id = "book-legacy",
            title = "Old",
            author = "",
            extractedDir = "/tmp/old",
            coverRelativePath = null,
            chapters = listOf(Chapter("One", "OPS/one.xhtml")),
            addedAt = 1L,
            chapterIndex = 3,
            pageIndex = 9,
            progress = .25f
        ).toJson().apply {
            remove("locusBlockIndex")
            remove("locusCharOffset")
            remove("locusAnchor")
        }

        val restored = Book.fromJson(legacy)

        assertEquals(Book.NO_LOCUS, restored.locusBlockIndex)
        assertEquals(0, restored.locusCharOffset)
        assertEquals(Book.ANCHOR_EXACT, restored.locusAnchor)
        assertFalse(restored.hasLocus)
        // 页码仍在，迁移期靠它落位
        assertEquals(3, restored.chapterIndex)
        assertEquals(9, restored.pageIndex)
    }

    @Test
    fun chapterEndAnchorCountsAsALocusEvenWithoutBlockIndex() {
        // 末页锚点不需要块下标：它在重排后每次重新解析为「当前的最后一页」。
        // 旧实现把它塞进页码字段（Int.MAX_VALUE），被 clamp 一夹就落回章首。
        val book = Book(
            id = "book-end",
            title = "End",
            author = "",
            extractedDir = "/tmp/end",
            coverRelativePath = null,
            chapters = listOf(Chapter("One", "OPS/one.xhtml")),
            addedAt = 1L,
            locusAnchor = Book.ANCHOR_CHAPTER_END
        )

        assertTrue(book.hasLocus)
        assertEquals(Book.NO_LOCUS, book.locusBlockIndex)
        assertEquals(Book.ANCHOR_CHAPTER_END, Book.fromJson(book.toJson()).locusAnchor)
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
            assertTrue(font.labelRes != 0, "${font.name} labelRes must be set")
            assertTrue(font.css.isNotBlank(), "${font.name} css must not be blank")
        }
        assertEquals(
            fonts.size,
            fonts.map { it.css }.toSet().size,
            "every ReaderFont must map to a distinct css stack"
        )
        assertEquals(
            fonts.size,
            fonts.map { it.labelRes }.toSet().size,
            "every ReaderFont must have a distinct labelRes"
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

    @Test
    fun savedWordJsonRoundTripPreservesSurfaceForms() {
        val original = SavedWord(
            id = "study",
            headword = "study",
            phonetic = "",
            meaning = "n. 学习",
            sentence = "He studied hard.",
            bookId = "book-1",
            bookTitle = "A Test Book",
            chapterTitle = "One",
            addedAt = 42L,
            surfaceForms = listOf("studied", "studying")
        )

        val restored = SavedWord.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(listOf("studied", "studying"), restored.surfaceForms)
    }

    @Test
    fun savedWordsWithoutSurfaceFormsStayCompatible() {
        // 老生词本没有 surfaceForms 字段：必须读成空列表，而不是解析失败丢整本生词。
        val legacy = SavedWord(
            id = "apple",
            headword = "apple",
            phonetic = "",
            meaning = "n. 苹果",
            sentence = "An apple a day.",
            bookId = "book-1",
            bookTitle = "A Test Book",
            chapterTitle = "One",
            addedAt = 1L
        ).toJson()
        legacy.remove("surfaceForms")

        val restored = SavedWord.fromJson(legacy)

        assertEquals(emptyList<String>(), restored.surfaceForms)
    }
}
