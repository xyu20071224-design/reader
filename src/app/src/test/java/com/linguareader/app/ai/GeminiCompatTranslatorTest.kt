package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCompatTranslatorTest {
    @Test
    fun `endpoint targets the model and strips the models prefix`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            GeminiCompatTranslator.buildEndpointUrl(
                "https://generativelanguage.googleapis.com", "gemini-2.0-flash"
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            GeminiCompatTranslator.buildEndpointUrl(
                "https://generativelanguage.googleapis.com/", "models/gemini-2.0-flash/"
            )
        )
    }

    @Test
    fun `request body routes system to system_instruction and toggles json mime type`() {
        val plain = GeminiCompatTranslator.buildRequestBody(
            "sys", "user", jsonMode = false, maxTokens = null
        )
        assertEquals(
            "sys",
            plain.getJSONObject("system_instruction")
                .getJSONArray("parts").getJSONObject(0).getString("text")
        )
        val turn = plain.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals("user", turn.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertFalse(plain.getJSONObject("generationConfig").has("responseMimeType"))
        assertEquals(
            GeminiCompatTranslator.DEFAULT_MAX_TOKENS,
            plain.getJSONObject("generationConfig").getInt("maxOutputTokens")
        )

        val jsonMode = GeminiCompatTranslator.buildRequestBody(
            "sys", "user", jsonMode = true, maxTokens = 8_192
        )
        assertEquals(
            "application/json",
            jsonMode.getJSONObject("generationConfig").getString("responseMimeType")
        )
        assertEquals(8_192, jsonMode.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test
    fun `reply extraction joins candidate parts`() {
        val text = GeminiCompatTranslator.extractReplyText(
            JSONObject(
                """
                {"candidates":[{"content":{"parts":[
                    {"text":"{\"a\":"},{"text":"\"b\":1}"}
                ]}}]}
                """.trimIndent()
            )
        )
        assertEquals("{\"a\":\"b\":1}", text)
    }

    @Test
    fun `missing candidates is a parse failure`() {
        try {
            GeminiCompatTranslator.extractReplyText(
                JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
            )
            throw IllegalStateException("expected AiRequestException")
        } catch (expected: AiRequestException) {
            assertTrue(expected.message!!.contains("无法解析"))
        }
    }

    @Test
    fun `json mime rejection retries without json mode`() {
        assertTrue(
            GeminiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 接口返回 HTTP 400：responseMimeType is not supported")
            )
        )
        assertTrue(
            GeminiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 接口返回 HTTP 400：unknown field response_mime_type")
            )
        )
        assertFalse(
            GeminiCompatTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 接口返回 HTTP 401：unauthorized")
            )
        )
    }
}
