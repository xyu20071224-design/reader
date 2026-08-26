package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MiMo-V2.5-TTS backend contract (docs 2026-07): OpenAI-compatible
 * chat/completions, `api-key` header, assistant message carries the text to
 * synthesize, user message carries the style instruction or voice-design
 * prompt, audio returns as base64 in choices[0].message.audio.data.
 *
 * Voice ids are three kinds: preset (bare id), designed (mimo-design:<key>)
 * and cloned (mimo-clone:<key>); the design/clone routing is what makes
 * role-specific voices beyond the presets possible.
 */
@RunWith(RobolectricTestRunner::class)
class MiMoTtsBackendTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun settings() = CloudTtsSettings(
        mode = TtsEngineMode.MIMO,
        mimoApiKey = "test-key",
        mimoModel = "mimo-v2.5-tts",
        mimoZhVoice = "mimo_default",
        mimoEnVoice = "Mia"
    )

    @Test
    fun `preset voice request carries model assistant text and wav voice`() {
        val body = JSONObject(
            MiMoTtsBackend.buildRequestBody(
                text = "Hello world.",
                voice = "Mia",
                styleInstruction = ""
            )
        )
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        val messages = body.getJSONArray("messages")
        // No style instruction -> no user message; the assistant message is the text.
        assertEquals(1, messages.length())
        assertEquals("assistant", messages.getJSONObject(0).getString("role"))
        assertEquals("Hello world.", messages.getJSONObject(0).getString("content"))
        assertEquals("wav", body.getJSONObject("audio").getString("format"))
        assertEquals("Mia", body.getJSONObject("audio").getString("voice"))
    }

    @Test
    fun `preset voice with style instruction puts the instruction in the user message`() {
        val body = JSONObject(
            MiMoTtsBackend.buildRequestBody(
                text = "Good news!",
                voice = "Mia",
                styleInstruction = "Bright, bouncy, fast pace, rising pitch at the end."
            )
        )
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals(
            "Bright, bouncy, fast pace, rising pitch at the end.",
            messages.getJSONObject(0).getString("content")
        )
        assertEquals("Good news!", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `designed voice selects voicedesign model and drops the preset voice field`() {
        val body = JSONObject(
            MiMoTtsBackend.buildRequestBody(
                text = "台词",
                voice = "mimo-design:gandalf",
                styleInstruction = "苍老、低沉、语速极慢的智者嗓音"
            )
        )
        assertEquals("mimo-v2.5-tts-voicedesign", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("苍老、低沉、语速极慢的智者嗓音", messages.getJSONObject(0).getString("content"))
        assertEquals("台词", messages.getJSONObject(1).getString("content"))
        // audio carries only the format for designed voices.
        val audio = body.getJSONObject("audio")
        assertEquals("wav", audio.getString("format"))
        assertFalse(audio.has("voice"))
    }

    @Test
    fun `cloned voice selects voiceclone model and passes the sample as data uri`() {
        val body = JSONObject(
            MiMoTtsBackend.buildRequestBody(
                text = "Hello.",
                voice = "mimo-clone:dumbledore",
                styleInstruction = "",
                sampleDataUri = "data:audio/mpeg;base64,AAAA"
            )
        )
        assertEquals("mimo-v2.5-tts-voiceclone", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("Hello.", messages.getJSONObject(0).getString("content"))
        assertEquals("data:audio/mpeg;base64,AAAA", body.getJSONObject("audio").getString("voice"))
    }

    @Test
    fun `designed voice without prompt is rejected`() {
        val thrown = runCatching {
            MiMoTtsBackend.buildRequestBody(text = "x", voice = "mimo-design:gandalf")
        }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `audio base64 is decoded from the chat completion response`() {
        val encoded = android.util.Base64.encodeToString("RIFF-WAV-BYTES".toByteArray(), android.util.Base64.DEFAULT)
        val json = """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}"""
        val bytes = MiMoTtsBackend.decodeAudioData(json)
        assertEquals("RIFF-WAV-BYTES", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `malformed responses fail loudly`() {
        assertTrue(runCatching { MiMoTtsBackend.decodeAudioData("{}") }.isFailure)
        assertTrue(
            runCatching {
                MiMoTtsBackend.decodeAudioData("""{"choices":[{"message":{}}]}""")
            }.isFailure
        )
    }

    @Test
    fun `voiceFor routes chinese to the zh preset and everything else to the en preset`() {
        val backend = MiMoTtsBackend(settings(), context)
        assertEquals("mimo_default", backend.voiceFor("你好，世界。"))
        assertEquals("Mia", backend.voiceFor("Hello world."))
        assertTrue(backend.isConfigured())
    }

    @Test
    fun `model selection follows the voice kind`() {
        assertEquals(MiMoTtsBackend.MODEL_VOICE_DESIGN, MiMoTtsBackend.modelForVoice("mimo-design:a"))
        assertEquals(MiMoTtsBackend.MODEL_VOICE_CLONE, MiMoTtsBackend.modelForVoice("mimo-clone:b"))
        assertEquals("mimo-v2.5-tts", MiMoTtsBackend.modelForVoice("Mia"))
    }
}