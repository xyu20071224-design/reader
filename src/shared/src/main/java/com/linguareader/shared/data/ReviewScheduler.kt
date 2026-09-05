package com.linguareader.shared.data

object ReviewScheduler {
    // Ebbinghaus forgetting-curve plan (day-granularity). A word is "learned"
    // when it is first reviewed; every successful review then schedules the
    // next point: 12 h → 1 d → 2 d → 4 d → 7 d → 15 d → 30 d. A forgotten
    // word ("再学一次") restarts from the 12-hour point. The base interval
    // is scaled by the selected pace (F-138) with a 30-minute floor.
    private val intervalsMillis = longArrayOf(
        12 * 3_600_000L,
        24 * 3_600_000L,
        2 * 24 * 3_600_000L,
        4 * 24 * 3_600_000L,
        7 * 24 * 3_600_000L,
        15 * 24 * 3_600_000L,
        30 * 24 * 3_600_000L
    )

    /** Number of Ebbinghaus review points (7). */
    val stageCount: Int get() = intervalsMillis.size

    /** reviewLevel at which the full plan is complete ("已掌握"). */
    val masteredLevel: Int get() = stageCount

    fun intervalFor(level: Int, pace: ReviewPace): Long {
        // Level 0 restarts at the first point; level k (>= 1) uses the k-th point.
        val base = intervalsMillis[(level - 1).coerceAtLeast(0)]
        return maxOf((base * pace.intervalMultiplier).toLong(), pace.minIntervalMillis)
    }

    fun intervalFor(level: Int, mode: ReviewMode): Long = intervalFor(level, mode.toPace())

    fun reviewed(
        word: SavedWord,
        remembered: Boolean,
        now: Long,
        pace: ReviewPace
    ): SavedWord {
        val level = if (remembered) {
            (word.reviewLevel + 1).coerceAtMost(masteredLevel)
        } else {
            0
        }
        return word.copy(
            reviewLevel = level,
            nextReviewAt = now + intervalFor(level, pace),
            reviewCount = word.reviewCount + 1
        )
    }

    fun reviewed(
        word: SavedWord,
        remembered: Boolean,
        now: Long,
        mode: ReviewMode = ReviewMode.GENTLE
    ): SavedWord = reviewed(word, remembered, now, mode.toPace())
}
