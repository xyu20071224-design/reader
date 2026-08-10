package com.linguareader.app.data

import com.linguareader.app.platform.androidAppContext

actual object ReviewNotifier {
    actual fun schedule(
        words: List<SavedWord>,
        pace: ReviewPace,
        notificationsEnabled: Boolean
    ) {
        ReviewReminderScheduler.schedule(androidAppContext, words, pace, notificationsEnabled)
    }
}
