package com.linguareader.app.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzureSpeechClientTest {
    @Test
    fun buildSsmlEscapesXmlSpecialCharacters() {
        val ssml = AzureSpeechClient.buildSsml(
            "He said \"Hello\" & <wave> it's fine.",
            "en-US-AriaNeural"
        )

        assertTrue(ssml.contains("<voice name='en-US-AriaNeural'>"))
        assertTrue(ssml.contains("He said &quot;Hello&quot; &amp; &lt;wave&gt; it&apos;s fine."))
        assertTrue(ssml.startsWith("<speak version='1.0'"))
        assertTrue(ssml.endsWith("</voice></speak>"))
    }

    @Test
    fun buildSsmlKeepsChineseTextIntact() {
        val ssml = AzureSpeechClient.buildSsml("你好，世界。", "zh-CN-XiaoxiaoNeural")

        assertEquals(
            "<speak version='1.0' xml:lang='en-US'><voice name='zh-CN-XiaoxiaoNeural'>你好，世界。</voice></speak>",
            ssml
        )
    }
}
