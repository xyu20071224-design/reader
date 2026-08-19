package com.linguareader.app.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewReminderInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vocabularyFile = File(context.filesDir, "vocabulary.json")
    private var originalVocabulary: String? = null
    private var originalMode: String? = null
    private var originalReminders: String? = null

    @Before
    fun setUp() {
        if (vocabularyFile.exists()) originalVocabulary = vocabularyFile.readText()
        val settings = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        originalMode = settings.getString(ReviewMode.PREFERENCE_KEY, null)
        originalReminders = settings.getString(ReviewReminders.STORAGE_KEY, null)
        context.getSharedPreferences("review_notifications", Context.MODE_PRIVATE)
            .edit().clear().apply()
        settings.edit().putString(ReviewMode.PREFERENCE_KEY, ReviewMode.DILIGENT.name).apply()
        ReviewReminders.write(settings, ReviewReminders(notifications = true))
        val grant = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val grantOutput = java.io.FileInputStream(grant.fileDescriptor).bufferedReader().readText()
        grant.close()
        assertTrue("grant output: [$grantOutput]", grantOutput.isBlank())
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .close()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        val settings = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        if (originalMode == null) settings.edit().remove(ReviewMode.PREFERENCE_KEY).apply()
        else settings.edit().putString(ReviewMode.PREFERENCE_KEY, originalMode).apply()
        if (originalReminders == null) settings.edit().remove(ReviewReminders.STORAGE_KEY).apply()
        else settings.edit().putString(ReviewReminders.STORAGE_KEY, originalReminders).apply()
        val notifications = context.getSharedPreferences("review_notifications", Context.MODE_PRIVATE)
        notifications.edit().clear().apply()
        if (originalVocabulary == null) vocabularyFile.delete()
        else vocabularyFile.writeText(originalVocabulary!!)
    }

    @Test
    fun receiverPostsNotificationAndHonorsDailyCap() {
        seedDueWord()

        val modeName = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
            .getString(ReviewMode.PREFERENCE_KEY, null)
        assertTrue("mode=$modeName", modeName == ReviewMode.DILIGENT.name)
        val reminders = ReviewReminders.fromPreferences(
            context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        )
        assertTrue("notifications=${reminders.notifications}", reminders.notifications)
        Assume.assumeTrue(
            "POST_NOTIFICATIONS cannot be auto-granted via shell on this device",
            ReviewReminderScheduler.notificationPermissionGranted(context)
        )
        val seeded = kotlinx.coroutines.runBlocking { VocabularyRepository(context).load() }
        assertTrue(
            "due=${seeded.count { it.nextReviewAt <= System.currentTimeMillis() }}",
            seeded.any { it.id == "reminder-word" }
        )

        fireReceiver()
        // recordNotification runs only after a successful notify, so the daily
        // counter is a deterministic proxy for "notification was posted".
        assertEquals(
            1,
            ReviewReminderScheduler.todayCount(context, System.currentTimeMillis())
        )

        context.getSharedPreferences("review_notifications", Context.MODE_PRIVATE)
            .edit()
            .putInt(ReviewReminderPolicy.dayKey(System.currentTimeMillis()), ReviewMode.DILIGENT.dailyPromptLimit)
            .apply()

        fireReceiver()
        assertEquals(
            ReviewMode.DILIGENT.dailyPromptLimit,
            ReviewReminderScheduler.todayCount(context, System.currentTimeMillis())
        )
    }

    @Test
    fun receiverStaysSilentWhenNotificationsDisabled() {
        // Same degradation branch as a denied permission: policy returns false,
        // no notification is posted, and the process must not crash. (Actually
        // revoking a runtime permission kills the test process, so the
        // permission-denied decision itself is covered by the unit policy test.)
        seedDueWord()
        val settings = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        settings.edit().putString(ReviewMode.PREFERENCE_KEY, ReviewMode.DILIGENT.name).apply()
        ReviewReminders.write(settings, ReviewReminders(notifications = false))

        fireReceiver()

        assertEquals(
            0,
            ReviewReminderScheduler.todayCount(context, System.currentTimeMillis())
        )
    }

    private fun seedDueWord() {
        val word = SavedWord(
            id = "reminder-word",
            headword = "reminder",
            phonetic = "",
            meaning = "提醒",
            sentence = "A reminder word.",
            bookId = "b",
            bookTitle = "t",
            chapterTitle = "c",
            addedAt = System.currentTimeMillis() - 60_000L,
            nextReviewAt = System.currentTimeMillis() - 1_000L
        )
        vocabularyFile.writeText(JSONArray().put(word.toJson()).toString())
    }

    private fun fireReceiver() {
        // Invoke synchronously instead of sendBroadcast: broadcast delivery is
        // asynchronous and can bleed into the next test on a loaded emulator.
        ReviewReminderReceiver().onReceive(context, Intent(context, ReviewReminderReceiver::class.java))
    }
}