package com.linguareader.app.tts

import com.linguareader.shared.packs.VoicePackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PackVoiceUnavailableReason { MISSING_SAMPLE, UNSUPPORTED_ENGINE }

/** UI receives reasons, never implementation ids. Audition failures do not pretend another voice was previewed. */
data class PackVoiceFallbackNotice(val reasons: Set<PackVoiceUnavailableReason>, val usedFallback: Boolean)

class PackVoiceUnavailableException(val reason: PackVoiceUnavailableReason) : IllegalStateException(
    "Voice package is unavailable; reinstall it or choose another voice"
)

data class PackVoiceResolution(val voiceId: String?, val unavailable: Map<String, PackVoiceUnavailableReason> = emptyMap())

/** Effective consumption only. This policy never edits CloudTtsSettings or persistent user intent. */
object PackVoiceSelectionPolicy {
    fun unavailableReason(voice: String, mode: TtsEngineMode, sampleAvailable: (String) -> Boolean): PackVoiceUnavailableReason? = when {
        !VoicePackSource.isPackVoice(voice) -> null
        mode != TtsEngineMode.MIMO -> PackVoiceUnavailableReason.UNSUPPORTED_ENGINE
        !sampleAvailable(voice) -> PackVoiceUnavailableReason.MISSING_SAMPLE
        else -> null
    }

    fun resolve(speaker: String, text: String, mappingVoice: String?, settings: CloudTtsSettings,
        sampleAvailable: (String) -> Boolean): PackVoiceResolution {
        val effective = if (settings.mode != TtsEngineMode.SYSTEM && (!settings.networkAiEnabled || !settings.isConfigured))
            settings.copy(mode = TtsEngineMode.SYSTEM) else settings
        val unavailable = linkedMapOf<String, PackVoiceUnavailableReason>()
        val manual = if (speaker.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) settings.narratorVoice else settings.dialogueVoice
        for (candidate in listOf(mappingVoice, manual)) {
            val id = candidate?.takeIf { it.isNotBlank() } ?: continue
            val reason = unavailableReason(id, effective.mode, sampleAvailable)
            if (reason == null) return PackVoiceResolution(id, unavailable)
            unavailable[id] = reason
        }
        val fallback = defaultVoice(text, effective, sampleAvailable)
        return fallback.copy(unavailable = unavailable + fallback.unavailable)
    }

    fun defaultVoice(text: String, settings: CloudTtsSettings, sampleAvailable: (String) -> Boolean): PackVoiceResolution {
        val zh = TtsLanguage.of(text) == TtsLanguage.CHINESE
        val candidates = when (settings.mode) {
            TtsEngineMode.OPENAI_COMPAT -> listOf(if (zh) settings.serverZhVoice else settings.serverEnVoice, settings.serverVoice, "default")
            TtsEngineMode.MIMO -> listOf(if (zh) settings.mimoZhVoice else settings.mimoEnVoice,
                if (zh) CloudTtsSettings.DEFAULT_MIMO_ZH_VOICE else CloudTtsSettings.DEFAULT_MIMO_EN_VOICE)
            TtsEngineMode.SYSTEM -> listOf(if (zh) settings.systemZhVoice else settings.systemEnVoice)
        }
        val unavailable = linkedMapOf<String, PackVoiceUnavailableReason>()
        for (id in candidates.filter { it.isNotBlank() }) {
            val reason = unavailableReason(id, settings.mode, sampleAvailable)
            if (reason == null) return PackVoiceResolution(id, unavailable)
            unavailable[id] = reason
        }
        // Null is the existing platform-engine default; engine readiness/failure remains visible via playback state.
        return PackVoiceResolution(null, unavailable)
    }

    fun effectiveMap(existing: BookVoiceMap?, available: Set<String>): BookVoiceMap? = existing?.copy(
        narrator = existing.narrator.filterValues { !VoicePackSource.isPackVoice(it) || it in available },
        characterVoice = existing.characterVoice.filterValues { !VoicePackSource.isPackVoice(it) || it in available }
    )

    /** Save new non-package assignments while retaining unavailable package choices and locks for reinstallation. */
    fun preserveUnavailableIntent(assigned: BookVoiceMap, existing: BookVoiceMap?, available: Set<String>): BookVoiceMap {
        if (existing == null) return assigned
        fun unavailable(id: String) = VoicePackSource.isPackVoice(id) && id !in available
        val characters = existing.characterVoice.filterValues(::unavailable)
        val keys = characters.keys.map(BookVoiceMap::keyOf).toSet()
        return assigned.copy(
            narrator = assigned.narrator + existing.narrator.filterValues(::unavailable),
            characterVoice = assigned.characterVoice.filterKeys { BookVoiceMap.keyOf(it) !in keys } + characters,
            userLocked = assigned.userLocked + existing.userLocked
        )
    }
}

internal object PackVoiceFallbackMonitor {
    private val mutable = MutableStateFlow<PackVoiceFallbackNotice?>(null)
    val state: StateFlow<PackVoiceFallbackNotice?> = mutable.asStateFlow()
    fun report(resolution: PackVoiceResolution) {
        if (resolution.unavailable.isNotEmpty()) mutable.value = PackVoiceFallbackNotice(resolution.unavailable.values.toSet(), true)
    }
    fun reject(reason: PackVoiceUnavailableReason) { mutable.value = PackVoiceFallbackNotice(setOf(reason), false) }
    fun clear() { mutable.value = null }
}
