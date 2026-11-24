package com.linguareader.shared.tts

/** Regeneration permission is explicit, session-scoped, and must not be inferred from PHRASES. */
data class ProsodyConfiguration(
    val policy: ProsodyPlanner.Policy = ProsodyPlanner.Policy(),
    val allowAudioPackRegeneration: Boolean = false
)

enum class ProsodyFallbackReason { AUDIO_PACK_ORIGINAL, UNSUPPORTED_BACKEND }

data class PlannedSpeechRequest(
    val source: TtsUtterance,
    val units: List<ProsodyPlanner.Unit>,
    val configuration: ProsodyConfiguration
)

/** Optional runtime capability. Persistent sentence/segment IDs and pipeline version stay intact. */
interface PlannedTtsSynthesizer {
    fun configureProsody(configuration: ProsodyConfiguration)
    fun speakPlanned(request: PlannedSpeechRequest, utteranceId: String, voice: String? = null)
}

/** Precompute quote context once per chapter, including cross-block dialogue continuations. */
class ProsodyPlanningContext(blocks: List<String>) {
    private val spans = QuoteSpans.spans(blocks)

    fun protectedRanges(source: TtsUtterance): List<IntRange> {
        if (source.blockIndex !in spans.indices || source.offset < 0) return source.text.indices.let(::listOf)
        val start = source.offset
        val end = start + source.length
        return spans[source.blockIndex].mapNotNull { range ->
            val from = maxOf(start, range.first)
            val until = minOf(end, range.last + 1)
            if (from >= until) null else (from - start)..(until - start - 1)
        }
    }
}

/** Waveform identity excludes local speed/pause, but binds text, parent coordinates and synth config. */
object ProsodyCacheKey {
    const val VERSION = 1

    fun relativePath(
        chapterIndex: Int,
        source: TtsUtterance,
        units: List<ProsodyPlanner.Unit>,
        partIndex: Int,
        engineTag: String,
        voice: String,
        synthesisConfiguration: String = ""
    ): String {
        require(chapterIndex >= 0 && partIndex in units.indices)
        val identity = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(VERSION.toString())
            field(TtsPipelineContract.VERSION.toString())
            field(source.sentenceIndex.toString())
            field(source.segmentIndex.toString())
            field(engineTag)
            field(voice)
            field(synthesisConfiguration)
            field(source.text)
            field(units.size.toString())
            units.forEach { field(it.utterance.text) }
        }
        val directory = "prosody-v$VERSION-" + TtsCacheKey.sha256Hex(identity)
        return "$chapterIndex/$directory/s${source.sentenceIndex}-${source.segmentIndex}-p$partIndex.mp3"
    }
}
