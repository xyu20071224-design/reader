package com.linguareader.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.linguareader.app.MainActivity
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure decision logic for local review notifications (testable). */
object ReviewReminderPolicy {
    fun dayKey(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))

    fun shouldNotify(
        pace: ReviewPace,
        notificationsEnabled: Boolean,
        permissionGranted: Boolean,
        todayCount: Int,
        now: Long,
        dueWords: Int
    ): Boolean =
        notificationsEnabled &&
            permissionGranted &&
            todayCount < pace.dailyPromptLimit &&
            dueWords > 0

    fun shouldNotify(
        mode: ReviewMode,
        notificationsEnabled: Boolean,
        permissionGranted: Boolean,
        todayCount: Int,
        now: Long,
        dueWords: Int
    ): Boolean = shouldNotify(
        mode.toPace(), notificationsEnabled, permissionGranted, todayCount, now, dueWords
    )
}

/** Schedules/cancels the single next-due local notification for enabled modes. */
object ReviewReminderScheduler {
    private const val PREFS = "review_notifications"
    private const val CHANNEL_ID = "review_reminder"
    private const val REQUEST_CODE = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "复习提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "已开启定时轻提醒时到期生词的本地提醒"
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun notificationPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun schedule(
        context: Context,
        words: List<SavedWord>,
        pace: ReviewPace,
        notificationsEnabled: Boolean
    ) {
        ensureChannel(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReviewReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canNotify = notificationsEnabled && notificationPermissionGranted(context)
        if (!canNotify) {
            alarmManager.cancel(pending)
            return
        }
        val now = System.currentTimeMillis()
        val next = words.filter { it.nextReviewAt > now }.minOfOrNull { it.nextReviewAt }
        if (next == null) {
            alarmManager.cancel(pending)
            return
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    fun schedule(
        context: Context,
        words: List<SavedWord>,
        mode: ReviewMode,
        notificationsEnabled: Boolean
    ) = schedule(context, words, mode.toPace(), notificationsEnabled)

    fun todayCount(context: Context, now: Long): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(ReviewReminderPolicy.dayKey(now), 0)
    }

    fun recordNotification(context: Context, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(ReviewReminderPolicy.dayKey(now), todayCount(context, now) + 1).apply()
    }
}

class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("review_settings", Context.MODE_PRIVATE)
        val pace = ReviewPace.fromPreferences(prefs.asPreferencesStore())
        val reminders = ReviewReminders.fromPreferences(prefs.asPreferencesStore())
        val now = System.currentTimeMillis()
        val words = runCatching {
            runBlocking { VocabularyRepository(context).load() }
        }.getOrDefault(emptyList())
        val dueCount = words.count { it.nextReviewAt <= now }
        val allowed = ReviewReminderPolicy.shouldNotify(
            pace = pace,
            notificationsEnabled = reminders.notifications,
            permissionGranted = ReviewReminderScheduler.notificationPermissionGranted(context),
            todayCount = ReviewReminderScheduler.todayCount(context, now),
            now = now,
            dueWords = dueCount
        )
        if (allowed) {
            showNotification(context, dueCount)
            ReviewReminderScheduler.recordNotification(context, now)
        }
        ReviewReminderScheduler.schedule(context, words, pace, reminders.notifications)
    }

    private fun showNotification(context: Context, dueCount: Int) {
        ReviewReminderScheduler.ensureChannel(context)
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "review_reminder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("有生词到期了")
            .setContentText(if (dueCount > 0) "有 $dueCount 个词可快速复习" else "打开应用查看待复习生词")
            .setContentIntent(launch)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(4202, notification)
    }
}