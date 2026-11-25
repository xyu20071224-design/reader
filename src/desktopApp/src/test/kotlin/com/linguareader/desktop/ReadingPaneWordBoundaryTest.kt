package com.linguareader.desktop

import com.linguareader.shared.data.DictionaryRepository
import com.linguareader.shared.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingPaneWordBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun middleWordDoesNotIncludeFollowingSpace() {
        val text = "I eat apples today."
        assertEquals("apples", wordAt(text, text.indexOf("apples") + 2))
    }

    @Test fun sentenceFinalWordDoesNotIncludePunctuation() {
        val text = "I eat apples."
        assertEquals("apples", wordAt(text, text.indexOf("apples") + 2))
    }

    @Test fun firstAndLastLetterOfSingleWordAreIncluded() {
        assertEquals("apple", wordAt("apple", 0))
        assertEquals("apple", wordAt("apple", 4))
    }

    @Test fun wordAtBeginningAndEndOfTextHasExactBounds() {
        assertEquals("apple", wordAt("apple pear", 0))
        assertEquals("pear", wordAt("apple pear", 9))
    }

    @Test fun spacesTabsAndLineBreaksAreNotWords() {
        val text = "apple \t\n pear"
        for (index in 5..8) assertNull("offset=$index", wordAt(text, index))
    }

    @Test fun punctuationClicksAndOutOfBoundsOffsetsReturnNull() {
        assertNull(wordAt("apple.", 5))
        assertNull(wordAt("apple", -1))
        assertNull(wordAt("apple", 5))
        assertNull(wordAt("", 0))
    }

    @Test fun asciiApostropheInsideWordKeepsOriginalSupport() {
        val text = "I don't know."
        assertEquals("don't", wordAt(text, text.indexOf("don't")))
        assertEquals("don't", wordAt(text, text.indexOf("don't") + 4))
        assertNull(wordAt(text, text.indexOf('\'')))
    }

    @Test fun curlyApostropheInsideWordKeepsOriginalSupport() {
        val text = "I don’t know."
        assertEquals("don’t", wordAt(text, text.indexOf("don’t") + 2))
        assertNull(wordAt(text, text.indexOf('’')))
    }

    @Test fun lineBreakAfterWordIsExcluded() {
        assertEquals("apples", wordAt("apples\npears", 2))
        assertEquals("pears", wordAt("apples\npears", 9))
    }

    @Test fun actualClickedMiddleWordReachesPackDictionaryLookup() = runBlocking {
        val home = temporary.newFolder()
        val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home, meaning = "点词包释义"))
        val sentence = "I eat apples today."
        val offset = sentence.indexOf("apples") + 2
        val word = requireNotNull(wordAt(sentence, offset))
        val result = DictionaryRepository(DesktopPackDictionary(repository) { null })
            .lookup(WordLookup(word, sentence, sentence, offset, 0f, 0f))
        assertEquals("apple", result.entry?.headword)
        assertEquals(true, result.entry?.senses?.any { it.text.contains("点词包释义") })
    }
}
