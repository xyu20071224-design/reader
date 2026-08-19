package com.linguareader.app.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.linguareader.app.data.AppPrefs
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
    PIPER,
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
            TtsEngineMode.PIPER -> true // models are bundled in assets, no config needed
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

        fun load(context: Context): CloudTtsSettings {
            val p = AppPrefs.get(context).cloudTts
            return CloudTtsSettings(
                mode = runCatching {
                    TtsEngineMode.valueOf(p.mode ?: "")
                }.getOrDefault(TtsEngineMode.SYSTEM),
                region = p.region.ifBlank { DEFAULT_REGION },
                apiKey = CloudKeyStore.decrypt(context, p.apiKey).orEmpty(),
                enVoice = p.enVoice,
                zhVoice = p.zhVoice,
                multilingualVoice = p.multilingualVoice,
                useMultilingual = p.useMultilingual,
                systemZhVoice = p.systemZhVoice,
                systemEnVoice = p.systemEnVoice,
                serverUrl = p.serverUrl,
                serverModel = p.serverModel.ifBlank { "tts-1" },
                serverToken = CloudKeyStore.decrypt(context, p.serverToken).orEmpty(),
                serverVoice = p.serverVoice,
                volcApiKey = CloudKeyStore.decrypt(context, p.volcApiKey).orEmpty(),
                volcAppId = p.volcAppId,
                volcToken = CloudKeyStore.decrypt(context, p.volcToken).orEmpty(),
                volcResourceId = p.volcResourceId.ifBlank { DEFAULT_VOLC_RESOURCE },
                volcZhVoice = p.volcZhVoice.ifBlank { DEFAULT_VOLC_ZH_VOICE },
                volcEnVoice = p.volcEnVoice.ifBlank { DEFAULT_VOLC_EN_VOICE }
            )
        }

        fun save(context: Context, settings: CloudTtsSettings) {
            val p = AppPrefs.get(context).cloudTts
            val encryptedKey = CloudKeyStore.encrypt(context, settings.apiKey)
            val encryptedToken = CloudKeyStore.encrypt(context, settings.serverToken)
            val encryptedVolcApiKey = CloudKeyStore.encrypt(context, settings.volcApiKey)
            val encryptedVolcToken = CloudKeyStore.encrypt(context, settings.volcToken)
            p.putMode(settings.mode.name)
            p.putRegion(settings.region.ifBlank { DEFAULT_REGION })
            p.putApiKey(encryptedKey)
            p.putEnVoice(settings.enVoice)
            p.putZhVoice(settings.zhVoice)
            p.putMultilingualVoice(settings.multilingualVoice)
            p.putUseMultilingual(settings.useMultilingual)
            p.putSystemZhVoice(settings.systemZhVoice)
            p.putSystemEnVoice(settings.systemEnVoice)
            p.putServerUrl(settings.serverUrl.trim())
            p.putServerModel(settings.serverModel.ifBlank { "tts-1" })
            p.putServerToken(encryptedToken)
            p.putServerVoice(settings.serverVoice)
            p.putVolcApiKey(encryptedVolcApiKey)
            p.putVolcAppId(settings.volcAppId.trim())
            p.putVolcToken(encryptedVolcToken)
            p.putVolcResourceId(settings.volcResourceId.ifBlank { DEFAULT_VOLC_RESOURCE })
            p.putVolcZhVoice(settings.volcZhVoice.ifBlank { DEFAULT_VOLC_ZH_VOICE })
            p.putVolcEnVoice(settings.volcEnVoice.ifBlank { DEFAULT_VOLC_EN_VOICE })
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
