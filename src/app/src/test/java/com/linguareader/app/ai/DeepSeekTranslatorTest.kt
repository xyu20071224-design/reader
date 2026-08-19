package com.linguareader.app.ai

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun `unparseable json reply retries keeping json mode`() {
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

    @Test
    fun `transient failures are retryable`() {
        assertTrue(DeepSeekTranslator.isTransientFailure(java.io.IOException("connection reset")))
        assertTrue(
            DeepSeekTranslator.isTransientFailure(java.net.SocketTimeoutException("read timed out"))
        )
        assertTrue(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("DeepSeek API 返回 HTTP 429：rate limited", statusCode = 429)
            )
        )
        assertTrue(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("DeepSeek API 返回 HTTP 500：boom", statusCode = 500)
            )
        )
        assertTrue(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("DeepSeek API 返回 HTTP 503：busy", statusCode = 503)
            )
        )
    }

    @Test
    fun `non transient failures are not retried`() {
        assertFalse(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("DeepSeek API 返回 HTTP 400：bad request", statusCode = 400)
            )
        )
        assertFalse(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("DeepSeek API 返回 HTTP 401：unauthorized", statusCode = 401)
            )
        )
        assertFalse(
            DeepSeekTranslator.isTransientFailure(
                AiRequestException("AI 返回了无法解析的 JSON：hello")
            )
        )
        assertFalse(DeepSeekTranslator.isTransientFailure(IllegalStateException("boom")))
        assertFalse(DeepSeekTranslator.isTransientFailure(CancellationException("cancelled")))
    }

    @Test
    fun `language display names map codes and fall back gracefully`() {
        assertEquals("简体中文", DeepSeekTranslator.languageDisplayName("zh-Hans", "x"))
        assertEquals("简体中文", DeepSeekTranslator.languageDisplayName("zh-CN", "x"))
        assertEquals("英文", DeepSeekTranslator.languageDisplayName("en", "x"))
        assertEquals("日语", DeepSeekTranslator.languageDisplayName("ja", "x"))
        assertEquals("klingon", DeepSeekTranslator.languageDisplayName("klingon", "x"))
        assertEquals("英文", DeepSeekTranslator.languageDisplayName("  ", "英文"))
    }
}
