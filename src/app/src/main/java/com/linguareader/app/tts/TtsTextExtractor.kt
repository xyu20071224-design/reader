package com.linguareader.app.tts

import com.linguareader.app.data.Book
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Plain text for one chapter, split into sentences for TTS playback.
 *
 * The block list mirrors the DOM structure used by the reader JavaScript
 * (`ttsBlocks()`): the same leaf block selector and the same whitespace
 * normalisation, so a sentence spoken by TTS can be highlighted exactly.
 */
data class TtsChapter(
    val chapterIndex: Int,
    val title: String,
    val blocks: List<String>,
    /** Per-sentence speaker tags, parallel to [sentences] (M1 multi-voice).
     *  Empty means every sentence is "narrator" (pre-M1 caches, no tagger). */
    val speakers: List<String> = emptyList()
) {
    private val sentencesByBlock: List<List<String>> = blocks.map { SentenceSplitter.split(it) }

    val sentences: List<String> get() = sentencesByBlock.flatten()

    val sentenceCount: Int get() = sentencesByBlock.sumOf { it.size }

    /** Speaker of the flat [sentenceIndex]-th sentence; "narrator" when the
     *  chapter carries no speaker tags (M1: "narrator" vs everything else). */
    fun speakerAt(sentenceIndex: Int): String =
        speakers.getOrNull(sentenceIndex)?.takeIf { it.isNotBlank() } ?: "narrator"

    /**
     * The same chapter with refined speaker tags (M2: the LLM tagger upgrades
     * the rule-layer tags asynchronously). A list that is not parallel to the
     * sentences is refused, so a stale answer can never shift voices.
     */
    fun withSpeakers(speakers: List<String>): TtsChapter =
        if (speakers.size == sentenceCount) copy(speakers = speakers) else this

    /** Flat sentence index for a tapped position inside one block. */
    fun sentenceIndexAt(blockText: String, blockOffset: Int): Int? {
        val (blockIndex, offsetInBlock) = locateBlock(blockText, blockOffset) ?: return null
        val prefix = (0 until blockIndex).sumOf { sentencesByBlock[it].size }
        val offset = offsetInBlock.coerceIn(0, blocks[blockIndex].length)
        var cursor = 0
        sentencesByBlock[blockIndex].forEachIndexed { insideIndex, sentence ->
            val found = blocks[blockIndex].indexOf(sentence, cursor)
            if (found >= 0) {
                if (offset >= found && offset < found + sentence.length) {
                    return prefix + insideIndex
                }
                cursor = found + sentence.length
            }
        }
        return null
    }

    /** Flat index of the first sentence in a block (used for page-follow sync). */
    fun firstSentenceIndexInBlock(blockText: String): Int? {
        val (blockIndex, _) = locateBlock(blockText, 0) ?: return null
        return (0 until blockIndex).sumOf { sentencesByBlock[it].size }
    }

    fun blockIndexForSentence(sentenceIndex: Int): Int {
        var remaining = sentenceIndex.coerceAtLeast(0)
        sentencesByBlock.forEachIndexed { index, list ->
            if (remaining < list.size) return index
            remaining -= list.size
        }
        return sentencesByBlock.lastIndex.coerceAtLeast(0)
    }

    fun sentenceBelongsToBlock(sentenceIndex: Int, blockText: String): Boolean {
        val (blockIndex, _) = locateBlock(blockText, 0) ?: return false
        return blockIndexForSentence(sentenceIndex) == blockIndex
    }

    /**
     * Block index, character offset and length of the flat [sentenceIndex]-th
     * sentence. Highlighting by this location (instead of searching the text)
     * keeps repeated sentences pointing at the occurrence actually being read.
     */
    fun sentenceLocation(sentenceIndex: Int): Triple<Int, Int, Int>? {
        var remaining = sentenceIndex.coerceAtLeast(0)
        for ((blockIndex, blockSentences) in sentencesByBlock.withIndex()) {
            if (remaining < blockSentences.size) {
                var cursor = 0
                for (i in 0..remaining) {
                    // Advance the cursor past each *preceding* sentence in the
                    // block; searching for the target sentence itself on every
                    // iteration makes every non-first sentence return null.
                    val sentence = blockSentences[i]
                    val found = blocks[blockIndex].indexOf(sentence, cursor)
                    if (found < 0) return null
                    if (i == remaining) return Triple(blockIndex, found, sentence.length)
                    cursor = found + sentence.length
                }
            }
            remaining -= blockSentences.size
        }
        return null
    }

    /**
     * Finds the leaf block the tapped paragraph belongs to and rebases the
     * tapped offset onto that block. Exact leaf match is preferred; when the
     * tapped text is an ancestor containing several leaves (selector drift or
     * nested wrappers), the longest contained leaf is used.
     */
    private fun locateBlock(blockText: String, blockOffset: Int): Pair<Int, Int>? {
        val normalized = blockText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return null
        blocks.indexOfFirst { it == normalized }.takeIf { it >= 0 }?.let {
            return it to blockOffset
        }
        val contained = blocks.mapIndexedNotNull { index, block ->
            val at = normalized.indexOf(block)
            if (at >= 0) Triple(index, at, at + block.length) else null
        }
        val hit = contained.firstOrNull { blockOffset in it.second until it.third }
        val leaf = hit ?: contained.maxByOrNull { blocks[it.first].length } ?: return null
        return leaf.first to (blockOffset - leaf.second)
    }
}

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
        val speakers = SpeakerRuleTagger.tag(blocks)
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
