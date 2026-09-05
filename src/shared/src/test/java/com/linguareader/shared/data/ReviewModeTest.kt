package com.linguareader.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewModeTest {
    private val saved = SavedWord(
        id = "word",
        headword = "word",
        phonetic = "",
        meaning = "",
        sentence = "",
        bookId = "b",
        bookTitle = "t",
        chapterTitle = "c",
        addedAt = 0L,
        nextReviewAt = 0L
    )

    @Test
    fun presetParametersMatchConfirmedDesign() {
        assertEquals(ReviewMode.GENTLE, ReviewMode.DEFAULT)

        assertEquals(2 * 3_600_000L, ReviewMode.IMMERSIVE.firstDelayMillis)
        assertEquals(30 * 60_000L, ReviewMode.GENTLE.firstDelayMillis)
        assertEquals(5 * 60_000L, ReviewMode.DILIGENT.firstDelayMillis)

        assertEquals(1.5, ReviewMode.IMMERSIVE.intervalMultiplier)
        assertEquals(1.0, ReviewMode.GENTLE.intervalMultiplier)
        assertEquals(0.75, ReviewMode.DILIGENT.intervalMultiplier)

        assertEquals(1, ReviewMode.IMMERSIVE.dailyPromptLimit)
        assertEquals(2, ReviewMode.GENTLE.dailyPromptLimit)
        assertEquals(4, ReviewMode.DILIGENT.dailyPromptLimit)

        assertEquals(3, ReviewMode.IMMERSIVE.sessionMaxWords)
        assertEquals(5, ReviewMode.GENTLE.sessionMaxWords)
        assertEquals(10, ReviewMode.DILIGENT.sessionMaxWords)

        assertEquals(5_000L, ReviewMode.IMMERSIVE.dwellMillis)
        assertEquals(10_000L, ReviewMode.GENTLE.dwellMillis)
        assertEquals(10_000L, ReviewMode.DILIGENT.dwellMillis)

        assertFalse(ReviewMode.IMMERSIVE.defaultReminders().notifications)
        assertFalse(ReviewMode.GENTLE.defaultReminders().notifications)
        assertTrue(ReviewMode.DILIGENT.defaultReminders().notifications)
    }

    @Test
    fun presetReminderCombinationsMatchConfirmedDesign() {
        val immersive = ReviewMode.IMMERSIVE.defaultReminders()
        assertTrue(immersive.contextHighlight)
        assertFalse(immersive.pausePrompt)
        assertTrue(immersive.toolbarBadge)
        assertFalse(immersive.notifications)

        val gentle = ReviewMode.GENTLE.defaultReminders()
        assertTrue(gentle.contextHighlight)
        assertTrue(gentle.pausePrompt)
        assertTrue(gentle.toolbarBadge)
        assertFalse(gentle.notifications)

        val diligent = ReviewMode.DILIGENT.defaultReminders()
        assertTrue(diligent.contextHighlight)
        assertTrue(diligent.pausePrompt)
        assertTrue(diligent.toolbarBadge)
        assertTrue(diligent.notifications)
    }

    @Test
    fun multiplierScalesTheBasePlan() {
        assertEquals(12 * 3_600_000L, ReviewScheduler.intervalFor(1, ReviewMode.GENTLE))
        assertEquals(18 * 3_600_000L, ReviewScheduler.intervalFor(1, ReviewMode.IMMERSIVE))
        assertEquals(9 * 3_600_000L, ReviewScheduler.intervalFor(1, ReviewMode.DILIGENT))
    }

    @Test
    fun reviewedWordUsesPresetInterval() {
        val gentle = ReviewScheduler.reviewed(saved, remembered = true, now = 1_000L, mode = ReviewMode.GENTLE)
        assertEquals(43_201_000L, gentle.nextReviewAt)

        val diligent = ReviewScheduler.reviewed(saved, remembered = true, now = 1_000L, mode = ReviewMode.DILIGENT)
        assertEquals(32_401_000L, diligent.nextReviewAt)
    }

    @Test
    fun masteredLevelKeepsMaintenanceCadenceScaledByPreset() {
        val mastered = saved.copy(reviewLevel = ReviewScheduler.masteredLevel)

        val again = ReviewScheduler.reviewed(mastered, remembered = true, now = 5_000L, mode = ReviewMode.IMMERSIVE)

        assertEquals(ReviewScheduler.masteredLevel, again.reviewLevel)
        assertEquals(5_000L + 30 * 86_400_000L * 3 / 2, again.nextReviewAt)
    }

    @Test
    fun forgottenWordRestartsAtScaledFirstPoint() {
        val forgotten = ReviewScheduler.reviewed(
            saved.copy(reviewLevel = 4),
            remembered = false,
            now = 2_000L,
            mode = ReviewMode.DILIGENT
        )

        assertEquals(0, forgotten.reviewLevel)
        assertEquals(2_000L + 9 * 3_600_000L, forgotten.nextReviewAt)
    }

    @Test
    fun defaultCustomStartsFromGentleParameters() {
        val custom = ReviewPace.defaultCustom()

        assertEquals(ReviewMode.GENTLE.firstDelayMillis, custom.firstDelayMillis)
        assertEquals(ReviewMode.GENTLE.intervalMultiplier, custom.intervalMultiplier)
        assertEquals(ReviewMode.GENTLE.dailyPromptLimit, custom.dailyPromptLimit)
        assertEquals(ReviewMode.GENTLE.sessionMaxWords, custom.sessionMaxWords)
    }

    @Test
    fun customPaceJsonRoundTrip() {
        val pace = ReviewPace(
            label = "自定义",
            firstDelayMillis = 2 * 3_600_000L,
            intervalMultiplier = 1.25,
            minIntervalMillis = 30 * 60_000L,
            dailyPromptLimit = 3,
            sessionMaxWords = 8,
            dwellMillis = 10_000L
        )

        assertEquals(pace, ReviewPace.fromJson(pace.toJson()))
    }

    @Test
    fun retentionTargetMapsToIntervalMultiplier() {
        assertEquals(1.0, ReviewPace.multiplierForRetention(ReviewPace.REFERENCE_RETENTION), 1e-9)
        assertTrue(ReviewPace.multiplierForRetention(0.90) < 1.0)
        assertTrue(ReviewPace.multiplierForRetention(0.80) > 1.0)
        // Higher retention (review sooner) hits the fast clamp, lower retention the slow one.
        assertEquals(ReviewPace.MIN_MULTIPLIER, ReviewPace.multiplierForRetention(0.95), 1e-9)
        assertEquals(ReviewPace.MAX_MULTIPLIER, ReviewPace.multiplierForRetention(0.70), 1e-9)

        val multiplier = ReviewPace.multiplierForRetention(0.82)
        assertEquals(0.82, ReviewPace.retentionForMultiplier(multiplier), 0.01)
    }

    @Test
    fun customPaceScalesTheBasePlan() {
        val custom = ReviewPace.defaultCustom().copy(intervalMultiplier = 1.25)

        assertEquals(15 * 3_600_000L, ReviewScheduler.intervalFor(1, custom))
        assertEquals(30 * 3_600_000L, ReviewScheduler.intervalFor(2, custom))

        // The 30-minute floor still applies to very fast custom paces.
        val tooFast = custom.copy(intervalMultiplier = 0.01)
        assertEquals(30 * 60_000L, ReviewScheduler.intervalFor(1, tooFast))
    }

    @Test
    fun remindersJsonRoundTripAndManualOnly() {
        val reminders = ReviewReminders(
            contextHighlight = false,
            pausePrompt = false,
            toolbarBadge = false,
            notifications = false
        )

        assertEquals(reminders, ReviewReminders.fromJson(reminders.toJson()))
        assertTrue(reminders.manualOnly)
        assertFalse(ReviewReminders.DEFAULT.manualOnly)
    }
}