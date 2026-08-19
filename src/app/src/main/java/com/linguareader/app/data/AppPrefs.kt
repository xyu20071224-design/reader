package com.linguareader.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用内所有 SharedPreferences 访问的唯一入口。
 *
 * 分层约定：
 * - UI 层（ReaderScreen 等）禁止直接调用 [Context.getSharedPreferences]；
 * - 各域 store（AiSettingsStore / ReviewReminder / CloudTtsSettings / CloudVoiceStore 等）
 *   一律通过本类的命名 section 读写；
 * - 敏感字段（api_key / azure_key / server_token / volc_* 等）的加解密仍在各 store 内
 *   通过 CloudKeyStore 完成，本类只负责裸字符串的存取。
 *
 * 新增偏好键必须在此登记（见 [Keys]），键名变更需在 commit message / 规约中说明旧数据迁移策略。
 *
 * 动态键登记：
 * - review_notifications 文件（[ReviewNotificationsSection]）使用 `yyyy-MM-dd` 格式的
 *   日期字符串作为键（由 ReviewReminderPolicy.dayKey 生成），记录当日已发送的复习提醒
 *   次数。这类键以运行时日期动态生成，无法在 [Keys] 中穷举，仅在此登记其语义。
 */
class AppPrefs private constructor(
    context: Context,
) {
    val ai = AiSection(open(context, PrefsNames.AI_SETTINGS))
    val reader = ReaderSection(open(context, PrefsNames.READER_PREFERENCES))
    val review = ReviewSection(open(context, PrefsNames.REVIEW_SETTINGS))
    val launch = LaunchSection(open(context, PrefsNames.LAUNCH_PROMO))
    val cloudTts = CloudTtsSection(open(context, PrefsNames.CLOUD_TTS_SETTINGS))
    val cloudTtsVoices = CloudTtsVoicesSection(open(context, PrefsNames.CLOUD_TTS_VOICES))
    val reviewNotifications = ReviewNotificationsSection(open(context, PrefsNames.REVIEW_NOTIFICATIONS))

    /** 各域偏好键常量，供迁移、调试与测试使用。 */
    object Keys {
        const val AI_ENABLED = "enabled"
        const val AI_API_KEY = "api_key"
        const val AI_BASE_URL = "base_url"
        const val AI_MODEL = "model"
        const val AI_AZURE_ENABLED = "azure_enabled"
        const val AI_AZURE_KEY = "azure_key"
        const val AI_AZURE_REGION = "azure_region"
        const val AI_AZURE_ENDPOINT = "azure_endpoint"
        const val AI_SOURCE_LANGUAGE = "source_language"
        const val AI_TARGET_LANGUAGE = "target_language"

        const val READER_FONT_PERCENT = "fontPercent"
        const val READER_LINE_HEIGHT = "lineHeight"
        const val READER_THEME = "theme"
        const val READER_FONT = "font"

        const val LAUNCH_LAST_SEEN_VERSION = "last_seen_version"

        const val TTS_MODE = "mode"
        const val TTS_REGION = "region"
        const val TTS_API_KEY = "api_key"
        const val TTS_EN_VOICE = "en_voice"
        const val TTS_ZH_VOICE = "zh_voice"
        const val TTS_MULTILINGUAL_VOICE = "multilingual_voice"
        const val TTS_USE_MULTILINGUAL = "use_multilingual"
        const val TTS_SYSTEM_ZH_VOICE = "system_zh_voice"
        const val TTS_SYSTEM_EN_VOICE = "system_en_voice"
        const val TTS_SERVER_URL = "server_url"
        const val TTS_SERVER_MODEL = "server_model"
        const val TTS_SERVER_TOKEN = "server_token"
        const val TTS_SERVER_VOICE = "server_voice"
        const val TTS_VOLC_API_KEY = "volc_api_key"
        const val TTS_VOLC_APP_ID = "volc_app_id"
        const val TTS_VOLC_TOKEN = "volc_token"
        const val TTS_VOLC_RESOURCE = "volc_resource_id"
        const val TTS_VOLC_ZH_VOICE = "volc_zh_voice"
        const val TTS_VOLC_EN_VOICE = "volc_en_voice"
        const val TTS_VOICES = "voices"

        const val REVIEW_MODE = "review_mode"
        const val REVIEW_MODE_CUSTOM = "review_mode_custom"
        const val REVIEW_REMINDERS = "review_reminders"
    }

    /** SharedPreferences 文件名常量。 */
    object PrefsNames {
        const val AI_SETTINGS = "ai_settings"
        const val READER_PREFERENCES = "reader_preferences"
        const val REVIEW_SETTINGS = "review_settings"
        const val REVIEW_NOTIFICATIONS = "review_notifications"
        const val LAUNCH_PROMO = "launch_promo"
        const val CLOUD_TTS_SETTINGS = "cloud_tts_settings"
        const val CLOUD_TTS_VOICES = "cloud_tts_voices"
    }

    companion object {
        @Volatile
        private var instance: AppPrefs? = null

        /** 进程级单例；所有调用方共享同一份偏好文件句柄。 */
        fun get(context: Context): AppPrefs = instance ?: synchronized(this) {
            instance ?: AppPrefs(context.applicationContext).also { instance = it }
        }

        /** 按名称打开指定偏好文件（供迁移/调试）；常规使用请走 [get] 的命名 section。 */
        fun open(context: Context, name: String): SharedPreferences =
            context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    class AiSection(prefs: SharedPreferences) {
        private val p = prefs
        val enabled: Boolean get() = p.getBoolean(Keys.AI_ENABLED, false)
        val apiKey: String get() = p.getString(Keys.AI_API_KEY, null).orEmpty()
        val baseUrl: String get() = p.getString(Keys.AI_BASE_URL, "https://api.deepseek.com")!!
        val model: String get() = p.getString(Keys.AI_MODEL, "deepseek-chat")!!
        val azureEnabled: Boolean get() = p.getBoolean(Keys.AI_AZURE_ENABLED, false)
        val azureKey: String get() = p.getString(Keys.AI_AZURE_KEY, null).orEmpty()
        val azureRegion: String get() = p.getString(Keys.AI_AZURE_REGION, "").orEmpty()
        val azureEndpoint: String get() =
            p.getString(Keys.AI_AZURE_ENDPOINT, "https://api.cognitive.microsofttranslator.com")!!
        val sourceLanguage: String get() = p.getString(Keys.AI_SOURCE_LANGUAGE, "en").orEmpty()
        val targetLanguage: String get() = p.getString(Keys.AI_TARGET_LANGUAGE, "zh-Hans").orEmpty()

        fun putEnabled(v: Boolean) = p.edit().putBoolean(Keys.AI_ENABLED, v).apply()
        fun putApiKey(v: String?) = p.edit().putString(Keys.AI_API_KEY, v).apply()
        fun putBaseUrl(v: String) = p.edit().putString(Keys.AI_BASE_URL, v).apply()
        fun putModel(v: String) = p.edit().putString(Keys.AI_MODEL, v).apply()
        fun putAzureEnabled(v: Boolean) = p.edit().putBoolean(Keys.AI_AZURE_ENABLED, v).apply()
        fun putAzureKey(v: String?) = p.edit().putString(Keys.AI_AZURE_KEY, v).apply()
        fun putAzureRegion(v: String) = p.edit().putString(Keys.AI_AZURE_REGION, v).apply()
        fun putAzureEndpoint(v: String) = p.edit().putString(Keys.AI_AZURE_ENDPOINT, v).apply()
        fun putSourceLanguage(v: String) = p.edit().putString(Keys.AI_SOURCE_LANGUAGE, v).apply()
        fun putTargetLanguage(v: String) = p.edit().putString(Keys.AI_TARGET_LANGUAGE, v).apply()
    }

    class ReaderSection(prefs: SharedPreferences) {
        private val p = prefs
        val fontPercent: Int get() = p.getInt(Keys.READER_FONT_PERCENT, 100)
        val lineHeight: Float get() = p.getFloat(Keys.READER_LINE_HEIGHT, 1.65f)
        val theme: String? get() = p.getString(Keys.READER_THEME, null)
        val font: String? get() = p.getString(Keys.READER_FONT, null)

        fun putFontPercent(v: Int) = p.edit().putInt(Keys.READER_FONT_PERCENT, v).apply()
        fun putLineHeight(v: Float) = p.edit().putFloat(Keys.READER_LINE_HEIGHT, v).apply()
        fun putTheme(v: String) = p.edit().putString(Keys.READER_THEME, v).apply()
        fun putFont(v: String) = p.edit().putString(Keys.READER_FONT, v).apply()
    }

    class ReviewSection(prefs: SharedPreferences) {
        private val p = prefs

        /** 复习节奏/提醒等 review_settings 文件的通用读写；具体语义由调用方决定。 */
        fun string(key: String, def: String? = null): String? = p.getString(key, def)
        fun int(key: String, def: Int = 0): Int = p.getInt(key, def)
        fun boolean(key: String, def: Boolean = false): Boolean = p.getBoolean(key, def)
        fun putString(key: String, v: String?) = p.edit().putString(key, v).apply()
        fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
        fun putBoolean(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    }

    class LaunchSection(prefs: SharedPreferences) {
        private val p = prefs
        val lastSeenVersion: Int get() = p.getInt(Keys.LAUNCH_LAST_SEEN_VERSION, 0)
        fun putLastSeenVersion(v: Int) = p.edit().putInt(Keys.LAUNCH_LAST_SEEN_VERSION, v).apply()
    }

    class CloudTtsSection(prefs: SharedPreferences) {
        private val p = prefs
        val mode: String? get() = p.getString(Keys.TTS_MODE, null)
        val region: String get() = p.getString(Keys.TTS_REGION, "chinanorth3")!!
        val apiKey: String? get() = p.getString(Keys.TTS_API_KEY, null)
        val enVoice: String get() = p.getString(Keys.TTS_EN_VOICE, "").orEmpty()
        val zhVoice: String get() = p.getString(Keys.TTS_ZH_VOICE, "").orEmpty()
        val multilingualVoice: String get() = p.getString(Keys.TTS_MULTILINGUAL_VOICE, "").orEmpty()
        val useMultilingual: Boolean get() = p.getBoolean(Keys.TTS_USE_MULTILINGUAL, true)
        val systemZhVoice: String get() = p.getString(Keys.TTS_SYSTEM_ZH_VOICE, "").orEmpty()
        val systemEnVoice: String get() = p.getString(Keys.TTS_SYSTEM_EN_VOICE, "").orEmpty()
        val serverUrl: String get() = p.getString(Keys.TTS_SERVER_URL, "").orEmpty()
        val serverModel: String get() = p.getString(Keys.TTS_SERVER_MODEL, "tts-1").orEmpty()
        val serverToken: String? get() = p.getString(Keys.TTS_SERVER_TOKEN, null)
        val serverVoice: String get() = p.getString(Keys.TTS_SERVER_VOICE, "").orEmpty()
        val volcApiKey: String? get() = p.getString(Keys.TTS_VOLC_API_KEY, null)
        val volcAppId: String get() = p.getString(Keys.TTS_VOLC_APP_ID, "").orEmpty()
        val volcToken: String? get() = p.getString(Keys.TTS_VOLC_TOKEN, null)
        val volcResourceId: String get() = p.getString(Keys.TTS_VOLC_RESOURCE, "seed-tts-2.0")!!
        val volcZhVoice: String get() =
            p.getString(Keys.TTS_VOLC_ZH_VOICE, "zh_female_shuangkuaisisi_uranus_bigtts")!!
        val volcEnVoice: String get() =
            p.getString(Keys.TTS_VOLC_EN_VOICE, "en_female_dacey_uranus_bigtts")!!

        fun putMode(v: String) = p.edit().putString(Keys.TTS_MODE, v).apply()
        fun putRegion(v: String) = p.edit().putString(Keys.TTS_REGION, v).apply()
        fun putApiKey(v: String?) = p.edit().putString(Keys.TTS_API_KEY, v).apply()
        fun putEnVoice(v: String) = p.edit().putString(Keys.TTS_EN_VOICE, v).apply()
        fun putZhVoice(v: String) = p.edit().putString(Keys.TTS_ZH_VOICE, v).apply()
        fun putMultilingualVoice(v: String) = p.edit().putString(Keys.TTS_MULTILINGUAL_VOICE, v).apply()
        fun putUseMultilingual(v: Boolean) = p.edit().putBoolean(Keys.TTS_USE_MULTILINGUAL, v).apply()
        fun putSystemZhVoice(v: String) = p.edit().putString(Keys.TTS_SYSTEM_ZH_VOICE, v).apply()
        fun putSystemEnVoice(v: String) = p.edit().putString(Keys.TTS_SYSTEM_EN_VOICE, v).apply()
        fun putServerUrl(v: String) = p.edit().putString(Keys.TTS_SERVER_URL, v).apply()
        fun putServerModel(v: String) = p.edit().putString(Keys.TTS_SERVER_MODEL, v).apply()
        fun putServerToken(v: String?) = p.edit().putString(Keys.TTS_SERVER_TOKEN, v).apply()
        fun putServerVoice(v: String) = p.edit().putString(Keys.TTS_SERVER_VOICE, v).apply()
        fun putVolcApiKey(v: String?) = p.edit().putString(Keys.TTS_VOLC_API_KEY, v).apply()
        fun putVolcAppId(v: String) = p.edit().putString(Keys.TTS_VOLC_APP_ID, v).apply()
        fun putVolcToken(v: String?) = p.edit().putString(Keys.TTS_VOLC_TOKEN, v).apply()
        fun putVolcResourceId(v: String) = p.edit().putString(Keys.TTS_VOLC_RESOURCE, v).apply()
        fun putVolcZhVoice(v: String) = p.edit().putString(Keys.TTS_VOLC_ZH_VOICE, v).apply()
        fun putVolcEnVoice(v: String) = p.edit().putString(Keys.TTS_VOLC_EN_VOICE, v).apply()
    }

    class CloudTtsVoicesSection(prefs: SharedPreferences) {
        private val p = prefs
        val voices: String? get() = p.getString(Keys.TTS_VOICES, null)
        fun putVoices(v: String?) = p.edit().putString(Keys.TTS_VOICES, v).apply()
    }

    /**
     * review_notifications 文件的读写。键为动态生成的 `yyyy-MM-dd` 日期串
     * （见 ReviewReminderPolicy.dayKey），值为当日已发送提醒次数；无固定静态键。
     */
    class ReviewNotificationsSection(prefs: SharedPreferences) {
        private val p = prefs
        fun int(key: String, def: Int = 0): Int = p.getInt(key, def)
        fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
    }
}
