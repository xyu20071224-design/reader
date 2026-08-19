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
    fun sentenceIndexAtRebasesOffsetFromAncestorParagraph() {
        val book = bookWith(
            """
            <html><body>
              <p>Alpha one. Beta two.</p>
              <p>Gamma three.</p>
            </body></html>
            """.trimIndent()
        )
        val chapter = TtsTextExtractor().chapter(book, 0)

        // Exact leaf paragraph: "Beta" starts at normalized offset 11.
        assertEquals(1, chapter.sentenceIndexAt("Alpha one. Beta two.", 11))

        // The tapped text is an ancestor containing both leaf blocks; the
        // offset must be rebased onto the leaf that actually contains it.
        assertEquals(1, chapter.sentenceIndexAt("Alpha one. Beta two. Gamma three.", 11))
        assertEquals(2, chapter.sentenceIndexAt("Alpha one. Beta two. Gamma three.", 21))
    }

    @Test
    fun sentenceLocationFindsNonFirstSentencesWithinBlock() {
        val book = bookWith(
            """
            <html><body>
              <p>Alpha one. Beta two. Gamma three.</p>
              <p>After block.</p>
            </body></html>
            """.trimIndent()
        )
        val chapter = TtsTextExtractor().chapter(book, 0)
        val alpha = "Alpha one."
        val beta = "Beta two."
        val gamma = "Gamma three."

        assertEquals(Triple(0, 0, alpha.length), chapter.sentenceLocation(0))
        // Second and third sentences of the same block must resolve to their
        // real offsets (regression: they previously returned null because the
        // cursor search reused the target sentence instead of advancing past
        // each preceding sentence).
        assertEquals(Triple(0, alpha.length + 1, beta.length), chapter.sentenceLocation(1))
        assertEquals(Triple(0, alpha.length + 1 + beta.length + 1, gamma.length), chapter.sentenceLocation(2))
        assertEquals(Triple(1, 0, "After block.".length), chapter.sentenceLocation(3))
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

    @Test
    fun treatsBlockContainingOnlyInlineElementsAsLeaf() {
        // A <div> wrapping only inline <span> markup is a leaf block, matching
        // the reader JS `!el.querySelector(TTS_BLOCK_SELECTOR)`. Previously the
        // extractor required "no element descendants at all", so <div><span>…
        // was skipped while <span> (not in BLOCK_SELECTOR) contributed nothing,
        // and block indices / TTS highlighting would drift out of sync with JS.
        val book = bookWith(
            """
            <html><body>
              <div><span>Inline alpha. Inline beta.</span></div>
              <p>After the inline block.</p>
            </body></html>
            """.trimIndent()
        )

        val chapter = TtsTextExtractor().chapter(book, 0)

        assertEquals(
            listOf("Inline alpha. Inline beta.", "After the inline block."),
            chapter.blocks
        )
    }

    @Test
    fun nestedBlockStillFoundWhenInnerBlockMatchesSelector() {
        // Regression guard for the leaf rule: an outer block that itself
        // contains another BLOCK_SELECTOR element is NOT a leaf, so only the
        // innermost block is emitted (no duplicated outer text).
        val book = bookWith(
            """
            <html><body>
              <div><span>Inline alpha.</span><p>Inner beta.</p></div>
            </body></html>
            """.trimIndent()
        )

        val chapter = TtsTextExtractor().chapter(book, 0)

        assertEquals(listOf("Inner beta."), chapter.blocks)
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
