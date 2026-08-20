package com.linguareader.app.tts

import org.json.JSONArray
import org.json.JSONObject

/** One voice returned by the Azure Speech `voices/list` endpoint. */
data class AzureVoice(
    val shortName: String,
    val locale: String,
    val gender: String,
    val displayName: String,
    val localeName: String = "",
    val secondaryLocales: List<String> = emptyList(),
    val status: String = "GA",
    /** Azure style tags (`StyleList`), used as the multi-voice style profile. */
    val styles: List<String> = emptyList()
) {
    fun isMultilingual(): Boolean =
        shortName.contains("Multilingual", ignoreCase = true)

    fun supportsEnglish(): Boolean =
        locale.lowercase().startsWith("en") ||
            secondaryLocales.any { it.lowercase().startsWith("en") }

    fun supportsChinese(): Boolean =
        locale.lowercase().startsWith("zh") ||
            secondaryLocales.any { it.lowercase().startsWith("zh") }

    fun toJson(): JSONObject = JSONObject()
        .put("shortName", shortName)
        .put("locale", locale)
        .put("gender", gender)
        .put("displayName", displayName)
        .put("localeName", localeName)
        .put("secondaryLocales", JSONArray(secondaryLocales))
        .put("status", status)
        .put("styles", JSONArray(styles))

    companion object {
        fun fromJson(json: JSONObject): AzureVoice {
            val secondary = json.optJSONArray("SecondaryLocaleList")
                ?: json.optJSONArray("secondaryLocales")
            val styleList = json.optJSONArray("StyleList")
                ?: json.optJSONArray("styles")
            return AzureVoice(
                shortName = firstString(json, "ShortName", "shortName"),
                locale = firstString(json, "Locale", "locale"),
                gender = firstString(json, "Gender", "gender"),
                displayName = firstString(json, "DisplayName", "displayName"),
                localeName = firstString(json, "LocaleName", "localeName"),
                secondaryLocales = if (secondary == null) {
                    emptyList()
                } else {
                    (0 until secondary.length()).map { secondary.optString(it) }
                },
                status = firstString(json, "Status", "status").ifBlank { "GA" },
                styles = if (styleList == null) {
                    emptyList()
                } else {
                    (0 until styleList.length())
                        .mapNotNull { styleList.optString(it).trim().takeIf(String::isNotBlank) }
                }
            )
        }

        private fun firstString(json: JSONObject, vararg keys: String): String =
            keys.firstNotNullOfOrNull { key ->
                json.optString(key).takeIf { it.isNotEmpty() }
            }.orEmpty()

        fun parse(json: JSONArray): List<AzureVoice> =
            (0 until json.length()).mapNotNull {
                runCatching { fromJson(json.getJSONObject(it)) }.getOrNull()
            }
    }
}

/** Picks sensible default voices from the region's actual voice list. */
object CloudVoicePicker {
    const val DEFAULT_ENGLISH = "en-US-AriaNeural"
    const val DEFAULT_CHINESE = "zh-CN-XiaoxiaoNeural"

    fun defaultEnglish(voices: List<AzureVoice>): String {
        val ga = voices.filter { it.status.equals("GA", ignoreCase = true) }
        return ga.firstOrNull { it.shortName.equals(DEFAULT_ENGLISH, ignoreCase = true) }?.shortName
            ?: ga.firstOrNull { it.locale.lowercase().startsWith("en") }?.shortName
            ?: DEFAULT_ENGLISH
    }

    fun defaultChinese(voices: List<AzureVoice>): String {
        val ga = voices.filter { it.status.equals("GA", ignoreCase = true) }
        return ga.firstOrNull { it.shortName.equals(DEFAULT_CHINESE, ignoreCase = true) }?.shortName
            ?: ga.firstOrNull { it.locale.lowercase().startsWith("zh") }?.shortName
            ?: DEFAULT_CHINESE
    }

    /** A multilingual voice that can read both English and Chinese when available. */
    fun defaultMultilingual(voices: List<AzureVoice>): String? {
        val ga = voices.filter {
            it.status.equals("GA", ignoreCase = true) &&
                it.isMultilingual() &&
                it.supportsEnglish() &&
                it.supportsChinese()
        }
        return ga.firstOrNull { it.locale.lowercase().startsWith("en") }?.shortName
            ?: ga.firstOrNull()?.shortName
    }
}
