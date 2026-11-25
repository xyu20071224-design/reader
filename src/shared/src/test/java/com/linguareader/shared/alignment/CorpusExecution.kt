package com.linguareader.shared.alignment

/** Evaluation validity, separate from any unapproved real-book coverage target. */
internal object CorpusExecution {
    fun requireSides(source: BookSide, target: BookSide) {
        check(source.sentCount > 0 && source.sentLangUnit.any { it }) { "No English language sentence actually processed" }
        check(target.sentCount > 0 && target.sentLangUnit.any { it }) { "No Chinese language sentence actually processed" }
        println("CORPUS_EXECUTED sourceSentences=${source.sentCount} targetSentences=${target.sentCount}")
    }

    fun requireDocument(document: AlignmentDocument) {
        check(document.units.isNotEmpty() && document.stats.sourceSentenceTotal > 0) { "Alignment produced no evaluable source sentence" }
        check(document.units.all { it.sourceSpanSize > 0 }) { "Invalid source span" }
        check(document.stats.alignedUnits == document.units.count { it.status == AlignmentStatus.ALIGNED } &&
            document.stats.uncertainUnits == document.units.count { it.status == AlignmentStatus.UNCERTAIN } &&
            document.stats.unalignedUnits == document.units.count { it.status == AlignmentStatus.UNALIGNED }) {
            "Status statistics do not account for actual unit statuses"
        }
        println("CORPUS_EXECUTED units=${document.units.size} sourceDenominator=${document.stats.sourceSentenceTotal}")
    }

    data class AiMetrics(
        val sourceSentences: Int, val alignedSentences: Int, val correctSentences: Int,
        val falseAligned: Int, val unscorableAligned: Int
    ) {
        val coverage get() = alignedSentences.toDouble() / sourceSentences
        val precision get() = if (alignedSentences == 0) 1.0 else correctSentences.toDouble() / alignedSentences
        fun line() = "coverage=$coverage($alignedSentences/$sourceSentences) " +
            "precision=$precision($correctSentences/$alignedSentences) falseAligned=$falseAligned " +
            "unscorableAligned=$unscorableAligned"

        /** Only the AI engine in F02's independently reviewed AI corpus has these approved targets. */
        fun assertApprovedAiTargets() {
            check(unscorableAligned == 0) { "AI truth cannot score $unscorableAligned ALIGNED source sentences; ${line()}" }
            check(falseAligned == 0) { "AI falseAligned must be 0; ${line()}" }
            check(coverage >= 0.90) { "AI paragraph-sentence coverage must be >=0.90; ${line()}" }
            check(precision >= 0.98) { "AI sentence precision must be >=0.98; ${line()}" }
        }
    }

    /** Exact complete sources, including contiguous N:1 truth pairs; never compare by unit ordinal. */
    fun expectedTarget(sourceText: String, pairs: List<Pair<String, String>>): String? {
        val needle = ReviewedSpanLocator.key(sourceText)
        if (needle.isEmpty()) return null
        val matches = ArrayList<String>()
        for (from in pairs.indices) {
            val english = ArrayList<String>()
            val chinese = ArrayList<String>()
            for (to in from until pairs.size) {
                english.addAll(ReviewedSpanLocator.key(pairs[to].first))
                chinese.add(pairs[to].second)
                if (english == needle) matches.add(chinese.joinToString(" "))
                if (english.size >= needle.size || english != needle.take(english.size)) break
            }
        }
        check(matches.size <= 1) { "Ambiguous truth for complete source text" }
        return matches.singleOrNull()
    }

    fun aiMetrics(document: AlignmentDocument, source: List<AlignmentChapter>, pairs: List<Pair<String, String>>): AiMetrics {
        check(pairs.isNotEmpty()) { "No AI truth pairs evaluated" }
        val projection = CorpusPositionProjection.source(document, source)
        val denominator = projection.side.sentLangUnit.count { it }
        check(denominator > 0) { "AI input has no language sentences" }
        val aligned = LinkedHashSet<Int>()
        val correct = LinkedHashSet<Int>()
        val wrong = LinkedHashSet<Int>()
        val unscorable = LinkedHashSet<Int>()
        projection.units.filter { it.unit.status == AlignmentStatus.ALIGNED }.forEach { positioned ->
            val unit = positioned.unit
            val actual = positioned.source.indices.filter { projection.side.sentLangUnit[it] }
            check(actual.none(aligned::contains)) { "Duplicate aligned source coverage" }
            aligned.addAll(actual)
            val truth = expectedTarget(unit.sourceText, pairs)
            when {
                truth == null -> unscorable.addAll(actual)
                ReviewedSpanLocator.sameText(truth, unit.targetText) -> correct.addAll(actual)
                else -> wrong.addAll(actual)
            }
        }
        check(aligned.size == document.stats.sourceSentencesAligned) { "AI aligned sentence accounting mismatch" }
        return AiMetrics(denominator, aligned.size, correct.size, wrong.size, unscorable.size)
    }
}
