package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSplitterTest {
    @Test
    fun splitsEnglishSentences() {
        val sentences = SentenceSplitter.split(
            "The quick brown fox jumps over the lazy dog. He ran away!"
        )

        assertEquals(2, sentences.size)
        assertEquals("The quick brown fox jumps over the lazy dog.", sentences[0])
        assertEquals("He ran away!", sentences[1])
    }

    @Test
    fun keepsCommonAbbreviationsTogether() {
        val sentences = SentenceSplitter.split(
            "Dr. Smith arrived early. Prof. Lee agreed with the plan."
        )

        assertEquals(2, sentences.size)
        assertEquals("Dr. Smith arrived early.", sentences[0])
        assertEquals("Prof. Lee agreed with the plan.", sentences[1])
    }

    @Test
    fun keepsInitialsInsideSentencesButSplitsAtSentenceEnd() {
        val sentences = SentenceSplitter.split(
            "He works for the U.S. government. Next year he retires."
        )

        assertEquals(2, sentences.size)
        assertEquals("He works for the U.S. government.", sentences[0])
        assertEquals("Next year he retires.", sentences[1])
    }

    @Test
    fun splitsChineseSentences() {
        val sentences = SentenceSplitter.split("你好。世界很大！我们走吧？")

        assertEquals(listOf("你好。", "世界很大！", "我们走吧？"), sentences)
    }

    @Test
    fun splitsMixedEnglishAndChinese() {
        val sentences = SentenceSplitter.split("He said \"Hello.\" 然后他走了。")

        assertEquals(2, sentences.size)
        assertEquals("He said \"Hello.\"", sentences[0])
        assertEquals("然后他走了。", sentences[1])
    }

    @Test
    fun keepsClosingQuotesWithFinishedSentence() {
        val sentences = SentenceSplitter.split(
            "She asked, \"Is it true?\" He nodded."
        )

        assertEquals(2, sentences.size)
        assertEquals("She asked, \"Is it true?\"", sentences[0])
        assertEquals("He nodded.", sentences[1])
    }

    @Test
    fun treatsTerminatorRunsAsOneBoundary() {
        val sentences = SentenceSplitter.split("Really!! I can't believe it. Wait… what?")

        assertEquals(3, sentences.size)
        assertEquals("Really!!", sentences[0])
        assertEquals("I can't believe it.", sentences[1])
        assertEquals("Wait… what?", sentences[2])
    }

    @Test
    fun `keepsCaptionFragmentWithItsQuoteWhenItStartsWithLowercase`() {
        // R3：句末标点后跟小写字母 = 说话人引导语延续，不是新句。
        val sentences = SentenceSplitter.split(
            "\"I am sorry, Frodo!\" he cried, full of concern. \"So much has happened this day.\""
        )

        assertEquals(2, sentences.size)
        assertEquals("\"I am sorry, Frodo!\" he cried, full of concern.", sentences[0].trim())
        assertEquals("\"So much has happened this day.\"", sentences[1].trim())
    }

    @Test
    fun `curlyQuoteCaptionMergesButAFreshQuoteStartsANewSentence`() {
        // 收紧版 R3：只有闭合引号归属后才可能合并；新引号开句仍切。
        val sentences = SentenceSplitter.split(
            "‘I am sorry, Frodo!’ he cried, full of concern. ‘So much has happened this day.’"
        )

        assertEquals(2, sentences.size)
        assertTrue(sentences[0].trim().startsWith("‘I am sorry"))
        assertTrue(sentences[1].trim().startsWith("‘So much"))
    }

    @Test
    fun `noMergeWithoutClosingQuoteAttribution`() {
        // 无引号归属的小写延续不被合并（保守：只修引语引导语，不吞整句对话）。
        val sentences = SentenceSplitter.split("This is odd. he said nothing.")
        assertEquals(2, sentences.size)
    }

    @Test
    fun `splitsWhenTerminatorIsFollowedByACapitalOrAQuote`() {
        // R3 不能误伤正常句界：句末后大写字母或新引号开句仍要切。
        val sentences = SentenceSplitter.split(
            "She asked, \"Is it true?\" He nodded. \"Then leave,\" she added."
        )

        assertEquals(3, sentences.size)
    }

    @Test
    fun returnsWholeTextWhenNoTerminatorExists() {
        val sentences = SentenceSplitter.split("This is one long unbroken sentence")

        assertEquals(listOf("This is one long unbroken sentence"), sentences)
    }

    @Test
    fun returnsEmptyListForBlankText() {
        assertEquals(emptyList<String>(), SentenceSplitter.split("   "))
        assertEquals(emptyList<String>(), SentenceSplitter.split(""))
    }

    @Test
    fun `threeDotEllipsisBehavesLikeUnicodeEllipsis`() {
        // 刀1：三个 ASCII 句点此前走普通句界分支，`…` 却看小写延续——
        // 同一种省略号两种命运。现在两者统一：小写延续合并，大写/句末照切。
        assertEquals(listOf("Wait... what?"), SentenceSplitter.split("Wait... what?"))
        assertEquals(2, SentenceSplitter.split("He paused... Then he left.").size)
        assertEquals(1, SentenceSplitter.split("Wait… what?").size)
    }

    @Test
    fun `titleAbbreviationsNeverEndASentence`() {
        val sentences = SentenceSplitter.split(
            "He met Dr. Watson near St. James's Park. Gen. Ross joined them."
        )
        assertEquals(2, sentences.size)
        assertEquals("He met Dr. Watson near St. James's Park.", sentences[0])
    }

    @Test
    fun `sentenceFinalAbbreviationSplitsBeforeCapitalisedWord`() {
        // 刀2：etc./Inc. 这类可以结束句子的缩写，后面跟大写词视为真句界。
        val sentences = SentenceSplitter.split("They sell tools, etc. The shop closes at five.")
        assertEquals(2, sentences.size)
        assertEquals("They sell tools, etc.", sentences[0])
    }

    @Test
    fun `abbreviatedQuoteDoesNotSwallowFollowingNarration`() {
        // 发言/旁白分离回归："No." 的句点后紧跟闭合引号，保护判定必须越过
        // 闭合串再看——否则引语和后面的旁白并成一句，只能用同一个声音读。
        val sentences = SentenceSplitter.split("He said, \"No.\" Then he left.")
        assertEquals(2, sentences.size)
        assertEquals("He said, \"No.\"", sentences[0])
        assertEquals("Then he left.", sentences[1])
    }

    @Test
    fun `sentenceFinalAbbreviationInsideQuoteKeepsAttributionMerged`() {
        // 同一规则的另一面：引语内的缩写句点 + 闭合引号 + 小写引导语，
        // 仍要合并为一句（说话人归属不能被切开）。
        assertEquals(
            listOf("\"Bring ropes, nails, etc.\" he said."),
            SentenceSplitter.split("\"Bring ropes, nails, etc.\" he said.")
        )
    }

    @Test
    fun `sentenceFinalAbbreviationStaysBeforeLowercaseOrDigits`() {
        assertEquals(1, SentenceSplitter.split("Read pp. 12 and pp. 13 for details.").size)
        assertEquals(1, SentenceSplitter.split("The firm Acme Inc. was founded here.").size)
    }

    @Test
    fun `splitsWhenEnglishTerminatorIsDirectlyFollowedByCjk`() {
        // 刀4：混排文本里英文终止符后无空格直接接中文字符（said.她走了）也要切。
        assertEquals(listOf("He said!", "她哭了。"), SentenceSplitter.split("He said!她哭了。"))
        assertEquals(2, SentenceSplitter.split("She answered.她走了").size)
    }

    @Test
    fun `hardSplitsOverlongSentencesAtWordBoundariesWhenCapped`() {
        val long = "word ".repeat(40).trim() // 200 字符，无终止符
        val chunks = SentenceSplitter.split(long, maxSentenceLength = 60)

        assertTrue(chunks.size > 1)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 60, "chunk too long: $chunk") }
        assertEquals(long, chunks.joinToString(" "))
        // 不带上限时保持原样（译本对齐线依赖这一点）。
        assertEquals(listOf(long), SentenceSplitter.split(long))
    }

    @Test
    fun `everySentenceIsFoundInOrderInsideTheNormalisedText`() {
        // 高亮契约：TtsChapter.sentenceLocation 靠 cursor indexOf 在归一化块里
        // 反查每个句子，任何 split 输出若不是原文的有序子串就会静默丢高亮。
        val samples = listOf(
            "He said \"Hello.\" 然后他走了。",
            "She asked, \"Is it true?\" He nodded. \"Then leave,\" she added.",
            "Really!! I can't believe it. Wait… what?",
            "He works for the U.S. government. Next year he retires.",
            "J. R. R. Tolkien wrote it. Mr. Baggins lived in No. 3. Then he left.",
            "He said!她哭了。她 answered.再来一句？",
            "\"I am sorry, Frodo!\" he cried, full of concern. \"So much has happened this day.\""
        )
        samples.forEach { sample ->
            val text = sample.replace(Regex("\\s+"), " ").trim()
            var cursor = 0
            SentenceSplitter.split(text).forEach { sentence ->
                val found = text.indexOf(sentence, cursor)
                assertTrue(
                    found >= 0,
                    "sentence \"$sentence\" not found in order inside \"$text\""
                )
                cursor = found + sentence.length
            }
        }
    }
}
