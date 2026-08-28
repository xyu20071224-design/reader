package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Listening-settings persistence, including the M4 multi-voice switch (which
 * must default to off so nothing networked starts by itself).
 */
@RunWith(RobolectricTestRunner::class)
class CloudTtsSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `multi voice switch defaults to off`() {
        assertFalse(CloudTtsSettings.load(context).multiVoiceEnabled)
        assertFalse(CloudTtsSettings().multiVoiceEnabled)
    }

    @Test
    fun `voice settings survive a save and load round trip`() {
        val saved = CloudTtsSettings(
            mode = TtsEngineMode.OPENAI_COMPAT,
            serverUrl = "http://192.168.1.10:8000",
            serverModel = "kokoro",
            serverVoice = "af_maple",
            serverEnVoice = "first_3s_1.wav",
            serverZhVoice = "voice_03.wav",
            narratorVoice = "af_maple",
            dialogueVoice = "af_sol",
            multiVoiceEnabled = true
        )
        CloudTtsSettings.save(context, saved)
        val loaded = CloudTtsSettings.load(context)

        assertEquals(TtsEngineMode.OPENAI_COMPAT, loaded.mode)
        assertEquals("http://192.168.1.10:8000", loaded.serverUrl)
        assertEquals("first_3s_1.wav", loaded.serverEnVoice)
        assertEquals("voice_03.wav", loaded.serverZhVoice)
        assertEquals("af_maple", loaded.narratorVoice)
        assertEquals("af_sol", loaded.dialogueVoice)
        assertTrue(loaded.multiVoiceEnabled)
        // The switch alone decides multi-voice; the engine is a cloud one here.
        assertTrue(MultiVoiceSupport.multiVoiceActive(loaded))
    }

    @Test
    fun `mimo settings survive a save and load round trip`() {
        val saved = CloudTtsSettings(
            mode = TtsEngineMode.MIMO,
            mimoApiKey = "mimo-secret-key",
            mimoModel = "mimo-v2.5-tts",
            mimoZhVoice = "mimo_default",
            mimoEnVoice = "Mia",
            mimoStyleInstruction = "轻快上扬、语速稍快"
        )
        CloudTtsSettings.save(context, saved)
        val loaded = CloudTtsSettings.load(context)
        assertEquals(TtsEngineMode.MIMO, loaded.mode)
        // mimoApiKey 走 CloudKeyStore 加密（与 serverToken 同一模式），
        // Robolectric 的 Keystore 影子不能保证回读原文，按项目惯例不回读密文。
        assertEquals("mimo_default", loaded.mimoZhVoice)
        assertEquals("Mia", loaded.mimoEnVoice)
        assertEquals("轻快上扬、语速稍快", loaded.mimoStyleInstruction)
        assertTrue(CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoApiKey = "k").isConfigured)
        assertFalse(CloudTtsSettings(mode = TtsEngineMode.MIMO).isConfigured)
    }

    @Test
    fun `turning the switch off is persisted too`() {
        CloudTtsSettings.save(context, CloudTtsSettings(multiVoiceEnabled = true))
        assertTrue(CloudTtsSettings.load(context).multiVoiceEnabled)

        CloudTtsSettings.save(context, CloudTtsSettings(multiVoiceEnabled = false))
        assertFalse(CloudTtsSettings.load(context).multiVoiceEnabled)
    }
}
