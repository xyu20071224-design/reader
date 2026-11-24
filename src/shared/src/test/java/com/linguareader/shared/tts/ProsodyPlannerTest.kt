package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProsodyPlannerTest {
    private val phrases = ProsodyPlanner.Policy(
        mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5
    )
    private fun source(text: String, offset: Int = 12, speaker: String = "narrator") =
        TtsUtterance(4, 2, 3, speaker, text, 7, offset)

    @Test fun originalIsTheDefaultAndPreservesTheExactSource() {
        val input = source("A long enough introduction, with another long enough final clause.")
        val units = ProsodyPlanner.plan(input, 1f)
        assertEquals(1, units.size)
        assertSame(input, units.single().utterance)
        assertEquals(0, units.single().pauseAfterMillis)
        assertEquals(1f, units.single().rate)
    }

    @Test fun phraseUnitsPreserveAllNonWhitespaceCharactersAndExactOffsets() {
        val input = source("Here is the first clause, followed by the second clause; finally the final clause.")
        val units = ProsodyPlanner.plan(input, 1f, phrases)
        assertEquals(3, units.size)
        assertEquals(input.text.filterNot(Char::isWhitespace), units.joinToString("") { it.utterance.text }.filterNot(Char::isWhitespace))
        for ((index, unit) in units.withIndex()) {
            val u = unit.utterance
            assertEquals(u.text, input.text.substring(u.offset - input.offset, u.offset - input.offset + u.length))
            assertEquals(input.sentenceIndex, u.sentenceIndex)
            assertEquals(input.segmentIndex, u.segmentIndex)
            assertEquals(input.segmentCount, u.segmentCount)
            assertEquals(input.blockIndex, u.blockIndex)
            assertEquals(input.speaker, u.speaker)
            assertEquals(index, unit.partIndex)
        }
    }

    @Test fun onlyInternalPartsPauseAndTheTerminalPartCanSlowSlightly() {
        val units = ProsodyPlanner.plan(source("Here is the first clause, followed by the second clause; finally the final clause."), 1.2f, phrases)
        assertEquals(listOf(100L, 160L, 0L), units.map { it.pauseAfterMillis })
        assertEquals(listOf(1.2f, 1.2f), units.dropLast(1).map { it.rate })
        assertEquals(1.2f * phrases.tailRateMultiplier, units.last().rate)
    }

    @Test fun aNonterminalVoiceSegmentDoesNotGetASentenceTailSlowdown() {
        val input = source("Here is the first clause, followed by the second clause,", speaker = "Gandalf")
        val units = ProsodyPlanner.plan(input, 1.2f, phrases)
        assertEquals(2, units.size)
        assertTrue(units.all { it.rate == 1.2f && it.utterance.speaker == "Gandalf" })
        assertEquals(0L, units.last().pauseAfterMillis)
    }

    @Test fun shortEnumerationsAreNotChoppedIntoIndividualWords() {
        val input = source("Apples, pears, peaches, and plums.")
        val units = ProsodyPlanner.plan(input, 1f, ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES))
        assertEquals(listOf(input), units.map { it.utterance })
    }

    @Test fun numericSeparatorsTimesRatiosAndUrlsStayIntact() {
        for (text in listOf("The final count was 12,345 and it remained unchanged.", "The train leaves at 12:30 after a short delay.", "The ratio remained 14:23 throughout the observation.", "You can visit https://example.com:8080/path for details.")) {
            val input = source(text)
            assertEquals(listOf(input), ProsodyPlanner.plan(input, 1f, phrases).map { it.utterance })
        }
    }

    @Test fun abbreviationBeforeCommaIsProtected() {
        val input = source("We considered a variety of cases e.g., all of the ordinary examples.")
        assertEquals(listOf(input), ProsodyPlanner.plan(input, 1f, phrases).map { it.utterance })
    }

    @Test fun quoteContentsAndBracketedAsidesAreProtected() {
        for (text in listOf("He said \"the first long phrase, followed by another long phrase\".", "He said “the first long phrase, followed by another long phrase”.", "The result (the first long phrase, followed by another long phrase) remained valid.")) {
            val input = source(text)
            assertEquals(listOf(input), ProsodyPlanner.plan(input, 1f, phrases).map { it.utterance })
        }
    }

    @Test fun apostrophesDoNotCreateQuoteSpansThatHideLaterClauseBoundaries() {
        val input = source("We don’t need another example, because this one is already clear.")
        val units = ProsodyPlanner.plan(input, 1f, phrases)
        assertEquals(2, units.size)
        assertTrue(units.first().utterance.text.contains("don’t"))
    }

    @Test fun knownCrossBlockQuoteContextCanProtectTheEntireUtterance() {
        val input = source("The first long phrase, followed by another long phrase.")
        assertEquals(listOf(input), ProsodyPlanner.plan(input, 1f, phrases, listOf(input.text.indices)).map { it.utterance })
    }

    @Test fun chinesePunctuationProducesOriginalSubstrings() {
        val input = source("这一部分包含完整的叙述，这一部分继续解释原来的想法；最后一部分结束这段叙述。")
        val units = ProsodyPlanner.plan(input, 1f, phrases)
        assertEquals(3, units.size)
        assertEquals(input.text, units.joinToString("") { it.utterance.text })
    }

    @Test fun maximumUnitsBoundsThePlanWithoutDroppingTheRemainder() {
        val input = source("A longer first clause, a longer second clause; a longer third clause, a longer fourth clause.")
        val units = ProsodyPlanner.plan(input, 1f, phrases.copy(maximumUnits = 2))
        assertEquals(2, units.size)
        assertEquals(input.text.filterNot(Char::isWhitespace), units.joinToString("") { it.utterance.text }.filterNot(Char::isWhitespace))
    }

    @Test fun ratesRemainWithinTheSupportedGlobalLimits() {
        val input = source("Here is a long first clause, followed by a longer final clause.")
        val low = ProsodyPlanner.plan(input, 0.5f, phrases.copy(tailRateMultiplier = 0.8f))
        val high = ProsodyPlanner.plan(input, 2f, phrases.copy(tailRateMultiplier = 1.2f))
        assertTrue(low.all { it.rate >= 0.5f })
        assertTrue(high.all { it.rate <= 2f })
    }

    @Test fun unknownBlockOffsetsAreNotInvented() {
        val input = source("Here is the first long clause, followed by another long clause.", offset = -1)
        assertTrue(ProsodyPlanner.plan(input, 1f, phrases).all { it.utterance.offset == -1 })
    }

    @Test fun invalidPoliciesAndUnknownRatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.Policy(commaPauseMillis = -1) }
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.Policy(tailRateMultiplier = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.Policy(minimumPhraseChars = 0) }
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.Policy(maximumUnits = 0) }
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.plan(source("Text."), Float.NaN) }
        val input = source("A long enough beginning, and an equally long ending.")
        assertFailsWith<IllegalArgumentException> { ProsodyPlanner.plan(input, 1f, phrases, listOf(-1..5)) }
    }
}
