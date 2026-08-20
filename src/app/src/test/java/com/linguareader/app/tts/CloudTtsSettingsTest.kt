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
            narratorVoice = "af_maple",
            dialogueVoice = "af_sol",
            multiVoiceEnabled = true
        )
        CloudTtsSettings.save(context, saved)
        val loaded = CloudTtsSettings.load(context)

        assertEquals(TtsEngineMode.OPENAI_COMPAT, loaded.mode)
        assertEquals("http://192.168.1.10:8000", loaded.serverUrl)
        assertEquals("af_maple", loaded.narratorVoice)
        assertEquals("af_sol", loaded.dialogueVoice)
        assertTrue(loaded.multiVoiceEnabled)
        // The switch alone decides multi-voice; the engine is a cloud one here.
        assertTrue(MultiVoiceSupport.multiVoiceActive(loaded))
    }

    @Test
    fun `turning the switch off is persisted too`() {
        CloudTtsSettings.save(context, CloudTtsSettings(multiVoiceEnabled = true))
        assertTrue(CloudTtsSettings.load(context).multiVoiceEnabled)

        CloudTtsSettings.save(context, CloudTtsSettings(multiVoiceEnabled = false))
        assertFalse(CloudTtsSettings.load(context).multiVoiceEnabled)
    }
}
