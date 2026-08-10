package com.linguareader.app.tts

import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.jsoup.Jsoup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtsTextExtractorTest {
    @Test
    fun extractsBlocksAndSentencesFromChapterHtml() {
        val book = bookWith(
            """
            <html><body>
              <h2>Chapter Title.</h2>
              <p>Hello world. Second sentence here.</p>
              <p>你好，世界。再见！</p>
            </body></html>
            """.trimIndent()
        )

        val chapter = TtsTextExtractor().chapter(book, 0)

        assertEquals(listOf("Chapter Title.", "Hello world. Second sentence here.", "你好，世界。再见！"), chapter.blocks)
        assertEquals(
            listOf("Chapter Title.", "Hello world.", "Second sentence here.", "你好，世界。", "再见！"),
            chapter.sentences
        )
        assertEquals(5, chapter.sentenceCount)
    }

    @Test
    fun flattensNestedBlockWrappersWithoutDuplicatingText() {
        val book = bookWith(
            """
            <html><body>
              <div><p>First paragraph. More text.</p><p>Second paragraph.</p></div>
              <section><p>Third.</p></section>
            </body></html>
            """.trimIndent()
        )

        val chapter = TtsTextExtractor().chapter(book, 0)

        assertEquals(
            listOf("First paragraph. More text.", "Second paragraph.", "Third."),
            chapter.blocks
        )
        assertEquals(
            listOf("First paragraph.", "More text.", "Second paragraph.", "Third."),
            chapter.sentences
        )
    }

    @Test
    fun ignoresScriptAndStyleContents() {
        val book = bookWith(
            """
            <html><head><style>p { color: red; }</style></head><body>
              <script>var secret = 'x';</script>
              <p>Visible text.</p>
            </body></html>
            """.trimIndent()
        )

        val chapter = TtsTextExtractor().chapter(book, 0)

        assertEquals(listOf("Visible text."), chapter.blocks)
        assertFalse(chapter.sentences.any { it.contains("secret") })
        assertFalse(chapter.sentences.any { it.contains("color") })
    }

    @Test
    fun sentenceIndexAtMapsTappedOffsetToFlatSentenceIndex() {
        val book = bookWith(
            """
            <html><body>
              <p>Hello world. Second sentence.</p>
              <p>Third block.</p>
            </body></html>
            """.trimIndent()
        )
        val chapter = TtsTextExtractor().chapter(book, 0)
        val block = "Hello world. Second sentence."

        // "Second" starts at normalized offset 13.
        assertEquals(1, chapter.sentenceIndexAt(block, 13))
        assertEquals(0, chapter.sentenceIndexAt(block, 1))
        assertEquals(2, chapter.firstSentenceIndexInBlock("Third block."))
        assertTrue(chapter.sentenceBelongsToBlock(1, block))
        assertFalse(chapter.sentenceBelongsToBlock(2, block))
        assertNull(chapter.firstSentenceIndexInBlock("No such paragraph."))
    }

    @Test
    fun jsoupAndNormalizerProduceWhitespaceCollapsedBlocks() {
        val document = Jsoup.parse(
            """
            <html><body><p>Hello
              world.   Second sentence.</p></body></html>
            """.trimIndent()
        )

        val text = document.selectFirst("p")!!.text()

        assertEquals("Hello world. Second sentence.", text)
    }

    private fun bookWith(html: String): Book {
        val dir = File.createTempFile("tts-chapter", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        File(dir, "chapter.xhtml").writeText(html)
        return Book(
            id = "tts-test",
            title = "TTS Book",
            author = "Tester",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter", "chapter.xhtml")),
            addedAt = 1L
        )
    }
}
