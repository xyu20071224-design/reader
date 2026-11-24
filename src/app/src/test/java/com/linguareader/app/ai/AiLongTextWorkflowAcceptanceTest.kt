package com.linguareader.app.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.LibraryRepository
import com.linguareader.app.translation.TranslationMemoryRepository
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.translation.TranslationMatchLevel
import com.linguareader.shared.tts.SentenceSplitter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/** 真实 TXT 导入/自动编排/检查点/书库挂载；服务使用确定性本地桩，不冒充真实 AI。 */
@RunWith(RobolectricTestRunner::class)
class AiLongTextWorkflowAcceptanceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val extractor = TtsTextExtractor()

    private data class Fixture(val book: Book, val paragraphs: List<String>, val batches: List<TranslationBatch>)
    private data class Request(val indices: List<Int>, val sources: List<String>)

    private class SyntheticChat(private val failCalls: Set<Int> = emptySet()) : AiTranslationChatClient {
        val requests = mutableListOf<Request>()
        override suspend fun translateSegments(system: String, user: String): JSONObject {
            val matches = Regex("\\[(\\d+)] (.+)").findAll(user).toList()
            requests += Request(matches.map { it.groupValues[1].toInt() }, matches.map { it.groupValues[2] })
            if (requests.size in failCalls) throw AiRequestException("fixture HTTP 503")
            val segments = JSONArray()
            for (match in matches) {
                segments.put(JSONObject().put("i", match.groupValues[1].toInt()).put("t", translate(match.groupValues[2])))
            }
            return JSONObject().put("segments", segments)
        }
    }

    private fun repository(chat: SyntheticChat) = AiTranslationRepository(
        app, AiSettingsStore(app), BookGlossaryRepository(app), retryDelay = {}
    ) { chat }

    private fun sentence(marker: Int) = "Aster carried marker $marker through the silver valley."

    private suspend fun fixture(name: String, seed: Int, mixed: Boolean): Fixture {
        val raw = if (mixed) {
            listOf(
                sentence(seed),
                List(400) { sentence(seed + 1 + it) }.joinToString(" "),
                "Aster " + List(2_500) { "token" }.joinToString(" ") + " ends.",
                List(400) { sentence(seed + 501 + it) }.joinToString(" "),
                sentence(seed + 999)
            ).joinToString("\n\n")
        } else {
            // 350 行，没有空行：必须经真实 TXT 导入合成一个超长原段。
            List(350) { sentence(seed + it) }.joinToString("\n")
        }
        val file = File(app.filesDir, "q1-b02-fixtures/$name.txt").apply {
            parentFile!!.mkdirs()
            writeText(raw)
        }
        val book = LibraryRepository(app).importDownloadedFile(file, name)
        val paragraphs = extractor.chapter(book, 0).blocks
        assertEquals(if (mixed) 5 else 1, paragraphs.size)
        val batches = AiBookTranslator.groupIntoBatches(0, paragraphs)
        assertTrue("fixture must cross several batches", batches.size >= 4)
        assertTrue(batches.all { it.paragraphs.sumOf(String::length) <= AiBookTranslator.MAX_CHARS_PER_BATCH })
        assertEquals(paragraphs.indices.toList(), batches.flatMap { it.sourceParagraphIndices }.distinct())
        if (mixed) {
            assertEquals(1, SentenceSplitter.count(paragraphs[2]))
            assertTrue(paragraphs[2].length > AiBookTranslator.MAX_CHARS_PER_BATCH)
        }
        BookGlossaryRepository(app).addOrUpdate(book.id, "Aster", "")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        println("[q1-b02] fixture=$name sha256=$fingerprint chars=${raw.length} paragraphs=${paragraphs.map(String::length)}")
        println("[q1-b02] batches=${batches.map { listOf(it.chapterIndex, it.batchIndex, it.paragraphs.sumOf(String::length), it.sourceParagraphIndices) }}")
        return Fixture(book, paragraphs, batches)
    }

    private fun checkpointDirectory(book: Book) = File(app.filesDir, "ai/ai-translations/${book.id}")
    private fun checkpoints(book: Book): List<File> = checkpointDirectory(book).listFiles().orEmpty()
        .filter { it.name.matches(Regex("\\d+-\\d+\\.json")) }.sortedBy { it.name }

    private fun translationDirectory(book: Book) = File(app.filesDir, "translations/${AiTranslationRepository.translationId(book.id)}")

    private fun assertRequestSizes(chat: SyntheticChat) {
        assertTrue(chat.requests.isNotEmpty())
        assertTrue(chat.requests.all { request ->
            request.indices.isNotEmpty() && request.indices.distinct().size == request.indices.size &&
                request.sources.sumOf(String::length) <= AiBookTranslator.MAX_CHARS_PER_BATCH
        })
        println("[q1-b02] requestSourceChars=${chat.requests.map { it.sources.sumOf(String::length) }}")
    }

    private fun assertParagraphs(fixture: Fixture, translated: Book, failed: Set<Int> = emptySet()) {
        val actual = extractor.chapter(translated, 0).blocks
        assertEquals("one final result for every original paragraph", fixture.paragraphs.size, actual.size)
        for (index in actual.indices) {
            val expected = if (index in failed) fixture.paragraphs[index] else translate(fixture.paragraphs[index])
            assertEquals("paragraph $index text and order", compact(expected), compact(actual[index]))
            assertEquals("paragraph $index numeric anchors", numbers(fixture.paragraphs[index]), numbers(actual[index]))
            assertEquals("paragraph $index retained glossary term", Regex("Aster").findAll(fixture.paragraphs[index]).count(), Regex("Aster").findAll(actual[index]).count())
            if (index in failed) assertEquals("failed chunks fall back as a whole original paragraph", fixture.paragraphs[index], actual[index])
        }
    }

    @Test
    fun `a no blank line TXT finishes automatically and reopens from bookshelf with first and last sentence`() = runBlocking {
        val fixture = fixture("q1-b02-no-blank-lines", 10_000, mixed = false)
        val chat = SyntheticChat()
        val translated = repository(chat).translateBook(fixture.book) {}
        assertEquals(fixture.batches.size, chat.requests.size)
        assertRequestSizes(chat)
        assertParagraphs(fixture, translated)

        val translationRepository = TranslationMemoryRepository(app)
        val attached = translationRepository.attachGenerated(fixture.book, translated)
        val library = LibraryRepository(app)
        library.saveTranslation(fixture.book, translated.id, translated.title, attached.memory.alignedAt)
        val reopenedBook = LibraryRepository(app).loadBooks().single { it.id == fixture.book.id }
        assertEquals(translated.id, reopenedBook.translationBookId)
        assertEquals(translated.title, reopenedBook.translationTitle)
        val reopenedMemory = TranslationMemoryRepository(app)
        val paragraph = fixture.paragraphs.single()
        for (sentence in listOf(SentenceSplitter.split(paragraph).first(), SentenceSplitter.split(paragraph).last())) {
            val lookup = WordLookup("marker", sentence, paragraph, sentence.indexOf("marker"), 0f, 0f)
            val result = reopenedMemory.lookup(reopenedBook, 0, lookup)
            assertNotNull(result)
            assertEquals(TranslationMatchLevel.SENTENCE, result!!.matchLevel)
            assertEquals(compact(translate(sentence)), compact(result.chinese))
        }
    }

    @Test
    fun `mixed oversized and single long sentence paragraphs preserve all original mappings`() = runBlocking {
        val fixture = fixture("q1-b02-mixed-long", 20_000, mixed = true)
        val chat = SyntheticChat()
        val translated = repository(chat).translateBook(fixture.book) {}

        assertEquals(fixture.batches.size, chat.requests.size)
        assertRequestSizes(chat)
        assertParagraphs(fixture, translated)
        assertEquals(fixture.batches.size, checkpoints(fixture.book).size)
    }

    @Test
    fun `cancellation resumes in a new repository and only a stale checkpoint is requested again`() = runBlocking {
        val fixture = fixture("q1-b02-restart-resume", 30_000, mixed = true)
        val initial = SyntheticChat()
        assertThrows(CancellationException::class.java) {
            runBlocking { repository(initial).translateBook(fixture.book) { throw CancellationException("fixture user cancellation") } }
        }
        assertEquals(1, initial.requests.size)
        assertEquals(1, checkpoints(fixture.book).size)
        assertFalse(translationDirectory(fixture.book).exists())
        val paidCheckpoint = checkpoints(fixture.book).single()
        val paidBytes = paidCheckpoint.readBytes()

        val resumed = SyntheticChat()
        val translated = repository(resumed).translateBook(fixture.book) {}
        assertEquals(fixture.batches.size - 1, resumed.requests.size)
        assertTrue(paidBytes.contentEquals(paidCheckpoint.readBytes()))
        assertRequestSizes(resumed)
        assertParagraphs(fixture, translated)

        val stale = File(checkpointDirectory(fixture.book), "0-1.json")
        stale.writeText(JSONObject(stale.readText()).put("sourceHash", "fixture-outdated-hash").toString())
        val retained = checkpoints(fixture.book).filter { it != stale }.associateWith { it.readBytes() }
        val repaired = SyntheticChat()
        val reopened = repository(repaired).translateBook(fixture.book) {}
        assertEquals(1, repaired.requests.size)
        assertEquals(fixture.batches[1].paragraphIndices, repaired.requests.single().indices)
        assertTrue(retained.all { (file, bytes) -> bytes.contentEquals(file.readBytes()) })
        assertRequestSizes(repaired)
        assertParagraphs(fixture, reopened)
    }

    @Test
    fun `one failed chunk falls back its whole original paragraph without duplicating others`() = runBlocking {
        val fixture = fixture("q1-b02-long-failure", 40_000, mixed = true)
        val chat = SyntheticChat(failCalls = setOf(3, 4))
        val failures = mutableListOf<TranslationBatch>()
        val translated = repository(chat).translateBook(fixture.book, onBatchFailed = { batch, _ -> failures += batch }) {}

        assertEquals(1, failures.size)
        assertEquals(fixture.batches.size + 1, chat.requests.size)
        val failedOriginals = failures.flatMap { it.sourceParagraphIndices }.toSet()
        assertTrue(failedOriginals.isNotEmpty())
        assertRequestSizes(chat)
        assertParagraphs(fixture, translated, failedOriginals)
        println("[q1-b02] failedOriginalParagraphs=${failedOriginals.sorted()}")
    }

    @Test
    fun `three consecutive failed long batches abort without publishing a book and keep the paid checkpoint`() = runBlocking {
        val fixture = fixture("q1-b02-long-abort", 50_000, mixed = true)
        val chat = SyntheticChat(failCalls = (2..7).toSet())
        val failures = mutableListOf<TranslationBatch>()
        assertThrows(AiTranslationAbortedException::class.java) {
            runBlocking {
                repository(chat).translateBook(fixture.book, onBatchFailed = { batch, _ -> failures += batch }) {}
            }
        }

        assertEquals(7, chat.requests.size)
        assertEquals(3, failures.size)
        assertEquals(1, checkpoints(fixture.book).size)
        assertFalse(translationDirectory(fixture.book).exists())
        assertRequestSizes(chat)
        println("[q1-b02] abortedAfterRequests=${chat.requests.size} retainedCheckpoints=${checkpoints(fixture.book).size}")
    }

    private companion object {
        val markerSentence = Regex("Aster carried marker (\\d+) through the silver valley\\.")
        fun compact(text: String) = text.filterNot(Char::isWhitespace)
        fun numbers(text: String) = Regex("\\d+").findAll(text).map { it.value }.toList()
        fun translate(text: String): String {
            val sentences = markerSentence.findAll(text).toList()
            if (sentences.isNotEmpty() && markerSentence.replace(text, "").isBlank()) {
                return sentences.joinToString("\n") { "Aster携带标记${it.groupValues[1]}穿过银色山谷。" }
            }
            // 超长单句在词界硬切，局部替换可独立拼接；没有服务端句译质量断言。
            return compact(text.replace("token", "符号").replace("ends.", "结束。"))
        }
    }
}
