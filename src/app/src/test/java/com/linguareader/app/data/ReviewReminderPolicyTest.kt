package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewReminderPolicyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun notificationsRequireEnabledChannelPermissionAndBudget() {
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.GENTLE,
                notificationsEnabled = false,
                permissionGranted = true,
                todayCount = 0,
                now = now,
                dueWords = 3
            )
        )
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.DILIGENT,
                notificationsEnabled = false,
                permissionGranted = true,
                todayCount = 0,
                now = now,
                dueWords = 3
            )
        )
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.DILIGENT,
                notificationsEnabled = true,
                permissionGranted = false,
                todayCount = 0,
                now = now,
                dueWords = 3
            )
        )
        assertTrue(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.DILIGENT,
                notificationsEnabled = true,
                permissionGranted = true,
                todayCount = 0,
                now = now,
                dueWords = 3
            )
        )
    }

    @Test
    fun dailyLimitAndEmptyQueueStopNotification() {
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.DILIGENT,
                notificationsEnabled = true,
                permissionGranted = true,
                todayCount = ReviewMode.DILIGENT.dailyPromptLimit,
                now = now,
                dueWords = 1
            )
        )
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                ReviewMode.DILIGENT,
                notificationsEnabled = true,
                permissionGranted = true,
                todayCount = 0,
                now = now,
                dueWords = 0
            )
        )
    }

    @Test
    fun customPaceUsesItsOwnDailyLimit() {
        val custom = ReviewPace.defaultCustom().copy(dailyPromptLimit = 3)

        assertTrue(
            ReviewReminderPolicy.shouldNotify(
                custom,
                notificationsEnabled = true,
                permissionGranted = true,
                todayCount = 2,
                now = now,
                dueWords = 2
            )
        )
        assertFalse(
            ReviewReminderPolicy.shouldNotify(
                custom,
                notificationsEnabled = true,
                permissionGranted = true,
                todayCount = 3,
                now = now,
                dueWords = 2
            )
        )
    }

    @Test
    fun dayKeyIsDateOnlyAndStable() {
        val first = ReviewReminderPolicy.dayKey(now)
        val second = ReviewReminderPolicy.dayKey(now + 3_600_000L)

        assertEquals(first, second)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(first))
    }
}