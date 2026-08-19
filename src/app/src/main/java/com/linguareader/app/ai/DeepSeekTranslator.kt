package com.linguareader.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek chat API implementation of [AiTranslator].
 *
 * The endpoint is OpenAI-compatible, so [AiSettings.baseUrl] and [AiSettings.model]
 * are configurable; the default points at https://api.deepseek.com.
 */
class DeepSeekTranslator(private val settings: AiSettings) : AiTranslator, SentenceTranslator {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val offline = false

    private val maxCharsPerRequest = 24_000

    override suspend fun buildBookContext(
        bookTitle: String,
        chapters: List<ChapterText>
    ): BookContextProfile {
        if (chapters.isEmpty()) {
            return BookContextProfile(bookId = "", bookTitle = bookTitle, source = "deepseek")
        }
        val segments = chapterSegments(chapters)
        val partials = segments.map { segment ->
            val answer = chat(
                system = CONTEXT_SYSTEM_PROMPT,
                user = contextUserPrompt(bookTitle, segment),
                jsonMode = true
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
            jsonMode = true
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
        val sourceName = languageDisplayName(settings.sourceLanguage, "英文")
        val targetName = languageDisplayName(settings.targetLanguage, "简体中文")
        val user = buildString {
            if (glossary.entries.isNotEmpty()) {
                appendLine("本书术语表（词条 | 译法 | 说明）：")
                glossary.entries.filter { it.enabled }.forEach { entry ->
                    appendLine("${entry.term} | ${entry.translation.ifBlank { "保留原文" }} | ${entry.note}")
                }
                appendLine()
            }
            appendLine("请把下面这句${sourceName}翻译成${targetName}，严格按照术语表译法处理专名与术语：")
            appendLine(sentence)
            appendLine()
            appendLine("只输出 JSON：{\"translation\":\"整句${targetName}翻译\"}")
        }
        val answer = chat(
            system = sentenceSystemPrompt(targetName),
            user = user,
            jsonMode = true
        )
        return answer.optString("translation").trim().ifBlank {
            throw AiRequestException("DeepSeek 未返回整句翻译")
        }
    }

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

        return BookContextProfile(
            bookId = "",
            bookTitle = "",
            summary = json.optString("summary").trim(),
            characters = terms("characters"),
            places = terms("places"),
            glossary = terms("glossary"),
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

        return BookContextProfile(
            bookId = "",
            bookTitle = bookTitle,
            summary = summaries.take(2).joinToString("\n").take(1_200),
            characters = mergeTerms(partials.map { it.characters }),
            places = mergeTerms(partials.map { it.places }),
            glossary = mergeTerms(partials.map { it.glossary }),
            styleNotes = styleNotes.take(16),
            source = "deepseek"
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

    // --- HTTP ---------------------------------------------------------------

    /**
     * Runs one chat request with bounded retries.
     *
     * - Endpoints that reject `response_format=json_object` get one retry
     *   without it (the prompt/parser still recover a JSON object).
     * - Transient failures (network errors, 429, 5xx) are retried with a
     *   short backoff up to [MAX_ATTEMPTS] total attempts, so a single
     *   timeout or rate-limit no longer drops the whole book profile to the
     *   offline fallback.
     */
    private suspend fun chat(
        system: String,
        user: String,
        jsonMode: Boolean
    ): JSONObject = withContext(Dispatchers.IO) {
        var activeJsonMode = jsonMode
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val result = runCatching { request(system, user, activeJsonMode) }
            val error = result.exceptionOrNull() ?: return@withContext result.getOrThrow()
            if (error is CancellationException) throw error
            if (activeJsonMode && attempt < MAX_ATTEMPTS && shouldRetryWithoutJsonMode(error)) {
                activeJsonMode = false
                continue
            }
            if (attempt < MAX_ATTEMPTS && shouldRetryKeepingJsonMode(error)) {
                continue
            }
            if (attempt < MAX_ATTEMPTS && isTransientFailure(error)) {
                delay(RETRY_BASE_DELAY_MS * attempt)
                continue
            }
            lastError = error
            break
        }
        throw lastError ?: AiRequestException("DeepSeek 请求失败")
    }

    private fun request(
        system: String,
        user: String,
        jsonMode: Boolean
    ): JSONObject {
        val url = URL(settings.baseUrl.trimEnd('/') + "/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = JSONObject()
                .put("model", settings.model)
                .put("temperature", 0.2)
                .put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                })
            if (jsonMode) {
                body.put("response_format", JSONObject().put("type", "json_object"))
            }

            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val payload = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = payload?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw AiRequestException(
                    "DeepSeek API 返回 HTTP $code：${text.take(300)}",
                    statusCode = code
                )
            }
            val content = try {
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (error: Exception) {
                throw AiRequestException("AI 返回了无法解析的 JSON：${text.take(200)}")
            }
            return parseJsonObject(content)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonObject(content: String): JSONObject {
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
        /** Total HTTP attempts per chat call (initial request + retries). */
        internal const val MAX_ATTEMPTS = 3

        /** Base backoff in ms; grows linearly per attempt (800, 1600). */
        internal const val RETRY_BASE_DELAY_MS = 800L

        /**
         * Failures worth retrying: network-level errors (DNS, connect,
         * timeout, reset) and transient HTTP statuses (429 rate limit,
         * 5xx server errors). Authorization/config errors (4xx) do not retry.
         */
        internal fun isTransientFailure(error: Throwable): Boolean =
            error is IOException ||
                (error is AiRequestException && (
                    error.statusCode == 429 || (error.statusCode ?: 0) in 500..599
                    ))

        /**
         * Some OpenAI-compatible endpoints (and some DeepSeek models) reject
         * `response_format={"type":"json_object"}` with a 400/422. Retrying
         * without it keeps the feature working; the prompt and parser still
         * recover a JSON object from the reply.
         */
        internal fun shouldRetryWithoutJsonMode(error: Throwable): Boolean =
            error is AiRequestException &&
                error.message?.contains("response_format", ignoreCase = true) == true

        /**
         * A successful HTTP response whose content is not valid JSON is a
         * content-formatting failure, not an endpoint capability failure.
         * Retrying with json_mode still enabled is more likely to fix it than
         * disabling the only thing that constrains the model to JSON.
         */
        internal fun shouldRetryKeepingJsonMode(error: Throwable): Boolean =
            error is AiRequestException &&
                error.statusCode == null &&
                error.message?.contains("无法解析的 JSON", ignoreCase = true) == true

        /** Chinese display name for a BCP-47-ish language code, else the code itself. */
        internal fun languageDisplayName(code: String, fallback: String): String {
            val clean = code.trim().ifBlank { return fallback }
            return LANGUAGE_NAMES[clean.lowercase()] ?: clean
        }

        private val LANGUAGE_NAMES = mapOf(
            "en" to "英文",
            "zh" to "中文",
            "zh-hans" to "简体中文",
            "zh-cn" to "简体中文",
            "zh-hant" to "繁体中文",
            "zh-tw" to "繁体中文",
            "ja" to "日语",
            "ko" to "韩语",
            "fr" to "法语",
            "de" to "德语",
            "es" to "西班牙语",
            "ru" to "俄语",
            "pt" to "葡萄牙语",
            "it" to "意大利语",
            "ar" to "阿拉伯语",
            "th" to "泰语",
            "vi" to "越南语",
            "id" to "印尼语",
            "tr" to "土耳其语"
        )

        private fun sentenceSystemPrompt(targetName: String) =
            "你是一个阅读辅助工具。把用户提供的句子翻译成自然、通顺的$targetName。" +
                "用户给出的术语表译法必须优先采用；译法为“保留原文”的词保持原文不译。" +
                "只输出 JSON 对象：{\"translation\":\"整句${targetName}翻译\"}。"

        private const val CONTEXT_SYSTEM_PROMPT =
            "你是一个英语阅读辅助工具。阅读用户提供的英文书籍章节，提取翻译语境信息。" +
                "只输出 JSON 对象，不要输出其他内容。JSON 结构：{\"summary\":\"全书/本节剧情与主题概述（中文，2-4句）\"," +
                "\"characters\":[{\"term\":\"英文名\",\"translation\":\"中文译名（无则留空）\",\"note\":\"角色说明\"}]," +
                "\"places\":[{\"term\":\"英文地名/机构名\",\"translation\":\"中文译名（无则留空）\",\"note\":\"说明\"}]," +
                "\"glossary\":[{\"term\":\"英文术语或反复出现的词\",\"translation\":\"本书语境译法\",\"note\":\"说明\"}]," +
                "\"styleNotes\":[\"文体/语气说明（口语、正式、方言、叙述风格等）\"]}。" +
                "术语列表控制在每项 8-15 条以内，优先保留对翻译影响大的专名与关键词。"

        private const val TRANSLATE_SYSTEM_PROMPT =
            "你是一个英语阅读辅助工具。用户读英文书时点击一个单词，需要你结合该书语境给出最贴合的中文释义。" +
                "优先从用户提供的本地词典义项中挑选；若点击词是书中专名或术语，给出本书中的固定译法。" +
                "只输出 JSON 对象，不要输出其他内容：{\"meaning\":\"中文释义（必填）\",\"explanation\":\"为什么这个义项贴合本句（可选，1-2句）\",\"phrase\":\"命中短语（可选）\"}。" +
                "meaning 要简短，像词典义项一样可以直接用于学习。"
    }
}
