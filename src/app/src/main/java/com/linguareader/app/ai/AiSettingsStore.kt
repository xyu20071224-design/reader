package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.tts.CloudKeyStore

/** Persists AI settings in app-private SharedPreferences. */
class AiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", false),
        apiKey = loadSecret("api_key"),
        baseUrl = prefs.getString("base_url", "https://api.deepseek.com")
            .orEmpty()
            .ifBlank { "https://api.deepseek.com" },
        model = prefs.getString("model", "deepseek-chat")
            .orEmpty()
            .ifBlank { "deepseek-chat" },
        azureTranslationEnabled = prefs.getBoolean("azure_enabled", false),
        azureKey = loadSecret("azure_key"),
        azureRegion = prefs.getString("azure_region", "").orEmpty(),
        azureEndpoint = prefs.getString("azure_endpoint", "https://api.cognitive.microsofttranslator.com")
            .orEmpty()
            .ifBlank { "https://api.cognitive.microsofttranslator.com" },
        powerEnabled = prefs.getBoolean("power_enabled", true)
    )

    fun save(settings: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("api_key", encryptOrNull(settings.apiKey))
            .putString("base_url", settings.baseUrl.trim().ifBlank { "https://api.deepseek.com" })
            .putString("model", settings.model.trim().ifBlank { "deepseek-chat" })
            .putBoolean("azure_enabled", settings.azureTranslationEnabled)
            .putString("azure_key", encryptOrNull(settings.azureKey))
            .putString("azure_region", settings.azureRegion.trim())
            .putString(
                "azure_endpoint",
                settings.azureEndpoint.trim().ifBlank { "https://api.cognitive.microsofttranslator.com" }
            )
            .putBoolean("power_enabled", settings.powerEnabled)
            .apply()
    }

    /**
     * Reads a credential, preferring the encrypted form and transparently
     * migrating a legacy plaintext value.
     *
     * [CloudKeyStore.decrypt] returns null either when the stored value is
     * already null/blank or when the value is not valid cipher text. In the
     * latter case the value is a plaintext key left by an older build, so we
     * fall back to returning it verbatim (the next [save] re-encrypts it) to
     * avoid losing the key on upgrade. A null result signals "no value" and
     * maps to "".
     */
    private fun loadSecret(key: String): String {
        val stored = prefs.getString(key, null)
        if (stored.isNullOrBlank()) return ""
        return CloudKeyStore.decrypt(appContext, stored)
            ?: stored
    }

    /** Encrypts a credential, or returns null when it is blank (cleared). */
    private fun encryptOrNull(value: String): String? {
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) null else CloudKeyStore.encrypt(appContext, trimmed)
    }
}
