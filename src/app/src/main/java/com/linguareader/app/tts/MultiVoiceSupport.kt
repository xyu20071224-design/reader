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
     * D2: only the cloud engines have a controllable voice library. Piper ships
     * two built-in voices and the system engine cannot guarantee any, so the
     * switch stays disabled there.
     */
    fun engineSupportsMultiVoice(settings: CloudTtsSettings): Boolean =
        settings.mode == TtsEngineMode.AZURE ||
            settings.mode == TtsEngineMode.VOLC ||
            settings.mode == TtsEngineMode.OPENAI_COMPAT

    /** Whether multi-voice should run at all right now. */
    fun multiVoiceActive(settings: CloudTtsSettings): Boolean =
        settings.multiVoiceEnabled &&
            settings.networkAiEnabled &&
            engineSupportsMultiVoice(settings)

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
                    language = profile.language
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
            settings.serverZhVoice
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
