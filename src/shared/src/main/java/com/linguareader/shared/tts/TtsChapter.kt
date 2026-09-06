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

    // ── 朗读片段（句内发言/旁白分离） ─────────────────────────────────────

    /** 每块的「字符是否在引语内」标记（QuoteSpans 坐标 = 原块文本坐标）。 */
    private val blockQuoteFlags: List<BooleanArray> by lazy {
        QuoteSpans.spans(blocks).mapIndexed { index, spans ->
            val flags = BooleanArray(blocks[index].length)
            spans.forEach { span -> for (i in span) if (i in flags.indices) flags[i] = true }
            flags
        }
    }

    /**
     * 全章朗读片段：合成与高亮的实际单位。纯旁白/纯引语句 1:1；引语嵌在句中
     * 的句子拆成 narrator / speaker 多段。由 [speakers] 派生——withSpeakers 的
     * copy 会生成新实例，懒属性随之重算。
     */
    val utterances: List<TtsUtterance> by lazy { buildUtterances() }

    private val utterancesBySentence: Map<Int, List<TtsUtterance>> by lazy {
        utterances.groupBy { it.sentenceIndex }
    }

    /** [sentenceIndex] 句的片段列表（按文档序；多数句恰为 1 段）。 */
    fun segmentsOf(sentenceIndex: Int): List<TtsUtterance> =
        utterancesBySentence[sentenceIndex].orEmpty()

    /** 点按处的 (句， 片段)；落在句间空白时退回句首段（与 sentenceIndexAt 同口径）。 */
    fun utteranceAt(blockText: String, blockOffset: Int): Pair<Int, Int>? {
        val (blockIndex, offsetInBlock) = locateBlock(blockText, blockOffset) ?: return null
        val offset = offsetInBlock.coerceIn(0, blocks[blockIndex].length)
        utterances.firstOrNull {
            it.blockIndex == blockIndex && offset >= it.offset && offset < it.offset + it.length
        }?.let { return it.sentenceIndex to it.segmentIndex }
        return sentenceIndexAt(blockText, blockOffset)?.let { it to 0 }
    }

    private fun buildUtterances(): List<TtsUtterance> {
        val result = mutableListOf<TtsUtterance>()
        var sentenceBase = 0
        for (blockIndex in blocks.indices) {
            val block = blocks[blockIndex]
            val flags = blockQuoteFlags[blockIndex]
            var cursor = 0
            sentencesByBlock[blockIndex].forEachIndexed { inside, sentence ->
                val sIndex = sentenceBase + inside
                val speaker = speakerAt(sIndex)
                val start = block.indexOf(sentence, cursor)
                val pieces = if (start < 0) {
                    // 定位失败（归一化契约被打破）：整句单段，高亮跳过但朗读照常。
                    listOf(TtsUtterance(sIndex, 0, 1, speaker, sentence, -1, -1))
                } else {
                    cursor = start + sentence.length
                    mergeSameSpeaker(
                        block,
                        segmentRuns(block, blockIndex, flags, start, start + sentence.length, sIndex, speaker)
                    )
                }
                val count = pieces.size
                pieces.forEachIndexed { seg, piece ->
                    result += if (count == 1) piece else piece.copy(segmentIndex = seg, segmentCount = count)
                }
            }
            sentenceBase += sentencesByBlock[blockIndex].size
        }
        return result
    }

    /** 把 [from, until) 按引语标记切成极大连续段（旁白/引语天然交替）。
     *  段边缘空白随坐标一起裁掉：保证 substring(offset, offset+length) == text。 */
    private fun segmentRuns(
        block: String,
        blockIndex: Int,
        flags: BooleanArray,
        from: Int,
        until: Int,
        sentenceIndex: Int,
        sentenceSpeaker: String
    ): List<TtsUtterance> {
        val pieces = mutableListOf<TtsUtterance>()
        var i = from
        while (i < until) {
            val inQuote = flags[i]
            var j = i
            while (j < until && flags[j] == inQuote) j++
            var start = i
            var end = j
            while (start < end && block[start] == ' ') start++
            while (end > start && block[end - 1] == ' ') end--
            if (end > start) {
                pieces += TtsUtterance(
                    sentenceIndex = sentenceIndex,
                    segmentIndex = 0,
                    segmentCount = 1,
                    speaker = if (inQuote) sentenceSpeaker else TtsUtterance.NARRATOR,
                    text = block.substring(start, end),
                    blockIndex = blockIndex,
                    offset = start
                )
            }
            i = j
        }
        return pieces
    }

    /** 无标签章节里整句都是 narrator 时，引语段与旁白段同声部——合并省请求。
     *  合并文本取块内连续子串（区间相邻），保持「offset 处 substring == text」。 */
    private fun mergeSameSpeaker(block: String, pieces: List<TtsUtterance>): List<TtsUtterance> {
        if (pieces.size < 2) return pieces
        val out = mutableListOf<TtsUtterance>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == piece.speaker) {
                val merged = block.substring(last.offset, piece.offset + piece.length).trim()
                out[out.lastIndex] = last.copy(text = merged)
            } else {
                out += piece
            }
        }
        return out
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
