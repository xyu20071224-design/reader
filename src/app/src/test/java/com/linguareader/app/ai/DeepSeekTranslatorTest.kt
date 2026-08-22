package com.linguareader.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekTranslatorTest {
    @Test
    fun `response format rejection retries without json mode`() {
        assertTrue(
            DeepSeekTranslator.shouldRetryWithoutJsonMode(
                AiRequestException(
                    "DeepSeek API 返回 HTTP 400：This response_format type is unavailable now"
                )
            )
        )
    }

    @Test
    fun `unparseable json reply retries with json mode`() {
        // BUG-013: dropping the JSON constraint after a parse failure
        // guarantees another non-JSON reply — the retry must keep it.
        assertFalse(
            DeepSeekTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("AI 返回了无法解析的 JSON：hello")
            )
        )
        assertTrue(
            DeepSeekTranslator.shouldRetryKeepingJsonMode(
                AiRequestException("AI 返回了无法解析的 JSON：hello")
            )
        )
    }

    @Test
    fun `other failures do not retry`() {
        assertFalse(
            DeepSeekTranslator.shouldRetryWithoutJsonMode(
                AiRequestException("DeepSeek API 返回 HTTP 401：unauthorized")
            )
        )
        assertFalse(
            DeepSeekTranslator.shouldRetryWithoutJsonMode(
                IllegalStateException("boom")
            )
        )
    }
}
