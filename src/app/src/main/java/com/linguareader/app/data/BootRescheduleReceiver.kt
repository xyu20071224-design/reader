package com.linguareader.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机后重排复习提醒（方案 D3.2）。
 *
 * 为什么需要它：提醒是**单次**闹钟（`ReviewReminderScheduler.schedule` 里的
 * `setAndAllowWhileIdle`），而 Android 在重启时会清空所有闹钟。此前没有开机接收器，
 * 于是「设备重启 → 未触发的提醒全丢 → 要等用户下次打开 App 才重新武装」。
 *
 * **降级仍然保留**：`AppViewModel` 每次刷新都会 `rescheduleReviewReminders()`，
 * 所以即使这个接收器被系统或 OEM 的自启限制拦掉（ColorOS 尤其严），打开 App 也能
 * 补回来 —— 这条接收器是**改善**，不是唯一依赖。
 */
class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val appContext = context.applicationContext
        // 读生词本要 IO，不能在广播的主线程上做；goAsync 给我们十秒左右的窗口。
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val words = VocabularyRepository(appContext).load()
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val pace = ReviewPace.fromPreferences(prefs.asPreferencesStore())
                // 提醒开关缺省时按当前预设的经典组合兜底，与 AppViewModel.init 一致。
                val presetName = prefs.getString(ReviewMode.PREFERENCE_KEY, null)
                val preset = if (presetName == ReviewPace.CUSTOM_NAME) null
                else runCatching { ReviewMode.valueOf(presetName ?: "") }.getOrNull()
                val reminders = ReviewReminders.fromPreferences(
                    prefs.asPreferencesStore(),
                    fallback = preset?.defaultReminders() ?: ReviewReminders.DEFAULT
                )
                ReviewReminderScheduler.schedule(appContext, words, pace, reminders.notifications)
                Log.i(TAG, "开机重排复习提醒：词 " + words.size + " 条，通知开关 " + reminders.notifications)
            } catch (e: Exception) {
                // 开机广播里失败没有第二次机会，但也不该拖垮开机流程：记一笔就算了，
                // 用户下次打开 App 时 AppViewModel 会重新排。
                Log.w(TAG, "开机重排失败，等打开 App 时补排", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReschedule"
        const val PREFS = "review_settings"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // 部分国产 ROM 用它代替标准广播。
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
