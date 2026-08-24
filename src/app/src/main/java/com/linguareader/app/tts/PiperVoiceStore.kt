package com.linguareader.app.tts

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the Piper voices imported into `filesDir` and resolves a voice id to
 * the concrete model/tokens to load. The bundled Ryan voice is always available;
 * the selected id itself lives in [CloudTtsSettings.piperEnVoiceId] so it rides
 * the settings sheet's save / reconfigure flow like the system voices.
 */
object PiperVoiceStore {
    private const val PREFS = "piper_voice_store"
    private const val KEY_IMPORTED = "imported"

    fun voicesDir(context: Context): File =
        File(context.filesDir, "piper-voices").apply { mkdirs() }

    /**
     * 已导入且**文件仍然存在**的音色。
     *
     * 应用数据被清理、用户手动删文件、导入中途失败等都会留下指向不存在文件的记录；
     * 这种音色一旦被选中，sherpa 初始化就会失败并让英文朗读整体哑掉，所以读取时
     * 直接过滤掉，并顺手把记录修正回去。
     */
    fun imported(context: Context): List<PiperVoice> {
        val parsed = parseImported(context)
        val usable = parsed.filter { voice ->
            val model = voice.modelPath?.let(::File)
            val tokens = voice.tokensPath?.let(::File)
            model != null && tokens != null && model.isFile && tokens.isFile
        }
        if (usable.size != parsed.size) saveImported(context, usable)
        return usable
    }

    private fun parseImported(context: Context): List<PiperVoice> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_IMPORTED, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PiperVoice(
                    id = o.getString("id"),
                    displayName = o.getString("name"),
                    gender = o.optString("gender", "?"),
                    language = o.optString("language", "en"),
                    sizeMb = o.optInt("sizeMb", 0),
                    sampleUrl = "",
                    modelUrl = "",
                    packageUrl = "",
                    modelPath = o.optString("modelPath").takeIf { it.isNotBlank() },
                    tokensPath = o.optString("tokensPath").takeIf { it.isNotBlank() }
                )
            }
        }.getOrElse { emptyList() }
    }

    /** Voices that can actually be selected for playback: bundled + imported. */
    fun installed(context: Context): List<PiperVoice> =
        listOf(PiperVoiceCatalog.builtin) + imported(context)

    /** Resolves a selected id to a loadable voice; unknown ids fall back to Ryan. */
    fun resolve(context: Context, id: String): PiperVoice =
        installed(context).firstOrNull { it.id == id } ?: PiperVoiceCatalog.builtin

    fun registerImported(context: Context, voice: PiperVoice) {
        val list = parseImported(context).filterNot { it.id == voice.id } + voice
        saveImported(context, list)
    }

    fun removeImported(context: Context, id: String) {
        File(voicesDir(context), id).deleteRecursively()
        saveImported(context, parseImported(context).filterNot { it.id == id })
    }

    private fun saveImported(context: Context, list: List<PiperVoice>) {
        val arr = JSONArray()
        list.forEach { v ->
            arr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("name", v.displayName)
                    put("gender", v.gender)
                    put("language", v.language)
                    put("sizeMb", v.sizeMb)
                    put("modelPath", v.modelPath.orEmpty())
                    put("tokensPath", v.tokensPath.orEmpty())
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_IMPORTED, arr.toString())
        }
    }
}
