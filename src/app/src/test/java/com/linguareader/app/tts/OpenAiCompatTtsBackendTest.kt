package com.linguareader.app.tts

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiCompatTtsBackendTest {
    @Test
    fun requestBodyCarriesModelInputVoiceAndFormat() {
        val body = JSONObject(
            OpenAiCompatTtsBackend.buildRequestBody(
                text = "Hello 世界",
                voice = "default",
                model = "fish-speech"
            )
        )

        assertEquals("fish-speech", body.getString("model"))
        assertEquals("Hello 世界", body.getString("input"))
        assertEquals("default", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
    }

    @Test
    fun voiceForUsesConfiguredVoiceOrDefaults() {
        val backend = OpenAiCompatTtsBackend(
            CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT, serverVoice = "aria")
        )
        val blank = OpenAiCompatTtsBackend(CloudTtsSettings())

        assertEquals("aria", backend.voiceFor("anything"))
        assertEquals("default", blank.voiceFor("anything"))
        assertTrue(blank.isConfigured().not())
    }
}
