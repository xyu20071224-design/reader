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
 * Cloud credentials (self-hosted token, MiMo API key) are encrypted with an
 * AES-GCM key kept in the Android Keystore; only the cipher text ever touches
 * SharedPreferences.
 */
enum class TtsEngineMode {
    SYSTEM,
    OPENAI_COMPAT,
    /** 小米 MiMo-V2.5-TTS 系列（API 文档 2026-07）：OpenAI 兼容
     *  `chat/completions` 形态，鉴权头 `api-key`，音频以 base64 返回。 */
    MIMO
}

data class CloudTtsSettings(
    val mode: TtsEngineMode = TtsEngineMode.SYSTEM,
    /** Master switch for networked TTS (OpenAI-compatible / MiMo). */
    val networkAiEnabled: Boolean = true,
    val systemZhVoice: String = "",
    val systemEnVoice: String = "",
    val serverUrl: String = "",
    val serverModel: String = "tts-1",
    val serverToken: String = "",
    val serverVoice: String = "",
    /**
     * Self-hosted engine, per-language voices (M1.5 结论：IndexTTS 的英文与中文
     * 各用一个参考音色）。Blank falls back to [serverVoice], which keeps the
     * single-voice behaviour of earlier builds.
     */
    val serverEnVoice: String = "",
    val serverZhVoice: String = "",
    /** Multi-voice M1: voice used for narration sentences (empty = off,
     *  follows [serverVoice] / engine default). */
    val narratorVoice: String = "",
    /** Multi-voice M1: voice used for dialogue sentences (empty = off). */
    val dialogueVoice: String = "",
    /**
     * Multi-voice M4 master switch (default off, PLAN-MULTI-VOICE §8.1).
     *
     * When off nothing multi-voice runs: no speaker tagging requests, no voice
     * assignment, and only the manual [narratorVoice] / [dialogueVoice] apply.
     * D2: only the cloud engines can honour it.
     */
    val multiVoiceEnabled: Boolean = false,
    /** MiMo 云 TTS：API Key（Keystore 加密）。 */
    val mimoApiKey: String = "",
    /** MiMo 预置模型；voice id 为预置音色时使用它（design/clone 各自固定模型）。 */
    val mimoModel: String = DEFAULT_MIMO_MODEL,
    /** MiMo 中文预置音色（默认 mimo_default，中国大陆集群=冰糖）。 */
    val mimoZhVoice: String = DEFAULT_MIMO_ZH_VOICE,
    /** MiMo 英文预置音色。 */
    val mimoEnVoice: String = DEFAULT_MIMO_EN_VOICE,
    /** MiMo 自然语言风格指令（可选，放 user 消息；预置/复刻模型生效）。 */
    val mimoStyleInstruction: String = "",
    /**
     * 音频缓存上限（MB），**0 = 不限**。
     *
     * 缓存在 filesDir 而非 cacheDir，系统低存储回收够不着它；此前无上限、无淘汰、
     * 无清理入口，长期听书会无界增长。保留「不限」是因为离线听书是核心场景 ——
     * 硬上限会伤到「出门前缓存整本」的用法。
     */
    val cacheLimitMb: Int = DEFAULT_CACHE_LIMIT_MB
) {
    val enabled: Boolean get() = mode != TtsEngineMode.SYSTEM

    val isConfigured: Boolean
        get() = when (mode) {
            TtsEngineMode.SYSTEM -> true
            TtsEngineMode.OPENAI_COMPAT -> serverUrl.isNotBlank()
            TtsEngineMode.MIMO -> mimoApiKey.isNotBlank()
        }

    companion object {
        const val MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"
        const val DEFAULT_MIMO_MODEL = "mimo-v2.5-tts"
        const val DEFAULT_MIMO_ZH_VOICE = "mimo_default"
        const val DEFAULT_MIMO_EN_VOICE = "Mia"

        /** 默认缓存上限：约 10–25 本整书缓存的量级（实测占用以「存储占用」页面为准）。 */
        const val DEFAULT_CACHE_LIMIT_MB = 512

        /** 设置页可选的上限档位；0 = 不限。 */
        val CACHE_LIMIT_OPTIONS_MB = listOf(256, 512, 1024, 0)
        private const val PREFS = "cloud_tts_settings"
        private const val KEY_MODE = "mode"
        private const val KEY_NETWORK_AI_ENABLED = "network_ai_enabled"
        private const val KEY_SYSTEM_ZH_VOICE = "system_zh_voice"
        private const val KEY_SYSTEM_EN_VOICE = "system_en_voice"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_MODEL = "server_model"
        private const val KEY_SERVER_TOKEN = "server_token"
        private const val KEY_SERVER_VOICE = "server_voice"
        private const val KEY_SERVER_EN_VOICE = "server_en_voice"
        private const val KEY_SERVER_ZH_VOICE = "server_zh_voice"
        private const val KEY_NARRATOR_VOICE = "narrator_voice"
        private const val KEY_DIALOGUE_VOICE = "dialogue_voice"
        private const val KEY_MULTI_VOICE = "multi_voice_enabled"
        private const val KEY_MIMO_API_KEY = "mimo_api_key"
        private const val KEY_MIMO_MODEL = "mimo_model"
        private const val KEY_MIMO_ZH_VOICE = "mimo_zh_voice"
        private const val KEY_MIMO_EN_VOICE = "mimo_en_voice"
        private const val KEY_MIMO_STYLE = "mimo_style_instruction"
        private const val KEY_CACHE_LIMIT_MB = "cache_limit_mb"

        fun load(context: Context): CloudTtsSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return CloudTtsSettings(
                mode = runCatching {
                    TtsEngineMode.valueOf(prefs.getString(KEY_MODE, null) ?: "")
                }.getOrDefault(TtsEngineMode.SYSTEM),
                networkAiEnabled = prefs.getBoolean(KEY_NETWORK_AI_ENABLED, true),
                systemZhVoice = prefs.getString(KEY_SYSTEM_ZH_VOICE, "").orEmpty(),
                systemEnVoice = prefs.getString(KEY_SYSTEM_EN_VOICE, "").orEmpty(),
                serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty(),
                serverModel = prefs.getString(KEY_SERVER_MODEL, "tts-1").orEmpty().ifBlank { "tts-1" },
                serverToken = CloudKeyStore.decrypt(context, prefs.getString(KEY_SERVER_TOKEN, null)).orEmpty(),
                serverVoice = prefs.getString(KEY_SERVER_VOICE, "").orEmpty(),
                serverEnVoice = prefs.getString(KEY_SERVER_EN_VOICE, "").orEmpty(),
                serverZhVoice = prefs.getString(KEY_SERVER_ZH_VOICE, "").orEmpty(),
                narratorVoice = prefs.getString(KEY_NARRATOR_VOICE, "").orEmpty(),
                dialogueVoice = prefs.getString(KEY_DIALOGUE_VOICE, "").orEmpty(),
                multiVoiceEnabled = prefs.getBoolean(KEY_MULTI_VOICE, false),
                mimoApiKey = CloudKeyStore.decrypt(context, prefs.getString(KEY_MIMO_API_KEY, null)).orEmpty(),
                mimoModel = prefs.getString(KEY_MIMO_MODEL, DEFAULT_MIMO_MODEL)
                    .orEmpty().ifBlank { DEFAULT_MIMO_MODEL },
                mimoZhVoice = prefs.getString(KEY_MIMO_ZH_VOICE, DEFAULT_MIMO_ZH_VOICE)
                    .orEmpty().ifBlank { DEFAULT_MIMO_ZH_VOICE },
                mimoEnVoice = prefs.getString(KEY_MIMO_EN_VOICE, DEFAULT_MIMO_EN_VOICE)
                    .orEmpty().ifBlank { DEFAULT_MIMO_EN_VOICE },
                mimoStyleInstruction = prefs.getString(KEY_MIMO_STYLE, "").orEmpty(),
                cacheLimitMb = prefs.getInt(KEY_CACHE_LIMIT_MB, DEFAULT_CACHE_LIMIT_MB)
            )
        }

        fun save(context: Context, settings: CloudTtsSettings) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encryptedToken = CloudKeyStore.encrypt(context, settings.serverToken)
            val encryptedMimoApiKey = CloudKeyStore.encrypt(context, settings.mimoApiKey)
            prefs.edit()
                .putString(KEY_MODE, settings.mode.name)
                .putBoolean(KEY_NETWORK_AI_ENABLED, settings.networkAiEnabled)
                .putString(KEY_SYSTEM_ZH_VOICE, settings.systemZhVoice)
                .putString(KEY_SYSTEM_EN_VOICE, settings.systemEnVoice)
                .putString(KEY_SERVER_URL, settings.serverUrl.trim())
                .putString(KEY_SERVER_MODEL, settings.serverModel.ifBlank { "tts-1" })
                .putString(KEY_SERVER_TOKEN, encryptedToken)
                .putString(KEY_SERVER_VOICE, settings.serverVoice)
                .putString(KEY_SERVER_EN_VOICE, settings.serverEnVoice.trim())
                .putString(KEY_SERVER_ZH_VOICE, settings.serverZhVoice.trim())
                .putString(KEY_NARRATOR_VOICE, settings.narratorVoice)
                .putString(KEY_DIALOGUE_VOICE, settings.dialogueVoice)
                .putBoolean(KEY_MULTI_VOICE, settings.multiVoiceEnabled)
                .putString(KEY_MIMO_API_KEY, encryptedMimoApiKey)
                .putString(KEY_MIMO_MODEL, settings.mimoModel.ifBlank { DEFAULT_MIMO_MODEL })
                .putString(KEY_MIMO_ZH_VOICE, settings.mimoZhVoice.ifBlank { DEFAULT_MIMO_ZH_VOICE })
                .putString(KEY_MIMO_EN_VOICE, settings.mimoEnVoice.ifBlank { DEFAULT_MIMO_EN_VOICE })
                .putString(KEY_MIMO_STYLE, settings.mimoStyleInstruction.trim())
                .putInt(KEY_CACHE_LIMIT_MB, settings.cacheLimitMb.coerceAtLeast(0))
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
