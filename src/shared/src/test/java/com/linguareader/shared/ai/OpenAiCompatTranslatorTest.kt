package com.linguareader.shared.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatTranslatorTest {
    @Test
    fun `response format rejection retries without json mode`() {
        assertTrue(
            OpenAiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException(
                    "AI 接口返回 HTTP 400：This response_format type is unavailable now"
                )
            )
        )
    }

    @Test
    fun `unparseable json reply retries with json mode`() {
        // BUG-013: dropping the JSON constraint after a parse failure
        // guarantees another non-JSON reply — the retry must keep it.
        assertFalse(
            OpenAiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 返回了无法解析的 JSON：hello")
            )
        )
        assertTrue(
            JsonChatTranslator.shouldRetryKeepingJsonMode(
                AiRequestException("AI 返回了无法解析的 JSON：hello")
            )
        )
    }

    @Test
    fun `other failures do not retry`() {
        assertFalse(
            OpenAiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 接口返回 HTTP 401：unauthorized")
            )
        )
        assertFalse(
            OpenAiCompatTranslator.shouldRetryWithoutJsonMode(
                IllegalStateException("boom")
            )
        )
    }

    @Test
    fun `request body carries model messages and optional json mode`() {
        val plain = OpenAiCompatTranslator.buildRequestBody(
            "test-model", "sys", "user", jsonMode = false, maxTokens = null
        )
        assertEquals("test-model", plain.getString("model"))
        assertEquals(
            "sys",
            plain.getJSONArray("messages").getJSONObject(0).getString("content")
        )
        assertEquals(
            "user",
            plain.getJSONArray("messages").getJSONObject(1).getString("content")
        )
        assertFalse(plain.has("response_format"))
        assertFalse(plain.has("max_tokens"))

        val jsonMode = OpenAiCompatTranslator.buildRequestBody(
            "test-model", "sys", "user", jsonMode = true, maxTokens = 8_192
        )
        assertEquals("json_object", jsonMode.getJSONObject("response_format").getString("type"))
        assertEquals(8_192, jsonMode.getInt("max_tokens"))
    }

    @Test
    fun `reply extraction reads the first choice content and rejects wrong shapes`() {
        val reply = OpenAiCompatTranslator.extractReplyContent(
            JSONObject(
                """{"choices":[{"message":{"role":"assistant","content":"{\"status\":\"ok\"}"}}]}"""
            )
        )
        assertEquals("{\"status\":\"ok\"}", reply)
        try {
            OpenAiCompatTranslator.extractReplyContent(JSONObject("""{"error":"no choices"}"""))
            throw IllegalStateException("expected AiRequestException")
        } catch (expected: AiRequestException) {
            assertTrue(expected.message!!.contains("无法解析"))
        }
    }
}
