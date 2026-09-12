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
    fun cloudEnginesSupportMultiVoice() {
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.OPENAI_COMPAT)))
        // MiMo joins the cloud engines: preset voices + user-designed/cloned
        // voices are all assignable per character.
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.MIMO)))
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
        assertTrue(MultiVoiceSupport.engineSupportsMultiVoice(settings(TtsEngineMode.OPENAI_COMPAT), 0))

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
        assertTrue(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.OPENAI_COMPAT)))
        assertFalse(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.OPENAI_COMPAT, enabled = false)))
        assertFalse(MultiVoiceSupport.multiVoiceActive(settings(TtsEngineMode.OPENAI_COMPAT, network = false)))
        // Default settings keep the feature off (§8.1).
        assertFalse(MultiVoiceSupport.multiVoiceActive(CloudTtsSettings()))
    }

    @Test
    fun manuallyConfiguredVoicesAreReserved() {
        val configured = CloudTtsSettings(narratorVoice = "af_maple", dialogueVoice = "af_sol")
        assertEquals(setOf("af_maple", "af_sol"), MultiVoiceSupport.reservedVoices(configured))
        assertTrue(MultiVoiceSupport.reservedVoices(CloudTtsSettings()).isEmpty())
    }

    /**
     * 第四轮审查 3-1：MiMo 模式下**中/英预置都要保留**。
     *
     * 此前 MIMO 分支只保留 `mimoZhVoice`，而英文对白/兜底走 `mimoEnVoice`（默认 `Mia`），
     * 于是英文角色可能被自动分配到同一个 id —— 角色与对白撞声。
     */
    @Test
    fun mimoPresetVoicesOfBothLanguagesAreReserved() {
        val mimo = CloudTtsSettings(
            mode = TtsEngineMode.MIMO,
            narratorVoice = "narrator.wav",
            dialogueVoice = "dialogue.wav"
        )

        val reserved = MultiVoiceSupport.reservedVoices(mimo)
        assertTrue(
            "中文预置应保留，实际：$reserved",
            CloudTtsSettings.DEFAULT_MIMO_ZH_VOICE in reserved
        )
        assertTrue(
            "英文预置（默认 ${CloudTtsSettings.DEFAULT_MIMO_EN_VOICE}）也必须保留，否则英文角色会与对白撞声",
            CloudTtsSettings.DEFAULT_MIMO_EN_VOICE in reserved
        )
        assertTrue("narratorVoice 应保留", "narrator.wav" in reserved)
        assertTrue("dialogueVoice 应保留", "dialogue.wav" in reserved)
    }

    @Test
    fun nonMimoEnginesDoNotReserveMimoPresets() {
        val reserved = MultiVoiceSupport.reservedVoices(CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT))
        assertFalse(
            "非 MiMo 引擎不应把 MiMo 预置音色算作已占用，实际：$reserved",
            CloudTtsSettings.DEFAULT_MIMO_EN_VOICE in reserved
        )
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
