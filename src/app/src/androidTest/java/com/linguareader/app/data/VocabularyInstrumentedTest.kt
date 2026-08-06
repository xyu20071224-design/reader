package com.linguareader.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyInstrumentedTest {
    @Test
    fun savedWordPersistsAndReviewStateUpdates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = VocabularyRepository(context)
        val unique = "test-word-${System.nanoTime()}"
        val book = Book(
            id = "test-book",
            title = "Test Book",
            author = "Tester",
            extractedDir = context.cacheDir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Test Chapter", "test.xhtml")),
            addedAt = 1L
        )
        val lookup = WordLookup(unique, "A $unique appeared.", "", 2, 0f, 0f)
        val entry = ContextualDictionaryEntry(
            surfaceWord = unique,
            headword = unique,
            phonetic = "test",
            senses = listOf(DictionarySense("n. 测试词", PartOfSpeech.NOUN, true)),
            definitions = listOf("n. a test word"),
            matchedPhrase = null,
            inferredPartOfSpeech = PartOfSpeech.NOUN
        )

        try {
            repository.save(book, "Test Chapter", lookup, entry)
            val stored = repository.load().firstOrNull { it.id == unique }
            assertNotNull(stored)
            assertTrue(stored!!.sentence.contains(unique))

            repository.review(unique, remembered = true, pace = ReviewMode.GENTLE.toPace())
            val reviewed = repository.load().first { it.id == unique }
            assertEquals(1, reviewed.reviewCount)
            assertEquals(1, reviewed.reviewLevel)
            assertTrue(reviewed.nextReviewAt > System.currentTimeMillis())
        } finally {
            repository.remove(unique)
        }
    }

    @Test
    fun newWordIsDueAtSaveTimePlusPresetFirstDelay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = VocabularyRepository(context)
        val unique = "delay-word-${System.nanoTime()}"
        val book = Book(
            id = "delay-book",
            title = "Delay Book",
            author = "Tester",
            extractedDir = context.cacheDir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("C", "c.xhtml")),
            addedAt = 1L
        )
        val lookup = WordLookup(unique, "A $unique appeared.", "", 2, 0f, 0f)
        val entry = ContextualDictionaryEntry(
            surfaceWord = unique,
            headword = unique,
            phonetic = "",
            senses = listOf(DictionarySense("n. 测试", PartOfSpeech.NOUN, true)),
            definitions = emptyList(),
            matchedPhrase = null,
            inferredPartOfSpeech = PartOfSpeech.NOUN
        )

        try {
            val before = System.currentTimeMillis()
            repository.save(book, "C", lookup, entry, mode = ReviewMode.DILIGENT)
            val stored = repository.load().first { it.id == unique }
            val expected = before + ReviewMode.DILIGENT.firstDelayMillis
            assertTrue(stored.nextReviewAt >= expected - 2_000L)
            assertTrue(stored.nextReviewAt <= expected + 2_000L)
        } finally {
            repository.remove(unique)
        }
    }

    @Test
    fun reviewSchedulesNextPointWithSelectedPace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = VocabularyRepository(context)
        val unique = "pace-word-${System.nanoTime()}"
        val book = Book(
            id = "pace-book",
            title = "Pace Book",
            author = "Tester",
            extractedDir = context.cacheDir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("C", "c.xhtml")),
            addedAt = 1L
        )
        val lookup = WordLookup(unique, "A $unique appeared.", "", 2, 0f, 0f)
        val entry = ContextualDictionaryEntry(
            surfaceWord = unique,
            headword = unique,
            phonetic = "",
            senses = listOf(DictionarySense("n. 测试", PartOfSpeech.NOUN, true)),
            definitions = emptyList(),
            matchedPhrase = null,
            inferredPartOfSpeech = PartOfSpeech.NOUN
        )

        try {
            repository.save(book, "C", lookup, entry, mode = ReviewMode.DILIGENT)
            val before = System.currentTimeMillis()
            repository.review(unique, remembered = true, pace = ReviewMode.DILIGENT.toPace())
            val reviewed = repository.load().first { it.id == unique }
            // 12h base point scaled by ×0.75 = 9h, from the review moment.
            val expected = before + 9 * 3_600_000L
            assertTrue(reviewed.nextReviewAt >= expected - 2_000L)
            assertTrue(reviewed.nextReviewAt <= expected + 2_000L)
        } finally {
            repository.remove(unique)
        }
    }
}