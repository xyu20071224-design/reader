package com.linguareader.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictionaryInstrumentedTest {
    @Test
    fun bundledDictionaryLooksUpCommonInflectedWord() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "She carried the lantern through the rain."
        val result = DictionaryRepository(context).lookup(
            WordLookup("carried", sentence, sentence, 4, 0f, 0f)
        )

        assertNotNull(result.entry)
        assertTrue(result.entry!!.headword == "carry")
        assertTrue(result.entry!!.senses.any { it.text.contains("携带") || it.text.contains("运送") })
        assertTrue(result.entry!!.senses.first().contextPreferred)
    }

    @Test
    fun bundledDictionaryPrefersLemmatizedPhrase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "They looked forward to spring."
        val result = DictionaryRepository(context).lookup(
            WordLookup("forward", sentence, sentence, 12, 0f, 0f)
        )

        assertNotNull(result.entry)
        assertTrue(result.entry!!.matchedPhrase == "look forward to")
        assertTrue(result.entry!!.senses.any { it.text.contains("期望") || it.text.contains("盼望") })
    }

    @Test
    fun irregularVerbCanMatchPhrasalVerb() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "One student took off his wet coat."
        val result = DictionaryRepository(context).lookup(
            WordLookup("off", sentence, sentence, 17, 0f, 0f)
        )

        assertNotNull(result.entry)
        assertTrue(result.entry!!.matchedPhrase == "take off")
        assertTrue(result.entry!!.senses.any { it.text.contains("脱下") || it.text.contains("拿掉") })
    }

    @Test
    fun abbreviationRetainsInternalPeriods() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "The U.S. team arrived."
        val result = DictionaryRepository(context).lookup(
            WordLookup("U.S.", sentence, sentence, 4, 0f, 0f)
        )

        assertNotNull(result.entry)
        assertTrue(result.entry!!.headword.equals("u.s.", ignoreCase = true))
        assertTrue(result.entry!!.senses.any { it.text.contains("美国") })
    }

    @Test
    fun functionWordInPhraseFallsBackToWordAndOffersRelatedPhrase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "They looked forward to spring."
        val result = DictionaryRepository(context).lookup(
            WordLookup("to", sentence, sentence, sentence.indexOf("to"), 0f, 0f)
        )

        assertNotNull(result.entry)
        assertEquals("to", result.entry!!.headword)
        assertNotNull(result.relatedPhrase)
        assertTrue(result.relatedPhrase!!.matchedPhrase == "look forward to")
    }

    @Test
    fun wordNearPhraseStaysWordLookupWhenNotCore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "We are going to look forward to better days."
        val result = DictionaryRepository(context).lookup(
            WordLookup("days", sentence, sentence, sentence.indexOf("days"), 0f, 0f)
        )

        assertNotNull(result.entry)
        assertEquals("day", result.entry!!.headword)
        assertNull(result.entry!!.matchedPhrase)
        assertNotNull(result.relatedPhrase)
        assertTrue(result.relatedPhrase!!.matchedPhrase == "good day")
    }

    @Test
    fun inflectedPhraseHeadStillTriggersPhrasePriority() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sentence = "I have got to go now."
        val result = DictionaryRepository(context).lookup(
            WordLookup("got", sentence, sentence, sentence.indexOf("got"), 0f, 0f)
        )

        assertNotNull(result.entry)
        assertEquals("have got to", result.entry!!.headword)
        assertTrue(result.entry!!.senses.any { it.text.contains("必须") || it.text.contains("不得不") })
    }
}
