package com.linguareader.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSentenceTranslatorTest {
    @Test
    fun `markupSentence wraps matched terms with dynamic dictionary tags`() {
        val sentence = "Harry Potter walked to Hogwarts."
        val matches = listOf(
            GlossaryMatch(
                entry = GlossaryEntry(term = "Harry Potter", translation = "哈利·波特"),
                text = "Harry Potter",
                start = 0,
                endExclusive = 12
            ),
            GlossaryMatch(
                entry = GlossaryEntry(term = "Hogwarts", translation = "霍格沃茨"),
                text = "Hogwarts",
                start = 23,
                endExclusive = 31
            )
        )

        val marked = AzureSentenceTranslator.markupSentence(sentence, matches)

        assertEquals(
            "<mstrans:dictionary translation=\"哈利·波特\">Harry Potter</mstrans:dictionary>" +
                " walked to " +
                "<mstrans:dictionary translation=\"霍格沃茨\">Hogwarts</mstrans:dictionary>.",
            marked
        )
    }

    @Test
    fun `blank translation keeps original text`() {
        val sentence = "Hermione is here."
        val matches = listOf(
            GlossaryMatch(
                entry = GlossaryEntry(term = "Hermione", translation = ""),
                text = "Hermione",
                start = 0,
                endExclusive = 8
            )
        )

        val marked = AzureSentenceTranslator.markupSentence(sentence, matches)

        assertTrue(marked.contains("translation=\"Hermione\""))
    }

    @Test
    fun `translation attribute is xml escaped`() {
        val sentence = "The \"Quiet\" Book."
        val matches = listOf(
            GlossaryMatch(
                entry = GlossaryEntry(term = "Quiet", translation = "安静 \"书\" & 故事"),
                text = "Quiet",
                start = 4,
                endExclusive = 9
            )
        )

        val marked = AzureSentenceTranslator.markupSentence(sentence, matches)

        assertTrue(marked.contains("translation=\"安静 &quot;书&quot; &amp; 故事\""))
    }

    @Test
    fun `element content is xml escaped`() {
        val sentence = "R&D spending rose."
        val matches = listOf(
            GlossaryMatch(
                entry = GlossaryEntry(term = "R&D", translation = "研发"),
                text = "R&D",
                start = 0,
                endExclusive = 3
            )
        )

        val marked = AzureSentenceTranslator.markupSentence(sentence, matches)

        assertEquals(
            "<mstrans:dictionary translation=\"研发\">R&amp;D</mstrans:dictionary> spending rose.",
            marked
        )
    }
}