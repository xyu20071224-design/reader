package com.linguareader.app.tts

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureVoiceTest {
    @Test
    fun parsesVoiceListJson() {
        val json = JSONArray(
            """
            [
              {
                "Name": "Microsoft Server Speech Text to Speech Voice (en-US, JennyNeural)",
                "DisplayName": "Jenny",
                "LocalName": "Jenny",
                "ShortName": "en-US-JennyNeural",
                "Gender": "Female",
                "Locale": "en-US",
                "LocaleName": "English (United States)",
                "StyleList": ["chat"],
                "SampleRateHertz": "48000",
                "VoiceType": "Neural",
                "Status": "GA",
                "WordsPerMinute": "152"
              },
              {
                "Name": "Microsoft Server Speech Text to Speech Voice (en-US, JennyMultilingualNeural)",
                "DisplayName": "Jenny Multilingual",
                "ShortName": "en-US-JennyMultilingualNeural",
                "Gender": "Female",
                "Locale": "en-US",
                "SecondaryLocaleList": ["zh-cn", "ja-JP"],
                "Status": "GA"
              },
              {
                "Name": "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)",
                "DisplayName": "Xiaoxiao",
                "LocalName": "晓晓",
                "ShortName": "zh-CN-XiaoxiaoNeural",
                "Gender": "Female",
                "Locale": "zh-CN",
                "Status": "GA"
              }
            ]
            """.trimIndent()
        )

        val voices = AzureVoice.parse(json)

        assertEquals(3, voices.size)
        assertEquals("en-US-JennyNeural", voices[0].shortName)
        assertEquals(listOf("zh-cn", "ja-JP"), voices[1].secondaryLocales)
        assertTrue(voices[1].isMultilingual())
        assertTrue(voices[1].supportsEnglish())
        assertTrue(voices[1].supportsChinese())
        assertFalse(voices[0].isMultilingual())
        assertTrue(voices[2].supportsChinese())
    }

    @Test
    fun voiceJsonRoundTripKeepsSecondaryLocales() {
        val voice = AzureVoice(
            shortName = "en-US-JennyMultilingualNeural",
            locale = "en-US",
            gender = "Female",
            displayName = "Jenny Multilingual",
            secondaryLocales = listOf("zh-cn", "fr-FR"),
            status = "GA"
        )

        assertEquals(voice, AzureVoice.fromJson(voice.toJson()))
    }

    @Test
    fun pickerPrefersNamedDefaultsAndFallsBackToFirstLocale() {
        val voices = listOf(
            AzureVoice("en-US-EmmaNeural", "en-US", "Female", "Emma", status = "GA"),
            AzureVoice("zh-CN-YunxiNeural", "zh-CN", "Male", "云希", status = "GA")
        )

        assertEquals("en-US-EmmaNeural", CloudVoicePicker.defaultEnglish(voices))
        assertEquals("zh-CN-YunxiNeural", CloudVoicePicker.defaultChinese(voices))
        assertEquals(CloudVoicePicker.DEFAULT_ENGLISH, CloudVoicePicker.defaultEnglish(emptyList()))
        assertEquals(CloudVoicePicker.DEFAULT_CHINESE, CloudVoicePicker.defaultChinese(emptyList()))
    }

    @Test
    fun pickerFindsMultilingualVoiceSupportingBothLanguages() {
        val voices = listOf(
            AzureVoice("en-US-JennyMultilingualNeural", "en-US", "Female", "Jenny",
                secondaryLocales = listOf("zh-cn", "ja-JP"), status = "GA"),
            AzureVoice("zh-CN-YunxiNeural", "zh-CN", "Male", "云希", status = "GA")
        )

        assertEquals("en-US-JennyMultilingualNeural", CloudVoicePicker.defaultMultilingual(voices))
        assertNull(CloudVoicePicker.defaultMultilingual(emptyList()))
    }
}
