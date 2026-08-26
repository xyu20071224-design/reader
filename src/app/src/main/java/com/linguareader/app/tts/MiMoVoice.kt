package com.linguareader.app.tts

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MiMo-V2.5-TTS 预置音色目录（来自小米官方文档「预置音色列表」）。
 *
 * 音色 id 就是请求体 `audio.voice` 里直传的值（`mimo_default` 在中国大陆集群
 * 默认是「冰糖」，其他集群是「Mia」）。语言/性别元数据同时喂给多角色分配器
 * （M3 硬过滤），所以预置音色可以直接作为各角色的可分配音色。
 */
object MiMoVoiceCatalog {

    /** 预置音色：id、显示名（资源 key）、语言、性别。 */
    data class Preset(
        val id: String,
        val nameKey: String,
        val language: String,
        val gender: String
    )

    val presets: List<Preset> = listOf(
        Preset("mimo_default", "tts_mimo_voice_default", "zh", "female"),
        Preset("Bingtang", "tts_mimo_voice_bingtang", "zh", "female"),
        Preset("Jasmine", "tts_mimo_voice_jasmine", "zh", "female"),
        Preset("Soda", "tts_mimo_voice_soda", "zh", "male"),
        Preset("Birch", "tts_mimo_voice_birch", "zh", "male"),
        Preset("Mia", "tts_mimo_voice_mia", "en", "female"),
        Preset("Chloe", "tts_mimo_voice_chloe", "en", "female"),
        Preset("Milo", "tts_mimo_voice_milo", "en", "male"),
        Preset("Dean", "tts_mimo_voice_dean", "en", "male")
    )

    val zhVoices: List<Preset> get() = presets.filter { it.language == "zh" }
    val enVoices: List<Preset> get() = presets.filter { it.language == "en" }

    /** 查预置音色；未知 id 返回 null（用于判定请求模型与是否走预置分支）。 */
    fun byId(id: String): Preset? = presets.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** 预置音色在音色库里的条目（可以直接喂 [VoicePicker] / 分配器）。 */
    fun asVoiceInfo(preset: Preset): VoiceInfo = VoiceInfo(
        id = preset.id,
        language = preset.language,
        gender = preset.gender,
        quality = 0.5f,
        source = "mimo"
    )
}

/**
 * MiMo 自定义音色（多角色 F-151 / 多角色服务）：
 *
 * - **设计音色**（`mimo-v2.5-tts-voicedesign`）：用自然语言描述生成专属音色。
 *   音色 id 形如 `mimo-design:<key>`，描述文本存这里，合成时放进 user 消息。
 * - **克隆音色**（`mimo-v2.5-tts-voiceclone`）：用一段音频样本复刻音色。
 *   音色 id 形如 `mimo-clone:<key>`，样本文件存 `filesDir/mimo-voices/`，
 *   合成时以 `data:audio/mpeg;base64,…` 放进 `audio.voice`（≤10 MB，mp3/wav）。
 *
 * 两种 id 与预置音色 id 一样走音色库 → 角色分配 → 逐句 `voiceFor` 的既有管线，
 * 引擎层不感知 id 形态；只有 [MiMoTtsBackend.synthesize] 按前缀分派模型。
 */
object MiMoVoiceStore {

    const val DESIGN_PREFIX = "mimo-design:"
    const val CLONE_PREFIX = "mimo-clone:"
    const val MAX_CLONE_BYTES = 10 * 1024 * 1024 // MiMo 文档：样本 base64 ≤ 10 MB

    /** 自定义音色登记项。 */
    data class CustomVoice(
        val id: String,
        val kind: Kind,
        val name: String,
        val language: String,
        val gender: String,
        /** 设计音色的描述 prompt；克隆音色为空。 */
        val prompt: String = "",
        /** 克隆音色样本文件名（相对 `mimo-voices/`）；设计音色为空。 */
        val sampleFile: String = ""
    ) {
        enum class Kind { DESIGN, CLONE }

        val isDesign: Boolean get() = kind == Kind.DESIGN
        val isClone: Boolean get() = kind == Kind.CLONE

        /** 音色库条目：id/语言/性别给分配器，质量设计 0.5、克隆 0.7（与
         *  既有 clone 音色先验一致）。 */
        fun asVoiceInfo(): VoiceInfo = VoiceInfo(
            id = id,
            language = language,
            gender = gender,
            quality = if (isClone) 0.7f else 0.5f,
            source = "mimo"
        )
    }

    private const val PREFS = "mimo_voice_store"
    private const val KEY_VOICES = "voices"

    fun installed(context: Context): List<CustomVoice> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VOICES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = runCatching {
                CustomVoice.Kind.valueOf(item.optString("kind"))
            }.getOrNull() ?: return@mapNotNull null
            CustomVoice(
                id = item.optString("id"),
                kind = kind,
                name = item.optString("name"),
                language = item.optString("language"),
                gender = item.optString("gender"),
                prompt = item.optString("prompt"),
                sampleFile = item.optString("sampleFile")
            ).takeIf { it.id.isNotBlank() }
        }
    }

    fun find(context: Context, id: String): CustomVoice? =
        installed(context).firstOrNull { it.id == id }

    /** 设计音色/克隆音色的 description 或样本，供 backend 取用。 */
    fun designPrompt(context: Context, id: String): String =
        find(context, id)?.prompt.orEmpty()

    /** 克隆样本文件（不存在返回 null）。 */
    fun sampleFile(context: Context, id: String): File? =
        find(context, id)?.sampleFile?.takeIf { it.isNotBlank() }
            ?.let { File(sampleDir(context), it) }
            ?.takeIf { it.exists() }

    /**
     * 新建设计音色：key = 名字的 ASCII slug（若撞 key 自动加序号），
     * 登记进 SharedPreferences。校验：名字与描述非空。
     */
    fun addDesign(
        context: Context,
        name: String,
        prompt: String,
        language: String,
        gender: String
    ): Result<CustomVoice> = runCatching {
        val cleanName = name.trim()
        val cleanPrompt = prompt.trim()
        check(cleanName.isNotEmpty()) { "音色名称不能为空" }
        check(cleanPrompt.isNotEmpty()) { "音色描述不能为空" }
        val key = uniqueKey(context, slugOf(cleanName))
        val voice = CustomVoice(
            id = DESIGN_PREFIX + key,
            kind = CustomVoice.Kind.DESIGN,
            name = cleanName,
            language = normalizeLanguage(language, "zh"),
            gender = normalizeGender(gender),
            prompt = cleanPrompt
        )
        saveAll(context, installed(context) + voice)
        voice
    }

    /**
     * 导入克隆样本：校验 mp3/wav + ≤10 MB，复制到
     * `filesDir/mimo-voices/<key>.<ext>` 后登记。
     */
    fun addClone(
        context: Context,
        name: String,
        source: File,
        language: String,
        gender: String
    ): Result<CustomVoice> = runCatching {
        val cleanName = name.trim()
        check(cleanName.isNotEmpty()) { "音色名称不能为空" }
        check(source.exists() && source.isFile) { "样本文件不存在" }
        check(source.length() > 0 && source.length() <= MAX_CLONE_BYTES) {
            "样本大小需在 0–10 MB 之间"
        }
        val ext = source.extension.lowercase()
        check(ext == "mp3" || ext == "wav") { "仅支持 mp3 / wav 样本" }
        val key = uniqueKey(context, slugOf(cleanName))
        val dir = sampleDir(context).apply { mkdirs() }
        val target = File(dir, "$key.$ext")
        source.copyTo(target, overwrite = true)
        val voice = CustomVoice(
            id = CLONE_PREFIX + key,
            kind = CustomVoice.Kind.CLONE,
            name = cleanName,
            language = normalizeLanguage(language, "zh"),
            gender = normalizeGender(gender),
            sampleFile = target.name
        )
        saveAll(context, installed(context) + voice)
        voice
    }

    /** 删除自定义音色：清登记 + 删样本文件。 */
    fun remove(context: Context, id: String) {
        val current = installed(context)
        val removed = current.firstOrNull { it.id == id } ?: return
        if (removed.sampleFile.isNotBlank()) {
            sampleFile(context, id)?.delete()
        }
        saveAll(context, current - removed)
    }

    private fun saveAll(context: Context, voices: List<CustomVoice>) {
        val array = JSONArray()
        voices.forEach { voice ->
            array.put(
                JSONObject()
                    .put("id", voice.id)
                    .put("kind", voice.kind.name)
                    .put("name", voice.name)
                    .put("language", voice.language)
                    .put("gender", voice.gender)
                    .put("prompt", voice.prompt)
                    .put("sampleFile", voice.sampleFile)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_VOICES, array.toString())
        }
    }

    private fun sampleDir(context: Context): File = File(context.filesDir, "mimo-voices")

    private fun uniqueKey(context: Context, base: String): String {
        val existing = installed(context).map { it.id.substringAfter(':') }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    private fun slugOf(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifEmpty { "voice" }
    }

    private fun normalizeLanguage(language: String, fallback: String): String =
        when (language.lowercase()) {
            "zh", "cn" -> "zh"
            "en" -> "en"
            else -> when (fallback) {
                "zh" -> "zh"
                else -> "en"
            }
        }

    private fun normalizeGender(gender: String): String =
        when (gender.lowercase()) {
            "male" -> "male"
            "female" -> "female"
            else -> ""
        }
}

/** MiMo 引擎的音色库：预置 + 自定义（设计/克隆）。 */
fun MiMoVoiceCatalog.library(context: Context): List<VoiceInfo> {
    val presetItems = presets.map { MiMoVoiceCatalog.asVoiceInfo(it) }
    val customs = MiMoVoiceStore.installed(context).map { it.asVoiceInfo() }
    return (presetItems + customs).distinctBy { it.id }
}