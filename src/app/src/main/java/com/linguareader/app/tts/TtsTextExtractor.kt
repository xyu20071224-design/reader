package com.linguareader.app.tts

import android.util.Log
import com.linguareader.app.data.Book
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** TtsChapter 真相已迁入 :shared（桌面迁移 M2 刀9）；此处 typealias 兜住同包引用。 */
typealias TtsChapter = com.linguareader.shared.tts.TtsChapter

/**
 * Extracts TTS chapters from the same HTML files the reader paginates.
 * Results are cached per (book id, chapter index) for a session.
 */
class TtsTextExtractor {
    // Concurrent: chapters are extracted on the IO dispatcher while the M2
    // speaker tagger writes refined tags back from its own coroutine.
    private val cache = ConcurrentHashMap<Pair<String, Int>, TtsChapter>()

    fun chapter(book: Book, chapterIndex: Int): TtsChapter {
        val key = book.id to chapterIndex
        cache[key]?.let { return it }
        val safeIndex = chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
        val chapter = book.chapters[safeIndex]
        val document = Jsoup.parse(File(book.extractedDir, chapter.relativePath), "UTF-8")
        val blocks = leafBlocks(document)
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        val speakers = SpeakerRuleTagger.tag(blocks, SentenceSplitter.TTS_MAX_SENTENCE_CHARS)
        return TtsChapter(safeIndex, chapter.title, blocks, speakers).also { cache[key] = it }
    }

    fun clear() {
        cache.clear()
    }

    /**
     * Applies refined speaker tags (M2 LLM layer) to a cached chapter, so the
     * next load of that chapter already carries them. A chapter that is not
     * cached, or a tag list of the wrong length, is ignored.
     */
    fun applySpeakers(bookId: String, chapterIndex: Int, speakers: List<String>) {
        val key = bookId to chapterIndex
        val cached = cache[key] ?: return
        cache[key] = cached.withSpeakers(speakers)
    }

    private fun leafBlocks(document: Document): List<Element> {
        val candidates = document.select(BLOCK_SELECTOR)
        // Leaf = no *descendant* matches BLOCK_SELECTOR. This must match the
        // reader JS `!el.querySelector(TTS_BLOCK_SELECTOR)` (querySelector only
        // looks at descendants, never the element itself).
        //
        // NOTE: Jsoup's Element.select(css) DOES include the element itself
        // when it matches, so `candidate.select(...).isEmpty()` would be false
        // for every candidate and filter them all out. Check that any match is
        // only the candidate itself instead, so a block whose descendants are
        // all inline (e.g. <div><span>text</span></div>) stays a leaf.
        return candidates.filter { candidate ->
            candidate.select(BLOCK_SELECTOR).all { it === candidate }
        }
    }

    companion object {
        /** Must stay in sync with `TTS_BLOCK_SELECTOR` in ReaderScripts.kt. */
        const val BLOCK_SELECTOR =
            "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, section, article, header, footer"
    }
}
