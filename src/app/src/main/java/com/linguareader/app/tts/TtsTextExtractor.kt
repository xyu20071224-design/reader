package com.linguareader.app.tts

import com.linguareader.app.data.Book
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File

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
    val blocks: List<String>
) {
    private val sentencesByBlock: List<List<String>> = blocks.map { SentenceSplitter.split(it) }

    val sentences: List<String> get() = sentencesByBlock.flatten()

    val sentenceCount: Int get() = sentencesByBlock.sumOf { it.size }

    /** Flat sentence index for a tapped position inside one block. */
    fun sentenceIndexAt(blockText: String, blockOffset: Int): Int? {
        val blockIndex = blockIndexFor(blockText) ?: return null
        val prefix = (0 until blockIndex).sumOf { sentencesByBlock[it].size }
        val offset = blockOffset.coerceIn(0, blocks[blockIndex].length)
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
        val blockIndex = blockIndexFor(blockText) ?: return null
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
        val blockIndex = blockIndexFor(blockText) ?: return false
        return blockIndexForSentence(sentenceIndex) == blockIndex
    }

    private fun blockIndexFor(blockText: String): Int? {
        val normalized = blockText.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return null
        return blocks.indexOfFirst { it == normalized }
            .takeIf { it >= 0 }
            ?: blocks.indexOfFirst { it.contains(normalized) }
            .takeIf { it >= 0 }
    }
}

/**
 * Extracts TTS chapters from the same HTML files the reader paginates.
 * Results are cached per (book id, chapter index) for a session.
 */
class TtsTextExtractor {
    private val cache = mutableMapOf<Pair<String, Int>, TtsChapter>()

    fun chapter(book: Book, chapterIndex: Int): TtsChapter {
        val key = book.id to chapterIndex
        cache[key]?.let { return it }
        val safeIndex = chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))
        val chapter = book.chapters[safeIndex]
        val document = Jsoup.parse(File(book.extractedDir, chapter.relativePath), "UTF-8")
        val blocks = leafBlocks(document)
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
        return TtsChapter(safeIndex, chapter.title, blocks).also { cache[key] = it }
    }

    fun clear() {
        cache.clear()
    }

    private fun leafBlocks(document: Document): List<Element> {
        val candidates = document.select(BLOCK_SELECTOR)
        // Element.select() includes the element itself when it matches, so a
        // leaf block is one whose descendant set contains no other block.
        return candidates.filter { candidate ->
            candidate.getAllElements().size == 1
        }
    }

    companion object {
        /** Must stay in sync with `TTS_BLOCK_SELECTOR` in ReaderScripts.kt. */
        const val BLOCK_SELECTOR =
            "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, section, article, header, footer"
    }
}
