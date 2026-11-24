package com.linguareader.app.translation

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.DictionaryLookupResult
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationLookupResult
import com.linguareader.shared.translation.TranslationMemory
import com.linguareader.shared.translation.TranslationSensePresentation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class TranslationSenseVisibilityTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun archive(id: String, english: String, chinese: String): Book {
        val memory = TranslationMemory(
            id, "Sense fixture", "zh-$id", "译本", 0L,
            listOf(AlignedSentencePair(0, 0, english, chinese, english, chinese, 0.9f)),
            alignerVersion = TranslationAligner.VERSION
        )
        File(app.filesDir, "translation-memory/$id.json").apply {
            parentFile!!.mkdirs()
            writeText(memory.toJson().toString())
        }
        return Book(id, "Sense fixture", "", app.filesDir.path, null, emptyList(), 0L)
    }

    private fun dictionary(name: String, translation: String): File {
        val file = File(app.filesDir, "q1-t09/$name.sqlite")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)")
            database.execSQL("CREATE TABLE forms(form TEXT, lemma TEXT)")
            database.execSQL("INSERT INTO entries VALUES ('walk', '', ?, '')", arrayOf(translation))
        }
        return file
    }

    private fun assertVisibleSource(dictionary: DictionaryLookupResult, result: TranslationLookupResult, headword: String) {
        val alignment = result.wordAlignment
        assertNotNull("fixture must produce a word alignment", alignment)
        val rows = TranslationSensePresentation.rows(dictionary.entry, alignment)
        val aligned = rows.singleOrNull { it.aligned }
        assertNotNull("the selected dictionary sense must be visible: ${alignment?.sourceSense}", aligned)
        assertEquals(alignment!!.sourceSense, aligned!!.sense.text)
        assertEquals(headword, aligned.headword)
        assertTrue("the panel stays bounded", rows.size <= TranslationSensePresentation.MAX_PRIMARY_SENSES + 1)
        assertEquals(alignment.word, result.chinese.substring(alignment.start, alignment.endExclusive))
    }

    @Test
    fun `a built in word sense remains visible when the context entry is a phrase`() = runBlocking {
        val english = "Tom paid him for the keys and walked out into the rain."
        val chinese = "他付了钥匙钱，走进雨中。"
        val lookup = WordLookup("walked", english, english, english.indexOf("walked"), 0f, 0f)
        val dictionary = DictionaryRepository(app)
        val displayed = dictionary.lookup(lookup)
        assertTrue(displayed.entry!!.senses.none { it.text.contains("走") })
        val word = dictionary.lookup(WordLookup("walked", "", "", 0, 0f, 0f)).entry!!
        val book = archive("q1-t09-builtin-phrase", english, chinese)
        val result = TranslationMemoryRepository(app).lookup(book, 0, lookup)!!

        assertEquals("走", result.wordAlignment?.word)
        assertVisibleSource(displayed, result, word.headword)
    }

    @Test
    fun `an active pack sense after the first eight is visible without dropping earlier senses`() = runBlocking {
        val file = dictionary("many-senses", listOf(
            "v. 农耕", "v. 烹饪", "v. 建造", "v. 绘画", "v. 修理", "v. 演奏", "v. 编织", "v. 跳舞", "v. 走"
        ).joinToString("\n"))
        val english = "We walk."
        val lookup = WordLookup("walk", english, english, 3, 0f, 0f)
        val displayed = DictionaryRepository(app) { file }.lookup(lookup)
        assertEquals(9, displayed.entry!!.senses.size)
        val book = archive("q1-t09-ninth-sense", english, "我们走。")
        val result = TranslationMemoryRepository(app) { file }.lookup(book, 0, lookup)!!

        assertEquals(displayed.entry!!.senses[8].text, result.wordAlignment?.sourceSense)
        assertVisibleSource(displayed, result, "walk")
        val rows = TranslationSensePresentation.rows(displayed.entry, result.wordAlignment)
        assertEquals(displayed.entry!!.senses.take(8), rows.take(8).map { it.sense })
    }

    @Test
    fun `switching the active pack refreshes alignment candidates together with the panel`() = runBlocking {
        val first = dictionary("source-first", "v. 走")
        val second = dictionary("source-second", "v. 行")
        var active = first
        val dictionary = DictionaryRepository(app) { active }
        val repository = TranslationMemoryRepository(app) { active }
        val english = "We walk."
        val lookup = WordLookup("walk", english, english, 3, 0f, 0f)
        val book = archive("q1-t09-dictionary-switch", english, "我们走行。")
        assertEquals("走", repository.lookup(book, 0, lookup)!!.wordAlignment?.word)
        dictionary.lookup(lookup)

        active = second
        dictionary.invalidate()
        repository.invalidateDictionary()
        val displayed = dictionary.lookup(lookup)
        val result = repository.lookup(book, 0, lookup)!!

        assertEquals(displayed.entry!!.senses.single().text, result.wordAlignment?.sourceSense)
        assertVisibleSource(displayed, result, "walk")
    }
}
