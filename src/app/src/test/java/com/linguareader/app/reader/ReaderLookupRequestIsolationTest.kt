package com.linguareader.app.reader

import com.linguareader.app.SettingsStatus
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.data.DictionaryLookupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderLookupRequestIsolationTest {
    private val dictionary = DictionaryLookupResult(null, null)
    private val result = SentenceTranslationResult("A 的旧译文", "fixture")
    private fun ReaderLookupSession.begin(sentence: String) = begin(dictionary, false, sentence)

    @Test fun lateSentenceACompletionWarmsItsCacheWithoutReplacingLookupB() = runBlocking {
        var session = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val request = session
        val done = CompletableDeferred<SentenceTranslationResult>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val completed = done.await()
            session = session.cacheSentence(request, "A", completed)
        }
        session = session.begin("B")
        done.complete(result)
        job.join()
        assertNull(session.sentenceTranslation)
        assertFalse(session.sentenceTranslationLoading)
        assertEquals(result, session.cachedSentenceOrNull("A"))
    }

    @Test fun closingAndReopeningTheSameSentenceRejectsTheOldUiCompletion() {
        val request = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val current = request.clearStatus().begin("A").beginSentenceTranslation()
        val completed = current.cacheSentence(request, "A", result)
        assertSame(current, completed)
        assertTrue(completed.sentenceTranslationLoading)
    }

    @Test fun anOldBooksCompletionCannotPopulateTheNewBooksCache() {
        val request = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val newBook = ReaderLookupSession.empty().begin("B")
        assertSame(newBook, newBook.cacheSentence(request, "A", result))
        assertNull(newBook.cachedSentenceOrNull("A"))
        assertEquals(result, request.cachedSentenceOrNull("A"))
    }

    @Test fun currentCompletionBecomesVisibleAndReleasesTheLoadingState() {
        val request = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val completed = request.cacheSentence(request, "A", result)
        assertEquals(result, completed.sentenceTranslation)
        assertFalse(completed.sentenceTranslationLoading)
    }

    @Test fun lateFailureCannotReplaceTheNewLookupWithAnError() {
        val request = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val current = request.begin("B")
        assertSame(current, current.failSentenceTranslation(request, "old failure"))
        assertNull(current.sentenceTranslationError)
    }

    @Test fun lateRetranslationCannotPublishItsStatusTranslationOrCompletionTick() {
        val request = ReaderLookupSession.empty().begin("A").startRetranslate()
        val current = request.begin("B")
        assertSame(current, current.finishRetranslate(request, SettingsStatus.success("old retranslation")))
        assertEquals(0, current.retranslateDoneTick)
        assertNull(current.status)
    }

    @Test fun switchingWordAndPhraseViewsKeepsTheSameSentenceRequestValid() {
        val request = ReaderLookupSession.empty().begin("A").beginSentenceTranslation()
        val current = request.showWordEntry().setShowingRelatedPhrase(true)
        assertTrue(current.isCurrentLookup(request))
        assertEquals(result, current.cacheSentence(request, "A", result).sentenceTranslation)
    }

    @Test fun sentenceTranslationStartedDuringDictionaryLoadingRemainsValidWhenDictionaryCompletes() {
        val initial = ReaderLookupSession.empty().begin("A").markDictionaryLoading()
        val translating = initial.beginSentenceTranslation()
        val dictionaryDone = translating.finishDictionaryLookup(initial, dictionary)
        assertFalse(dictionaryDone.dictionaryLoading)
        assertTrue(dictionaryDone.sentenceTranslationLoading)
        assertTrue(dictionaryDone.isCurrentLookup(translating))
        assertEquals(result, dictionaryDone.cacheSentence(translating, "A", result).sentenceTranslation)
    }

    @Test fun dictionaryCompletionCannotResetAnAlreadyVisibleSentenceTranslationOrOverwriteAnotherLookup() {
        val initial = ReaderLookupSession.empty().begin("A").markDictionaryLoading()
        val translated = initial.cacheSentence(initial, "A", result)
        assertEquals(result, translated.finishDictionaryLookup(initial, dictionary).sentenceTranslation)
        val next = translated.begin("B").markDictionaryLoading()
        assertSame(next, next.finishDictionaryLookup(initial, dictionary))
        assertTrue(next.dictionaryLoading)
    }
}
