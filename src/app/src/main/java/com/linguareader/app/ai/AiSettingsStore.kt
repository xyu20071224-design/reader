package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.tts.CloudKeyStore
import org.json.JSONArray
import org.json.JSONObject

/** Persists AI settings in app-private SharedPreferences. */
class AiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun load(): AiSettings {
        val apiKey = loadSecret("api_key")
        val baseUrl = prefs.getString("base_url", DEFAULT_BASE_URL)
            .orEmpty()
            .ifBlank { DEFAULT_BASE_URL }
        val model = prefs.getString("model", "deepseek-chat")
            .orEmpty()
            .ifBlank { "deepseek-chat" }
        val providers = prefs.getString(PROVIDERS_KEY, null)
            ?.let(::decodeProviders)
            .orEmpty()
        val activeId = prefs.getString(ACTIVE_KEY, "").orEmpty()

        // 旧版只有单插槽字段（api_key/base_url/model）：首次加载时在内存里合成
        // 首个服务商，下次 save 落盘。迁移只此一条路，之后 providers 永远非空。
        val migrated = if (providers.isEmpty() && apiKey.isNotBlank()) {
            listOf(
                AiProviderProfile(
                    id = AiProviderProfile.DEFAULT_ID,
                    name = "DeepSeek",
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    protocol = AiProtocol.OPENAI_COMPAT,
                    model = model
                )
            )
        } else {
            providers
        }
        return AiSettings(
            enabled = prefs.getBoolean("enabled", false),
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            powerEnabled = prefs.getBoolean("power_enabled", true),
            providers = migrated,
            activeProviderId = when {
                migrated.isEmpty() -> ""
                migrated.any { it.id == activeId } -> activeId
                else -> migrated.first().id
            }
        )
    }

    fun save(settings: AiSettings) {
        // 镜像生效服务商回旧字段：所有只认 api_key/base_url/model 的读者
        // （仓库 gate、MultiVoiceSupport、降级安装）继续工作。
        val mirrored = settings.withActiveMirrored()
        prefs.edit()
            .putBoolean("enabled", mirrored.enabled)
            .putString("api_key", encryptOrNull(mirrored.apiKey))
            .putString("base_url", mirrored.baseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString("model", mirrored.model.trim().ifBlank { "deepseek-chat" })
            .putBoolean("power_enabled", mirrored.powerEnabled)
            .putString(PROVIDERS_KEY, encodeProviders(mirrored.providers))
            .putString(ACTIVE_KEY, mirrored.activeProviderId)
            .apply()
    }

    // --- providers ------------------------------------------------------------

    private fun encodeProviders(providers: List<AiProviderProfile>): String =
        JSONArray().apply {
            providers.forEach { provider ->
                put(
                    JSONObject()
                        .put("id", provider.id)
                        .put("name", provider.name)
                        .put("baseUrl", provider.baseUrl)
                        .put("apiKey", encryptOrNull(provider.apiKey).orEmpty())
                        .put("protocol", provider.protocol)
                        .put("model", provider.model)
                )
            }
        }.toString()

    /** 每个服务商的 Key 单独加密；解不开的旧值按明文迁移（同 [loadSecret] 的规则）。 */
    private fun decodeProviders(raw: String): List<AiProviderProfile> =
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val stored = entry.optString("apiKey")
                AiProviderProfile.fromJson(entry)?.copy(
                    apiKey = if (stored.isBlank()) ""
                    else CloudKeyStore.decrypt(appContext, stored) ?: stored
                )
            }
        }.getOrDefault(emptyList())

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

    private companion object {
        const val PROVIDERS_KEY = "providers_v1"
        const val ACTIVE_KEY = "active_provider_id"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
