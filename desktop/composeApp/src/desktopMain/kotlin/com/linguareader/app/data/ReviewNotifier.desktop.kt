package com.linguareader.app.data

actual object ReviewNotifier {
    actual fun schedule(
        words: List<SavedWord>,
        pace: ReviewPace,
        notificationsEnabled: Boolean
    ) {
        // Windows MVP: scheduled reminders are intentionally not included.
    }
}
