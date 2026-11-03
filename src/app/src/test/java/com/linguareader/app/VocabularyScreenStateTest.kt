package com.linguareader.app

import com.linguareader.shared.data.SavedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生词本的派生逻辑（到期 / 已掌握 / 搜索过滤）。
 *
 * 这三段 filter 原先内联在 composable 里，单测够不着；抽成纯函数 `vocabularyUiState`
 * 之后，「什么算到期」「搜索覆盖哪几个字段」第一次可以断言。
 */
class VocabularyScreenStateTest {

    private val now = 1_700_000_000_000L

    private fun word(
        id: String,
        headword: String = "word",
        meaning: String = "释义",
        aiMeaning: String = "",
        sentence: String = "a sentence",
        nextReviewAt: Long = 0L,
        reviewLevel: Int = 0
    ) = SavedWord(
        id = id,
        headword = headword,
        phonetic = "",
        meaning = meaning,
        aiMeaning = aiMeaning,
        sentence = sentence,
        bookId = "b",
        bookTitle = "t",
        chapterTitle = "c",
        addedAt = 0L,
        reviewLevel = reviewLevel,
        nextReviewAt = nextReviewAt
    )

    @Test
    fun dueWordsAreThoseWhoseReviewTimeHasPassed() {
        val state = vocabularyUiState(
            listOf(
                word("due-now", nextReviewAt = now),
                word("overdue", nextReviewAt = now - 1_000),
                word("future", nextReviewAt = now + 1_000)
            ),
            query = "",
            now = now
        )
        // 边界：nextReviewAt == now 算到期（与迁移前的 `<= now` 一致）。
        assertEquals(listOf("due-now", "overdue"), state.dueWords.map { it.id })
    }

    @Test
    fun masteredCountsWordsAtOrAboveMasteredLevel() {
        val state = vocabularyUiState(
            listOf(
                word("a", reviewLevel = 0),
                word("b", reviewLevel = com.linguareader.shared.data.ReviewScheduler.masteredLevel),
                word("c", reviewLevel = com.linguareader.shared.data.ReviewScheduler.masteredLevel + 3)
            ),
            query = "",
            now = now
        )
        assertEquals(listOf("b", "c"), state.masteredWords.map { it.id })
    }

    @Test
    fun searchCoversHeadwordMeaningAiMeaningAndSentence() {
        val words = listOf(
            word("by-headword", headword = "ephemeral"),
            word("by-meaning", meaning = "转瞬即逝"),
            word("by-ai", aiMeaning = "语境义"),
            word("by-sentence", sentence = "the quick brown fox"),
            word("no-match", headword = "zzz")
        )
        assertEquals(listOf("by-headword"), vocabularyUiState(words, "ephem", now).filtered.map { it.id })
        assertEquals(listOf("by-meaning"), vocabularyUiState(words, "转瞬", now).filtered.map { it.id })
        assertEquals(listOf("by-ai"), vocabularyUiState(words, "语境", now).filtered.map { it.id })
        assertEquals(listOf("by-sentence"), vocabularyUiState(words, "brown fox", now).filtered.map { it.id })
    }

    @Test
    fun searchIsCaseInsensitiveAndBlankQueryKeepsEverything() {
        val words = listOf(word("1", headword = "Ephemeral"))
        assertEquals(listOf("1"), vocabularyUiState(words, "EPHEM", now).filtered.map { it.id })
        assertEquals(listOf("1"), vocabularyUiState(words, "   ", now).filtered.map { it.id })
        assertEquals(listOf("1"), vocabularyUiState(words, "", now).filtered.map { it.id })
    }

    @Test
    fun emptyAndSearchingFlagsDriveTheTwoEmptyStates() {
        val empty = vocabularyUiState(emptyList(), "", now)
        assertTrue(empty.isEmptyLibrary)
        assertFalse(empty.isSearching)

        val noMatch = vocabularyUiState(listOf(word("1")), "nothing", now)
        assertFalse(noMatch.isEmptyLibrary)
        assertTrue(noMatch.isSearching)
        assertTrue(noMatch.filtered.isEmpty())
    }
}
