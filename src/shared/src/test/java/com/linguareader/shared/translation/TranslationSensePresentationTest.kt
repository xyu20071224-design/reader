package com.linguareader.shared.translation

import com.linguareader.shared.data.ContextualDictionaryEntry
import com.linguareader.shared.data.DictionarySense
import com.linguareader.shared.data.PartOfSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSensePresentationTest {
    private fun sense(text: String) = DictionarySense(text, PartOfSpeech.VERB, contextPreferred = false)

    private fun entry(headword: String, senses: List<DictionarySense>) = ContextualDictionaryEntry(
        headword, headword, "", senses, emptyList(), null, PartOfSpeech.UNKNOWN
    )

    private fun alignment(headword: String?, text: String) = WordAlignment(
        "走", 0, 1, 0.9f, WordAlignmentSource.DICTIONARY,
        sourceSense = text, sourceHeadword = headword
    )

    @Test
    fun `the first eight senses retain their order and one exact source is marked`() {
        val senses = List(12) { sense("v. 义项$it") }
        val rows = TranslationSensePresentation.rows(entry("walk", senses), alignment("walk", senses[3].text))

        assertEquals(senses.take(8), rows.map { it.sense })
        assertEquals(listOf(3), rows.indices.filter { rows[it].aligned })
        assertTrue(rows.none { it.supplemental })
    }

    @Test
    fun `a late source sense is appended once with its actual sense metadata`() {
        val senses = List(12) { sense("v. 义项$it") }
        val rows = TranslationSensePresentation.rows(entry("walk", senses), alignment("walk", senses[10].text))

        assertEquals(9, rows.size)
        assertEquals(senses.take(8), rows.take(8).map { it.sense })
        assertEquals(senses[10], rows.last().sense)
        assertTrue(rows.last().aligned)
        assertTrue(rows.last().supplemental)
    }

    @Test
    fun `equal text from another headword cannot mark a phrase sense as the word source`() {
        val sameText = "v. 走"
        val rows = TranslationSensePresentation.rows(entry("walk out", listOf(sense(sameText))), alignment("walk", sameText))

        assertEquals(2, rows.size)
        assertFalse(rows.first().aligned)
        assertEquals("walk out", rows.first().headword)
        assertTrue(rows.last().aligned)
        assertTrue(rows.last().supplemental)
        assertEquals("walk", rows.last().headword)
    }

    @Test
    fun `switching between phrase and word views keeps the same real source`() {
        val aligned = alignment("walk", "v. 走")
        val wordRows = TranslationSensePresentation.rows(entry("walk", listOf(sense("v. 走"))), aligned)
        val phraseRows = TranslationSensePresentation.rows(entry("walk out", listOf(sense("v. 退场"))), aligned)
        val returnedRows = TranslationSensePresentation.rows(entry("walk", listOf(sense("v. 走"))), aligned)

        assertEquals("walk", wordRows.single { it.aligned }.headword)
        assertEquals("walk", phraseRows.single { it.aligned }.headword)
        assertTrue(phraseRows.single { it.aligned }.supplemental)
        assertEquals(wordRows, returnedRows)
    }

    @Test
    fun `missing source identity does not invent a row or claim a current sense`() {
        val rows = TranslationSensePresentation.rows(entry("walk", listOf(sense("v. 走"))), alignment(null, "v. 走"))

        assertEquals(1, rows.size)
        assertTrue(rows.none { it.aligned || it.supplemental })
    }

    @Test
    fun `anchors and absent alignments keep only the ordinary dictionary rows`() {
        val entry = entry("year", listOf(sense("n. 年")))
        val anchor = WordAlignment("1926", 0, 4, 0.95f, WordAlignmentSource.ANCHOR)
        val ordinary = TranslationSensePresentation.rows(entry, null)

        assertEquals(ordinary, TranslationSensePresentation.rows(entry, anchor))
        assertTrue(ordinary.none { it.aligned || it.supplemental })
        assertTrue(TranslationSensePresentation.rows(null, anchor).isEmpty())
    }

    @Test
    fun `duplicate lines mark only the first real origin and remain bounded`() {
        val rows = TranslationSensePresentation.rows(
            entry("walk", List(20) { sense("v. 走") }), alignment("walk", "v. 走")
        )

        assertEquals(8, rows.size)
        assertEquals(1, rows.count { it.aligned })
        assertTrue(rows.first().aligned)
    }

    @Test
    fun `a real fallback origin remains visible when the current entry is absent`() {
        val rows = TranslationSensePresentation.rows(null, alignment("walk", "v. 走"))

        assertEquals(1, rows.size)
        assertEquals("walk", rows.single().headword)
        assertEquals("v. 走", rows.single().sense.text)
        assertTrue(rows.single().aligned)
        assertTrue(rows.single().supplemental)
    }
}
