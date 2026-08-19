package com.linguareader.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Azure AI Translator sentence translation with per-request dynamic dictionary.
 *
 * Enabled glossary entries found in the sentence are wrapped in
 * `<mstrans:dictionary translation="...">` markup so proper nouns and terms
 * keep the book's chosen translation. A blank translation keeps the English
 * text unchanged (translation = original surface text).
 */
class AzureSentenceTranslator(private val settings: AiSettings) : SentenceTranslator {
    override val id = "azure-translator"
    override val displayName = "Azure"
    override val offline = false

    override suspend fun translateSentence(
        sentence: String,
        glossary: BookGlossary
    ): String {
        val marked = markupSentence(sentence, glossary.matchesIn(sentence))
        return withContext(Dispatchers.IO) {
            val base = settings.azureEndpoint.trimEnd('/')
            val fromLang = settings.sourceLanguage.trim().ifBlank { "en" }
            val toLang = settings.targetLanguage.trim().ifBlank { "zh-Hans" }
            val query = buildString {
                append("api-version=3.0")
                append("&from=").append(URLEncoder.encode(fromLang, "UTF-8"))
                append("&to=").append(URLEncoder.encode(toLang, "UTF-8"))
                append("&textType=plain")
            }
            val url = URL("$base/translate?$query")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Ocp-Apim-Subscription-Key", settings.azureKey)
                if (settings.azureRegion.isNotBlank()) {
                    connection.setRequestProperty("Ocp-Apim-Subscription-Region", settings.azureRegion)
                }
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true

                val body = JSONArray().put(JSONObject().put("Text", marked))
                connection.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val payload = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = payload?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw AiRequestException(
                        "Azure 翻译 API 返回 HTTP $code：${text.take(300)}",
                        statusCode = code
                    )
                }
                val root = JSONArray(text)
                root.getJSONObject(0)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        internal fun markupSentence(sentence: String, matches: List<GlossaryMatch>): String {
            if (matches.isEmpty()) return sentence
            val builder = StringBuilder(sentence)
            matches.sortedByDescending { it.start }.forEach { match ->
                val translation = match.entry.translation.ifBlank { match.text }
                val escaped = xmlEscape(translation)
                builder.replace(
                    match.start,
                    match.endExclusive,
                    "<mstrans:dictionary translation=\"$escaped\">${xmlEscape(match.text)}</mstrans:dictionary>"
                )
            }
            return builder.toString()
        }

        private fun xmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
