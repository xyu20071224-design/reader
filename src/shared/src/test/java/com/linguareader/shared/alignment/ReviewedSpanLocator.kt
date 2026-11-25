package com.linguareader.shared.alignment

internal data class ReviewedSpan(val from: Int, val size: Int) {
    init { check(from >= 0 && size > 0) { "Invalid reviewed span" } }
    val indices get() = from until from + size
    fun contains(other: ReviewedSpan): Boolean = other.from >= from && other.from + other.size <= from + size
    fun overlaps(other: ReviewedSpan): Boolean = from < other.from + other.size && other.from < from + size
}

/** Full token sequences preserve Latin word boundaries and punctuation; only inter-token whitespace is immaterial. */
internal object ReviewedSpanLocator {
    private val tokens = Regex("[A-Za-z0-9]+|[^\\s]")
    fun key(text: String): List<String> = tokens.findAll(text).map { it.value }.toList()
    fun sameText(first: String, second: String) = key(first) == key(second)
    fun text(side: BookSide, span: ReviewedSpan) = span.indices.joinToString(" ") { side.sentText[it] }

    fun locate(side: BookSide, text: String): ReviewedSpan {
        val needle = key(text)
        check(needle.isNotEmpty()) { "Empty independent reviewed text" }
        val sentenceKeys = side.sentText.map(::key)
        val candidates = ArrayList<ReviewedSpan>()
        for (from in 0 until side.sentCount) {
            val chapter = side.paraChapter[side.sentParagraph[from]]
            val combined = ArrayList<String>()
            for (to in from until side.sentCount) {
                if (side.paraChapter[side.sentParagraph[to]] != chapter) break
                combined.addAll(sentenceKeys[to])
                if (combined.size > needle.size || combined != needle.take(combined.size)) break
                if (combined == needle) { candidates.add(ReviewedSpan(from, to - from + 1)); break }
            }
        }
        check(candidates.size == 1) {
            "Independent complete reviewed text must locate uniquely: matches=${candidates.size}; no prefix/fragment fallback"
        }
        return candidates.single()
    }

    fun locateParagraph(side: BookSide, text: String): ReviewedSpan {
        val candidates = (0 until side.paraCount).filter { paragraph ->
            side.paraSentCount[paragraph] > 0 && sameText(side.paraText[paragraph], text)
        }
        check(candidates.size == 1) { "Independent complete reviewed paragraph must locate uniquely: matches=${candidates.size}" }
        val paragraph = candidates.single()
        return ReviewedSpan(side.paraFirstSent[paragraph], side.paraSentCount[paragraph])
    }

    /** Validate output coordinates as well as complete text; an ambiguous repeated target is not guessed. */
    fun target(side: BookSide, unit: AlignedUnit): ReviewedSpan {
        check(unit.targetSpanSize > 0 && unit.targetText.isNotBlank()) { "Aligned output has no complete target span" }
        val candidates = ArrayList<ReviewedSpan>()
        for (paragraph in 0 until side.paraCount) {
            if (side.paraChapter[paragraph] != unit.targetChapter ||
                !sameText(side.paraText[paragraph], unit.targetParagraph) ||
                unit.targetIndexInParagraph !in 0 until side.paraSentCount[paragraph]) continue
            val from = side.paraFirstSent[paragraph] + unit.targetIndexInParagraph
            if (unit.targetSpanSize > side.sentCount - from) continue
            val span = ReviewedSpan(from, unit.targetSpanSize)
            if (span.indices.any { side.paraChapter[side.sentParagraph[it]] != unit.targetChapter }) continue
            if (sameText(text(side, span), unit.targetText)) candidates.add(span)
        }
        check(candidates.distinct().size == 1) { "Output target coordinates/text are absent or ambiguous: matches=${candidates.distinct().size}" }
        return candidates.single()
    }
}

internal data class SourceUnitPosition(val unit: AlignedUnit, val source: ReviewedSpan)
internal data class SourceProjection(val side: BookSide, val units: List<SourceUnitPosition>)

internal object CorpusPositionProjection {
    /** Stable output order consumes each real input sentence once, including non-language units. */
    fun source(document: AlignmentDocument, chapters: List<AlignmentChapter>): SourceProjection {
        CorpusExecution.requireDocument(document)
        val side = BookSide.build(SideKind.SOURCE, chapters)
        var cursor = 0
        val positions = document.units.mapIndexed { index, unit ->
            check(unit.sourceSpanSize <= side.sentCount - cursor) { "Output source span exceeds actual input at unit=$index" }
            val span = ReviewedSpan(cursor, unit.sourceSpanSize)
            val paragraph = side.sentParagraph[cursor]
            val chapter = side.paraChapter[paragraph]
            check(unit.sourceChapter == chapter && unit.sourceIndexInParagraph == side.sentIdxInPara[cursor] &&
                ReviewedSpanLocator.sameText(unit.sourceParagraph, side.paraText[paragraph])) {
                "Output source coordinates are out of order/duplicated at unit=$index position=$cursor"
            }
            check(span.indices.all { side.paraChapter[side.sentParagraph[it]] == chapter } &&
                ReviewedSpanLocator.sameText(unit.sourceText, ReviewedSpanLocator.text(side, span))) {
                "Output source complete text/span mismatch at unit=$index position=$cursor span=${span.size}"
            }
            cursor += span.size
            SourceUnitPosition(unit, span)
        }
        check(cursor == side.sentCount) { "Output omitted actual input sentences: consumed=$cursor actual=${side.sentCount}" }
        val total = side.sentLangUnit.count { it }
        check(document.stats.sourceSentenceTotal == total) { "Source sentence denominator mismatch" }
        val actualAligned = positions.filter { it.unit.status == AlignmentStatus.ALIGNED }
            .flatMap { it.source.indices }.count { side.sentLangUnit[it] }
        check(document.stats.sourceSentencesAligned == actualAligned) { "Actual aligned source sentence count disagrees with stats" }
        return SourceProjection(side, positions)
    }
}
