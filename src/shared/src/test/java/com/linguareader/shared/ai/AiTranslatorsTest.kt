package com.linguareader.shared.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslatorsTest {
    @Test
    fun `settings without providers dispatch to the openai compat translator`() {
        // 兼容桥：直接构造 AiSettings（旧路径与既有测试）默认 OpenAI 兼容。
        val translator = AiTranslators.forSettings(
            AiSettings(enabled = true, apiKey = "k", baseUrl = "https://api.example.com", model = "m")
        )
        assertTrue(translator is OpenAiCompatTranslator)
        assertEquals("https://api.example.com", (translator as OpenAiCompatTranslator).endpointBaseUrl)
        assertEquals("m", translator.endpointModel)
        assertEquals("DeepSeek", translator.displayName)
    }

    @Test
    fun `active provider protocol decides the wire translator`() {
        val settings = AiSettings(
            providers = listOf(
                AiProviderProfile(id = "a", name = "A 站", baseUrl = "https://a", apiKey = "ka", model = "m1"),
                AiProviderProfile(
                    id = "b", name = "Claude", baseUrl = "https://b", apiKey = "kb",
                    model = "claude-test", protocol = AiProtocol.ANTHROPIC
                )
            ),
            activeProviderId = "b"
        )
        val translator = AiTranslators.forSettings(settings)
        assertTrue(translator is AnthropicCompatTranslator)
        assertEquals("Claude", (translator as AnthropicCompatTranslator).displayName)
        assertEquals("claude-test", translator.endpointModel)
    }

    @Test
    fun `a stale active id falls back to the first provider`() {
        val settings = AiSettings(
            providers = listOf(
                AiProviderProfile(
                    id = "g", name = "Gemini", baseUrl = "https://g", apiKey = "kg",
                    model = "gemini-test", protocol = AiProtocol.GEMINI
                )
            ),
            activeProviderId = "missing"
        )
        assertTrue(AiTranslators.forSettings(settings) is GeminiCompatTranslator)
        assertEquals(AiProtocol.GEMINI, settings.effectiveProtocol)
    }

    @Test
    fun `for provider serves the settings editor draft probe`() {
        val draft = AiProviderProfile(
            id = "", name = "", baseUrl = "https://draft.example", apiKey = "kd",
            protocol = AiProtocol.GEMINI, model = "gemini-test"
        )
        val translator = AiTranslators.forProvider(draft)
        assertTrue(translator is GeminiCompatTranslator)
        assertEquals("https://draft.example", (translator as GeminiCompatTranslator).endpointBaseUrl)
    }
}
