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

    @Test
    fun voiceForRoutesChineseAndEnglishToTheirOwnVoices() {
        // M1.5 结论：IndexTTS 英文与中文各用一个参考音色。
        val split = OpenAiCompatTtsBackend(
            CloudTtsSettings(
                mode = TtsEngineMode.OPENAI_COMPAT,
                serverVoice = "voice_03.wav",
                serverEnVoice = "first_3s_1.wav",
                serverZhVoice = "voice_03.wav"
            )
        )
        assertEquals("first_3s_1.wav", split.voiceFor("Frodo woke suddenly."))
        assertEquals("voice_03.wav", split.voiceFor("佛罗多突然醒了。"))

        // Only one of them configured: the other language falls back.
        val partial = OpenAiCompatTtsBackend(
            CloudTtsSettings(
                mode = TtsEngineMode.OPENAI_COMPAT,
                serverVoice = "default",
                serverEnVoice = "af_maple"
            )
        )
        assertEquals("af_maple", partial.voiceFor("Hello."))
        assertEquals("default", partial.voiceFor("你好。"))
    }

    @Test
    fun voiceListParsingAcceptsEveryServerShape() {
        // Local Kokoro wrapper: {"voices":[...]}
        assertEquals(
            listOf("af_maple", "zf_001"),
            OpenAiCompatTtsBackend
                .parseVoiceList("{\"voices\":[\"af_maple\",\"zf_001\"],\"count\":2}")
                .map { it.id }
        )
        // OpenAI style: {"data":[{"id":…}]}
        assertEquals(
            listOf("alloy", "echo"),
            OpenAiCompatTtsBackend
                .parseVoiceList("{\"data\":[{\"id\":\"alloy\"},{\"id\":\"echo\"}]}")
                .map { it.id }
        )
        // Bare array, objects carrying only a name, duplicates collapsed.
        assertEquals(
            listOf("x", "y"),
            OpenAiCompatTtsBackend.parseVoiceList("[\"x\",\"y\",\"x\"]").map { it.id }
        )
        assertEquals(
            listOf("named"),
            OpenAiCompatTtsBackend.parseVoiceList("[{\"name\":\"named\"}]").map { it.id }
        )
        // Unusable payloads never break the library build.
        assertTrue(OpenAiCompatTtsBackend.parseVoiceList("not json").isEmpty())
        assertTrue(OpenAiCompatTtsBackend.parseVoiceList("{}").isEmpty())
        assertTrue(OpenAiCompatTtsBackend.parseVoiceList("").isEmpty())
    }

    @Test
    fun voiceListKeepsCloneMetadataWhenTheServerProvidesIt() {
        // M1.5: the IndexTTS wrapper describes each clone voice, so the M3
        // assigner can hard-filter it by language and gender.
        val voices = OpenAiCompatTtsBackend.parseVoiceList(
            "{\"voices\":[{\"id\":\"clone_gandalf.wav\",\"language\":\"en\"," +
                "\"gender\":\"male\",\"style\":[\"deep\",\"calm\"]}," +
                "{\"id\":\"voice_03.wav\"}],\"default\":\"voice_03.wav\"}"
        )
        assertEquals(2, voices.size)
        val clone = voices.first()
        assertEquals("clone_gandalf.wav", clone.id)
        assertEquals("en", clone.language)
        assertEquals("male", clone.gender)
        assertEquals(listOf("deep", "calm"), clone.style)
        // A bare entry stays metadata-free and is filled by the naming priors.
        assertEquals(ServerVoice("voice_03.wav"), voices[1])
    }
}
