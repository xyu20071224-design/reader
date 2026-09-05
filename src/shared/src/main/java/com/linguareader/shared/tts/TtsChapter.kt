package com.linguareader.shared.tts

// 桌面迁移 M2 刀9：TtsChapter 自 TtsTextExtractor.kt 逐字下沉（Log.w→println，桌面无 android.util.Log）。
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
    private val sentencesByBlock: List<List<String>> =
        blocks.map { SentenceSplitter.split(it, SentenceSplitter.TTS_MAX_SENTENCE_CHARS) }

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

    companion object {
        private const val TAG = "TtsChapter"
    }

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

    fun blockIndexForSentence(sentenceIndex: Int): Int {
        var remaining = sentenceIndex.coerceAtLeast(0)
        sentencesByBlock.forEachIndexed { index, list ->
            if (remaining < list.size) return index
            remaining -= list.size
        }
        return sentencesByBlock.lastIndex.coerceAtLeast(0)
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
                    if (found < 0) {
                        // 静默 null 会让高亮无声消失，这里至少留一条排查线索。
                        // 常见成因：分句输出与块文本的归一化契约被打破
                        // （split 内部做了新的字符级改写，或块归一化方式漂移）。
                        println("[$TAG] sentence $i not found in block $blockIndex; " +
                                "highlight skipped. block=\"${blocks[blockIndex].take(80)}\" " +
                                "sentence=\"${sentence.take(60)}\""
                        )
                        return null
                    }
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
