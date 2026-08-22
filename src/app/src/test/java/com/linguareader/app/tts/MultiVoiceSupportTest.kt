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
    fun systemEngineJoinsOnceEnoughVoicesAreAnnotated() {
        // D2' (PLAN-MULTI-VOICE §13.4): the default count keeps every existing
        // call site and verdict unchanged; the system engine needs ≥2 usable
        // annotated voices.
        assertFalse(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.SYSTEM), 0))
        assertFalse(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.SYSTEM), 1))
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.SYSTEM), 2))
        // Cloud engines ignore the count entirely.
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.AZURE), 0))

        assertTrue(
            MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.SYSTEM), 2)
        )
        assertFalse(
            MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.SYSTEM, enabled = false), 2)
        )
        assertFalse(
            MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.SYSTEM, network = false), 2)
        )
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
        // 状态是数据而非文案，界面再映射到资源字符串（可本地化）。
        val library = VoiceLibrary(listOf(VoiceInfo("af_maple", "en", "female")), engine = "e")
        val map = BookVoiceMap("b", characterVoice = mapOf("Gandalf" to "af_maple"))

        assertEquals(
            MultiVoiceStatusKind.NO_LIBRARY,
            MultiVoiceSupport.status(true, 2, VoiceLibrary(), map).kind
        )
        assertEquals(
            MultiVoiceStatusKind.NO_ROSTER,
            MultiVoiceSupport.status(true, 0, library, map).kind
        )
        assertEquals(
            MultiVoiceStatusKind.RULE_MODE,
            MultiVoiceSupport.status(false, 2, library, map).kind
        )
        assertEquals(
            MultiVoiceStatusKind.NO_MAP,
            MultiVoiceSupport.status(true, 2, library, null).kind
        )
        val ready = MultiVoiceSupport.status(true, 1, library, map)
        assertEquals(MultiVoiceStatusKind.READY, ready.kind)
        assertEquals(1, ready.characters)
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
        val status = MultiVoiceSupport.status(true, 3, library, shared)
        assertEquals(MultiVoiceStatusKind.SHARED_VOICES, status.kind)
        assertEquals(3, status.characters)
        assertEquals(2, status.shared)

        val distinct = BookVoiceMap("b", characterVoice = mapOf("A" to "v1", "B" to "v2"))
        assertEquals(0, MultiVoiceSupport.sharedVoiceCount(distinct))
    }
}
