package com.linguareader.app.update

import android.content.Context

/**
 * 自动更新设置。隐私边界：自动检查是出网行为，出厂默认关闭，由用户显式打开。
 */
data class AppUpdateSettings(
    /** 启动时自动检查更新；默认 false（离线优先承诺）。 */
    val autoCheckEnabled: Boolean = false
) {
    companion object {
        private const val PREFS = "update_settings"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"

        fun load(context: Context): AppUpdateSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppUpdateSettings(
                autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK, false)
            )
        }

        fun save(context: Context, settings: AppUpdateSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_CHECK, settings.autoCheckEnabled)
                .apply()
        }
    }
}
