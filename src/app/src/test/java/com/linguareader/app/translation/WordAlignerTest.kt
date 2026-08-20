package com.linguareader.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WordAlignerTest {

    @Test
    fun `numbers align by anchor`() {
        val alignment = WordAligner.align(
            enWord = "1420",
            enSentence = "It happened in 1420.",
            zhSentence = "那是在1420年。",
            candidates = emptyList()
        )

        assertNotNull(alignment)
        assertEquals(WordAlignmentSource.ANCHOR, alignment!!.source)
        assertEquals("1420", alignment.word)
        assertEquals(3, alignment.start)
        assertEquals(7, alignment.endExclusive)
    }

    @Test
    fun `latin proper nouns align by anchor even inside a chinese sentence`() {
        val alignment = WordAligner.align(
            enWord = "Frodo",
            enSentence = "Frodo smiled.",
            zhSentence = "Frodo 笑了。",
            candidates = emptyList()
        )

        assertEquals(WordAlignmentSource.ANCHOR, alignment?.source)
        assertEquals(0, alignment?.start)
    }

    @Test
    fun `dictionary senses locate the chinese term when no anchor applies`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "in a hole in the ground",
            zhSentence = "在地下的洞穴里",
            candidates = listOf("洞穴；孔洞（地下的）"),
            enOffset = 5
        )

        assertNotNull(alignment)
        assertEquals(WordAlignmentSource.DICTIONARY, alignment!!.source)
        assertEquals("洞穴", alignment.word)
        assertEquals(4, alignment.start)
        assertEquals(6, alignment.endExclusive)
    }

    @Test
    fun `a book specific translation wins through the prefer bonus`() {
        val alignment = WordAligner.align(
            enWord = "ring",
            enSentence = "the ring was lost",
            zhSentence = "那枚魔戒不见了",
            candidates = emptyList(),
            enOffset = 4,
            prefer = mapOf("魔戒" to 0.3f)
        )

        assertEquals("魔戒", alignment?.word)
        assertEquals(WordAlignmentSource.DICTIONARY, alignment?.source)
    }

    @Test
    fun `returns null when nothing matches so the caller can degrade`() {
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "in a hole",
                zhSentence = "完全无关的句子",
                candidates = listOf("洞穴")
            )
        )
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "in a hole",
                zhSentence = "",
                candidates = listOf("洞穴")
            )
        )
    }
}
