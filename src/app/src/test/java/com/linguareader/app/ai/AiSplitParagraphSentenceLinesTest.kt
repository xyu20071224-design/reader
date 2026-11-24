package com.linguareader.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.tts.SentenceSplitter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 跨批长段走真实翻译仓库，确认网络生成与检查点组装都保留句界。 */
@RunWith(RobolectricTestRunner::class)
class AiSplitParagraphSentenceLinesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sentenceCount = 250
    private val translatedLine = "小鸟回到了安静的家中。"

    private class SentenceChat(private val line: String) : AiTranslationChatClient {
        var calls = 0

        override suspend fun translateSegments(system: String, user: String): JSONObject {
            calls++
            val segments = JSONArray()
            Regex("\\[(\\d+)] (.+)").findAll(user).forEach { match ->
                val lines = List(SentenceSplitter.count(match.groupValues[2])) { line }
                segments.put(JSONObject().put("i", match.groupValues[1].toInt()).put("t", lines.joinToString("\n")))
            }
            return JSONObject().put("segments", segments)
        }
    }

    private fun sourceBook(id: String): Book {
        val directory = File(context.filesDir, "q1-t01-books/$id").apply { mkdirs() }
        val paragraph = List(sentenceCount) { "The small bird came back to its quiet home." }.joinToString(" ")
        assertTrue(paragraph.length > AiBookTranslator.MAX_CHARS_PER_BATCH)
        File(directory, "c0.xhtml").writeText("<html><body><p>$paragraph</p></body></html>")
        return Book(
            id = id, title = "Sentence boundary fixture", author = "",
            extractedDir = directory.absolutePath, coverRelativePath = null,
            chapters = listOf(Chapter("Chapter", "c0.xhtml")), addedAt = 0L
        )
    }

    private fun repository(chat: SentenceChat) = AiTranslationRepository(
        context, AiSettingsStore(context), BookGlossaryRepository(context), retryDelay = {}
    ) { chat }

    private suspend fun assertSidecar(repo: AiTranslationRepository, source: Book, translated: Book) {
        val actualParagraph = TtsTextExtractor().chapter(translated, 0).blocks.single()
        val sidecar = repo.readSentenceLines(source.id)
        assertEquals(setOf(actualParagraph), sidecar.keys)
        assertEquals(List(sentenceCount) { translatedLine }, sidecar[actualParagraph])
    }

    @Test
    fun `automatic translation retains every sentence boundary of a split paragraph`() = runBlocking {
        val source = sourceBook("q1-t01-automatic")
        val chat = SentenceChat(translatedLine)
        val repo = repository(chat)
        val translated = repo.translateBook(source) {}

        assertTrue("夹具必须真实跨批", chat.calls > 1)
        assertSidecar(repo, source, translated)
    }

    @Test
    fun `checkpoint assembly rebuilds sentence boundaries without more requests`() = runBlocking {
        val source = sourceBook("q1-t01-checkpoints")
        val chat = SentenceChat(translatedLine)
        val repo = repository(chat)
        repo.translateBook(source) {}
        val requestsBefore = chat.calls
        assertTrue(requestsBefore > 1)
        File(context.filesDir, "ai/ai-translations/${source.id}/sentence-lines.json").delete()

        val assembled = repo.completeFromCheckpoints(source, "Fixture")

        assertEquals(requestsBefore, chat.calls)
        assertSidecar(repo, source, assembled)
    }
}
