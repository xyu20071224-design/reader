package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VocabularyTest {
    private val saved = SavedWord(
        id = "look forward to",
        headword = "look forward to",
        phonetic = "luk",
        meaning = "期望, 盼望",
        sentence = "They looked forward to working together.",
        bookId = "book-1",
        bookTitle = "A \"Quiet\" Book",
        chapterTitle = "Looking Forward",
        addedAt = 100L,
        nextReviewAt = 100L
    )

    @Test
    fun savedWordJsonRoundTripPreservesReviewState() {
        val reviewed = saved.copy(reviewLevel = 3, reviewCount = 5, nextReviewAt = 999L)

        assertEquals(reviewed, SavedWord.fromJson(reviewed.toJson()))
    }

    @Test
    fun savedWordPreservesAiContext() {
        val withAi = saved.copy(
            aiMeaning = "期待",
            aiSource = "DeepSeek",
            aiExplanation = "本句表示期待"
        )

        assertEquals(withAi, SavedWord.fromJson(withAi.toJson()))

        val csv = VocabularyRepository.csv(listOf(withAi))
        assertContains(csv, "\"期待\"")
        assertContains(csv, "DeepSeek")
        assertContains(csv, "ai_explanation")
        assertContains(csv, "本句表示期待")
    }

    @Test
    fun forgottenWordReturnsToFirstReviewInterval() {
        val reviewed = ReviewScheduler.reviewed(
            saved.copy(reviewLevel = 4, reviewCount = 2),
            remembered = false,
            now = 1_000L
        )

        assertEquals(0, reviewed.reviewLevel)
        assertEquals(3, reviewed.reviewCount)
        // Forgotten words restart the Ebbinghaus plan at the 12-hour point.
        assertEquals(43_201_000L, reviewed.nextReviewAt)
    }

    @Test
    fun rememberedWordAdvancesAndCsvEscapesContent() {
        val reviewed = ReviewScheduler.reviewed(saved, remembered = true, now = 0L)
        val csv = VocabularyRepository.csv(listOf(reviewed))

        assertEquals(1, reviewed.reviewLevel)
        // The first Ebbinghaus review point is 12 hours after learning.
        assertEquals(43_200_000L, reviewed.nextReviewAt)
        assertContains(csv, "\"A \"\"Quiet\"\" Book\"")
        assertContains(csv, "\"look forward to\"")
        assertTrue(csv.lineSequence().count() >= 2)
    }

    @Test
    fun ebbinghausPlanAdvancesThroughAllStages() {
        val intervals = longArrayOf(
            12 * 3_600_000L,
            24 * 3_600_000L,
            2 * 24 * 3_600_000L,
            4 * 24 * 3_600_000L,
            7 * 24 * 3_600_000L,
            15 * 24 * 3_600_000L,
            30 * 24 * 3_600_000L
        )
        assertEquals(7, ReviewScheduler.stageCount)

        var word = saved
        var now = 0L
        intervals.forEachIndexed { index, interval ->
            word = ReviewScheduler.reviewed(word, remembered = true, now = now)
            assertEquals(minOf(index + 1, ReviewScheduler.masteredLevel), word.reviewLevel)
            assertEquals(now + interval, word.nextReviewAt)
            now = word.nextReviewAt
        }

        // Past the final stage the word keeps the 30-day maintenance cadence.
        val again = ReviewScheduler.reviewed(word, remembered = true, now = now)
        assertEquals(word.reviewLevel, again.reviewLevel)
        assertEquals(now + intervals.last(), again.nextReviewAt)
    }

    @Test
    fun csvExportsHumanReadableReviewDate() {
        val reviewed = saved.copy(nextReviewAt = 86_401_000L)
        val csv = VocabularyRepository.csv(listOf(reviewed))

        // Timezone-independent check: an epoch millis becomes a yyyy-MM-dd date.
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(csv))
    }

    @Test
    fun csvUsesZeroForUnreviewedWord() {
        val csv = VocabularyRepository.csv(listOf(saved.copy(nextReviewAt = 0L)))

        // next_review_at is the trailing column; a bare "0" also matches
        // review_count, so pin the assertion to the end of the row.
        assertTrue(csv.trimEnd().endsWith("\"0\""))
    }
}
