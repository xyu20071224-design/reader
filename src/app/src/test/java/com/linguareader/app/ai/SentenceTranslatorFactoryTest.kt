package com.linguareader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceTranslatorFactoryTest {
    @Test
    fun `prefers azure when configured`() {
        val settings = AiSettings(
            azureTranslationEnabled = true,
            azureKey = "azure-key"
        )

        assertEquals("azure-translator", SentenceTranslatorFactory.from(settings)?.id)
    }

    @Test
    fun `falls back to deepseek when azure is not configured`() {
        val settings = AiSettings(enabled = true, apiKey = "deepseek-key")

        assertEquals("deepseek", SentenceTranslatorFactory.from(settings)?.id)
    }

    @Test
    fun `returns null when no translator is configured`() {
        assertNull(SentenceTranslatorFactory.from(AiSettings()))
    }
}
