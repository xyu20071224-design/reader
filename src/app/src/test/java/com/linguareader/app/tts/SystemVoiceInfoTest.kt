package com.linguareader.app.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SystemVoiceInfoTest {
    @Test
    fun chineseVoiceDetected() {
        val voice = SystemVoiceInfo("zh-CN-test", Locale.CHINA)
        assertTrue(voice.isChinese)
        assertFalse(voice.isEnglish)
    }

    @Test
    fun englishVoiceDetected() {
        val voice = SystemVoiceInfo("en-US-test", Locale.US)
        assertTrue(voice.isEnglish)
        assertFalse(voice.isChinese)
    }

    @Test
    fun displayNameIncludesNameAndNetworkMarker() {
        val local = SystemVoiceInfo("zh-CN-test", Locale.CHINA)
        val network = SystemVoiceInfo("en-US-test", Locale.US, isNetwork = true)
        assertTrue(local.displayName().contains("zh-CN-test"))
        assertFalse(local.displayName().contains("（网络）"))
        assertTrue(network.displayName().contains("（网络）"))
    }
}
