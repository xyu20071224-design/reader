package com.linguareader.shared.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceTranslatorFactoryTest {
    @Test
    fun `serves the configured provider when remote is ready`() {
        val settings = AiSettings(enabled = true, apiKey = "provider-key")

        assertEquals("deepseek", SentenceTranslatorFactory.from(settings)?.id)
    }

    @Test
    fun `returns null when no translator is configured`() {
        assertNull(SentenceTranslatorFactory.from(AiSettings()))
    }

    @Test
    fun `isConfigured mirrors the factory choice`() {
        assertTrue(
            SentenceTranslatorFactory.isConfigured(AiSettings(enabled = true, apiKey = "provider-key"))
        )
        assertFalse(SentenceTranslatorFactory.isConfigured(AiSettings()))
    }

    @Test
    fun `isConfigured is false when the master power switch is off`() {
        val settings = AiSettings(
            enabled = true,
            apiKey = "provider-key",
            powerEnabled = false
        )

        assertFalse(SentenceTranslatorFactory.isConfigured(settings))
    }
}
