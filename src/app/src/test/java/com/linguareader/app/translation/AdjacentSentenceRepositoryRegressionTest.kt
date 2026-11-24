package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMatchLevel
import com.linguareader.shared.translation.TranslationMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AdjacentSentenceRepositoryRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `legacy archive upgrade and targeted retranslation preserve adjacent sentence identity`() = runBlocking {
        val first = "The hobbit lived in a hole."
        val second = "It was a comfortable hole."
        val english = "$first $second"
        val chinese = "哈比人住在洞里。那是个舒服的洞。"
        val id = "q1-t04-upgrade-adjacent"
        val directory = File(app.filesDir, "books/$id").apply { mkdirs() }
        File(directory, "c0.xhtml").writeText("<html><body><p>$english</p></body></html>")
        val book = Book(
            id, "Adjacent fixture", "", directory.absolutePath, null,
            listOf(Chapter("Chapter", "c0.xhtml")), 0L
        )
        val memory = TranslationMemory(
            id, book.title, "zh-$id", "译本", 0L,
            listOf(AlignedSentencePair(0, 0, english, chinese, english, "哈比人住在洞里。", 0.9f)),
            alignerVersion = 6
        )
        File(app.filesDir, "translation-memory/$id.json").apply {
            parentFile!!.mkdirs()
            writeText(memory.toJson().toString())
        }
        val repository = TranslationMemoryRepository(app)
        fun query(sentence: String) = WordLookup("hole", sentence, english, sentence.indexOf("hole"), 0f, 0f)

        for (sentence in listOf(first, second)) {
            val old = repository.lookup(book, 0, query(sentence))
            assertEquals(TranslationMatchLevel.PARAGRAPH, old?.matchLevel)
            assertEquals(chinese, old?.chinese)
            assertNull(old?.wordAlignment)
        }
        assertTrue(repository.isMemoryOutdated(id))

        val upgraded = repository.realign(book)
        assertEquals(TranslationAligner.VERSION, upgraded?.alignerVersion)
        val firstHit = repository.lookup(book, 0, query(first))
        val secondHit = repository.lookup(book, 0, query(second))
        assertEquals(TranslationMatchLevel.SENTENCE, firstHit?.matchLevel)
        assertEquals(TranslationMatchLevel.SENTENCE, secondHit?.matchLevel)
        assertEquals("哈比人住在洞里。", firstHit?.chinese)
        assertEquals("那是个舒服的洞。", secondHit?.chinese)
        assertNotNull(secondHit)

        val reopened = TranslationMemoryRepository(app)
        assertEquals(firstHit, reopened.lookup(book, 0, query(first)))
        assertEquals(secondHit, reopened.lookup(book, 0, query(second)))

        val replacement = "那是一个舒适的住处。"
        repository.replaceSentenceTranslation(id, secondHit!!.pairIndex, replacement)
        assertEquals(firstHit, repository.lookup(book, 0, query(first)))
        assertEquals(replacement, repository.lookup(book, 0, query(second))?.chinese)
        val afterRestart = TranslationMemoryRepository(app)
        assertEquals(firstHit, afterRestart.lookup(book, 0, query(first)))
        assertEquals(replacement, afterRestart.lookup(book, 0, query(second))?.chinese)
    }
}
