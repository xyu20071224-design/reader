package com.linguareader.shared.sync

import com.linguareader.shared.app.PreferencesStore
import org.json.JSONObject

/** 同步的用户可见设置。令牌**不在**这里，走 [SecretStore]。 */
data class SyncSettings(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    /** 自签证书的 SHA-256 指纹（支持 openssl 的 AA:BB:… 形式）。 */
    val pinnedCertSha256: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("serverUrl", serverUrl)
        .put("username", username)
        .put("pinnedCertSha256", pinnedCertSha256)

    companion object {
        const val PREFS_NAME = "sync_settings"
        private const val KEY = "settings"

        fun fromJson(json: JSONObject): SyncSettings = SyncSettings(
            enabled = json.optBoolean("enabled"),
            serverUrl = json.optString("serverUrl"),
            username = json.optString("username"),
            pinnedCertSha256 = json.optString("pinnedCertSha256")
        )

        fun load(prefs: PreferencesStore): SyncSettings {
            val raw = prefs.getString(KEY) ?: return SyncSettings()
            return runCatching { fromJson(JSONObject(raw)) }.getOrElse { SyncSettings() }
        }

        fun save(prefs: PreferencesStore, settings: SyncSettings) {
            prefs.putString(KEY, settings.toJson().toString())
        }
    }
}
