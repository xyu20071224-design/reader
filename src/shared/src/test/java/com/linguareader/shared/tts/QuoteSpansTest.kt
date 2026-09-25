package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 引语扫描的字符契约——issue #2：`her’s` 在撇号处断句。
 *
 * 契约：[QuoteSpans.normalizeQuotes] 把**弯引号**折成 ASCII `"`（逐字符等长替换），
 * 但**撇号**不是引号。ASCII `'` 一直享受这条豁免（`don't`、`king's ring` 不开引语区间），
 * U+2019 `’` 必须同等对待：否则 `her’s` / `don’t` 会凭空开出一个未闭合引语区间，
 * 下游 [TtsChapter.segmentRuns] 在该区间边界裂句、`SpeakerRuleTagger`（:app）把
 * 后半段判成 dialogue，用户听到「在撇号处把一句读成两段」。
 *
 * ⚠️ 未闭合引语还会 carry 进下一块，污染下一段的引号配对——下面两条 carry 用例守着这一点。
 */
class QuoteSpansTest {

    // ── 撇号不具引号语义（红：缺陷所在） ────────────────────────────────

    @Test
    fun asciiApostropheNeverOpensAQuote() {
        // 既有契约（对照组）：ASCII 撇号从来不折成 `"`。
        assertEquals(emptyList(), QuoteSpans.spans(listOf("Don't touch the king's ring."))[0])
    }

    @Test
    fun wordInternalCurlyApostropheIsNotAQuote() {
        // 用户 issue #2 的正文形态：her’s / don’t 的撇号两侧都是词字符。
        assertEquals(
            emptyList(),
            QuoteSpans.spans(listOf("It was her’s. He left."))[0],
            "U+2019 在词内被折成了引号：her’s 开出一个未闭合引语区间"
        )
        assertEquals(
            emptyList(),
            QuoteSpans.spans(listOf("He said don’t go."))[0],
            "don’t 的 U+2019 被折成了引号"
        )
    }

    @Test
    fun curlyApostropheDoesNotCarryQuoteStateIntoNextBlock() {
        // 缺陷的跨块后果：第一块未闭合 → inQuote 带入第二块，第二块的引号配对整体错位。
        val spans = QuoteSpans.spans(listOf("It was her’s.", "\"Fine,\" she said."))

        assertEquals(emptyList(), spans[0], "第一块不该有引语区间")
        assertEquals(listOf(0..6), spans[1], "第二块的引语区间被上一块 carry 出来的假引号污染")
    }

    @Test
    fun wordInternalCurlyApostropheDoesNotSplitSentenceIntoSegments() {
        // 缺陷的听感形态：同一句被 segmentRuns 在撇号处裂成 narrator + dialogue 两段。
        // speakers 取自 SpeakerRuleTagger（:app）在本缺陷下的真实输出——该 tagger 用的
        // 就是同一个 QuoteSpans，所以它同样把含撇号的句子判成 dialogue。
        val ch = TtsChapter(
            chapterIndex = 0,
            title = "Ch",
            blocks = listOf("It was her’s. He left."),
            speakers = listOf("dialogue", "narrator")
        )

        assertEquals(
            listOf("It was her’s.", "He left."),
            ch.utterances.map { it.text },
            "句子在撇号处被切成两段（后半段 ’s. 被当成对话）"
        )
        assertTrue(
            ch.utterances.all { it.segmentIndex == 0 && it.segmentCount == 1 },
            "每句应当只有一段：${ch.utterances.map { it.text }}"
        )
    }

    // ── 对照组：成对引号与引号内的撇号行为不变 ──────────────────────────

    @Test
    fun curlyApostropheInsideDoubleQuotedSpeechKeepsThePair() {
        // 引语内部的撇号不能让成对引号错位：区间必须仍然覆盖整段引语。
        assertEquals(
            listOf(0..14),
            QuoteSpans.spans(listOf("\"I don’t know,\" he said."))[0]
        )
    }

    @Test
    fun pairedCurlyDoubleQuotesStillFormOneSpan() {
        assertEquals(listOf(0..5), QuoteSpans.spans(listOf("“Fly!” he said."))[0])
    }

    @Test
    fun pairedCurlySingleQuotesStillFormOneSpan() {
        // 整体成对的弯单引号（SpeakerRuleTaggerTest 锁定的行为）必须照旧。
        assertEquals(listOf(0..13), QuoteSpans.spans(listOf("‘Hello there.’"))[0])
    }

    @Test
    fun quotedSpeechAfterAnApostropheStaysCorrectlyPaired() {
        // 撇号 + 真引语同块：撇号豁免不能把真引语也一起豁免掉。
        val spans = QuoteSpans.spans(listOf("It was her’s. \"Mine,\" he said."))

        assertEquals(listOf(14..20), spans[0])
    }
}
