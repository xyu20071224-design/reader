package com.linguareader.app.tts

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Canonical character names plus their aliases, used to validate LLM answers
 * (PLAN-MULTI-VOICE §4.2 「校验」).
 *
 * The roster is built from the per-book glossary (kind = "character"), which is
 * itself filled by the AI context profile and editable by the user (§7), so the
 * tagger can never invent a character that the book profile does not know.
 */
class SpeakerRoster private constructor(
    private val canonicalByAlias: Map<String, String>,
    val names: List<String>,
    private val aliasesByName: Map<String, List<String>>
) {
    val isEmpty: Boolean get() = names.isEmpty()

    /**
     * Canonical name for [name]: an exact (case-insensitive) name or alias hit,
     * "narrator" for the narration marker, null for anything else - an answer
     * outside the roster is rejected rather than trusted.
     */
    fun canonical(name: String?): String? {
        val cleaned = name?.trim()?.trim('"', '\'', '.', ',', ':', ';')?.trim()
        if (cleaned.isNullOrEmpty()) return null
        if (cleaned.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true)) {
            return SpeakerRuleTagger.NARRATOR
        }
        return canonicalByAlias[cleaned.lowercase()]
    }

    fun aliasesOf(name: String): List<String> = aliasesByName[name.lowercase()].orEmpty()

    /** Roster lines for the prompt, e.g. "Gandalf（别名：Mithrandir）". */
    fun promptLines(limit: Int = PROMPT_LIMIT): List<String> = names.take(limit).map { name ->
        val aliases = aliasesOf(name)
        if (aliases.isEmpty()) name else name + "（别名：" + aliases.joinToString("、") + "）"
    }

    data class Entry(val name: String, val aliases: List<String> = emptyList())

    companion object {
        private const val PROMPT_LIMIT = 40

        val EMPTY: SpeakerRoster = SpeakerRoster(emptyMap(), emptyList(), emptyMap())

        fun of(entries: List<Entry>): SpeakerRoster {
            val canonical = linkedMapOf<String, String>()
            val aliasesByName = linkedMapOf<String, List<String>>()
            val names = mutableListOf<String>()
            for (entry in entries) {
                val name = entry.name.trim()
                if (name.isEmpty()) continue
                val key = name.lowercase()
                if (aliasesByName.containsKey(key)) continue
                names += name
                canonical[key] = name
                val aliases = entry.aliases
                    .map(String::trim)
                    .filter { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
                    .distinctBy { it.lowercase() }
                aliasesByName[key] = aliases
                // A name always wins over an alias of another character.
                aliases.forEach { alias ->
                    val aliasKey = alias.lowercase()
                    if (!canonical.containsKey(aliasKey)) canonical[aliasKey] = name
                }
            }
            return SpeakerRoster(canonical, names, aliasesByName)
        }
    }
}

/**
 * Result of one chapter tagging attempt.
 *
 * [speakers] is always usable and always parallel to the chapter sentence list:
 * every rejected or missing LLM answer degrades to the rule-layer tag, so a
 * failed request can only ever cost quality, never correctness.
 */
data class SpeakerTagResult(
    val speakers: List<String>,
    /** [SpeakerLlmTagger.SOURCE_LLM] or [SpeakerLlmTagger.SOURCE_RULE]. */
    val source: String,
    /** LLM requests issued for the chapter (0 = pure rule layer). */
    val requests: Int = 0,
    /** Requests that returned a usable JSON answer. */
    val answers: Int = 0
) {
    /** Only a fully answered chapter is worth caching as an LLM result. */
    val complete: Boolean get() = requests > 0 && answers == requests
}

/**
 * LLM speaker tagger (PLAN-MULTI-VOICE §4.2, M2).
 *
 * Per D1 the chapter request rides the shared AI chat infrastructure: the
 * caller passes [chat] (in the app: `JsonChatTranslator::chatJson`), so key
 * handling, JSON-mode retry and error reporting are the context-profile ones.
 *
 * The class itself is pure Kotlin - prompt, validation, alignment and
 * degradation are all unit-testable with a scripted [chat] lambda:
 *
 * - **alignment** happens on (paragraph index, quote ordinal) pairs coming from
 *   [SpeakerRuleTagger.index], never on text matching, so LLM answers cannot
 *   drift against the extractor's whitespace normalisation;
 * - **validation** rejects speakers outside the roster and answers below
 *   [minConfidence];
 * - **degradation**: a rejected answer, a malformed field or a failed request
 *   falls back to the rule-layer tag for exactly those sentences. Note this is
 *   deliberately stronger than the plan's "fall back to narrator": the rule
 *   layer never invents a character either (it answers "dialogue" for an
 *   unattributed quote), so keeping it preserves the M1 two-voice behaviour
 *   instead of reading dialogue in the narrator voice;
 * - **budget**: long chapters are split into paragraph windows of at most
 *   [maxCharsPerRequest] characters, and windows without a single quote are
 *   never sent (pure narration is narrator by rule).
 */
class SpeakerLlmTagger(
    private val chat: suspend (system: String, user: String) -> JSONObject,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    private val maxCharsPerRequest: Int = DEFAULT_MAX_CHARS
) {

    /**
     * Tags one chapter. Never throws for backend problems (only cancellation
     * propagates); inspect [SpeakerTagResult.source] / [SpeakerTagResult.complete]
     * to decide whether the result is worth caching.
     */
    suspend fun tag(
        chapterTitle: String,
        blocks: List<String>,
        roster: SpeakerRoster
    ): SpeakerTagResult {
        val index = SpeakerRuleTagger.index(blocks)
        val rule = index.ruleSpeakers
        // Nothing to attribute: no sentences, no quotes, or no roster to
        // validate against (the book profile has not produced characters yet).
        if (rule.isEmpty() || index.quoteCount == 0 || roster.isEmpty) {
            return SpeakerTagResult(rule, SOURCE_RULE)
        }
        val windows = windows(blocks, index, maxCharsPerRequest)
        if (windows.isEmpty()) return SpeakerTagResult(rule, SOURCE_RULE)

        val answers = mutableListOf<JSONObject>()
        for (window in windows) {
            val answer = try {
                chat(SYSTEM_PROMPT, userPrompt(chapterTitle, blocks, index, roster, window))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                null
            }
            if (answer != null) answers += answer
        }
        if (answers.isEmpty()) {
            return SpeakerTagResult(rule, SOURCE_RULE, requests = windows.size, answers = 0)
        }
        return SpeakerTagResult(
            speakers = applyTags(index, answers, roster, minConfidence),
            source = SOURCE_LLM,
            requests = windows.size,
            answers = answers.size
        )
    }

    companion object {
        const val SOURCE_LLM = "llm"
        const val SOURCE_RULE = "rule"

        /** Answers below this confidence are dropped (§4.2 置信度阈值). */
        const val DEFAULT_MIN_CONFIDENCE = 0.6f

        /** Prompt budget per request; a long chapter is split into windows. */
        const val DEFAULT_MAX_CHARS = 12_000

        const val SYSTEM_PROMPT =
            "你是一个有声书制作助手。给定小说章节的连续段落与其中的引文编号，判断每一段引文由哪个角色说出。" +
                "只输出 JSON 对象，不要输出其他内容。" +
                "JSON 结构：{\"paragraphs\":[{\"p\":段索引,\"speaker\":\"整段旁白/独白的说话人\"," +
                "\"quotes\":[{\"q\":引文序号,\"speaker\":\"角色名\",\"confidence\":0.0到1.0}]}]}。" +
                "speaker 只能取用户给出的角色表中的名字，或 narrator（旁白、间接引语、心理活动、歌谣）。" +
                "拿不准时写 narrator 并给出较低 confidence，绝不要编造角色名或输出未给出的段索引。"

        /**
         * Paragraph windows to request, each at most [maxChars] characters and
         * containing at least one quote (a window of pure narration would cost
         * a request to confirm what the rule layer already knows).
         */
        fun windows(
            blocks: List<String>,
            index: SpeakerQuoteIndex,
            maxChars: Int = DEFAULT_MAX_CHARS
        ): List<IntRange> {
            if (blocks.isEmpty()) return emptyList()
            val budget = maxChars.coerceAtLeast(1)
            val ranges = mutableListOf<IntRange>()
            var start = 0
            var chars = 0
            for (paragraph in blocks.indices) {
                val length = blocks[paragraph].length + PARAGRAPH_OVERHEAD
                if (paragraph > start && chars + length > budget) {
                    ranges += start until paragraph
                    start = paragraph
                    chars = 0
                }
                chars += length
            }
            ranges += start..blocks.lastIndex
            return ranges.filter { range ->
                range.any { index.quotesOf(it).isNotEmpty() }
            }
        }

        /** The user prompt for one paragraph [window]; indices are absolute. */
        fun userPrompt(
            chapterTitle: String,
            blocks: List<String>,
            index: SpeakerQuoteIndex,
            roster: SpeakerRoster,
            window: IntRange
        ): String = buildString {
            if (chapterTitle.isNotBlank()) appendLine("章节：" + chapterTitle)
            appendLine("角色表（speaker 只能用这些名字或 narrator）：" + roster.promptLines().joinToString("，"))
            appendLine("以下是章节的连续段落，[pN] 是段索引（全章唯一），每段的引文按出现顺序编号 q0、q1…：")
            for (paragraph in window) {
                val text = blocks.getOrNull(paragraph) ?: continue
                appendLine("[p" + paragraph + "] " + text)
                index.quotesOf(paragraph).forEachIndexed { ordinal, quote ->
                    appendLine("  q" + ordinal + ": " + quote)
                }
            }
            appendLine()
            appendLine(
                "请为上面出现的每一条引文给出说话人，只输出 JSON，例如：" +
                    "{\"paragraphs\":[{\"p\":" + window.first + ",\"quotes\":[{\"q\":0," +
                    "\"speaker\":\"narrator\",\"confidence\":0.9}]}]}"
            )
        }

        /**
         * Projects LLM [answers] onto the sentence list of [index].
         *
         * A quote sentence takes its own quote answer, else a non-narrator
         * paragraph answer (some replies only tag the paragraph of a single
         * quote), else the rule tag. A narration sentence takes the paragraph
         * answer, else the rule tag.
         */
        fun applyTags(
            index: SpeakerQuoteIndex,
            answers: List<JSONObject>,
            roster: SpeakerRoster,
            minConfidence: Float = DEFAULT_MIN_CONFIDENCE
        ): List<String> {
            val paragraphSpeakers = mutableMapOf<Int, String>()
            val quoteSpeakers = mutableMapOf<Pair<Int, Int>, String>()
            for (answer in answers) {
                val paragraphs = answer.optJSONArray("paragraphs") ?: continue
                for (i in 0 until paragraphs.length()) {
                    val item = paragraphs.optJSONObject(i) ?: continue
                    val paragraph = item.optInt("p", -1)
                    if (paragraph < 0) continue
                    validate(item, roster, minConfidence)?.let {
                        paragraphSpeakers[paragraph] = it
                    }
                    val quotes = item.optJSONArray("quotes") ?: continue
                    for (j in 0 until quotes.length()) {
                        val quote = quotes.optJSONObject(j) ?: continue
                        val ordinal = quote.optInt("q", -1)
                        if (ordinal < 0) continue
                        validate(quote, roster, minConfidence)?.let {
                            quoteSpeakers[paragraph to ordinal] = it
                        }
                    }
                }
            }
            return index.slots.map { slot ->
                val ordinal = slot.quote
                if (ordinal != null) {
                    quoteSpeakers[slot.paragraph to ordinal]
                        ?: paragraphSpeakers[slot.paragraph]
                            ?.takeIf { it != SpeakerRuleTagger.NARRATOR }
                        ?: slot.ruleSpeaker
                } else {
                    paragraphSpeakers[slot.paragraph] ?: slot.ruleSpeaker
                }
            }
        }

        /** Single-answer convenience overload. */
        fun applyTags(
            index: SpeakerQuoteIndex,
            answer: JSONObject,
            roster: SpeakerRoster,
            minConfidence: Float = DEFAULT_MIN_CONFIDENCE
        ): List<String> = applyTags(index, listOf(answer), roster, minConfidence)

        private const val PARAGRAPH_OVERHEAD = 8

        /** Roster membership + confidence threshold; null = rejected. */
        private fun validate(
            json: JSONObject,
            roster: SpeakerRoster,
            minConfidence: Float
        ): String? {
            val canonical = roster.canonical(json.optString("speaker")) ?: return null
            // A missing confidence is treated as certain: the model answered
            // without being asked to score, and the name did validate.
            val confidence = json.optDouble("confidence", 1.0).toFloat()
            if (confidence < minConfidence) return null
            return canonical
        }
    }
}
