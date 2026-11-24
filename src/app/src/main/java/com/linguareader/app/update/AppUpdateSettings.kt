package com.linguareader.app.update

import android.content.Context

/**
 * 自动更新设置。隐私边界：自动检查是出网行为，出厂默认关闭，由用户显式打开。
 */
data class AppUpdateSettings(
    /** 启动时自动检查更新；默认 false（离线优先承诺）。 */
    val autoCheckEnabled: Boolean = false,
    /** null = GitHub; a mirror is used only after the user explicitly chooses its HTTPS catalog. */
    val catalogUrl: String? = null
) {
    companion object {
        private const val PREFS = "update_settings"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_CATALOG = "catalog_url"

        fun load(context: Context): AppUpdateSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppUpdateSettings(
                autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK, false),
                catalogUrl = prefs.getString(KEY_CATALOG, null)?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        fun save(context: Context, settings: AppUpdateSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_CHECK, settings.autoCheckEnabled)
                .putString(KEY_CATALOG, settings.catalogUrl)
                .apply()
        }
    }
}
