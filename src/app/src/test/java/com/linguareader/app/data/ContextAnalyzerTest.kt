package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class ContextAnalyzerTest {
    @Test
    fun tokenizationKeepsAbbreviationsPossessivesAndHyphens() {
        val tokens = ContextAnalyzer.tokenize("The U.S. editor praised the well-known writer's work.")

        assertTrue(tokens.map { it.text }.containsAll(listOf("U.S.", "well-known", "writer's")))
    }

    @Test
    fun phraseWindowsContainClickedTokenAndPreferLongerPhrases() {
        val sentence = "They looked forward to spring."
        val lookup = WordLookup("forward", sentence, sentence, 12, 0f, 0f)
        val tokens = ContextAnalyzer.tokenize(sentence)
        val target = ContextAnalyzer.targetIndex(tokens, lookup)
        val windows = ContextAnalyzer.phraseWindows(tokens, target)

        assertEquals("They looked forward to spring", windows.first().text)
        assertTrue(windows.any { it.text == "looked forward to" })
    }

    @Test
    fun verbSenseMovesAheadForVerbContext() {
        val sentence = "They will carry the lantern."
        val lookup = WordLookup("carry", sentence, sentence, 10, 0f, 0f)
        val tokens = ContextAnalyzer.tokenize(sentence)
        val inferred = ContextAnalyzer.inferPartOfSpeech(
            tokens,
            ContextAnalyzer.targetIndex(tokens, lookup)
        )
        val senses = ContextAnalyzer.senses(
            "n. 进位, 运载\\nvt. 携带, 运送\\nvi. 能达到",
            inferred
        )

        assertEquals(PartOfSpeech.VERB, inferred)
        assertTrue(senses.first().text.startsWith("vt."))
        assertTrue(senses.first().contextPreferred)
    }

    @Test
    fun lyAdjectivesAreNotMisclassifiedAsAdverbs() {
        val sentence = "She is a friendly teacher."
        val lookup = WordLookup("friendly", sentence, sentence, sentence.indexOf("friendly"), 0f, 0f)
        val tokens = ContextAnalyzer.tokenize(sentence)
        val inferred = ContextAnalyzer.inferPartOfSpeech(
            tokens,
            ContextAnalyzer.targetIndex(tokens, lookup)
        )

        assertEquals(PartOfSpeech.ADJECTIVE, inferred)
    }

    @Test
    fun regularLyAdverbsStillInferAsAdverbs() {
        val sentence = "She ran quickly."
        val lookup = WordLookup("quickly", sentence, sentence, sentence.indexOf("quickly"), 0f, 0f)
        val tokens = ContextAnalyzer.tokenize(sentence)
        val inferred = ContextAnalyzer.inferPartOfSpeech(
            tokens,
            ContextAnalyzer.targetIndex(tokens, lookup)
        )

        assertEquals(PartOfSpeech.ADVERB, inferred)
    }

    @Test
    fun phraseCoreTokenAllowsHeadAndParticlesButNotFunctionWords() {
        assertTrue(ContextAnalyzer.isCorePhraseToken("look", "look forward to"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("forward", "look forward to"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("to", "look forward to"))

        assertTrue(ContextAnalyzer.isCorePhraseToken("take", "take off"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("off", "take off"))

        assertTrue(ContextAnalyzer.isCorePhraseToken("lot", "a lot of"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("a", "a lot of"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("of", "a lot of"))

        assertTrue(ContextAnalyzer.isCorePhraseToken("order", "in order to"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("to", "in order to"))
    }

    @Test
    fun wordsInsidePhraseButNotHeadOrParticleDoNotTriggerPhrasePriority() {
        // A word merely adjacent to a phrase must not replace its own
        // lookup with the phrase meaning (F-122 false-positive guard).
        assertFalse(ContextAnalyzer.isCorePhraseToken("day", "good day"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("time", "out of time"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("coat", "take off one's coat"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("out", "run out of"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("together", "work together"))

        // in/on are particles after a verb but function words at the head
        // of a prepositional phrase.
        assertTrue(ContextAnalyzer.isCorePhraseToken("in", "give in"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("in", "in order to"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("order", "in order to"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("on", "turn on"))
        assertFalse(ContextAnalyzer.isCorePhraseToken("on", "on behalf of"))
        assertTrue(ContextAnalyzer.isCorePhraseToken("behalf", "on behalf of"))
    }

    @Test
    fun phraseCoreTokenMatchesLemmaOfInflectedSurfaceHead() {
        // The dictionary stores "have got to" inflected; tapping "got"
        // lemmatizes to "get" and must still count as the phrase core.
        assertTrue(
            ContextAnalyzer.isCorePhraseToken(
                "get", "have got to", listOf("have", "get", "to")
            )
        )
        assertFalse(
            ContextAnalyzer.isCorePhraseToken(
                "have", "have got to", listOf("have", "get", "to")
            )
        )
        assertTrue(
            ContextAnalyzer.isCorePhraseToken(
                "go", "to go", listOf("to", "go")
            )
        )
    }

    @Test
    fun targetIndexAtExclusiveBoundarySelectsNextToken() {
        val sentence = "good day"
        val tokens = ContextAnalyzer.tokenize(sentence)
        // The caret at the start of the next token belongs to that token, not
        // to the previous one whose endExclusive is exclusive.
        val offset = tokens[1].start
        val lookup = WordLookup("day", sentence, sentence, offset, 0f, 0f)
        val target = ContextAnalyzer.targetIndex(tokens, lookup)

        assertEquals(1, target)
    }

    @Test
    fun targetIndexPrefersClickedWordOverDriftedOffset() {
        val sentence = "He has got to go now."
        // The offset points inside "got", but the tapped word is "go":
        // the lookup must resolve to "go" so a nearby phrase stays
        // bound to the word actually tapped.
        val lookup = WordLookup("go", sentence, sentence, sentence.indexOf("got"), 0f, 0f)
        val tokens = ContextAnalyzer.tokenize(sentence)
        val target = ContextAnalyzer.targetIndex(tokens, lookup)

        assertEquals("go", tokens[target].text)
    }
}
