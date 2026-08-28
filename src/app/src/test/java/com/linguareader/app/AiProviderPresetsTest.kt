package com.linguareader.app

import com.linguareader.app.ai.AiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderPresetsTest {
    @Test
    fun `deepseek preset serves the official endpoint with v4 flash as default`() {
        val preset = AiProviderPresets.DEEPSEEK
        assertEquals("deepseek", preset.id)
        assertEquals("https://api.deepseek.com", preset.baseUrl)
        assertEquals(AiProtocol.OPENAI_COMPAT, preset.protocol)
        assertEquals("deepseek-v4-flash", preset.defaultModel)
        assertTrue(preset.models.contains("deepseek-v4-pro"))
    }

    @Test
    fun `catalog exposes every preset`() {
        assertEquals(listOf(AiProviderPresets.DEEPSEEK), AiProviderPresets.ALL)
    }
}
