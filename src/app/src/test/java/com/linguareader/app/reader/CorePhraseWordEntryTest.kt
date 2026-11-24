package com.linguareader.app.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Real bundled SQLite lookup, not a fabricated related-phrase response. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorePhraseWordEntryTest {
    @Test fun defaultCorePhrasePriorityKeepsAnExplicitLookAlternative() = verify("look")
    @Test fun defaultCorePhrasePriorityKeepsAnExplicitParticleAlternative() = verify("up")

    private fun verify(word: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DictionaryRepository(context)
        try {
            val sentence = "Please look up the word."
            val result = repository.lookup(WordLookup(word, sentence, sentence, sentence.indexOf(word), 0f, 0f))
            assertEquals("look up", result.entry?.headword)
            assertEquals("look up", result.entry?.matchedPhrase)
            assertNull(result.relatedPhrase, "Core phrase priority must remain the primary result")
            assertEquals(word, repository.lookup(WordLookup(word, "$word.", "$word.", 0, 0f, 0f)).entry?.headword)
            assertEquals(word, result.wordEntry?.headword, "The primary phrase must retain its clicked-word alternative")
            assertNull(result.wordEntry?.matchedPhrase)
            assertEquals(word, result.wordEntry?.surfaceWord)
        } finally { repository.invalidate() }
    }
}
