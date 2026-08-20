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

    /** One short audition line, in the language the voice speaks. */
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
     * Status line for the settings panel (§8.5): what the reader needs to know
     * before expecting different voices.
     */
    fun statusMessage(
        taggingReady: Boolean,
        rosterSize: Int,
        library: VoiceLibrary,
        map: BookVoiceMap?
    ): String = when {
        library.isEmpty ->
            "当前引擎还没有可用音色列表：请先在上方获取/填写音色（Azure 拉取音色、自建服务器提供 /voices）。"
        rosterSize == 0 ->
            "本书还没有角色表：请在 AI 中心生成语境档案，生成后角色会出现在这里。"
        !taggingReady ->
            "未配置 DeepSeek Key：说话人识别使用规则模式（只区分旁白与对白），角色音色暂不会生效。"
        map == null ->
            "尚未生成音色映射。"
        sharedVoiceCount(map) > 0 ->
            "音色数量不足：已为 " + map.characterVoice.size + " 个角色分配音色，其中 " +
                sharedVoiceCount(map) + " 个与不同场的角色共用音色。"
        else -> "已为 " + map.characterVoice.size + " 个角色分配音色。"
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
