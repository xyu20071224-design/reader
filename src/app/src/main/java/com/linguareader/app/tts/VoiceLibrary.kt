package com.linguareader.app.tts

/**
 * One synthesizable voice with the profile the assigner needs
 * (PLAN-MULTI-VOICE §3.4).
 *
 * Attributes come from engine metadata where it exists (a self-hosted server's
 * voice list) and from naming conventions otherwise (Kokoro `zf_/zm_/af_/bf_`,
 * 豆包 `zh_female_*`), so no extra sampling pass is required to get a usable
 * library. [quality] is a placeholder for the optional f0/sample analysis of
 * the plan and only breaks ties today.
 */
data class VoiceInfo(
    val id: String,
    /** "zh" / "en" / … ; blank means multilingual or unknown. */
    val language: String = "",
    /** "male" / "female"; blank means unknown. */
    val gender: String = "",
    val style: List<String> = emptyList(),
    val ageGroup: String = "",
    val quality: Float = 0.5f,
    /** server / kokoro / clone …（音色 id 的来源，仅作展示与调试）。 */
    val source: String = ""
) {
    val available: Boolean get() = id.isNotBlank()

    /** True when the voice can read [language] (blank language = any). */
    fun speaks(language: String): Boolean =
        language.isBlank() || this.language.isBlank() || this.language.equals(language, ignoreCase = true)
}

/**
 * The voices available on the currently configured engine.
 *
 * [engine] identifies the engine the library was built for, so a stored
 * [BookVoiceMap] can tell that the音色库 changed and needs re-assignment.
 */
data class VoiceLibrary(
    val voices: List<VoiceInfo> = emptyList(),
    val engine: String = ""
) {
    val isEmpty: Boolean get() = voices.none { it.available }

    fun byId(id: String): VoiceInfo? = voices.firstOrNull { it.id == id }

    fun forLanguage(language: String): List<VoiceInfo> =
        voices.filter { it.available && it.speaks(language) }

    companion object {
        /**
         * Similarity of two voices (0..1) by (gender, language, style overlap),
         * used as the distinctness penalty between co-occurring characters
         * (§5.2 `sim(v1,v2)`).
         */
        fun similarity(first: VoiceInfo, second: VoiceInfo): Float {
            if (first.id == second.id) return 1f
            var score = 0f
            if (first.gender.isNotBlank() && first.gender.equals(second.gender, ignoreCase = true)) {
                score += 0.5f
            }
            if (first.language.equals(second.language, ignoreCase = true)) score += 0.2f
            score += 0.3f * jaccard(first.style, second.style)
            return score.coerceIn(0f, 1f)
        }

        internal fun jaccard(first: List<String>, second: List<String>): Float {
            if (first.isEmpty() || second.isEmpty()) return 0f
            val a = first.map { it.lowercase() }.toSet()
            val b = second.map { it.lowercase() }.toSet()
            val union = (a + b).size
            if (union == 0) return 0f
            return a.intersect(b).size.toFloat() / union
        }
    }
}

/** Sentence language routing shared by the voice map and the backends. */
object TtsLanguage {
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    /** "zh" when the sentence contains Han characters, otherwise "en". */
    fun of(text: String): String = if (text.any(::isHan)) CHINESE else ENGLISH

    private fun isHan(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF
    }
}

/**
 * Voice attributes inferred from id naming conventions (§3.4 「名字先验」).
 *
 * - Kokoro: `<locale><gender>_<name>` - `zf_001` (中文女), `zm_009` (中文男),
 *   `af_maple` / `am_onyx` (美式英语), `bf_alice` / `bm_george` (英式英语),
 *   plus `ef/em`(es) `ff`(fr) `hf/hm`(hi) `if/im`(it) `jf/jm`(ja) `pf/pm`(pt).
 * - 豆包/火山系: `zh_female_xxx` / `en_male_xxx`.
 * - Azure 风格: `zh-CN-XiaoxiaoNeural` gives the language; the gender comes
 *   from the engine metadata instead.
 */
object VoiceNaming {
    private val kokoroLocales = mapOf(
        'a' to "en", 'b' to "en", 'z' to "zh", 'e' to "es", 'f' to "fr",
        'h' to "hi", 'i' to "it", 'j' to "ja", 'p' to "pt"
    )

    private val audioExtensions = listOf(".wav", ".mp3", ".flac", ".ogg")

    /** Gender words accepted inside a clone voice name. */
    private val femaleWords = setOf("f", "female", "woman", "girl", "nv")
    private val maleWords = setOf("m", "male", "man", "boy", "nan")
    private val languageWords = setOf("zh", "cn", "en", "ja", "jp", "es", "fr", "hi", "it", "pt")

    fun infer(id: String, source: String = ""): VoiceInfo {
        val clean = id.trim()
        if (clean.isEmpty()) return VoiceInfo(id = "", source = source)
        // A reference-audio server (IndexTTS) advertises file names; the priors
        // below describe the *name*, so drop the extension first and keep the
        // original id for the request body.
        val name = audioExtensions
            .firstOrNull { clean.endsWith(it, ignoreCase = true) }
            ?.let { clean.dropLast(it.length) }
            ?: clean
        clone(clean, name, source)?.let { return it }
        volcano(name, source)?.let { return it.copy(id = clean) }
        kokoro(name, source)?.let { return it.copy(id = clean) }
        azure(name, source)?.let { return it.copy(id = clean) }
        return VoiceInfo(id = clean, source = source)
    }

    /**
     * Clone voices (M1.5 / §12.2): `clone_<角色>_<lang>_<gender>` in any order,
     * e.g. `clone_gandalf_en_m.wav`. Unknown parts simply stay blank, so a bare
     * `clone_gandalf` is still recognised as a clone voice.
     */
    private fun clone(id: String, name: String, source: String): VoiceInfo? {
        val parts = name.lowercase().split("_", "-")
        if (parts.firstOrNull() != "clone") return null
        val tail = parts.drop(1)
        val language = tail.firstOrNull { it in languageWords }?.let {
            if (it == "cn") "zh" else if (it == "jp") "ja" else it
        }.orEmpty()
        val gender = when {
            tail.any { it in femaleWords } -> "female"
            tail.any { it in maleWords } -> "male"
            else -> ""
        }
        return VoiceInfo(
            id = id,
            language = language,
            gender = gender,
            quality = 0.7f,
            source = source.ifBlank { "clone" }
        )
    }

    /** `zh_female_shuangkuaisisi_uranus_bigtts` → zh / female. */
    private fun volcano(id: String, source: String): VoiceInfo? {
        val parts = id.lowercase().split("_")
        if (parts.size < 2) return null
        val language = when (parts[0]) {
            "zh", "cn" -> "zh"
            "en" -> "en"
            "ja", "jp" -> "ja"
            else -> return null
        }
        val gender = when (parts[1]) {
            "female" -> "female"
            "male" -> "male"
            else -> ""
        }
        if (gender.isBlank()) return null
        return VoiceInfo(id = id, language = language, gender = gender, source = source)
    }

    /** `zf_001` / `af_maple` → language from the first letter, gender from the second. */
    private fun kokoro(id: String, source: String): VoiceInfo? {
        val lower = id.lowercase()
        if (lower.length < 4 || lower[2] != '_') return null
        val language = kokoroLocales[lower[0]] ?: return null
        val gender = when (lower[1]) {
            'f' -> "female"
            'm' -> "male"
            else -> return null
        }
        return VoiceInfo(id = id, language = language, gender = gender, source = source)
    }

    /** `zh-CN-XiaoxiaoNeural` → language only (gender comes from metadata). */
    private fun azure(id: String, source: String): VoiceInfo? {
        val parts = id.split("-")
        if (parts.size < 3) return null
        val language = languageOfLocale(parts[0]) ?: return null
        return VoiceInfo(id = id, language = language, source = source)
    }

    /** Maps a BCP-47 locale ("zh-CN", "en-US") onto the assigner language. */
    fun languageOfLocale(locale: String): String? {
        val head = locale.trim().lowercase().substringBefore('-')
        if (head.length != 2) return null
        return head
    }
}
