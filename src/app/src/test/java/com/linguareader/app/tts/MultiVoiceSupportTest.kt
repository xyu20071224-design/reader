package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 support tests (PLAN-MULTI-VOICE §8): the D2 engine gate behind the master
 * switch, the reserved manual voices, audition sample lines and the status line
 * shown in the settings panel.
 */
class MultiVoiceSupportTest {

    private fun settings(
        mode: TtsEngineMode,
        enabled: Boolean = true,
        network: Boolean = true
    ) = CloudTtsSettings(mode = mode, networkAiEnabled = network, multiVoiceEnabled = enabled)

    @Test
    fun onlyCloudEnginesSupportMultiVoice() {
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.AZURE)))
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.VOLC)))
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.OPENAI_COMPAT)))
        // D2: Piper has two built-in voices, the system engine none it controls.
        assertFalse(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.PIPER)))
        assertFalse(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.SYSTEM)))
    }

    @Test
    fun multiVoiceNeedsSwitchNetworkAndACloudEngine() {
        assertTrue(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.AZURE)))
        assertFalse(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.AZURE, enabled = false)))
        assertFalse(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.AZURE, network = false)))
        assertFalse(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.PIPER)))
        // Default settings keep the feature off (§8.1).
        assertFalse(MultiVoiceSupport.multiVoiceActive(CloudTtsSettings()))
    }

    @Test
    fun manuallyConfiguredVoicesAreReserved() {
        val configured = CloudTtsSettings(narratorVoice = "af_maple", dialogueVoice = "af_sol")
        assertEquals(setOf("af_maple", "af_sol"), MultiVoiceSupport.reservedVoices(configured))
        assertTrue(MultiVoiceSupport.reservedVoices(CloudTtsSettings()).isEmpty())
    }

    @Test
    fun sampleLinesFollowTheVoiceLanguage() {
        assertEquals("Hello, I am Gandalf.", MultiVoiceSupport.sampleText("Gandalf", "en"))
        assertEquals("你好，我是甘道夫。", MultiVoiceSupport.sampleText("甘道夫", "zh"))
        // Narration gets a neutral prose line instead of an introduction.
        assertTrue(MultiVoiceSupport.sampleText("narrator", "en").startsWith("He closed"))
        assertTrue(MultiVoiceSupport.sampleText("", "zh").startsWith("他合上书"))
    }

    @Test
    fun statusExplainsEveryBlockedState() {
        val library = VoiceLibrary(listOf(VoiceInfo("af_maple", "en", "female")), engine = "e")
        val map = BookVoiceMap("b", characterVoice = mapOf("Gandalf" to "af_maple"))

        assertTrue(
            MultiVoiceSupport.statusMessage(true, 2, VoiceLibrary(), map).contains("没有可用音色列表")
        )
        assertTrue(
            MultiVoiceSupport.statusMessage(true, 0, library, map).contains("还没有角色表")
        )
        assertTrue(
            MultiVoiceSupport.statusMessage(false, 2, library, map).contains("规则模式")
        )
        assertTrue(
            MultiVoiceSupport.statusMessage(true, 2, library, null).contains("尚未生成音色映射")
        )
        assertTrue(
            MultiVoiceSupport.statusMessage(true, 1, library, map).contains("已为 1 个角色")
        )
    }

    @Test
    fun sharedVoicesAreReportedAsAShortage() {
        val shared = BookVoiceMap(
            "b",
            characterVoice = mapOf(
                "Gandalf" to "am_onyx",
                "Innkeeper" to "am_onyx",
                "Galadriel" to "af_sol"
            )
        )
        assertEquals(2, MultiVoiceSupport.sharedVoiceCount(shared))
        val library = VoiceLibrary(listOf(VoiceInfo("am_onyx", "en", "male")), engine = "e")
        assertTrue(MultiVoiceSupport.statusMessage(true, 3, library, shared).contains("音色数量不足"))

        val distinct = BookVoiceMap("b", characterVoice = mapOf("A" to "v1", "B" to "v2"))
        assertEquals(0, MultiVoiceSupport.sharedVoiceCount(distinct))
    }
}
