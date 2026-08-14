package com.linguareader.app.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * TTS engine settings (cloud TTS configuration F-151 plus system voice
 * preferences).
 *
 * Cloud credentials (Azure API key, self-hosted token, Volcano API key /
 * access token) are encrypted with an AES-GCM key kept in the Android
 * Keystore; only the cipher text ever touches SharedPreferences.
 */
enum class TtsEngineMode {
    SYSTEM,
    AZURE,
    OPENAI_COMPAT,
    VOLC
}

data class CloudTtsSettings(
    val mode: TtsEngineMode = TtsEngineMode.SYSTEM,
    val region: String = DEFAULT_REGION,
    val apiKey: String = "",
    val enVoice: String = "",
    val zhVoice: String = "",
    val multilingualVoice: String = "",
    val useMultilingual: Boolean = true,
    val systemZhVoice: String = "",
    val systemEnVoice: String = "",
    val serverUrl: String = "",
    val serverModel: String = "tts-1",
    val serverToken: String = "",
    val serverVoice: String = "",
    val volcApiKey: String = "",
    val volcAppId: String = "",
    val volcToken: String = "",
    val volcResourceId: String = DEFAULT_VOLC_RESOURCE,
    val volcZhVoice: String = DEFAULT_VOLC_ZH_VOICE,
    val volcEnVoice: String = DEFAULT_VOLC_EN_VOICE
) {
    val enabled: Boolean get() = mode != TtsEngineMode.SYSTEM

    val isConfigured: Boolean
        get() = when (mode) {
            TtsEngineMode.SYSTEM -> true
            TtsEngineMode.AZURE -> region.isNotBlank() && apiKey.isNotBlank()
            TtsEngineMode.OPENAI_COMPAT -> serverUrl.isNotBlank()
            TtsEngineMode.VOLC ->
                volcApiKey.isNotBlank() ||
                    (volcAppId.isNotBlank() && volcToken.isNotBlank())
        }

    companion object {
        const val DEFAULT_REGION = "chinanorth3"
        const val DEFAULT_VOLC_RESOURCE = "seed-tts-2.0"
        const val DEFAULT_VOLC_ZH_VOICE = "zh_female_shuangkuaisisi_uranus_bigtts"
        const val DEFAULT_VOLC_EN_VOICE = "en_female_dacey_uranus_bigtts"
        private const val PREFS = "cloud_tts_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_REGION = "region"
        private const val KEY_API = "api_key"
        private const val KEY_EN_VOICE = "en_voice"
        private const val KEY_ZH_VOICE = "zh_voice"
        private const val KEY_MULTILINGUAL_VOICE = "multilingual_voice"
        private const val KEY_USE_MULTILINGUAL = "use_multilingual"
        private const val KEY_SYSTEM_ZH_VOICE = "system_zh_voice"
        private const val KEY_SYSTEM_EN_VOICE = "system_en_voice"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_MODEL = "server_model"
        private const val KEY_SERVER_TOKEN = "server_token"
        private const val KEY_SERVER_VOICE = "server_voice"
        private const val KEY_VOLC_API_KEY = "volc_api_key"
        private const val KEY_VOLC_APP_ID = "volc_app_id"
        private const val KEY_VOLC_TOKEN = "volc_token"
        private const val KEY_VOLC_RESOURCE = "volc_resource_id"
        private const val KEY_VOLC_ZH_VOICE = "volc_zh_voice"
        private const val KEY_VOLC_EN_VOICE = "volc_en_voice"

        fun load(context: Context): CloudTtsSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return CloudTtsSettings(
                mode = runCatching {
                    TtsEngineMode.valueOf(prefs.getString(KEY_MODE, null) ?: "")
                }.getOrDefault(TtsEngineMode.SYSTEM),
                region = prefs.getString(KEY_REGION, DEFAULT_REGION).orEmpty().ifBlank { DEFAULT_REGION },
                apiKey = CloudKeyStore.decrypt(context, prefs.getString(KEY_API, null)).orEmpty(),
                enVoice = prefs.getString(KEY_EN_VOICE, "").orEmpty(),
                zhVoice = prefs.getString(KEY_ZH_VOICE, "").orEmpty(),
                multilingualVoice = prefs.getString(KEY_MULTILINGUAL_VOICE, "").orEmpty(),
                useMultilingual = prefs.getBoolean(KEY_USE_MULTILINGUAL, true),
                systemZhVoice = prefs.getString(KEY_SYSTEM_ZH_VOICE, "").orEmpty(),
                systemEnVoice = prefs.getString(KEY_SYSTEM_EN_VOICE, "").orEmpty(),
                serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
                serverModel = prefs.getString(KEY_SERVER_MODEL, "tts-1").orEmpty().ifBlank { "tts-1" },
                serverToken = CloudKeyStore.decrypt(context, prefs.getString(KEY_SERVER_TOKEN, null)).orEmpty(),
                serverVoice = prefs.getString(KEY_SERVER_VOICE, "").orEmpty(),
                volcApiKey = CloudKeyStore.decrypt(context, prefs.getString(KEY_VOLC_API_KEY, null)).orEmpty(),
                volcAppId = prefs.getString(KEY_VOLC_APP_ID, "").orEmpty(),
                volcToken = CloudKeyStore.decrypt(context, prefs.getString(KEY_VOLC_TOKEN, null)).orEmpty(),
                volcResourceId = prefs.getString(KEY_VOLC_RESOURCE, DEFAULT_VOLC_RESOURCE)
                    .orEmpty().ifBlank { DEFAULT_VOLC_RESOURCE },
                volcZhVoice = prefs.getString(KEY_VOLC_ZH_VOICE, DEFAULT_VOLC_ZH_VOICE)
                    .orEmpty().ifBlank { DEFAULT_VOLC_ZH_VOICE },
                volcEnVoice = prefs.getString(KEY_VOLC_EN_VOICE, DEFAULT_VOLC_EN_VOICE)
                    .orEmpty().ifBlank { DEFAULT_VOLC_EN_VOICE }
            )
        }

        fun save(context: Context, settings: CloudTtsSettings) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encryptedKey = CloudKeyStore.encrypt(context, settings.apiKey)
            val encryptedToken = CloudKeyStore.encrypt(context, settings.serverToken)
            val encryptedVolcApiKey = CloudKeyStore.encrypt(context, settings.volcApiKey)
            val encryptedVolcToken = CloudKeyStore.encrypt(context, settings.volcToken)
            prefs.edit()
                .putString(KEY_MODE, settings.mode.name)
                .putString(KEY_REGION, settings.region.ifBlank { DEFAULT_REGION })
                .putString(KEY_API, encryptedKey)
                .putString(KEY_EN_VOICE, settings.enVoice)
                .putString(KEY_ZH_VOICE, settings.zhVoice)
                .putString(KEY_MULTILINGUAL_VOICE, settings.multilingualVoice)
                .putBoolean(KEY_USE_MULTILINGUAL, settings.useMultilingual)
                .putString(KEY_SYSTEM_ZH_VOICE, settings.systemZhVoice)
                .putString(KEY_SYSTEM_EN_VOICE, settings.systemEnVoice)
                .putString(KEY_SERVER_URL, settings.serverUrl.trim())
                .putString(KEY_SERVER_MODEL, settings.serverModel.ifBlank { "tts-1" })
                .putString(KEY_SERVER_TOKEN, encryptedToken)
                .putString(KEY_SERVER_VOICE, settings.serverVoice)
                .putString(KEY_VOLC_API_KEY, encryptedVolcApiKey)
                .putString(KEY_VOLC_APP_ID, settings.volcAppId.trim())
                .putString(KEY_VOLC_TOKEN, encryptedVolcToken)
                .putString(KEY_VOLC_RESOURCE, settings.volcResourceId.ifBlank { DEFAULT_VOLC_RESOURCE })
                .putString(KEY_VOLC_ZH_VOICE, settings.volcZhVoice.ifBlank { DEFAULT_VOLC_ZH_VOICE })
                .putString(KEY_VOLC_EN_VOICE, settings.volcEnVoice.ifBlank { DEFAULT_VOLC_EN_VOICE })
                .apply()
        }
    }
}

/** AES-GCM encryption backed by the Android Keystore. */
object CloudKeyStore {
    private const val ALIAS = "lingua_reader_cloud_tts_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun encrypt(context: Context, plain: String): String? {
        if (plain.isBlank()) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            Base64.encodeToString(iv + bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

    fun decrypt(context: Context, encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            // A key shorter than the GCM IV (12 bytes) is not a valid encrypted
            // value; bail out early instead of letting copyOfRange throw inside
            // runCatching, which would be indistinguishable from "no key set".
            if (decoded.size < IV_SIZE) return@runCatching null
            val iv = decoded.copyOfRange(0, IV_SIZE)
            val cipherText = decoded.copyOfRange(IV_SIZE, decoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    // Lazy ensures the key is generated at most once per process. `lazy` default
    // mode (LazyThreadSafetyMode.SYNCHRONIZED) serializes concurrent first calls,
    // so two threads can no longer both see a missing alias and generate two
    // keys that clobber each other and make previously written ciphertext
    // permanently undecryptable (silent API-key loss).
    private val key: SecretKey by lazy { generateKey() }

    private fun generateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
