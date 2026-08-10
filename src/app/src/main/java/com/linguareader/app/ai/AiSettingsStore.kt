package com.linguareader.app.ai

import android.content.Context

/** Persists AI settings in app-private SharedPreferences. */
class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", false),
        apiKey = prefs.getString("api_key", "").orEmpty(),
        baseUrl = prefs.getString("base_url", "https://api.deepseek.com")
            .orEmpty()
            .ifBlank { "https://api.deepseek.com" },
        model = prefs.getString("model", "deepseek-chat")
            .orEmpty()
            .ifBlank { "deepseek-chat" },
        azureTranslationEnabled = prefs.getBoolean("azure_enabled", false),
        azureKey = prefs.getString("azure_key", "").orEmpty(),
        azureRegion = prefs.getString("azure_region", "").orEmpty(),
        azureEndpoint = prefs.getString("azure_endpoint", "https://api.cognitive.microsofttranslator.com")
            .orEmpty()
            .ifBlank { "https://api.cognitive.microsofttranslator.com" }
    )

    fun save(settings: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("api_key", settings.apiKey.trim())
            .putString("base_url", settings.baseUrl.trim().ifBlank { "https://api.deepseek.com" })
            .putString("model", settings.model.trim().ifBlank { "deepseek-chat" })
            .putBoolean("azure_enabled", settings.azureTranslationEnabled)
            .putString("azure_key", settings.azureKey.trim())
            .putString("azure_region", settings.azureRegion.trim())
            .putString(
                "azure_endpoint",
                settings.azureEndpoint.trim().ifBlank { "https://api.cognitive.microsofttranslator.com" }
            )
            .apply()
    }
}
