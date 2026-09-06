package com.linguareader.app.tts

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 LLM speaker tagger tests (PLAN-MULTI-VOICE §9): JSON parsing, roster and
 * alias normalisation, the confidence threshold, paragraph/quote alignment,
 * request windowing and the rule-layer degradation path.
 */
class SpeakerLlmTaggerTest {

    private val roster = SpeakerRoster.of(
        listOf(
            SpeakerRoster.Entry("Gandalf", listOf("Mithrandir")),
            SpeakerRoster.Entry("Frodo")
        )
    )

    // A two-paragraph chapter: p0 is two sentences carrying one quote each,
    // p1 is pure narration. Rule layer: dialogue, dialogue, narrator.
    private val blocks = listOf(
        "\"Fly, you fools,\" he shouted. \"Run to the bridge,\" he added.",
        "The hall fell silent."
    )

    private fun answer(json: String): JSONObject = JSONObject(json)

    // ── roster ────────────────────────────────────────────────────────────

    @Test
    fun rosterNormalisesAliasesAndCasing() {
        assertEquals("Gandalf", roster.canonical("Gandalf"))
        assertEquals("Gandalf", roster.canonical("gandalf"))
        assertEquals("Gandalf", roster.canonical("Mithrandir"))
        assertEquals("Gandalf", roster.canonical(" \"Gandalf,\" "))
        assertEquals("narrator", roster.canonical("Narrator"))
        assertNull(roster.canonical("Sauron"))
        assertNull(roster.canonical(""))
        assertNull(roster.canonical(null))
    }

    @Test
    fun rosterPromptListsAliases() {
        val prompt = roster.promptLines()
        assertEquals(listOf("Gandalf（别名：Mithrandir）", "Frodo"), prompt)
        assertTrue(SpeakerRoster.EMPTY.isEmpty)
    }

    // ── alignment / validation ────────────────────────────────────────────

    @Test
    fun quoteAnswersAlignByParagraphAndQuoteIndex() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer(
                "{\"paragraphs\":[{\"p\":0,\"quotes\":[" +
                    "{\"q\":0,\"speaker\":\"Gandalf\",\"confidence\":0.97}," +
                    "{\"q\":1,\"speaker\":\"Frodo\",\"confidence\":0.88}]}," +
                    "{\"p\":1,\"speaker\":\"narrator\"}]}"
            ),
            roster
        )
        assertEquals(index.slots.size, speakers.size)
        assertEquals(listOf("Gandalf", "Frodo", "narrator"), speakers)
    }

    @Test
    fun aliasAnswerIsNormalisedToTheCanonicalName() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Mithrandir\"}]}]}"),
            roster
        )
        assertEquals("Gandalf", speakers[0])
    }

    @Test
    fun unknownSpeakerFallsBackToTheRuleLayer() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Sauron\",\"confidence\":0.99}]}]}"),
            roster
        )
        // Rejected name: the quote keeps the rule-layer tag (dialogue), it is
        // never read as narration and never becomes an invented character.
        assertEquals(index.ruleSpeakers, speakers)
        assertEquals("dialogue", speakers[0])
    }

    @Test
    fun dialogueIsAcceptedForAnUnattributableQuote() {
        // 提示词让模型「拿不准时写 dialogue」；它必须能通过校验放行，
        // 与规则层的未署名标签同值，而不是被拒绝后绕一圈回退。
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"dialogue\",\"confidence\":0.7}]}]}"),
            roster
        )
        assertEquals("dialogue", speakers[0])
        assertEquals("dialogue", roster.canonical("dialogue"))
    }

    @Test
    fun paragraphLevelDialogueDoesNotSilenceNarration() {
        // 段落级 speaker 语义是「整段旁白/独白的说话人」；若模型误把它写成
        // dialogue（未署名对话），绝不能应用到该段的旁白句上——否则整段
        // 旁白都会变成对话声。引文级 dialogue 才放行。
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":1,\"speaker\":\"dialogue\"}]}"),
            roster
        )
        // p1 是纯旁白句；段落级 dialogue 被忽略，旁白保留规则层的 narrator。
        assertEquals("narrator", speakers[2])
    }

    @Test
    fun lowConfidenceAnswerIsDropped() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Frodo\",\"confidence\":0.2}]}]}"),
            roster,
            minConfidence = 0.6f
        )
        assertEquals("dialogue", speakers[0])
    }

    @Test
    fun missingFieldsAreIgnored() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer(
                "{\"paragraphs\":[{\"quotes\":[{\"q\":0,\"speaker\":\"Frodo\"}]}," +
                    "{\"p\":0,\"quotes\":[{\"speaker\":\"Frodo\"},{\"q\":1}]}," +
                    "{\"p\":9,\"speaker\":\"Gandalf\"}]}"
            ),
            roster
        )
        // No paragraph index, no quote index, and a paragraph that does not
        // exist: everything degrades to the rule layer, nothing shifts.
        assertEquals(index.ruleSpeakers, speakers)
    }

    @Test
    fun malformedAnswerKeepsRuleSpeakers() {
        val index = SpeakerRuleTagger.index(blocks)
        assertEquals(
            index.ruleSpeakers,
            SpeakerLlmTagger.applyTags(index, answer("{\"oops\":true}"), roster)
        )
    }

    @Test
    fun paragraphSpeakerCoversNarrationAndUnansweredQuotes() {
        // A quote-less speaker line ("NAME: line" style narration) plus a
        // paragraph-level answer for a paragraph whose quote got no entry.
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer(
                "{\"paragraphs\":[{\"p\":0,\"speaker\":\"Gandalf\"}," +
                    "{\"p\":1,\"speaker\":\"Frodo\"}]}"
            ),
            roster
        )
        // p0: both quotes inherit the paragraph speaker; p1: narration line
        // spoken by Frodo (script-style paragraph).
        assertEquals(listOf("Gandalf", "Gandalf", "Frodo"), speakers)
    }

    @Test
    fun narratorParagraphAnswerNeverSilencesDialogue() {
        val index = SpeakerRuleTagger.index(blocks)
        val speakers = SpeakerLlmTagger.applyTags(
            index,
            answer("{\"paragraphs\":[{\"p\":0,\"speaker\":\"narrator\"}]}"),
            roster
        )
        // The quotes in p0 keep the dialogue tag: a paragraph-level narrator
        // answer only ever applies to narration sentences.
        assertEquals(listOf("dialogue", "dialogue", "narrator"), speakers)
    }

    // ── windowing ─────────────────────────────────────────────────────────

    @Test
    fun windowsSkipParagraphRangesWithoutQuotes() {
        val plain = listOf("A.", "B.", "C.")
        val index = SpeakerRuleTagger.index(plain)
        assertTrue(SpeakerLlmTagger.windows(plain, index).isEmpty())

        val index2 = SpeakerRuleTagger.index(blocks)
        assertEquals(listOf(0..1), SpeakerLlmTagger.windows(blocks, index2))
    }

    @Test
    fun longChaptersAreSplitIntoWindows() {
        val long = List(4) { "\"Line " + it + ".\" said Frodo." + "x".repeat(60) }
        val index = SpeakerRuleTagger.index(long)
        val windows = SpeakerLlmTagger.windows(long, index, maxChars = 100)
        assertEquals(4, windows.size)
        assertEquals(0..0, windows.first())
        assertEquals(3..3, windows.last())
    }

    @Test
    fun promptCarriesAbsoluteParagraphAndQuoteIndexes() {
        val index = SpeakerRuleTagger.index(blocks)
        val prompt = SpeakerLlmTagger.userPrompt("Chapter 1", blocks, index, roster, 0..1)
        assertTrue(prompt.contains("[p0]"))
        assertTrue(prompt.contains("[p1]"))
        assertTrue(prompt.contains("q0: Fly, you fools,"))
        assertTrue(prompt.contains("q1: Run to the bridge,"))
        assertTrue(prompt.contains("Gandalf（别名：Mithrandir）"))
    }

    // ── end to end with a scripted chat backend ───────────────────────────

    @Test
    fun taggerUsesLlmAnswerAndReportsCompleteness() = runTest {
        val prompts = mutableListOf<String>()
        val tagger = SpeakerLlmTagger(chat = { _, user ->
            prompts += user
            JSONObject(
                "{\"paragraphs\":[{\"p\":0,\"quotes\":[" +
                    "{\"q\":0,\"speaker\":\"Gandalf\",\"confidence\":0.9}," +
                    "{\"q\":1,\"speaker\":\"Gandalf\",\"confidence\":0.9}]}]}"
            )
        })
        val result = tagger.tag("Chapter 1", blocks, roster)
        assertEquals(1, prompts.size)
        assertEquals(SpeakerLlmTagger.SOURCE_LLM, result.source)
        assertTrue(result.complete)
        assertEquals(listOf("Gandalf", "Gandalf", "narrator"), result.speakers)
    }

    @Test
    fun backendFailureDegradesToRuleLayer() = runTest {
        val tagger = SpeakerLlmTagger(chat = { _, _ -> throw IllegalStateException("HTTP 500") })
        val result = tagger.tag("Chapter 1", blocks, roster)
        assertEquals(SpeakerLlmTagger.SOURCE_RULE, result.source)
        assertEquals(SpeakerRuleTagger.tag(blocks), result.speakers)
        assertEquals(1, result.requests)
        assertEquals(0, result.answers)
        assertTrue(!result.complete)
    }

    @Test
    fun partialWindowFailureIsUsedButNotCacheable() = runTest {
        val long = List(4) { "\"Line " + it + ".\" said someone." + "x".repeat(60) }
        var call = 0
        val tagger = SpeakerLlmTagger(
            chat = { _, _ ->
                call++
                if (call == 1) {
                    JSONObject("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Frodo\"}]}]}")
                } else {
                    throw IllegalStateException("timeout")
                }
            },
            maxCharsPerRequest = 100
        )
        val result = tagger.tag("Chapter 1", long, roster)
        assertEquals(SpeakerLlmTagger.SOURCE_LLM, result.source)
        assertEquals(4, result.requests)
        assertEquals(1, result.answers)
        assertTrue(!result.complete)
        assertEquals("Frodo", result.speakers.first())
    }

    @Test
    fun noRosterOrNoQuotesNeverCallsTheBackend() = runTest {
        var calls = 0
        val tagger = SpeakerLlmTagger(chat = { _, _ -> calls++; JSONObject("{}") })

        val withoutRoster = tagger.tag("Chapter 1", blocks, SpeakerRoster.EMPTY)
        assertEquals(SpeakerLlmTagger.SOURCE_RULE, withoutRoster.source)

        val withoutQuotes = tagger.tag("Chapter 1", listOf("Plain narration."), roster)
        assertEquals(SpeakerLlmTagger.SOURCE_RULE, withoutQuotes.source)
        assertEquals(listOf("narrator"), withoutQuotes.speakers)

        val empty = tagger.tag("Chapter 1", emptyList(), roster)
        assertEquals(emptyList<String>(), empty.speakers)
        assertEquals(0, calls)
    }

    @Test
    fun taggedSpeakersStayParallelToChapterSentences() = runTest {
        val tagger = SpeakerLlmTagger(chat = { _, _ ->
            JSONObject("{\"paragraphs\":[{\"p\":0,\"quotes\":[{\"q\":0,\"speaker\":\"Frodo\"}]}]}")
        })
        val result = tagger.tag("Chapter 1", blocks, roster)
        val chapter = TtsChapter(0, "Chapter 1", blocks).withSpeakers(result.speakers)
        assertEquals(chapter.sentenceCount, result.speakers.size)
        assertEquals("Frodo", chapter.speakerAt(0))
        assertEquals("narrator", chapter.speakerAt(chapter.sentenceCount - 1))
    }
}
