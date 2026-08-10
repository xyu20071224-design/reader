package com.linguareader.app.data

/** Scheduled reminder hook. Desktop MVP intentionally does nothing. */
expect object ReviewNotifier {
    fun schedule(words: List<SavedWord>, pace: ReviewPace, notificationsEnabled: Boolean)
}
