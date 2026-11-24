package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AdjacentSentenceQueryRegressionTest {
    private fun archive(pairs: List<AlignedSentencePair>, version: Int = TranslationAligner.VERSION) = TranslationMemory(
        "adjacent-source", "Source", "adjacent-zh", "译本", 0L, pairs, alignerVersion = version
    )

    @Test
    fun `legacy merged fragments cannot bypass the guard through fuzzy token matching`() {
        val paragraph = "The bird left. The bird left again."
        val chinese = "小鸟离开了。小鸟又一次离开了。"
        val memory = archive(listOf(AlignedSentencePair(
            0, 0, paragraph, chinese, paragraph, "小鸟离开了。", 0.9f
        )), version = 6)

        val result = TranslationMemoryIndex(memory).lookup(0, "The bird left again.", paragraph)

        assertNotNull(result)
        assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
        assertEquals(chinese, result.chinese)
    }

    @Test
    fun `an unmatched complete source sentence is not a fragment of its longer neighbor`() {
        val first = "The old bird came back."
        val second = "The old bird came."
        val paragraph = "$first $second"
        val chinese = "老鸟回来了。老鸟来了。"
        val memory = archive(listOf(AlignedSentencePair(
            0, 0, paragraph, chinese, first, "老鸟回来了。", 0.9f
        )))

        val index = TranslationMemoryIndex(memory)
        for (query in listOf(second, "  The   old bird came  ")) {
            val result = index.lookup(0, query, paragraph)

            assertNotNull(result)
            assertEquals(TranslationMatchLevel.PARAGRAPH, result!!.matchLevel)
            assertEquals(chinese, result.chinese)
        }
    }

    @Test
    fun `a missing sentence in a known paragraph is not borrowed from a different paragraph`() {
        val first = "The old bird came back."
        val second = "The old bird came."
        val paragraph = "$first $second"
        val chinese = "老鸟回来了。老鸟来了。"
        val memory = archive(listOf(
            AlignedSentencePair(0, 0, paragraph, chinese, first, "老鸟回来了。", 0.9f),
            AlignedSentencePair(0, 0, second, "那只老鸟飞来了。", second, "那只老鸟飞来了。", 0.9f)
        ))

        val result = TranslationMemoryIndex(memory).lookup(0, second, paragraph)

        assertEquals(TranslationMatchLevel.PARAGRAPH, result?.matchLevel)
        assertEquals(chinese, result?.chinese)
        assertEquals(paragraph, result?.englishParagraph)
    }

    @Test
    fun `exact sentence lookup still tolerates an unknown paragraph`() {
        val sentence = "The old bird came."
        val memory = archive(listOf(AlignedSentencePair(
            0, 0, sentence, "老鸟来了。", sentence, "老鸟来了。", 0.9f
        )))

        val result = TranslationMemoryIndex(memory).lookup(0, sentence, "A changed paragraph.")

        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals("老鸟来了。", result?.chinese)
    }

    @Test
    fun `serialized single sentence archives keep the right adjacent sentence across versions`() {
        val first = "The old bird came back."
        val second = "The young bird stayed home."
        val paragraph = "$first $second"
        val chinese = "老鸟回来了。小鸟留在家里。"
        val pairs = listOf(
            AlignedSentencePair(0, 0, paragraph, chinese, first, "老鸟回来了。", 0.9f),
            AlignedSentencePair(0, 0, paragraph, chinese, second, "小鸟留在家里。", 0.9f)
        )
        for (version in listOf(7, 8, 11, TranslationAligner.VERSION)) {
            val restored = TranslationMemory.fromJson(archive(pairs, version).toJson())
            val index = TranslationMemoryIndex(restored)
            assertEquals("v$version", "老鸟回来了。", index.lookup(0, first, paragraph)?.chinese)
            assertEquals("v$version", "小鸟留在家里。", index.lookup(0, second, paragraph)?.chinese)
            assertEquals("v$version", TranslationMatchLevel.SENTENCE, index.lookup(0, second, paragraph)?.matchLevel)
        }
    }

    @Test
    fun `a real sentence fragment still uses its stored sentence`() {
        val sentence = "Dr. Gray waited for the old bird."
        val chinese = "格雷博士等着老鸟。"
        val memory = archive(listOf(AlignedSentencePair(0, 0, sentence, chinese, sentence, chinese, 0.9f)))

        val result = TranslationMemoryIndex(memory).lookup(0, "waited for the old bird", sentence)

        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals(chinese, result?.chinese)
    }

    @Test
    fun `fuzzy lookup remains available when the reported paragraph has drifted`() {
        val sentence = "Then he ran."
        val memory = archive(listOf(AlignedSentencePair(0, 0, sentence, "然后他跑了。", sentence, "然后他跑了。", 0.9f)))

        val result = TranslationMemoryIndex(memory).lookup(0, "Then he ran fast.", "A changed paragraph.")

        assertEquals(TranslationMatchLevel.SENTENCE, result?.matchLevel)
        assertEquals("然后他跑了。", result?.chinese)
    }
}
