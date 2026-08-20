package com.linguareader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `isConfigured mirrors the factory choice`() {
        assertTrue(
            SentenceTranslatorFactory.isConfigured(
                AiSettings(azureTranslationEnabled = true, azureKey = "azure-key")
            )
        )
        assertTrue(
            SentenceTranslatorFactory.isConfigured(AiSettings(enabled = true, apiKey = "deepseek-key"))
        )
        assertFalse(SentenceTranslatorFactory.isConfigured(AiSettings()))
    }

    @Test
    fun `isConfigured is false when the master power switch is off`() {
        val settings = AiSettings(
            azureTranslationEnabled = true,
            azureKey = "azure-key",
            powerEnabled = false
        )

        assertFalse(SentenceTranslatorFactory.isConfigured(settings))
    }
}
