package com.linguareader.shared.reader

import com.linguareader.shared.app.AppContext
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedWordPersistenceMatrixTest {
    private val book = Book("mark-matrix", "Fixture", "", "/tmp", null, emptyList(), 0L)
    private fun <T> store(block: suspend (AppContext, VocabularyRepository) -> T): T = runBlocking {
        val dir = File.createTempFile("word-marks-store-", "").apply { delete(); mkdirs() }
        val context = object : AppContext {
            override val filesDir = dir
            override fun prefs(name: String): PreferencesStore = error("Vocabulary does not use preferences")
        }
        try { block(context, VocabularyRepository(context)) } finally { dir.deleteRecursively() }
    }
    private fun dictionary(entries: List<String>, forms: Map<String, List<String>> = emptyMap()) =
        DictionaryRepository(object : DictionaryDatabase {
            override fun queryEntry(word: String) = word.takeIf { it in entries }?.let { RawDictionaryEntry(it, "", "fixture meaning", "") }
            override fun lemmaCandidates(form: String) = forms[form].orEmpty()
        })
    private fun lookup(word: String, sentence: String) = WordLookup(word, sentence, sentence, sentence.indexOf(word), 0f, 0f)
    private fun saved(headword: String, forms: List<String> = emptyList()) = SavedWord(
        id = headword, headword = headword, phonetic = "", meaning = "", sentence = "fixture",
        bookId = book.id, bookTitle = book.title, chapterTitle = "Chapter", addedAt = 1L,
        reviewLevel = 4, reviewCount = 7, nextReviewAt = 99_000L, surfaceForms = forms
    )

    @Test fun t07SavingOnlyAPhraseDoesNotCollectTheClickedWordAsAPhraseForm() = store { _, repository ->
        val request = lookup("in", "They are in fact ready.")
        val phrase = dictionary(listOf("in", "in fact")).lookup(request).relatedPhrase!!
        val record = repository.save(book, "Chapter", request, phrase).single()
        assertEquals("in fact", record.id)
        assertEquals(emptyList(), record.surfaceForms)
        assertFalse(SavedWordMarks.of(repository.load(), 0L).forms.contains("in"))
    }

    @Test fun t07InflectedPhrasePreservesTheWholeMatchedSurface() = store { _, repository ->
        val request = lookup("gave", "They gave up yesterday.")
        val result = dictionary(listOf("give", "up", "give up"), mapOf("gave" to listOf("give"))).lookup(request)
        val phrase = result.entry!!
        assertEquals("give up", phrase.matchedPhrase)
        assertEquals("gave up", phrase.surfaceWord)
        val record = repository.save(book, "Chapter", request, phrase).single()
        assertEquals(listOf("gave up"), record.surfaceForms)
    }

    @Test fun t07LegacyPhraseClickFormsDoNotMarkUnrelatedWordsOrResetReviewProgress() = store { context, repository ->
        val old = saved("in fact", listOf("in", "IN FACT", "in\n fact"))
        repository.upsert(old)
        val reloaded = VocabularyRepository(context).load().single()
        val payload = SavedWordMarks.of(listOf(reloaded), now = 100_000L)
        assertFalse(payload.forms.contains("in"))
        assertFalse(payload.dueForms.contains("in"))
        assertTrue(payload.forms.contains("in fact"))
        assertTrue(payload.forms.contains("IN FACT"))
        assertTrue(payload.forms.contains("in\n fact"))
        assertEquals(old, VocabularyRepository(context).load().single(), "Marking must not rewrite a stored review record")
    }

    @Test fun t08RepeatedSeenFormsSurviveReloadWithoutResettingAnOldReviewRecord() = store { context, repository ->
        val old = saved("go")
        repository.upsert(old)
        val dictionary = dictionary(listOf("go"), mapOf("went" to listOf("go"), "gone" to listOf("go")))
        for (surface in listOf("went", "gone", "WENT")) {
            val request = lookup(surface, "$surface alone.")
            repository.save(book, "Chapter", request, dictionary.lookup(request).entry!!)
        }
        val result = VocabularyRepository(context).load().single()
        assertEquals("go", result.id)
        assertEquals(listOf("went", "gone"), result.surfaceForms)
        assertEquals(old.reviewLevel, result.reviewLevel)
        assertEquals(old.reviewCount, result.reviewCount)
        assertEquals(old.nextReviewAt, result.nextReviewAt)
        assertEquals(old.addedAt, result.addedAt)
        assertEquals(listOf("go", "went", "gone"), SavedWordMarks.of(listOf(result), old.nextReviewAt).dueForms)
    }
}
