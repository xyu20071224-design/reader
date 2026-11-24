package com.linguareader.app.reader

import com.linguareader.app.SettingsStatus
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import com.linguareader.app.data.DictionaryLookupResult
import org.junit.Test

class ReaderVocabularyFeedbackTest {
    @Test fun switchingFromPhraseToWordClearsThePhraseSaveFeedback() {
        val session = ReaderLookupSession.empty()
            .setShowingRelatedPhrase(true)
            .setStatus(SettingsStatus.success("已保存 look up"))
        assertNull(session.setShowingRelatedPhrase(false).status)
    }

    private val success = SettingsStatus.success("已保存 look up")

    @Test fun returningToTheSamePhraseViewCannotAcceptItsOldCompletion() {
        val pending = ReaderLookupSession.empty().setShowingRelatedPhrase(true).beginVocabularyChange()
        val token = pending.vocabularyFeedbackToken!!
        val current = pending.setShowingRelatedPhrase(false).setShowingRelatedPhrase(true)
        assertSame(current, current.finishVocabularyChange(token, success))
        assertNull(current.status)
        assertFalse(current.changingVocabulary)
    }

    @Test fun anOlderCompletionCannotReplaceTheNewRequestsResult() {
        val old = ReaderLookupSession.empty().beginVocabularyChange()
        val current = old.beginVocabularyChange()
        val completed = current.finishVocabularyChange(current.vocabularyFeedbackToken!!, success)
        assertSame(completed, completed.finishVocabularyChange(old.vocabularyFeedbackToken!!,
            SettingsStatus.danger("旧请求失败")))
        assertSame(success, completed.status)
    }

    @Test fun closingThenReopeningTheSameLookupRejectsTheOldCompletion() {
        val pending = ReaderLookupSession.empty().beginVocabularyChange()
        val closed = pending.clearStatus()
        assertSame(closed, closed.finishVocabularyChange(pending.vocabularyFeedbackToken!!, success))
        val reopened = ReaderLookupSession.empty().beginVocabularyChange()
        assertSame(reopened, reopened.finishVocabularyChange(pending.vocabularyFeedbackToken!!, success))
        assertTrue(reopened.changingVocabulary)
    }

    @Test fun beginningANewDictionaryLookupInvalidatesPendingFeedback() {
        val pending = ReaderLookupSession.empty().beginVocabularyChange()
        val current = pending.begin(DictionaryLookupResult(null, null), false, "another sentence")
        assertSame(current, current.finishVocabularyChange(pending.vocabularyFeedbackToken!!, success))
        assertFalse(current.changingVocabulary)
    }

    @Test fun currentFailureReleasesTheActionForRetryAndDuplicateCompletionIsIgnored() {
        val pending = ReaderLookupSession.empty().beginVocabularyChange()
        val token = pending.vocabularyFeedbackToken!!
        val failure = SettingsStatus.danger("保存失败")
        val failed = pending.finishVocabularyChange(token, failure)
        assertSame(failure, failed.status)
        assertFalse(failed.changingVocabulary)
        assertSame(failed, failed.finishVocabularyChange(token, success))
        assertTrue(failed.beginVocabularyChange().changingVocabulary)
    }

    @Test fun selectingTheAlreadySelectedViewPreservesTheActiveRequest() {
        val pending = ReaderLookupSession.empty().beginVocabularyChange()
        assertSame(pending, pending.setShowingRelatedPhrase(false))
    }
}
