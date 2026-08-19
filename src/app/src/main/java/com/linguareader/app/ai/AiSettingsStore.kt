package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.data.AppPrefs
import com.linguareader.app.tts.CloudKeyStore

/** Persists AI settings in app-private SharedPreferences. */
class AiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AppPrefs.get(context).ai

    fun load(): AiSettings = AiSettings(
        enabled = prefs.enabled,
        apiKey = loadSecret(prefs.apiKey),
        baseUrl = prefs.baseUrl.ifBlank { "https://api.deepseek.com" },
        model = prefs.model.ifBlank { "deepseek-chat" },
        azureTranslationEnabled = prefs.azureEnabled,
        azureKey = loadSecret(prefs.azureKey),
        azureRegion = prefs.azureRegion,
        azureEndpoint = prefs.azureEndpoint.ifBlank { "https://api.cognitive.microsofttranslator.com" },
        sourceLanguage = prefs.sourceLanguage.ifBlank { "en" },
        targetLanguage = prefs.targetLanguage.ifBlank { "zh-Hans" }
    )

    fun save(settings: AiSettings) {
        prefs.putEnabled(settings.enabled)
        prefs.putApiKey(encryptOrNull(settings.apiKey))
        prefs.putBaseUrl(settings.baseUrl.trim().ifBlank { "https://api.deepseek.com" })
        prefs.putModel(settings.model.trim().ifBlank { "deepseek-chat" })
        prefs.putAzureEnabled(settings.azureTranslationEnabled)
        prefs.putAzureKey(encryptOrNull(settings.azureKey))
        prefs.putAzureRegion(settings.azureRegion.trim())
        prefs.putAzureEndpoint(
            settings.azureEndpoint.trim().ifBlank { "https://api.cognitive.microsofttranslator.com" }
        )
        prefs.putSourceLanguage(settings.sourceLanguage.trim().ifBlank { "en" })
        prefs.putTargetLanguage(settings.targetLanguage.trim().ifBlank { "zh-Hans" })
        revision++
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
    private fun loadSecret(stored: String): String {
        if (stored.isBlank()) return ""
        return CloudKeyStore.decrypt(appContext, stored)
            ?: stored
    }

    /** Encrypts a credential, or returns null when it is blank (cleared). */
    private fun encryptOrNull(value: String): String? {
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) null else CloudKeyStore.encrypt(appContext, trimmed)
    }

    companion object {
        /**
         * Global save counter. [BookContextRepository]'s settings cache uses it
         * to detect saves from any [AiSettingsStore] instance and reload once.
         */
        @Volatile
        var revision: Long = 0L
            private set
    }
}
