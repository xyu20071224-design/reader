package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M1 rule-tagger tests (PLAN-MULTI-VOICE §9): quote normalisation, quote
 * spans, front/back/mid attribution, cross-paragraph quotes, and the
 * narrator defaults for unquoted prose.
 */
class SpeakerRuleTaggerTest {

    private fun speakersOf(vararg blocks: String): List<String> =
        SpeakerRuleTagger.tag(blocks.toList())

    @Test
    fun plainNarrationIsNarrator() {
        assertEquals(
            listOf("narrator"),
            speakersOf("The wind blew through the trees.")
        )
    }

    @Test
    fun indirectSpeechStaysNarrator() {
        assertEquals(
            listOf("narrator"),
            speakersOf("He said that it was too late to leave.")
        )
    }

    @Test
    fun asciiApostrophesNeverOpenAQuote() {
        // Possessives and contractions must not be mistaken for quotes.
        assertEquals(
            listOf("narrator", "narrator"),
            speakersOf("Don't touch the king's ring. It's dangerous, 'tis true.")
        )
    }

    @Test
    fun curlyQuotesNormalisedToDialogue() {
        assertEquals(
            listOf("dialogue"),
            speakersOf("“Fly, you fools.”")
        )
        assertEquals(
            listOf("dialogue"),
            speakersOf("‘Hello there.’")
        )
    }

    @Test
    fun bareQuoteIsDialogue() {
        assertEquals(
            listOf("dialogue"),
            speakersOf("\"Fly, you fools.\"")
        )
    }

    @Test
    fun mixedBlockNarratorThenDialogue() {
        // Sentence splitter yields: "She nodded." | "\"Yes,\" she said." | "\"Go on.\""
        assertEquals(
            listOf("narrator", "dialogue", "dialogue"),
            speakersOf("She nodded. \"Yes,\" she said. \"Go on.\"")
        )
    }

    @Test
    fun frontAttributionPicksSpeakerName() {
        // "Gandalf said, "Fly, you fools."" is one sentence overlapping the
        // quote; the front attribution names the speaker for M2.
        assertEquals(
            listOf("Gandalf"),
            speakersOf("Gandalf said, \"Fly, you fools.\"")
        )
    }

    @Test
    fun frontAttributionIgnoresPronouns() {
        // "He said, …" — pronouns are not names, so the dialogue is
        // unattributed rather than being tagged "He".
        assertEquals(
            listOf("dialogue"),
            speakersOf("He said, \"Come with me.\"")
        )
    }

    @Test
    fun backAttributionPicksSpeakerName() {
        assertEquals(
            listOf("Gandalf"),
            speakersOf("\"Fly, you fools.\", said Gandalf.")
        )
    }

    @Test
    fun midAttributionCoversInsertedSpeech() {
        // "\"Fly\", said Gandalf, \"you fools.\"" — one sentence, two quotes,
        // both dialogue; the back attribution after the first quote names him.
        assertEquals(
            listOf("Gandalf"),
            speakersOf("\"Fly\", said Gandalf, \"you fools.\"")
        )
    }

    @Test
    fun crossParagraphQuoteCarriesOver() {
        // Block 1 ends inside an open quote; block 2 starts inside it.
        val speakers = speakersOf(
            "He said, \"I am coming.",
            "I really am.\" Then he left."
        )
        // Block 1: one sentence, inside the (unclosed) quote.
        // Block 2: sentence 1 inside the carried-over quote; sentence 2 narration.
        assertEquals(
            listOf("dialogue", "dialogue", "narrator"),
            speakers
        )
    }

    @Test
    fun trailingQuoteWithoutAttributionIsDialogue() {
        assertEquals(
            listOf("narrator", "dialogue"),
            speakersOf("He paused. \"Wait!\"")
        )
    }

    @Test
    fun speakersParallelToSentencesAcrossBlocks() {
        val speakers = speakersOf(
            "One. Two!",
            "\"Three?\"",
            "Four."
        )
        assertEquals(4, speakers.size)
        assertEquals(listOf("narrator", "narrator", "dialogue", "narrator"), speakers)
    }

    // ── paragraph / quote index (M2 alignment input) ──────────────────────

    @Test
    fun indexRecordsParagraphAndQuoteOrdinalPerSentence() {
        val index = SpeakerRuleTagger.index(
            listOf(
                "\"Fly, you fools,\" he shouted. \"Run to the bridge,\" he added.",
                "The hall fell silent."
            )
        )
        assertEquals(3, index.slots.size)
        assertEquals(listOf(0, 0, 1), index.slots.map { it.paragraph })
        assertEquals(listOf(0, 1, null), index.slots.map { it.quote })
        assertEquals(listOf("dialogue", "dialogue", "narrator"), index.ruleSpeakers)
        assertEquals(2, index.quoteCount)
        assertEquals(listOf("Fly, you fools,", "Run to the bridge,"), index.quotesOf(0))
        assertEquals(emptyList<String>(), index.quotesOf(1))
        assertEquals(emptyList<String>(), index.quotesOf(9))
    }

    @Test
    fun indexProjectsOntoTheSameSpeakersAsTag() {
        val blocks = listOf(
            "Gandalf said, \"Fly, you fools.\"",
            "He paused. \"Wait!\"",
            "Plain narration."
        )
        assertEquals(SpeakerRuleTagger.tag(blocks), SpeakerRuleTagger.index(blocks).ruleSpeakers)
    }

    @Test
    fun indexOfCarriedOverQuoteNumbersItInsideItsOwnParagraph() {
        val index = SpeakerRuleTagger.index(
            listOf(
                "He said, \"I am coming.",
                "I really am.\" Then he left."
            )
        )
        // The continuation quote is quote 0 of paragraph 1, so an LLM answer
        // for (p1, q0) still lands on the right sentence.
        assertEquals(listOf(0, 1, 1), index.slots.map { it.paragraph })
        assertEquals(listOf(0, 0, null), index.slots.map { it.quote })
    }

    @Test
    fun indexOfEmptyChapterIsEmpty() {
        val index = SpeakerRuleTagger.index(emptyList())
        assertEquals(emptyList<SpeakerSlot>(), index.slots)
        assertEquals(0, index.quoteCount)
    }

    // ── TtsChapter integration ────────────────────────────────────────────

    @Test
    fun chapterSpeakerAtDefaultsToNarrator() {
        val chapter = TtsChapter(0, "T", listOf("Hello. \"Hi!\""))
        assertEquals("narrator", chapter.speakerAt(0))
        // Out-of-range and missing tags fall back to narrator.
        assertEquals("narrator", chapter.speakerAt(5))
    }

    @Test
    fun chapterSpeakerAtReadsParallelTags() {
        val chapter = TtsChapter(
            0, "T",
            blocks = listOf("She nodded. \"Yes,\" she said."),
            speakers = listOf("narrator", "dialogue")
        )
        assertEquals("narrator", chapter.speakerAt(0))
        assertEquals("dialogue", chapter.speakerAt(1))
        assertEquals("narrator", chapter.speakerAt(99))
    }
}
