package com.linguareader.app.tts

/** 一个可选音色；[recommended] 表示语言与性别都贴合当前角色。 */
data class VoiceOption(
    val voice: VoiceInfo,
    val recommended: Boolean
)

/** 音色选择器里的一组（如「推荐」「英文 · 男」）。 */
data class VoiceGroup(
    val title: String,
    val options: List<VoiceOption>
)

/**
 * 音色选择列表的纯逻辑：搜索 + 分组 + 展示文案。
 *
 * Kokoro 一个服务器就有 100+ 音色，裸下拉不可用，所以先按「推荐（同语言且性别不
 * 冲突）」置顶，再按「语言 · 性别」分组，并支持关键字过滤。
 */
object VoicePicker {

    private const val UNKNOWN_LANGUAGE = "未标语言"
    private const val UNKNOWN_GENDER = "未标性别"

    /** 关键字过滤：音色 id、语言、性别、风格词任一命中即保留。 */
    fun filter(voices: List<VoiceInfo>, query: String): List<VoiceInfo> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return voices
        return voices.filter { voice ->
            voice.id.lowercase().contains(needle) ||
                voice.language.lowercase().contains(needle) ||
                voice.gender.lowercase().contains(needle) ||
                genderLabel(voice.gender).contains(needle) ||
                languageLabel(voice.language).contains(needle) ||
                voice.style.any { it.lowercase().contains(needle) }
        }
    }

    /**
     * 分组结果：第一组是「推荐」（同语言 + 性别不冲突），其余按「语言 · 性别」
     * 分组，角色自身语言的组排在前面。空组不会出现。
     */
    fun groups(
        voices: List<VoiceInfo>,
        language: String,
        gender: String?,
        query: String = ""
    ): List<VoiceGroup> {
        val available = filter(voices.filter { it.available }, query)
        if (available.isEmpty()) return emptyList()
        val recommended = available.filter { matches(it, language, gender) }
        val rest = available.filterNot { matches(it, language, gender) }
        val groups = mutableListOf<VoiceGroup>()
        if (recommended.isNotEmpty()) {
            groups += VoiceGroup(
                title = "推荐（" + recommended.size + "）",
                options = recommended.sortedBy { it.id }.map { VoiceOption(it, true) }
            )
        }
        rest.groupBy { languageLabel(it.language) to genderLabel(it.gender) }
            .toList()
            .sortedWith(
                compareByDescending<Pair<Pair<String, String>, List<VoiceInfo>>> {
                    // 角色语言的组优先，其次组内数量多的在前，最后按名字稳定排序。
                    it.first.first == languageLabel(language)
                }
                    .thenByDescending { it.second.size }
                    .thenBy { it.first.first + it.first.second }
            )
            .forEach { (key, items) ->
                groups += VoiceGroup(
                    title = key.first + " · " + key.second + "（" + items.size + "）",
                    options = items.sortedBy { it.id }.map { VoiceOption(it, false) }
                )
            }
        return groups
    }

    /** 列表行文案：音色 id +（性别·语言·风格）。 */
    fun label(voice: VoiceInfo): String {
        val tags = mutableListOf<String>()
        if (voice.gender.isNotBlank()) tags += genderLabel(voice.gender)
        if (voice.language.isNotBlank()) tags += languageLabel(voice.language)
        voice.style.firstOrNull()?.let { tags += it }
        return if (tags.isEmpty()) voice.id else voice.id + "（" + tags.joinToString("·") + "）"
    }

    private fun matches(voice: VoiceInfo, language: String, gender: String?): Boolean =
        voice.speaks(language) &&
            (gender.isNullOrBlank() || voice.gender.isBlank() || voice.gender.equals(gender, ignoreCase = true))

    private fun languageLabel(language: String): String = when (language.lowercase()) {
        "zh", "cn" -> "中文"
        "en" -> "英文"
        "ja", "jp" -> "日文"
        "" -> UNKNOWN_LANGUAGE
        else -> language.lowercase()
    }

    private fun genderLabel(gender: String): String = when (gender.lowercase()) {
        "male" -> "男"
        "female" -> "女"
        else -> UNKNOWN_GENDER
    }
}
