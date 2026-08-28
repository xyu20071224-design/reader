package com.linguareader.app.tts

import android.content.Context
import com.linguareader.app.ai.AiSettingsStore
import com.linguareader.app.ai.BookGlossaryRepository
import com.linguareader.app.ai.SpeakerTagRepository

/**
 * Shared wiring for the multi-voice feature, used by both the playback service
 * (M2/M3) and the listening settings UI (M4), so the panel always shows exactly
 * what playback will do.
 */
/** 多角色设置当前处于哪种状态（面板据此选文案）。 */
enum class MultiVoiceStatusKind {
    /** 引擎还没有可用音色库。 */
    NO_LIBRARY,

    /** 本书还没有角色表（语境档案未生成）。 */
    NO_ROSTER,

    /** 没有 DeepSeek Key，说话人识别只能走规则层。 */
    RULE_MODE,

    /** 还没算出音色映射。 */
    NO_MAP,

    /** 音色不足，部分不共场的角色共用音色。 */
    SHARED_VOICES,

    /** 一切就绪。 */
    READY
}

data class MultiVoiceStatus(
    val kind: MultiVoiceStatusKind,
    val characters: Int = 0,
    val shared: Int = 0
)

object MultiVoiceSupport {

    /** Narration voices are assigned per language (mixed zh/en books). */
    val NARRATOR_LANGUAGES = listOf(TtsLanguage.ENGLISH, TtsLanguage.CHINESE)

    /**
     * D3 + M5 (PLAN-MULTI-VOICE §13.4): the cloud engines have a controllable
     * voice library; the system engine joins conditionally — only once the user
     * annotated at least two usable voices ([SystemVoiceStore]), so the switch
     * stays off while there is nothing to assign.
     */
    fun engineSupportsMultiVoice(settings: CloudTtsSettings, systemUsableVoices: Int = 0): Boolean =
        settings.mode == TtsEngineMode.OPENAI_COMPAT ||
            settings.mode == TtsEngineMode.MIMO ||
            (settings.mode == TtsEngineMode.SYSTEM && systemUsableVoices >= 2)

    /**
     * Whether multi-voice should run at all right now. Cloud engines need the
     * network master switch (their synthesis is remote); only the LLM speaker
     * tagging degrades to rules. The system engine keeps the M5 gate: it also
     * needs the network switch.
     */
    fun multiVoiceActive(settings: CloudTtsSettings, systemUsableVoices: Int = 0): Boolean =
        settings.multiVoiceEnabled &&
            engineSupportsMultiVoice(settings, systemUsableVoices) &&
            settings.networkAiEnabled

    /**
     * Annotated system voices usable for assignment, for callers that hold a
     * [Context] (service / UI). Pure call sites pass the count themselves.
     */
    fun systemUsableVoiceCount(context: Context): Int =
        SystemVoiceStore.usableVoices(context).size

    fun voiceMapRepository(context: Context): VoiceMapRepository {
        val appContext = context.applicationContext
        val tags = speakerTagRepository(appContext)
        return VoiceMapRepository(
            appContext,
            charactersProvider = { bookId -> characters(appContext, bookId) },
            cooccurrenceProvider = { bookId ->
                SpeakerCooccurrence.from(tags.cachedSpeakerLists(bookId))
            }
        )
    }

    fun speakerTagRepository(context: Context): SpeakerTagRepository {
        val appContext = context.applicationContext
        return SpeakerTagRepository(
            appContext,
            AiSettingsStore(appContext),
            BookGlossaryRepository(appContext)
        )
    }

    /** 角色表的存储层（多角色面板「添加角色」直接写这里）。 */
    fun glossaryRepository(context: Context): BookGlossaryRepository =
        BookGlossaryRepository(context.applicationContext)

    /**
     * Characters to assign voices to: the per-book glossary roster, which the AI
     * profile fills and the user can edit (§7).
     */
    suspend fun characters(context: Context, bookId: String): List<VoiceCharacter> =
        BookGlossaryRepository(context.applicationContext).load(bookId).entries
            .filter { it.enabled }
            .mapNotNull { it.characterProfile() }
            .map { profile ->
                VoiceCharacter(
                    name = profile.name,
                    gender = profile.gender,
                    ageGroup = profile.ageGroup,
                    style = profile.style,
                    importance = profile.importance,
                    language = profile.language,
                    aliases = profile.aliases
                )
            }

    /** Voice ids the user already spent manually; kept out of auto assignment. */
    fun reservedVoices(settings: CloudTtsSettings): Set<String> =
        listOf(
            settings.narratorVoice,
            settings.dialogueVoice,
            // Per-language narration voices of a self-hosted engine are also
            // "spent": a character must not end up sounding like the narrator.
            settings.serverEnVoice,
            settings.serverZhVoice,
            // MiMo: the preset zh/en voices are the per-language narration
            // defaults (same rule as serverEn/zhVoice); designed/cloned voices
            // stay assignable because they exist exactly for characters.
            if (settings.mode == TtsEngineMode.MIMO) {
                settings.mimoZhVoice.ifBlank { CloudTtsSettings.DEFAULT_MIMO_ZH_VOICE }
            } else {
                ""
            }
        )
            .filter { it.isNotBlank() }
            .toSet()

    /** Refreshes the engine voice list when needed and returns the library. */
    suspend fun library(context: Context, settings: CloudTtsSettings): VoiceLibrary {
        val appContext = context.applicationContext
        VoiceLibraryLoader.refresh(appContext, settings)
        return VoiceLibraryLoader.load(appContext, settings)
    }

    /** Whether remote speaker tagging is available (else the rule layer runs). */
    fun taggingReady(context: Context): Boolean {
        val settings = AiSettingsStore(context.applicationContext).load()
        return settings.powerEnabled && settings.remoteReady
    }

    /**
     * One short audition line, in the language the voice speaks.
     *
     * Deliberately not a string resource: this is the *text being synthesised*
     * (TTS content), which must follow the voice language rather than the UI
     * locale.
     */
    fun sampleText(speakerName: String, language: String): String {
        val name = speakerName.trim()
        return if (language.equals(TtsLanguage.CHINESE, ignoreCase = true)) {
            if (name.isEmpty() || name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
                "他合上书，屋里安静了下来。"
            } else {
                "你好，我是" + name + "。"
            }
        } else {
            if (name.isEmpty() || name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
                "He closed the book, and the room fell silent."
            } else {
                "Hello, I am " + name + "."
            }
        }
    }

    /**
     * Status of the multi-voice setup (§8.5), as data rather than prose so the
     * panel can render it from string resources (and tests can assert on the
     * decision instead of the wording).
     */
    fun status(
        taggingReady: Boolean,
        rosterSize: Int,
        library: VoiceLibrary,
        map: BookVoiceMap?
    ): MultiVoiceStatus = when {
        library.isEmpty -> MultiVoiceStatus(MultiVoiceStatusKind.NO_LIBRARY)
        rosterSize == 0 -> MultiVoiceStatus(MultiVoiceStatusKind.NO_ROSTER)
        !taggingReady -> MultiVoiceStatus(MultiVoiceStatusKind.RULE_MODE)
        map == null -> MultiVoiceStatus(MultiVoiceStatusKind.NO_MAP)
        sharedVoiceCount(map) > 0 -> MultiVoiceStatus(
            kind = MultiVoiceStatusKind.SHARED_VOICES,
            characters = map.characterVoice.size,
            shared = sharedVoiceCount(map)
        )

        else -> MultiVoiceStatus(
            kind = MultiVoiceStatusKind.READY,
            characters = map.characterVoice.size
        )
    }

    /** How many characters share a voice with someone else. */
    fun sharedVoiceCount(map: BookVoiceMap): Int {
        val counts = mutableMapOf<String, Int>()
        map.characterVoice.values.forEach { voice ->
            counts[voice] = (counts[voice] ?: 0) + 1
        }
        return counts.values.filter { it > 1 }.sum()
    }
}
