package com.linguareader.app.tts

import com.linguareader.shared.tts.QuoteSpans

/**
 * Rule-based speaker tagger (PLAN-MULTI-VOICE §4.1, M1 scope).
 *
 * Turns an extracted chapter's leaf blocks into a per-sentence speaker list
 * parallel to `TtsChapter.sentences`. M1 only needs two classes — narration
 * vs dialogue — so the tagger marks every quoted span as dialogue and every
 * unquoted sentence as narrator. When a speaker can be attributed from the
 * surrounding attribution pattern (`X said, "…"`, `"…", said X`,
 * `"…", said X, "…"`), the name is recorded for M2; unattributed dialogue
 * falls back to the "dialogue" marker.
 *
 * Deliberately conservative:
 * - ASCII apostrophes (`'`) are never treated as quote marks, so possessives
 *   and contractions (`don't`, `it's`, `'tis`) cannot open a false quote.
 * - Indirect speech (`he said that …`), thoughts and songs without quotes all
 *   stay narrator.
 * - An unclosed quote at the end of a block carries over into the next block
 *   (cross-paragraph dialogue is merged, §4.1).
 */
/**
 * One sentence's place in the chapter's paragraph / quote structure.
 *
 * The LLM layer (M2) answers by paragraph index + quote ordinal, never by
 * text, so mapping its answer back onto sentences is a pure lookup here and
 * cannot drift with whitespace normalisation (PLAN-MULTI-VOICE §4.2 「对齐」).
 */
data class SpeakerSlot(
    /** Leaf-block index the sentence belongs to. */
    val paragraph: Int,
    /** Ordinal of the quote covering this sentence, or null for narration. */
    val quote: Int?,
    /** Rule-layer result: "narrator", "dialogue" or an attributed name. */
    val ruleSpeaker: String
)

/**
 * Rule-layer view of one chapter: a slot per sentence (parallel to
 * `TtsChapter.sentences`) plus the quote texts of every paragraph, which the
 * LLM prompt uses to number the quotes it must attribute.
 */
data class SpeakerQuoteIndex(
    val slots: List<SpeakerSlot>,
    /** quotes[paragraph] = quote texts of that paragraph, quote marks stripped. */
    val quotes: List<List<String>>
) {
    /** Per-sentence rule-layer speakers (the M1 result and the M2 fallback). */
    val ruleSpeakers: List<String> get() = slots.map { it.ruleSpeaker }

    val quoteCount: Int get() = quotes.sumOf { it.size }

    fun quotesOf(paragraph: Int): List<String> = quotes.getOrNull(paragraph).orEmpty()
}

object SpeakerRuleTagger {

    private val speechVerb = Regex(
        "said|asked|replied|whispered|shouted|muttered|cried|answered|yelled|" +
            "called|demanded|murmured|added|began|continued|explained|responded|" +
            "exclaimed|sighed"
    )
    /** `X said, "…"` — name before the verb, right before the opening quote. */
    private val frontAttribution = Regex(
        "([A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+)?)\\s+(?:$speechVerb)\\s*[,:]?\\s*$"
    )
    /** `"…", said X` — verb before the name, right after the closing quote. */
    private val backAttribution = Regex(
        "^\\s*(?:[,—–:;]\\s*)?(?:$speechVerb)\\s+([A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+)?)"
    )
    /** `"…", said X, "…"` — verb+name right before the opening quote. */
    private val midAttribution = Regex(
        "(?:$speechVerb)\\s+([A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+)?)\\s*[,:]?\\s*$"
    )

    private const val ATTRIBUTION_WINDOW = 80

    /** Capitalised pronouns are never speaker names. */
    private val pronoun = Regex("\\b(?:He|She|It|I|You|We|They)\\b")

    /**
     * Tags every sentence of [blocks] (leaf blocks, whitespace-normalised as
     * produced by `TtsTextExtractor`). [maxSentenceLength] must match the one
     * `TtsChapter` used for its own split, so the returned list stays parallel
     * to `blocks.flatMap { SentenceSplitter.split(it, maxSentenceLength) }`.
     */
    fun tag(blocks: List<String>, maxSentenceLength: Int = Int.MAX_VALUE): List<String> =
        index(blocks, maxSentenceLength).ruleSpeakers

    /**
     * Full rule-layer analysis: the per-sentence slots (paragraph + quote
     * ordinal + rule speaker) and the quote texts per paragraph. [tag] is this
     * result projected onto speakers only; the M2 LLM layer needs the slots to
     * align its per-quote answer with the sentence list.
     */
    fun index(blocks: List<String>, maxSentenceLength: Int = Int.MAX_VALUE): SpeakerQuoteIndex {
        val slots = mutableListOf<SpeakerSlot>()
        val quotesByParagraph = mutableListOf<List<String>>()
        // 引号区间统一走 shared 的 QuoteSpans（与 TtsChapter 片段化同一条口径，
        // 避免两处扫描漂移）；坐标是原块文本坐标（normalizeQuotes 等长替换）。
        val blockSpans = QuoteSpans.spans(blocks)
        for ((paragraph, block) in blocks.withIndex()) {
            val text = QuoteSpans.normalizeQuotes(block)
            val sentences = SentenceSplitter.split(text, maxSentenceLength)
            val ranges = sentenceRanges(text, sentences)
            val spans = blockSpans[paragraph]
            quotesByParagraph += spans.map { span ->
                text.substring(span.first, span.last + 1).trim('"', ' ')
            }
            for (range in ranges) {
                // A sentence may touch two quotes (mid-sentence attribution);
                // the first one owns it, exactly like the M1 behaviour.
                val quoteOrdinal = spans
                    .indexOfFirst { q -> range.first <= q.last && range.last >= q.first }
                    .takeIf { it >= 0 }
                val span = quoteOrdinal?.let { spans[it] }
                val speaker = if (span == null) NARRATOR else attribute(text, span) ?: DIALOGUE
                slots += SpeakerSlot(paragraph, quoteOrdinal, speaker)
            }
        }
        return SpeakerQuoteIndex(slots, quotesByParagraph)
    }

    /** Character ranges of the split sentences inside [text], via cursor search. */
    private fun sentenceRanges(text: String, sentences: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (sentence in sentences) {
            val found = text.indexOf(sentence, cursor)
            if (found < 0) continue
            ranges += found until (found + sentence.length)
            cursor = found + sentence.length
        }
        return ranges
    }

    /** Attribution for the sentence containing [span]; null when unknown. */
    private fun attribute(text: String, span: IntRange): String? {
        val before = text.substring(0, span.first).takeLast(ATTRIBUTION_WINDOW)
        frontAttribution.find(before)?.let { name ->
            return name.groupValues[1].takeIf { !pronoun.matches(it) }
        }
        midAttribution.find(before)?.let { name ->
            return name.groupValues[1].takeIf { !pronoun.matches(it) }
        }
        val after = text.substring(span.last + 1).take(ATTRIBUTION_WINDOW)
        backAttribution.find(after)?.let { name ->
            return name.groupValues[1].takeIf { !pronoun.matches(it) }
        }
        return null
    }

    /** Marker for dialogue whose speaker is unknown (M1: maps to the
     *  dialogue voice like any named speaker). */
    const val DIALOGUE = "dialogue"

    /** Narration speaker tag (the default for every untagged sentence). */
    const val NARRATOR = "narrator"
}
