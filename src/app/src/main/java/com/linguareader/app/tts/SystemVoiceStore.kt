package com.linguareader.app.tts

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One user annotation for a system TTS voice (PLAN-MULTI-VOICE §13.2, M5).
 *
 * The system engine exposes no usable metadata, so the multi-voice library is
 * built from what the user tells us: only the gender matters today (language is
 * derived from the voice locale). Voices left unknown or disabled never enter
 * the library, so the assigner can never pick a voice whose gender nobody
 * confirmed.
 */
data class SystemVoiceAnnotation(
    val voiceName: String,
    val gender: String = "",
    val enabled: Boolean = true
)

/**
 * Persistence for the system-engine multi-voice library (§13.3), mirroring
 * [ServerVoiceStore]: everything lives in one SharedPreferences file keyed by
 * the connected engine package, so annotations made for one engine (e.g.
 * Google TTS) are neither used nor lost after switching to another.
 *
 * Two records per engine:
 * - **Snapshot** of the offline voices the engine advertised when last probed
 *   (`VoiceLibraryLoader.refresh`); storing it keeps `load()` synchronous even
 *   though enumerating voices needs an async system TTS connection.
 * - The user's **annotations**.
 */
object SystemVoiceStore {
    private const val PREFS = "system_voice_annotations"
    private const val KEY_SNAPSHOT = "snapshot@"
    private const val KEY_ANNOTATIONS = "annotations@"
    private const val KEY_CURRENT_ENGINE = "current_engine"

    data class Snapshot(
        val engine: String,
        val voices: List<SystemVoiceInfo>
    )

    fun loadSnapshot(context: Context, enginePackage: String): Snapshot {
        val raw = prefs(context).getString(KEY_SNAPSHOT + key(enginePackage), null) ?: return Snapshot("", emptyList())
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return Snapshot("", emptyList())
        val array = obj.optJSONArray("voices") ?: JSONArray()
        val voices = (0 until array.length()).mapNotNull { item ->
            val voice = array.optJSONObject(item) ?: return@mapNotNull null
            val name = voice.optString("name").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val locale = Locale.forLanguageTag(voice.optString("locale").ifBlank { "en" })
            SystemVoiceInfo(name, locale)
        }
        return Snapshot(obj.optString("engine").trim(), voices)
    }

    fun saveSnapshot(context: Context, snapshot: Snapshot) {
        if (snapshot.engine.isBlank() || snapshot.voices.isEmpty()) return
        val array = JSONArray()
        snapshot.voices.forEach { voice ->
            array.put(
                JSONObject()
                    .put("name", voice.name)
                    .put("locale", voice.locale.toLanguageTag())
            )
        }
        val raw = JSONObject().put("engine", snapshot.engine).put("voices", array).toString()
        prefs(context).edit { putString(KEY_SNAPSHOT + key(snapshot.engine), raw) }
    }

    fun loadAnnotations(context: Context, enginePackage: String): List<SystemVoiceAnnotation> {
        val raw = prefs(context).getString(KEY_ANNOTATIONS + key(enginePackage), null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { item ->
            val obj = array.optJSONObject(item) ?: return@mapNotNull null
            val name = obj.optString("voiceName").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SystemVoiceAnnotation(
                voiceName = name,
                gender = obj.optString("gender").trim().lowercase(),
                enabled = obj.optBoolean("enabled", true)
            )
        }
    }

    fun saveAnnotations(context: Context, enginePackage: String, annotations: List<SystemVoiceAnnotation>) {
        val array = JSONArray()
        annotations.forEach { annotation ->
            array.put(
                JSONObject()
                    .put("voiceName", annotation.voiceName)
                    .put("gender", annotation.gender)
                    .put("enabled", annotation.enabled)
            )
        }
        prefs(context).edit { putString(KEY_ANNOTATIONS + key(enginePackage), array.toString()) }
    }

    /**
     * Library members for the engine the app last probed: annotated, enabled
     * and with a known gender (§13.4「可用」). Pure aside from the store read so
     * tests can drive [buildVoices] directly.
     */
    fun usableVoices(context: Context): List<VoiceInfo> {
        val engine = currentEngine(context)
        if (engine.isBlank()) return emptyList()
        return usableVoices(context, engine)
    }

    fun usableVoices(context: Context, enginePackage: String): List<VoiceInfo> =
        buildVoices(loadSnapshot(context, enginePackage).voices, loadAnnotations(context, enginePackage))

    /** The engine [VoiceLibraryLoader.refresh] last probed; blank = never. */
    fun currentEngine(context: Context): String =
        prefs(context).getString(KEY_CURRENT_ENGINE, null).orEmpty()

    fun setCurrentEngine(context: Context, enginePackage: String) {
        prefs(context).edit { putString(KEY_CURRENT_ENGINE, key(enginePackage)) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(enginePackage: String) = enginePackage.trim().lowercase()

    /**
     * Snapshot × annotations → assigner library. A voice participates only
     * when it is annotated, enabled and has a known gender; its language
     * comes from the locale prior ([SystemVoiceInfo.assignerLanguage]).
     */
    fun buildVoices(
        snapshot: List<SystemVoiceInfo>,
        annotations: List<SystemVoiceAnnotation>
    ): List<VoiceInfo> {
        val byName = annotations.associateBy { it.voiceName }
        return snapshot.mapNotNull { voice ->
            val annotation = byName[voice.name] ?: return@mapNotNull null
            if (!annotation.enabled || annotation.gender !in setOf("male", "female")) return@mapNotNull null
            VoiceInfo(
                id = voice.name,
                language = voice.assignerLanguage,
                gender = annotation.gender,
                source = "system"
            )
        }
    }
}
