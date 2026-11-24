package com.linguareader.app.reader

import com.linguareader.app.SettingsStatus
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.PartOfSpeech
import com.linguareader.app.data.WordLookup
import org.junit.Assert.*
import org.junit.Test

class CorePhraseSelectionStateTest {
    private val word = ContextualDictionaryEntry("up", "up", "", emptyList(), emptyList(), null, PartOfSpeech.UNKNOWN)
    private val phrase = word.copy(surfaceWord = "looked up", headword = "look up", matchedPhrase = "look up")
    private val lookup = WordLookup("up", "They looked up.", "They looked up.", 12, 0f, 0f)
    private fun session(wordEntry: ContextualDictionaryEntry? = word) = ReaderLookupSession.empty()
        .begin(DictionaryLookupResult(phrase, null, wordEntry), false, lookup.sentence)

    @Test fun defaultPhrasePriorityCanExplicitlySelectTheWordAndReturn() {
        val initial = session()
        assertSame(phrase, initial.vocabularyEntry(lookup))
        assertTrue(initial.canShowWord)
        val selected = initial.showWordEntry()
        assertSame(word, selected.displayedEntry)
        assertSame(word, selected.vocabularyEntry(lookup))
        assertSame(phrase, selected.relatedPhrase)
        assertSame(phrase, selected.setShowingRelatedPhrase(true).vocabularyEntry(lookup))
    }

    @Test fun anUnlistedWordCanStillBeChosenIndependentlyFromItsKnownPhrase() {
        val selected = session(null).showWordEntry()
        assertNull(selected.displayedEntry)
        assertEquals("up", selected.vocabularyEntry(lookup).headword)
        assertNull(selected.vocabularyEntry(lookup).matchedPhrase)
        assertSame(phrase, selected.relatedPhrase)
    }

    @Test fun choosingTheWordInvalidatesAnEarlierPhraseSaveCallback() {
        val pending = session().beginVocabularyChange()
        val selected = pending.showWordEntry()
        assertSame(selected, selected.finishVocabularyChange(pending.vocabularyFeedbackToken!!,
            SettingsStatus.success("Saved phrase")))
        assertFalse(selected.changingVocabulary)
        assertNull(selected.status)
    }

    @Test fun aNewLookupRestoresDefaultPriorityAndClearsAnExplicitWordChoice() {
        val selected = session().showWordEntry()
        val next = selected.begin(DictionaryLookupResult(phrase, null, word), false, lookup.sentence)
        assertFalse(next.showingWordEntry)
        assertSame(phrase, next.displayedEntry)
    }
}
