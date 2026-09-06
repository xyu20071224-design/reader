package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 句内片段化（发言/旁白分离）的数据模型测试：引语区间 → 旁白/引语段、
 * 坐标契约（offset 处 substring == text）、跨段引语 carry、点按定位。
 */
class TtsChapterUtteranceTest {

    private fun chapter(vararg blocks: String, speakers: List<String> = emptyList()) =
        TtsChapter(0, "Ch", blocks.toList(), speakers)

    @Test
    fun monoNarrationSentenceIsOneSegment() {
        val ch = chapter("Hello world. Second one.")

        assertEquals(2, ch.utterances.size)
        val first = ch.utterances[0]
        assertEquals(0, first.sentenceIndex)
        assertEquals(0, first.segmentIndex)
        assertEquals(1, first.segmentCount)
        assertEquals(TtsUtterance.NARRATOR, first.speaker)
        assertEquals("Hello world.", first.text)
        assertEquals(0, first.blockIndex)
        assertEquals(0, first.offset)
    }

    @Test
    fun midSentenceQuoteSplitsIntoNarratorAndSpeaker() {
        val ch = chapter("Gandalf said, \"Fly, you fools.\"", speakers = listOf("Gandalf"))

        assertEquals(2, ch.utterances.size)
        val narration = ch.utterances[0]
        val quote = ch.utterances[1]
        assertEquals(TtsUtterance.NARRATOR, narration.speaker)
        assertEquals("Gandalf said,", narration.text)
        assertEquals(0, narration.offset)
        assertEquals("Gandalf", quote.speaker)
        assertEquals("\"Fly, you fools.\"", quote.text)
        assertEquals(14, quote.offset)
        // 同一句的两段：segmentIndex 0/1，sentenceIndex 都是 0。
        assertEquals(0 to 2, narration.segmentIndex to narration.segmentCount)
        assertEquals(1 to 2, quote.segmentIndex to quote.segmentCount)
        assertEquals(0, quote.sentenceIndex)
    }

    @Test
    fun untaggedChapterMergesSameSpeakerRunsIntoOneSegment() {
        // 无标签（pre-M1 缓存）：整句都是 narrator，引语段与旁白段同声部，
        // 合并成 1 段——不为同一个声音多付一次合成请求。
        // （小写 then 是引语延续，保证整块只有一句。）
        val ch = chapter("He said, \"Hi.\" then left.")

        assertEquals(1, ch.utterances.size)
        assertEquals("He said, \"Hi.\" then left.", ch.utterances[0].text)
        assertEquals(TtsUtterance.NARRATOR, ch.utterances[0].speaker)
    }

    @Test
    fun curlyQuotesMapToOriginalBlockCoordinates() {
        // normalizeQuotes 是等长替换：片段坐标必须直接落在**原始**块文本上，
        // 引语段的 text 保留原弯引号（高亮 substring 不能错位）。
        val ch = chapter("“Fly!” he said.", speakers = listOf("Gandalf"))

        assertEquals(2, ch.utterances.size)
        assertEquals("“Fly!”", ch.utterances[0].text)
        assertEquals(0, ch.utterances[0].offset)
        assertEquals("Gandalf", ch.utterances[0].speaker)
        assertEquals("he said.", ch.utterances[1].text)
        assertEquals(7, ch.utterances[1].offset)
        assertEquals(TtsUtterance.NARRATOR, ch.utterances[1].speaker)
    }

    @Test
    fun crossParagraphQuoteCarriesSpeakerIntoNextBlock() {
        // 块尾未闭合的引语把「引语内」状态带入下一块：续段是 dialogue，
        // 引号闭合后的旁白回到 narrator。
        val ch = chapter(
            "He said, \"I am coming.",
            "I really am.\" Then he left.",
            speakers = listOf("dialogue", "dialogue", "narrator")
        )

        val segments = ch.utterances
        assertEquals(4, segments.size)
        assertEquals(listOf(0, 0, 1, 2), segments.map { it.sentenceIndex })
        assertEquals(
            listOf(TtsUtterance.NARRATOR, "dialogue", "dialogue", TtsUtterance.NARRATOR),
            segments.map { it.speaker }
        )
        assertEquals("He said,", segments[0].text)
        assertEquals("\"I am coming.", segments[1].text)
        assertEquals("I really am.\"", segments[2].text)
        assertEquals("Then he left.", segments[3].text)
    }

    @Test
    fun everySegmentIsTheExactBlockSubstringAtItsCoordinates() {
        // 高亮契约：任何片段都必须满足 blocks[blockIndex].substring(offset, offset+length) == text，
        // 否则 JS 侧高亮范围会漂到别的字符上。
        val samples = listOf(
            "Gandalf said, \"Fly, you fools.\"",
            "She nodded. \"Yes,\" she said. \"Go on.\"",
            "“Fly!” he said.",
            "He said, \"I am coming.",
            "I really am.\" Then he left."
        )
        for (block in samples) {
            val ch = chapter(block, speakers = List(5) { if (it == 0) "Gandalf" else "narrator" })
            for (u in ch.utterances) {
                if (u.blockIndex < 0) continue
                val actual = ch.blocks[u.blockIndex].substring(u.offset, u.offset + u.length)
                assertEquals(u.text, actual, "segment coords drifted in block: $block")
            }
        }
    }

    @Test
    fun utteranceAtTapsQuoteAndNarrationSeparately() {
        val ch = chapter("Gandalf said, \"Fly, you fools.\"", speakers = listOf("Gandalf"))
        val block = "Gandalf said, \"Fly, you fools.\""

        // 点在旁白 "Gandalf" 上 → 句0片段0；点在引语 "you" 上 → 句0片段1。
        assertEquals(0 to 0, ch.utteranceAt(block, 3))
        assertEquals(0 to 1, ch.utteranceAt(block, 20))
        // 句间空白（本块没有）退回句级：点到 "Gandalf said," 后的空格附近仍能定位。
        assertTrue(ch.utteranceAt(block, 13) != null)
    }

    @Test
    fun segmentsOfReturnsPerSentenceLists() {
        val ch = chapter(
            "Gandalf said, \"Fly, you fools.\"",
            "Plain narration.",
            speakers = listOf("Gandalf", "narrator")
        )

        assertEquals(2, ch.segmentsOf(0).size)
        assertEquals(1, ch.segmentsOf(1).size)
        assertEquals(1, ch.segmentsOf(1)[0].segmentCount)
        assertTrue(ch.segmentsOf(99).isEmpty())
    }
}
