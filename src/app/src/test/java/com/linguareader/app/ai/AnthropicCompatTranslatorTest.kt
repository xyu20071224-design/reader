package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicCompatTranslatorTest {
    @Test
    fun `endpoint appends the versioned messages path`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AnthropicCompatTranslator.buildEndpointUrl("https://api.anthropic.com")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AnthropicCompatTranslator.buildEndpointUrl("https://api.anthropic.com/")
        )
    }

    @Test
    fun `request body puts system on top and requires max tokens`() {
        val body = AnthropicCompatTranslator.buildRequestBody(
            "claude-test", "sys prompt", "user prompt", maxTokens = null
        )
        assertEquals("claude-test", body.getString("model"))
        assertEquals(AnthropicCompatTranslator.DEFAULT_MAX_TOKENS, body.getInt("max_tokens"))
        assertEquals("sys prompt", body.getString("system"))
        assertEquals(0.2, body.getDouble("temperature"), 1e-9)
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("user prompt", messages.getJSONObject(0).getString("content"))

        val explicit = AnthropicCompatTranslator.buildRequestBody(
            "claude-test", "sys", "user", maxTokens = 8_192
        )
        assertEquals(8_192, explicit.getInt("max_tokens"))
    }

    @Test
    fun `reply extraction joins text blocks and skips other block types`() {
        val text = AnthropicCompatTranslator.extractReplyText(
            JSONObject(
                """
                {"content":[
                    {"type":"text","text":"{\"a\":"},
                    {"type":"tool_use","id":"x"},
                    {"type":"text","text":"\"b\":1}"}
                ]}
                """.trimIndent()
            )
        )
        assertEquals("{\"a\":\"b\":1}", text)
    }

    @Test
    fun `empty or malformed content is a parse failure`() {
        listOf("""{"content":[]}""", """{"error":{"type":"overloaded"}}""").forEach { raw ->
            try {
                AnthropicCompatTranslator.extractReplyText(JSONObject(raw))
                throw IllegalStateException("expected AiRequestException for $raw")
            } catch (expected: AiRequestException) {
                assertTrue(expected.message!!.contains("无法解析"))
            }
        }
    }
}
