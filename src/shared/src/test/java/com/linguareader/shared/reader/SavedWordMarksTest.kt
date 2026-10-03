package com.linguareader.shared.reader

import com.linguareader.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SavedWordMarksTest {

    private fun word(
        headword: String,
        nextReviewAt: Long = 0L,
        surfaceForms: List<String> = emptyList()
    ) = SavedWord(
        id = headword.lowercase(),
        headword = headword,
        phonetic = "",
        meaning = "",
        sentence = "",
        bookId = "b",
        bookTitle = "t",
        chapterTitle = "c",
        addedAt = 0L,
        nextReviewAt = nextReviewAt,
        surfaceForms = surfaceForms
    )

    @Test
    fun equalityBoundaryCountsAsDue() {
        // 口径铁律：nextReviewAt <= now 即到期，等号必须算到期，
        // 与 ReaderScreen.kt / VocabularyScreen.kt 的 filter 完全一致。
        val marks = SavedWordMarks.of(listOf(word("now", nextReviewAt = 1_000L)), now = 1_000L)

        assertEquals(listOf("now"), marks.forms)
        assertEquals(listOf("now"), marks.dueForms)
    }

    @Test
    fun futureWordStaysOutOfDueForms() {
        val marks = SavedWordMarks.of(listOf(word("later", nextReviewAt = 1_001L)), now = 1_000L)

        assertEquals(listOf("later"), marks.forms)
        assertEquals(emptyList(), marks.dueForms)
    }

    @Test
    fun surfaceFormsLandInBothListsWhenDue() {
        // 正文里的变形（studied/studying）本来就会被点状下划线命中；
        // 该词到期时它们必须一起变成实线，否则同一个词在同章出现两种样式。
        val marks = SavedWordMarks.of(
            listOf(word("study", nextReviewAt = 0L, surfaceForms = listOf("studied", "studying"))),
            now = 500L
        )

        assertEquals(listOf("study", "studied", "studying"), marks.forms)
        assertEquals(listOf("study", "studied", "studying"), marks.dueForms)
    }

    @Test
    fun capTruncatesBothListsInSavedOrder() {
        val words = (1..5).map { word("w$it", nextReviewAt = 0L) }

        val marks = SavedWordMarks.of(words, now = 1L, limit = 3)

        assertEquals(listOf("w1", "w2", "w3"), marks.forms)
        assertEquals(listOf("w1", "w2", "w3"), marks.dueForms)
    }

    @Test
    fun emptyInputAndNonPositiveLimitYieldEmptyMarks() {
        assertEquals(SavedWordMarks(emptyList(), emptyList()), SavedWordMarks.of(emptyList(), now = 1L))
        assertEquals(
            SavedWordMarks(emptyList(), emptyList()),
            SavedWordMarks.of(listOf(word("a", nextReviewAt = 0L)), now = 1L, limit = 0)
        )
    }

    @Test
    fun duplicateFormsAreCollapsedAndBlankFormsDropped() {
        // 跨书同名生词只留一条（全局去重），但形态表还要挡重复形态与空白项，
        // 否则注入的 JSON 会白白膨胀（上限是 600）。
        val marks = SavedWordMarks.of(
            listOf(
                word("carry", nextReviewAt = 0L, surfaceForms = listOf("carried", "carried")),
                word("carry", nextReviewAt = 0L, surfaceForms = listOf("  ", "carries"))
            ),
            now = 1L
        )

        assertEquals(listOf("carry", "carried", "carries"), marks.forms)
        assertEquals(listOf("carry", "carried", "carries"), marks.dueForms)
    }

    @Test
    fun dueAndNonDueWordsCoexist() {
        val marks = SavedWordMarks.of(
            listOf(
                word("due", nextReviewAt = 10L),
                word("fresh", nextReviewAt = 10_000L)
            ),
            now = 100L
        )

        assertEquals(listOf("due", "fresh"), marks.forms)
        assertEquals(listOf("due"), marks.dueForms)
        assertTrue(marks.forms.containsAll(marks.dueForms))
    }
}
