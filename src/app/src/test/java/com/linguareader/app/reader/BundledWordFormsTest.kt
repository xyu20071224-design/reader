package com.linguareader.app.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.AndroidAppContext
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import com.linguareader.shared.reader.SavedWordMarks
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BundledWordFormsTest {
    @Test fun t08BundledDictionaryLemmasKeepClickedFormsThroughPersistenceAndReviewMarks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dictionary = DictionaryRepository(context)
        val store = com.linguareader.shared.data.VocabularyRepository(AndroidAppContext(context))
        val book = Book("real-forms", "Fixture", "", context.cacheDir.absolutePath, null, emptyList(), 1L)
        try {
            for ((surface, lemma) in listOf("studied" to "study", "making" to "make", "running" to "run", "went" to "go")) {
                val old = SavedWord(id = lemma, headword = lemma, phonetic = "", meaning = "fixture", sentence = "",
                    bookId = book.id, bookTitle = book.title, chapterTitle = "Chapter", addedAt = 1L,
                    reviewLevel = 4, reviewCount = 7, nextReviewAt = 99_000L)
                store.upsert(old)
                val request = WordLookup(surface, "$surface.", "$surface.", 0, 0f, 0f)
                val entry = dictionary.lookup(request).wordEntry!!
                assertEquals(lemma, entry.headword, "Bundled dictionary lemma for $surface")
                assertEquals(surface, entry.surfaceWord)
                VocabularyRepository(context).save(book, "Chapter", request, entry)
                val loaded = VocabularyRepository(context).load().first { it.id == lemma }
                assertEquals(listOf(surface), loaded.surfaceForms)
                assertEquals(old.reviewLevel, loaded.reviewLevel)
                assertEquals(old.reviewCount, loaded.reviewCount)
                assertEquals(old.nextReviewAt, loaded.nextReviewAt)
                val payload = SavedWordMarks.of(listOf(loaded), old.nextReviewAt)
                assertTrue(payload.forms.contains(surface))
                assertTrue(payload.dueForms.contains(surface))
            }
        } finally { dictionary.invalidate() }
    }
}
