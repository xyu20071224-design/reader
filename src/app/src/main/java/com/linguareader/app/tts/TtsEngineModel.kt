package com.linguareader.app.tts

/**
 * Engine capability declaration (mirrors Readest's `TTSCapabilities`).
 *
 * The playback controller gates feature paths on these flags — never on the
 * concrete synthesizer type — so UI and queue logic degrade gracefully when a
 * backend cannot provide a capability (e.g. no word timestamps -> sentence
 * level highlighting only).
 */
data class TtsCapabilities(
    /** Can emit word-level timestamps -> word-level highlighting. */
    val wordBoundaries: Boolean,
    /** Can pre-generate a whole chapter (replaces `as? ChapterTtsPreparer`). */
    val chapterPreparer: Boolean,
    /** Inter-sentence gap is adjustable. */
    val gapControl: Boolean,
    /** Rate change does not re-synthesize / re-bill. */
    val liveRateChange: Boolean
)

/**
 * Full location of one sentence (Readest's `TTSMark`); filled from
 * [TtsChapter.sentenceLocation]. Lets a spoken event point at the exact DOM
 * block/offset/length without the controller re-searching the text.
 */
data class TtsMark(
    val sentenceIndex: Int,
    val text: String,
    val blockIndex: Int,
    val offset: Int,      // character offset inside the block
    val length: Int
)

/**
 * Unified event stream (Readest's `TTSMessageEvent`). Synthesizer callbacks are
 * folded into exactly these four events so new event kinds (notably word
 * boundaries) can be added later without touching the playback control flow.
 */
sealed interface TtsEvent {
    /** The sentence starts producing audio -> switch current-sentence highlight. */
    data class Boundary(val mark: TtsMark) : TtsEvent

    /** A word range was spoken -> word-level highlight within the sentence. */
    data class WordBoundary(val mark: TtsMark, val startChar: Int, val endChar: Int) : TtsEvent

    /** The sentence finished -> advance the queue. */
    data class End(val mark: TtsMark) : TtsEvent

    data class Error(val mark: TtsMark, val message: String?) : TtsEvent
}
