package com.linguareader.app.tts

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches the voice list of a self-hosted OpenAI-compatible server, including the
 * optional per-voice metadata (M1.5: IndexTTS clone voices describe their
 * language and gender, which the assigner needs for its hard filter).
 */
object ServerVoiceStore {
    private const val PREFS = "server_tts_voices"

    fun load(context: Context, serverUrl: String): List<ServerVoice> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyFor(serverUrl), null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                // Older builds cached plain ids; keep reading them.
                is String -> item.trim().takeIf { it.isNotEmpty() }?.let { ServerVoice(it) }
                is JSONObject -> item.optString("id").trim().takeIf { it.isNotEmpty() }?.let { id ->
                    ServerVoice(
                        id = id,
                        language = item.optString("language").trim(),
                        gender = item.optString("gender").trim(),
                        style = item.optJSONArray("style")?.let { styles ->
                            (0 until styles.length()).mapNotNull { position ->
                                styles.optString(position).trim().takeIf(String::isNotEmpty)
                            }
                        }.orEmpty()
                    )
                }

                else -> null
            }
        }
    }

    fun save(context: Context, serverUrl: String, voices: List<ServerVoice>) {
        val array = JSONArray()
        voices.forEach { voice ->
            array.put(
                JSONObject()
                    .put("id", voice.id)
                    .put("language", voice.language)
                    .put("gender", voice.gender)
                    .put("style", JSONArray().apply { voice.style.forEach { put(it) } })
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(keyFor(serverUrl), array.toString())
        }
    }

    private fun keyFor(serverUrl: String): String = "voices@" + serverUrl.trim().trimEnd('/')
}

/**
 * Builds the [VoiceLibrary] of the currently configured engine
 * (PLAN-MULTI-VOICE §3.4「来源」).
 *
 * Metadata first: Azure ships gender + style tags with `voices/list`; a
 * self-hosted Kokoro/IndexTTS server ships bare ids, so those are enriched with
 * the naming priors in [VoiceNaming]. Configured voice ids (server voice, M1
 * narrator/dialogue voices) are always part of the library, so a server without
 * any voice endpoint still supports assignment.
 */
object VoiceLibraryLoader {

    /** Identity of the音色库: a different engine or server means re-assignment. */
    fun engineKey(settings: CloudTtsSettings): String = when (settings.mode) {
        TtsEngineMode.AZURE -> "azure:" + settings.region.trim().lowercase()
        TtsEngineMode.VOLC -> "volc:" + settings.volcResourceId.trim().lowercase()
        TtsEngineMode.OPENAI_COMPAT -> "server:" + settings.serverUrl.trim().trimEnd('/').lowercase()
        else -> settings.mode.name.lowercase()
    }

    fun load(context: Context, settings: CloudTtsSettings): VoiceLibrary {
        val engine = engineKey(settings)
        return when (settings.mode) {
            TtsEngineMode.AZURE -> VoiceLibrary(azureVoices(context), engine)
            TtsEngineMode.VOLC -> VoiceLibrary(configuredVoices(settings, "volc"), engine)
            TtsEngineMode.OPENAI_COMPAT -> VoiceLibrary(serverVoices(context, settings), engine)
            // M5 (PLAN-MULTI-VOICE §13): the system library is whatever the
            // user annotated for the last probed engine; empty until then.
            TtsEngineMode.SYSTEM -> systemVoices(context)
            // D2: Piper has no controllable voice library.
            else -> VoiceLibrary(emptyList(), engine)
        }
    }

    /**
     * Fetches the server voice list once when nothing is cached yet. Safe to
     * call on every playback start: it is a no-op for other engines and for a
     * server whose list was already stored.
     */
    suspend fun refresh(context: Context, settings: CloudTtsSettings) {
        if (settings.mode == TtsEngineMode.SYSTEM) {
            refreshSystem(context)
            return
        }
        if (settings.mode != TtsEngineMode.OPENAI_COMPAT) return
        if (settings.serverUrl.isBlank()) return
        if (ServerVoiceStore.load(context, settings.serverUrl).isNotEmpty()) return
        val voices = OpenAiCompatTtsBackend(settings).listVoices().getOrNull().orEmpty()
        if (voices.isNotEmpty()) ServerVoiceStore.save(context, settings.serverUrl, voices)
    }

    /**
     * Probes the connected system engine once and stores its offline voice
     * snapshot (M5 §13.3). The engine package comes from the framework setting
     * a default-constructed TextToSpeech binds to; a transiently empty probe
     * must not wipe an existing snapshot (engines may still be loading their
     * voice list).
     */
    private suspend fun refreshSystem(context: Context) {
        val appContext = context.applicationContext
        val enginePackage = SystemTtsVoices.currentEngine(appContext)
        if (enginePackage.isBlank()) return
        SystemVoiceStore.setCurrentEngine(appContext, enginePackage)
        val voices = SystemTtsVoices.probe(appContext)
        if (voices.isNotEmpty()) {
            SystemVoiceStore.saveSnapshot(appContext, SystemVoiceStore.Snapshot(enginePackage, voices))
        }
    }

    /** Annotated voices of the last probed engine, keyed so that switching
     *  engines re-triggers assignment ([BookVoiceMap.engine]). */
    private fun systemVoices(context: Context): VoiceLibrary {
        val enginePackage = SystemVoiceStore.currentEngine(context)
        if (enginePackage.isBlank()) return VoiceLibrary(emptyList(), "system")
        return VoiceLibrary(SystemVoiceStore.usableVoices(context, enginePackage), "system:$enginePackage")
    }

    private fun azureVoices(context: Context): List<VoiceInfo> =
        CloudVoiceStore.load(context)
            .filter { it.status.equals("GA", ignoreCase = true) && it.shortName.isNotBlank() }
            .map { voice ->
                val multilingual = voice.isMultilingual() &&
                    voice.supportsEnglish() &&
                    voice.supportsChinese()
                VoiceInfo(
                    id = voice.shortName,
                    // A multilingual voice reads both languages, so it stays
                    // language-agnostic for the hard filter.
                    language = if (multilingual) "" else VoiceNaming.languageOfLocale(voice.locale).orEmpty(),
                    gender = voice.gender.trim().lowercase(),
                    style = voice.styles,
                    quality = if (multilingual) 0.6f else 0.5f,
                    source = "azure"
                )
            }

    private fun serverVoices(context: Context, settings: CloudTtsSettings): List<VoiceInfo> {
        val advertised = ServerVoiceStore.load(context, settings.serverUrl)
        val configured = configuredIds(settings).map { ServerVoice(it) }
        return (advertised + configured)
            .map { it.copy(id = it.id.trim()) }
            .filter { it.id.isNotEmpty() && !it.id.equals("default", ignoreCase = true) }
            .distinctBy { it.id }
            .map { voice ->
                // Naming priors first, then whatever the server actually told us
                // (a clone voice knows its own language/gender better than a
                // guess from its file name).
                val inferred = VoiceNaming.infer(voice.id, "server")
                inferred.copy(
                    language = voice.language.ifBlank { inferred.language },
                    gender = voice.gender.ifBlank { inferred.gender },
                    style = if (voice.style.isNotEmpty()) voice.style else inferred.style
                )
            }
    }

    private fun configuredVoices(settings: CloudTtsSettings, source: String): List<VoiceInfo> =
        (configuredIds(settings) + listOf(settings.volcZhVoice, settings.volcEnVoice))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { VoiceNaming.infer(it, source) }

    private fun configuredIds(settings: CloudTtsSettings): List<String> = listOf(
        settings.serverVoice,
        settings.serverEnVoice,
        settings.serverZhVoice,
        settings.narratorVoice,
        settings.dialogueVoice
    )
}
