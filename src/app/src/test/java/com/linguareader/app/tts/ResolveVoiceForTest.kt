package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第四轮审查 3-2：`resolveVoice` 的三级优先级此前只靠注释维系、服务本体零测试。
 *
 * 契约（从高到低）：
 * 1. 角色映射（M2/M3 自动分配 + M4 手调）；
 * 2. M1 手填旁白音色（speaker = narrator）；
 * 3. M1 手填对白音色。
 */
class ResolveVoiceForTest {

    @Test
    fun `character mapping wins over manual narrator and dialogue voices`() {
        assertEquals(
            "mapped-hero",
            resolveVoiceFor(
                speaker = "Frodo",
                mappingVoice = "mapped-hero",
                narratorVoice = "narrator.wav",
                dialogueVoice = "dialogue.wav"
            )
        )
    }

    @Test
    fun `mapping wins for the narrator speaker too`() {
        assertEquals(
            "mapped-narrator",
            resolveVoiceFor(
                speaker = SpeakerRuleTagger.NARRATOR,
                mappingVoice = "mapped-narrator",
                narratorVoice = "narrator.wav",
                dialogueVoice = "dialogue.wav"
            )
        )
    }

    @Test
    fun `blank mapping falls back to the manual voices`() {
        // 空/纯空白映射等于没有映射：不能把 "" 当成一个有效音色返回。
        assertEquals(
            "narrator.wav",
            resolveVoiceFor(SpeakerRuleTagger.NARRATOR, "   ", "narrator.wav", "dialogue.wav")
        )
        assertEquals(
            "dialogue.wav",
            resolveVoiceFor("Frodo", "   ", "narrator.wav", "dialogue.wav")
        )
    }

    @Test
    fun `narrator speaker uses the narrator voice regardless of case`() {
        assertEquals(
            "narrator.wav",
            resolveVoiceFor("Narrator", null, "narrator.wav", "dialogue.wav")
        )
        assertEquals(
            "narrator.wav",
            resolveVoiceFor(SpeakerRuleTagger.NARRATOR, null, "narrator.wav", "dialogue.wav")
        )
    }

    @Test
    fun `any other speaker uses the dialogue voice`() {
        assertEquals(
            "dialogue.wav",
            resolveVoiceFor("Sam", null, "narrator.wav", "dialogue.wav")
        )
        assertEquals(
            "dialogue.wav",
            resolveVoiceFor(SpeakerRuleTagger.DIALOGUE, null, "narrator.wav", "dialogue.wav")
        )
    }

    @Test
    fun `no mapping and no manual voice resolves to null`() {
        // null = 交给引擎默认音色（backend.voiceFor），不能返回空串。
        assertNull(resolveVoiceFor("Frodo", null, "", ""))
        assertNull(resolveVoiceFor(SpeakerRuleTagger.NARRATOR, null, "  ", ""))
    }
}
