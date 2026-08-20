package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 co-occurrence tests (PLAN-MULTI-VOICE §5.1): who speaks next to whom,
 * which is what pulls adjacent characters onto distinct voices.
 */
class SpeakerCooccurrenceTest {

    @Test
    fun countsAdjacentCharactersAcrossNarration() {
        val counts = SpeakerCooccurrence.from(
            listOf(listOf("Gandalf", "narrator", "Frodo", "Frodo", "Gandalf", "narrator"))
        )
        // Narration is skipped and the repeated Frodo line collapses, so the
        // turn sequence is Gandalf → Frodo → Gandalf: two adjacencies.
        assertEquals(2, SpeakerCooccurrence.count(counts, "Gandalf", "Frodo"))
        assertEquals(2, SpeakerCooccurrence.count(counts, "frodo", "gandalf"))
        assertEquals(2, SpeakerCooccurrence.degree(counts, "Gandalf"))
    }

    @Test
    fun unattributedDialogueIsNotACharacter() {
        val counts = SpeakerCooccurrence.from(listOf(listOf("dialogue", "Frodo", "dialogue")))
        assertTrue(counts.isEmpty())
        assertEquals(0, SpeakerCooccurrence.degree(counts, "Frodo"))
    }

    @Test
    fun charactersInDifferentChaptersAccumulate() {
        val counts = SpeakerCooccurrence.from(
            listOf(
                listOf("Gandalf", "Frodo"),
                listOf("Frodo", "Sam"),
                listOf("Gandalf", "Frodo")
            )
        )
        assertEquals(2, SpeakerCooccurrence.count(counts, "Gandalf", "Frodo"))
        assertEquals(1, SpeakerCooccurrence.count(counts, "Frodo", "Sam"))
        assertEquals(0, SpeakerCooccurrence.count(counts, "Gandalf", "Sam"))
        assertEquals(3, SpeakerCooccurrence.degree(counts, "Frodo"))
    }

    @Test
    fun aSoloSpeakerNeverCoOccursWithItself() {
        val counts = SpeakerCooccurrence.from(listOf(listOf("Frodo", "Frodo", "Frodo")))
        assertTrue(counts.isEmpty())
        assertEquals(0, SpeakerCooccurrence.count(counts, "Frodo", "Frodo"))
    }
}
