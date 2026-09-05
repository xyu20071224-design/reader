package com.linguareader.shared.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 三种线路协议（OpenAI 兼容 / Anthropic / Gemini）共享的业务层基类。
 *
 * prompt 构造、语境档案分块与合并、点词/整句/整本翻译的编排、JSON 容错
 * 解析全部在这里；子类只实现一条「system + user → JSONObject」的线路
 * （[chat]），以及各自的 JSON 模式重试策略。构造参数是显式连接四元组，
 * 由 [AiTranslators] 从生效服务商或设置表单草稿解析而来——线路层因此
 * 不认识 [AiSettings]，测试连接才能作用于还没保存的草稿。
 */
abstract class JsonChatTranslator(
    internal val endpointBaseUrl: String,
    internal val endpointApiKey: String,
    internal val endpointModel: String,
    override val displayName: String
) : AiTranslator, SentenceTranslator, AiChatClient, AiTranslationChatClient {

    override val offline = false

    private val maxCharsPerRequest = 24_000

    override suspend fun buildBookContext(
        bookTitle: String,
        chapters: List<ChapterText>
    ): BookContextProfile {
        if (chapters.isEmpty()) {
            return BookContextProfile(bookId = "", bookTitle = bookTitle, source = SOURCE_TAG)
        }
        val segments = chapterSegments(chapters)
        val partials = segments.map { segment ->
            val answer = chat(
                system = CONTEXT_SYSTEM_PROMPT,
                user = contextUserPrompt(bookTitle, segment),
                jsonMode = true,
                readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
                maxTokens = null
            )
            parsePartialProfile(answer)
        }
        return mergeProfiles(partials, bookTitle)
    }

    override suspend fun translate(
        profile: BookContextProfile,
        request: AiLookupRequest
    ): AiLookupResult? {
        val answer = chat(
            system = TRANSLATE_SYSTEM_PROMPT,
            user = translateUserPrompt(profile, request),
            jsonMode = true,
            readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
            maxTokens = null
        )
        val meaning = answer.optString("meaning").trim()
        if (meaning.isBlank()) return null
        return AiLookupResult(
            headword = request.headword,
            contextualMeaning = meaning,
            explanation = answer.optString("explanation").trim(),
            phrase = answer.optString("phrase").trim().takeIf { it.isNotBlank() }
                ?: request.matchedPhrase,
            source = displayName
        )
    }

    override suspend fun translateSentence(
        sentence: String,
        glossary: BookGlossary
    ): String {
        val user = buildString {
            if (glossary.entries.isNotEmpty()) {
                appendLine("本书术语表（词条 | 译法 | 说明）：")
                glossary.entries.filter { it.enabled }.forEach { entry ->
                    appendLine("${entry.term} | ${entry.translation.ifBlank { "保留原文" }} | ${entry.note}")
                }
                appendLine()
            }
            appendLine("请把下面这句英文翻译成简体中文，严格按照术语表译法处理专名与术语：")
            appendLine(sentence)
            appendLine()
            appendLine("只输出 JSON：{\"translation\":\"整句中文翻译\"}")
        }
        val answer = chat(
            system = SENTENCE_SYSTEM_PROMPT,
            user = user,
            jsonMode = true,
            readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
            maxTokens = null
        )
        return answer.optString("translation").trim().ifBlank {
            throw AiRequestException("AI 未返回整句翻译")
        }
    }

    /**
     * Shared JSON chat entry point (D1). The speaker tagger drives its own
     * prompts through this method, so retries, JSON recovery and error
     * reporting stay identical to the context-profile path.
     */
    override suspend fun chatJson(system: String, user: String): JSONObject =
        chat(system, user, jsonMode = true, DEFAULT_READ_TIMEOUT_MS, null)

    /**
     * 整本书翻译的批量出口（[AiTranslationChatClient]）。与语境请求不同，
     * 每批要原样吐回几千字的译文：输出 token 是真正的瓶颈，所以要显式
     * max_tokens（不设的话服务端默认值更小，长批会被静默截断），并把
     * 读超时放宽到 5 分钟（生成 8k token 是分钟级的事）。
     */
    override suspend fun translateSegments(system: String, user: String): JSONObject =
        chat(system, user, jsonMode = true, readTimeoutMs = 300_000, maxTokens = 8_192)

    /**
     * Connectivity probe for the settings screen: sends one tiny chat request
     * with the draft connection so the user can confirm the credential actually
     * reaches the endpoint before believing "就绪". Throws on any real failure
     * (bad key, bad model name, unreachable endpoint) so the caller can surface
     * the true reason. `jsonMode = false` avoids depending on any wire-level
     * JSON constraint; a reply that merely fails to parse as JSON still proves
     * the auth/endpoint/model round-trip works, so parse-only failures are
     * swallowed as success.
     */
    suspend fun verifyConnection() {
        try {
            chat(
                system = "你是连通性测试助手。",
                user = "请只输出一个 JSON 对象：{\"status\":\"ok\"}",
                jsonMode = false,
                readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
                maxTokens = null
            )
        } catch (e: Throwable) {
            if (e is AiRequestException && e.message?.contains("无法解析的 JSON") == true) return
            throw e
        }
    }

    /** One wire round trip; each protocol owns its JSON-mode retry policy. */
    protected abstract suspend fun chat(
        system: String,
        user: String,
        jsonMode: Boolean,
        readTimeoutMs: Int,
        maxTokens: Int?
    ): JSONObject

    // --- book context -------------------------------------------------------

    private fun chapterSegments(chapters: List<ChapterText>): List<List<ChapterText>> {
        val segments = mutableListOf<List<ChapterText>>()
        var current = mutableListOf<ChapterText>()
        var currentChars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                segments += current.toList()
                current = mutableListOf()
                currentChars = 0
            }
        }

        for (chapter in chapters) {
            if (chapter.text.length > maxCharsPerRequest) {
                flush()
                chapter.text.chunked(maxCharsPerRequest).forEach { chunk ->
                    segments += listOf(chapter.copy(text = chunk))
                }
                continue
            }
            if (current.isNotEmpty() && currentChars + chapter.text.length > maxCharsPerRequest) {
                flush()
            }
            current += chapter
            currentChars += chapter.text.length
        }
        flush()
        return segments
    }

    private fun contextUserPrompt(bookTitle: String, chapters: List<ChapterText>): String =
        buildString {
            appendLine("书名：$bookTitle")
            appendLine("以下是这本书的部分章节文本。请阅读并生成这本书的翻译语境档案，输出 JSON。")
            appendLine()
            chapters.forEach { chapter ->
                appendLine("## 第 ${chapter.index + 1} 章 ${chapter.title}")
                appendLine(chapter.text)
                appendLine()
            }
        }

    private fun parsePartialProfile(json: JSONObject): BookContextProfile {
        fun terms(key: String): List<ContextTerm> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val term = item.optString("term").trim()
                if (term.isBlank()) null
                else ContextTerm(
                    term = term,
                    translation = item.optString("translation").trim(),
                    note = item.optString("note").trim()
                )
            }
        }

        fun strings(key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
        }

        // Multi-voice M2 (D1): the book profile carries the voice-facing
        // character table produced by the same request.
        val profiles = json.optJSONArray("characterProfiles")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)
                    ?.let(CharacterProfile::fromJson)
                    ?.takeIf { it.name.isNotBlank() }
            }
        }.orEmpty()

        return BookContextProfile(
            bookId = "",
            bookTitle = "",
            summary = json.optString("summary").trim(),
            characters = terms("characters"),
            places = terms("places"),
            glossary = terms("glossary"),
            characterProfiles = profiles,
            styleNotes = strings("styleNotes")
        )
    }

    private fun mergeProfiles(
        partials: List<BookContextProfile>,
        bookTitle: String
    ): BookContextProfile {
        val summaries = partials.map { it.summary }.filter(String::isNotBlank).distinct()
        val styleNotes = partials.flatMap { it.styleNotes }.distinct()

        fun mergeTerms(groups: List<List<ContextTerm>>): List<ContextTerm> {
            val byKey = linkedMapOf<String, ContextTerm>()
            groups.flatten().forEach { term ->
                val key = term.term.lowercase()
                val existing = byKey[key]
                if (existing == null) {
                    byKey[key] = term
                } else if (existing.translation.isBlank() && term.translation.isNotBlank()) {
                    byKey[key] = existing.copy(translation = term.translation)
                }
            }
            return byKey.values.take(120)
        }

        val characterProfiles = linkedMapOf<String, CharacterProfile>()
        partials.flatMap { it.characterProfiles }.forEach { profile ->
            if (profile.name.isBlank()) return@forEach
            val existing = characterProfiles[profile.key]
            characterProfiles[profile.key] =
                existing?.mergedWith(profile) ?: profile
        }

        return BookContextProfile(
            bookId = "",
            bookTitle = bookTitle,
            summary = summaries.take(2).joinToString("\n").take(1_200),
            characters = mergeTerms(partials.map { it.characters }),
            places = mergeTerms(partials.map { it.places }),
            glossary = mergeTerms(partials.map { it.glossary }),
            characterProfiles = characterProfiles.values.take(60),
            styleNotes = styleNotes.take(16),
            source = SOURCE_TAG
        )
    }

    // --- translation --------------------------------------------------------

    private fun translateUserPrompt(
        profile: BookContextProfile,
        request: AiLookupRequest
    ): String = buildString {
        appendLine("书名：${request.bookTitle}")
        if (profile.summary.isNotBlank()) {
            appendLine("本书摘要：${profile.summary}")
        }
        if (profile.styleNotes.isNotEmpty()) {
            appendLine("文体说明：${profile.styleNotes.joinToString("；")}")
        }
        val terms = (
            if (request.glossary.isNotEmpty()) {
                request.glossary.map { Triple(it.term, it.translation, it.note) }
            } else {
                (profile.characters + profile.places + profile.glossary)
                    .map { Triple(it.term, it.translation, it.note) }
            }
            ).distinctBy { it.first.lowercase() }.take(80)
        if (terms.isNotEmpty()) {
            appendLine("本书术语（词条 | 译法 | 说明）：")
            terms.forEach { appendLine("${it.first} | ${it.second} | ${it.third}") }
        }
        appendLine()
        appendLine("点击词：${request.surfaceWord}")
        if (request.headword != request.surfaceWord) {
            appendLine("词形还原：${request.headword}")
        }
        request.matchedPhrase?.let { appendLine("命中的短语：$it") }
        appendLine("当前句：${request.sentence}")
        if (request.localSenses.isNotEmpty()) {
            appendLine("本地词典中文义项：")
            request.localSenses.take(8).forEachIndexed { index, sense ->
                appendLine("${index + 1}. $sense")
            }
        }
        if (request.localDefinitions.isNotEmpty()) {
            appendLine("本地词典英文定义：")
            request.localDefinitions.take(2).forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine(
            "请结合本书语境，从本地义项中选出最贴合本句的译法；若点击词是书中专名/术语，请给出本书译法。" +
                "只输出 JSON：{\"meaning\":\"中文释义\",\"explanation\":\"简短说明（可选）\",\"phrase\":\"命中的短语（无则省略）\"}"
        )
    }

    // --- shared parsing -----------------------------------------------------

    protected fun parseJsonObject(content: String): JSONObject {
        val trimmed = content.trim()
        runCatching { return JSONObject(trimmed) }.getOrNull()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { return JSONObject(trimmed.substring(start, end + 1)) }
                .getOrNull()
        }
        throw AiRequestException("AI 返回了无法解析的 JSON：${trimmed.take(200)}")
    }

    companion object {
        /**
         * 会话档案来源标签沿用 "deepseek"：磁盘上的旧档案、状态播种与
         * 远程/本地判定都按这个值区分，协议分化不改它。
         */
        const val SOURCE_TAG = "deepseek"
        const val DEFAULT_READ_TIMEOUT_MS = 60_000

        /** BUG-013: response-parse failures retry *with* the JSON constraint. */
        internal fun shouldRetryKeepingJsonMode(error: Throwable): Boolean =
            error is AiRequestException &&
                error.message?.contains("无法解析的 JSON", ignoreCase = true) == true

        private const val CONTEXT_SYSTEM_PROMPT =
            "你是一个英语阅读辅助工具。阅读用户提供的英文书籍章节，提取翻译语境信息，并顺带整理有台词的角色画像（用于听书多音色分配）。" +
                "只输出 JSON 对象，不要输出其他内容。JSON 结构：{\"summary\":\"全书/本节剧情与主题概述（中文，2-4句）\"," +
                "\"characters\":[{\"term\":\"英文名\",\"translation\":\"中文译名（无则留空）\",\"note\":\"角色说明\"}]," +
                "\"places\":[{\"term\":\"英文地名/机构名\",\"translation\":\"中文译名（无则留空）\",\"note\":\"说明\"}]," +
                "\"glossary\":[{\"term\":\"英文术语或反复出现的词\",\"translation\":\"本书语境译法\",\"note\":\"说明\"}]," +
                "\"characterProfiles\":[{\"name\":\"英文名（必须与 characters 中的 term 完全一致）\"," +
                "\"aliases\":[\"别名/尊称/昵称\"],\"gender\":\"male|female|unknown\"," +
                "\"ageGroup\":\"child|young|adult|elderly|unknown\",\"style\":[\"声音风格词，如 calm/deep/lively\"]," +
                "\"importance\":\"major|medium|minor\",\"language\":\"en|zh\",\"confidence\":0.9}]," +
                "\"styleNotes\":[\"文体/语气说明（口语、正式、方言、叙述风格等）\"]}。" +
                "角色判别（关键）：characters 和 characterProfiles 只能收\"会说话、有台词/对话、会思考行动\"的人或拟人化角色——" +
                "例如有对白的 Harry、Hermione、Gandalf。下述都\"不是角色\"，一律放进 places（地名/机构/组织/国家/城市/建筑/大学/帝国）" +
                "或 glossary（术语/专有名词/物品/法器/武器/年份/民族/头衔职衔/仅在叙述中被提及的专名）：Hogwarts、London、Azkaban、Ministry、" +
                "the Burrow、Diagon Alley、Quidditch、the Elder Wand、1926、Muggle（术语）。" +
                "拿不准某实体算角色还是地方/专名时，宁可放进 places/glossary，不要放进 characters。" +
                "characterProfiles 只列本节确实有台词或被明确称呼的角色（不超过 12 个），name 必须与 characters 的 term 完全一致；" +
                "无法判断的字段留空或写 unknown；术语列表控制在每项 8-15 条以内。"

        private const val TRANSLATE_SYSTEM_PROMPT =
            "你是一个英语阅读辅助工具。用户读英文书时点击一个单词，需要你结合该书语境给出最贴合的中文释义。" +
                "优先从用户提供的本地词典义项中挑选；若点击词是书中专名或术语，给出本书中的固定译法。" +
                "只输出 JSON 对象，不要输出其他内容：{\"meaning\":\"中文释义（必填）\",\"explanation\":\"为什么这个义项贴合本句（可选，1-2句）\",\"phrase\":\"命中短语（可选）\"}。" +
                "meaning 要简短，像词典义项一样可以直接用于学习。"

        private const val SENTENCE_SYSTEM_PROMPT =
            "你是一个英语阅读辅助工具。把用户提供的英文句子翻译成自然、通顺的简体中文。" +
                "用户给出的术语表译法必须优先采用；译法为“保留原文”的词保持英文不译。" +
                "只输出 JSON 对象：{\"translation\":\"整句中文翻译\"}。"
    }
}
