package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

class ProsodyCacheKeyTest {
    private val source = TtsUtterance(3, 1, 2, "narrator", "This is a long first clause, followed by another long clause.", 0, 0)
    private val policy = ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5)
    private fun key(
        input: TtsUtterance = source, rate: Float = 1f, p: ProsodyPlanner.Policy = policy,
        engine: String = "server:model:a", voice: String = "voice", part: Int = 0, synthesis: String = ""
    ): String = ProsodyCacheKey.relativePath(0, input, ProsodyPlanner.plan(input, rate, p), part, engine, voice, synthesis)

    @Test fun localSpeedPauseAndTailChangesReuseTheSameWaveformKey() {
        assertEquals(key(), key(rate = 1.5f, p = policy.copy(commaPauseMillis = 220, tailRateMultiplier = 0.88f)))
    }
    @Test fun sourceEngineVoiceAndSynthesisConfigurationAreAllBound() {
        assertNotEquals(key(), key(input = source.copy(text = source.text.replace("first", "other"))))
        assertNotEquals(key(), key(engine = "server:model:b"))
        assertNotEquals(key(), key(voice = "other voice"))
        assertNotEquals(key(), key(synthesis = "native-style-b"))
        assertNotEquals(key(), key(input = source.copy(sentenceIndex = 4)))
        assertNotEquals(key(), key(input = source.copy(segmentIndex = 0)))
    }
    @Test fun changingPlanBoundariesOrPartIndexCannotReadAnOldPart() {
        assertNotEquals(key(), key(p = policy.copy(minimumPhraseChars = 100)))
        assertNotEquals(key(), key(part = 1))
    }
    @Test fun namespaceIsSeparateAndDoesNotExposeTextOrServerAddress() {
        val value = key(engine = "https://private.example/path", voice = "../unsafe")
        assertEquals(true, value.startsWith("0/prosody-v1-"))
        assertFalse(value.contains("private.example"))
        assertFalse(value.contains("unsafe"))
        assertFalse(value.contains("first clause"))
        assertFalse(value.contains(".."))
    }
    @Test fun crossBlockQuoteProtectionIsTheSameForPreparingAndPlaying() {
        val blocks = listOf("He said, \"The story begins.", "A long continuation, followed by a second long clause.\"")
        val chapter = TtsChapter(0, "Chapter", blocks)
        val context = ProsodyPlanningContext(blocks)
        val continued = chapter.utterances.first { it.blockIndex == 1 }
        val plan = ProsodyPlanner.plan(continued, 1f, policy, context.protectedRanges(continued))
        assertEquals(listOf(continued), plan.map { it.utterance })
    }
}
