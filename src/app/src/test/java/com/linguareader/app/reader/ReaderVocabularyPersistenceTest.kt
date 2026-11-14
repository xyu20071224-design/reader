package com.linguareader.app.reader

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.AppViewModel
import com.linguareader.app.R
import com.linguareader.app.StatusTone
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.DictionarySense
import com.linguareader.app.data.PartOfSpeech
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ReaderVocabularyPersistenceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val lookup = WordLookup("Mithril", "A Mithril coat.", "A Mithril coat.", 2, 0f, 0f)
    private val book = Book(
        id = "vocabulary-test", title = "Test Book", author = "Tester",
        extractedDir = context.cacheDir.absolutePath, coverRelativePath = null,
        chapters = listOf(Chapter("Test Chapter", "test.xhtml")), addedAt = 1L
    )

    private fun dictionaryMiss() = ReaderLookupSession.empty().begin(
        DictionaryLookupResult(null, null), hasTranslation = false, sentence = lookup.sentence
    )

    @Test
    fun dictionaryMissSavesAndReloadsOriginalWordAndSentence() = runBlocking {
        val repository = VocabularyRepository(context)
        repository.save(book, "Test Chapter", lookup, dictionaryMiss().vocabularyEntry(lookup))

        val saved = VocabularyRepository(context).load().single()
        assertEquals("mithril", saved.id)
        assertEquals("Mithril", saved.headword)
        assertEquals(lookup.sentence, saved.sentence)
        assertEquals(book.id, saved.bookId)
        assertEquals("Test Chapter", saved.chapterTitle)
        assertEquals("", saved.meaning)
        assertEquals("", saved.aiMeaning)

        repository.remove(saved.id)
        assertTrue(VocabularyRepository(context).load().isEmpty())
    }

    @Test
    fun aiOnlyLookupKeepsContextMeaningSeparateFromMissingDictionarySenses() = runBlocking {
        val ai = AiLookupResult("mithril", "秘银", "故事中的一种金属", source = "test")
        val session = dictionaryMiss().withAiResult(ai)
        val entry = session.vocabularyEntry(lookup)
        val saved = VocabularyRepository(context).save(book, "Test Chapter", lookup, entry,
            aiResult = session.aiResult).single()

        assertEquals("mithril", saved.id)
        assertEquals("", saved.meaning)
        assertEquals("秘银", saved.aiMeaning)
        assertEquals("故事中的一种金属", saved.aiExplanation)
        assertEquals("test", saved.aiSource)
        assertEquals(saved, VocabularyRepository(context).load().single())
    }

    @Test
    fun dictionaryLemmasAndExplicitPhraseSelectionKeepTheirEntries() = runBlocking {
        val word = ContextualDictionaryEntry(
            "studied", "study", "stʌdi", listOf(DictionarySense("v. 学习", PartOfSpeech.VERB, true)),
            emptyList(), null, PartOfSpeech.VERB
        )
        val phrase = word.copy(headword = "study up", matchedPhrase = "study up")
        val request = lookup.copy(word = "studied", sentence = "They studied up.")
        val session = ReaderLookupSession.empty().begin(
            DictionaryLookupResult(word, phrase), hasTranslation = false, sentence = request.sentence
        )
        assertEquals(word, session.vocabularyEntry(request))
        assertEquals(phrase, session.setShowingRelatedPhrase(true).vocabularyEntry(request))
        val repository = VocabularyRepository(context)
        val saved = repository.save(book, "Test Chapter", request, session.vocabularyEntry(request)).single()
        assertEquals("study", saved.id)
        assertEquals(listOf("studied"), saved.surfaceForms)
        assertEquals("v. 学习", saved.meaning)
        assertEquals("study up", repository.save(book, "Test Chapter", request,
            session.setShowingRelatedPhrase(true).vocabularyEntry(request)).first { it.id == "study up" }.headword)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("异步保存未完成", condition())
    }

    @Test
    fun saveCallbackFollowsPersistenceAndFailureCanBeRetried() {
        val viewModel = AppViewModel(context)
        await { !viewModel.state.value.loading }
        val entry = dictionaryMiss().vocabularyEntry(lookup)
        // 用目录占住临时文件，确定性复现写盘失败，不修改任何断言或吞掉异常。
        val blockedTemp = File(context.filesDir, "vocabulary.json.tmp").apply { mkdirs() }
        var completed: Boolean? = null
        viewModel.saveWord(book, "Test Chapter", lookup, entry) { completed = it }
        await { completed != null }
        assertEquals(false, completed)
        assertTrue(viewModel.state.value.savedWords.isEmpty())
        assertEquals(StatusTone.DANGER, viewModel.state.value.noticeTone)
        assertEquals(context.getString(R.string.notice_word_save_failed), viewModel.state.value.notice)

        assertTrue(blockedTemp.delete())
        completed = null
        viewModel.saveWord(book, "Test Chapter", lookup, entry) {
            assertEquals("mithril", viewModel.state.value.savedWords.single().id)
            completed = it
        }
        await { completed != null }
        assertEquals(true, completed)
        assertEquals(StatusTone.SUCCESS, viewModel.state.value.noticeTone)
        assertEquals("mithril", runBlocking { VocabularyRepository(context).load().single().id })
    }
}
