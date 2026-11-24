package com.linguareader.shared.data

import com.linguareader.shared.app.AppContext
import com.linguareader.shared.app.PreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.*

class VocabularySurfaceObservationTest {
    private fun <T> fixture(block: suspend (AppContext, VocabularyRepository) -> T): T = runBlocking {
        val directory = File.createTempFile("surface-observation-", "").apply { delete(); mkdirs() }
        val context = object : AppContext {
            override val filesDir = directory
            override fun prefs(name: String): PreferencesStore = error("No preferences used")
        }
        try { block(context, VocabularyRepository(context)) } finally { directory.deleteRecursively() }
    }
    private fun word(id: String = "go", forms: List<String> = emptyList()) = SavedWord(
        id = id, headword = id, phonetic = "old phonetic", meaning = "old meaning", aiMeaning = "old AI meaning",
        aiSource = "old source", aiExplanation = "old explanation", sentence = "old context",
        bookId = "old-book", bookTitle = "old title", chapterTitle = "old chapter", addedAt = 1L,
        reviewLevel = 4, reviewCount = 7, nextReviewAt = 99_000L, updatedAt = 10L, surfaceForms = forms
    )
    private fun entry(surface: String, headword: String = "go", phrase: Boolean = false) = ContextualDictionaryEntry(
        surface, headword, "new phonetic", listOf(DictionarySense("new meaning", PartOfSpeech.VERB, false)),
        emptyList(), if (phrase) headword else null, PartOfSpeech.VERB
    )

    @Test fun unknownEntriesDoNotCreateVocabularyOrWriteAFile() = fixture { context, repository ->
        val result = repository.rememberSurfaceForms(listOf(entry("went")))
        assertFalse(result.changed)
        assertTrue(result.savedWords.isEmpty())
        assertFalse(File(context.filesDir, "vocabulary.json").exists())
    }

    @Test fun aNewObservedSurfaceChangesOnlyFormsAndUpdateTimestamp() = fixture { context, repository ->
        val old = word()
        repository.upsert(old)
        val before = System.currentTimeMillis()
        val result = repository.rememberSurfaceForms(listOf(entry("went")))
        assertTrue(result.changed)
        val observed = result.savedWords.single()
        assertTrue(observed.updatedAt >= before)
        assertEquals(old.copy(surfaceForms = listOf("went"), updatedAt = observed.updatedAt), observed)
        assertEquals(observed, VocabularyRepository(context).load().single())
    }

    @Test fun repeatsCaseVariantsAndTheCanonicalHeadwordDoNotWriteAgain() = fixture { context, repository ->
        val old = word(forms = listOf("went"))
        repository.upsert(old)
        val file = File(context.filesDir, "vocabulary.json")
        val originalBytes = file.readBytes()
        assertTrue(file.setLastModified(1_000_000L))
        val stamp = file.lastModified()
        val result = repository.rememberSurfaceForms(listOf(entry("WENT"), entry("go"), entry("went")))
        assertFalse(result.changed)
        assertEquals(old, result.savedWords.single())
        assertContentEquals(originalBytes, file.readBytes())
        assertEquals(stamp, file.lastModified())
        val phrase = word("give up", listOf("gave\n up"))
        repository.upsert(phrase)
        val phraseBytes = file.readBytes()
        assertTrue(file.setLastModified(1_000_000L))
        val phraseStamp = file.lastModified()
        val samePhrase = repository.rememberSurfaceForms(listOf(entry("GAVE UP", "give up", phrase = true)))
        assertFalse(samePhrase.changed)
        assertEquals(phrase, samePhrase.savedWords.first { it.id == phrase.id })
        assertContentEquals(phraseBytes, file.readBytes())
        assertEquals(phraseStamp, file.lastModified())
    }

    @Test fun wordPrimaryAndRelatedPhraseCandidatesAreGroupedInOneExistingIdUpdate() = fixture { _, repository ->
        repository.upsert(word("give"))
        repository.upsert(word("give up"))
        val result = repository.rememberSurfaceForms(listOf(entry("gave", "give"),
            entry("gave up", "give up", phrase = true), entry("GAVE", "give"), entry("unknown", "unknown")))
        assertTrue(result.changed)
        assertEquals(setOf("give", "give up"), result.savedWords.map { it.id }.toSet())
        assertEquals(listOf("gave"), result.savedWords.first { it.id == "give" }.surfaceForms)
        assertEquals(listOf("gave up"), result.savedWords.first { it.id == "give up" }.surfaceForms)
    }

    @Test fun aPhraseClickTokenIsNeverRecordedAsAnIndependentPhraseForm() = fixture { context, repository ->
        val old = word("give up", listOf("give"))
        repository.upsert(old)
        val ignored = repository.rememberSurfaceForms(listOf(entry("gave", "give up", phrase = true)))
        assertFalse(ignored.changed)
        assertEquals(old, VocabularyRepository(context).load().single())
        val learned = repository.rememberSurfaceForms(listOf(entry("gave up", "give up", phrase = true)))
        assertTrue(learned.changed)
        assertEquals(listOf("gave up"), learned.savedWords.single().surfaceForms)
        assertEquals(old.reviewCount, learned.savedWords.single().reviewCount)
    }

    @Test fun observingANewSurfaceKeepsTheExistingEightFormLimit() = fixture { _, repository ->
        val old = word(forms = (1..8).map { "seen$it" })
        repository.upsert(old)
        val result = repository.rememberSurfaceForms(listOf(entry("went")))
        assertEquals((2..8).map { "seen$it" } + "went", result.savedWords.single().surfaceForms)
        assertEquals(old.reviewLevel, result.savedWords.single().reviewLevel)
        assertEquals(old.nextReviewAt, result.savedWords.single().nextReviewAt)
    }

    @Test fun failedObservationPreservesTheRecordAndCanBeRetried() = fixture { context, repository ->
        val old = word()
        repository.upsert(old)
        val blocked = File(context.filesDir, "vocabulary.json.tmp").apply { mkdirs() }
        try {
            assertFailsWith<java.io.IOException> { repository.rememberSurfaceForms(listOf(entry("went"))) }
            assertEquals(old, VocabularyRepository(context).load().single())
        } finally { blocked.delete() }
        assertTrue(repository.rememberSurfaceForms(listOf(entry("went"))).changed)
    }

    @Test fun aRemovedWordCannotBeRecreatedByALateLookupObservation() = fixture { _, repository ->
        repository.upsert(word())
        repository.remove("go")
        val result = repository.rememberSurfaceForms(listOf(entry("went")))
        assertFalse(result.changed)
        assertTrue(result.savedWords.isEmpty())
    }
}
