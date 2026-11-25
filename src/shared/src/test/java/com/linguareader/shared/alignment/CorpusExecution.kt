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
        check(document.stats.alignedUnits + document.stats.uncertainUnits + document.stats.unalignedUnits == document.units.size) {
            "Status statistics do not account for all units"
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
        val needle = AlignmentCorpusInputs.matchText(sourceText)
        if (needle.isEmpty()) return null
        val matches = ArrayList<String>()
        for (from in pairs.indices) {
            val english = StringBuilder()
            val chinese = StringBuilder()
            for (to in from until pairs.size) {
                english.append(AlignmentCorpusInputs.matchText(pairs[to].first))
                chinese.append(pairs[to].second)
                if (english.toString() == needle) matches.add(chinese.toString())
                if (english.length >= needle.length || !needle.startsWith(english.toString())) break
            }
        }
        check(matches.size <= 1) { "Ambiguous truth for complete source text" }
        return matches.singleOrNull()
    }

    fun aiMetrics(document: AlignmentDocument, source: List<AlignmentChapter>, pairs: List<Pair<String, String>>): AiMetrics {
        requireDocument(document)
        check(pairs.isNotEmpty()) { "No AI truth pairs evaluated" }
        val side = BookSide.build(SideKind.SOURCE, source)
        val denominator = side.sentLangUnit.count { it }
        check(denominator > 0 && document.stats.sourceSentenceTotal == denominator) { "AI sentence denominator mismatch" }
        var aligned = 0
        var correct = 0
        var wrong = 0
        var unscorable = 0
        document.units.filter { it.status == AlignmentStatus.ALIGNED }.forEach { unit ->
            aligned += unit.sourceSpanSize
            val truth = expectedTarget(unit.sourceText, pairs)
            when {
                truth == null -> unscorable += unit.sourceSpanSize
                AlignmentCorpusInputs.matchText(truth) == AlignmentCorpusInputs.matchText(unit.targetText) -> correct += unit.sourceSpanSize
                else -> wrong += unit.sourceSpanSize
            }
        }
        check(aligned <= denominator && aligned == document.stats.sourceSentencesAligned) { "AI aligned sentence accounting mismatch" }
        return AiMetrics(denominator, aligned, correct, wrong, unscorable)
    }
}
