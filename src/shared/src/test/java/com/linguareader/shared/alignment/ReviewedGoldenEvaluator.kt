package com.linguareader.shared.alignment

import org.json.JSONObject

/** Independently approved source/target relations; historical verdicts do not approve new output. */
internal object ReviewedGoldenEvaluator {
    data class Review(val source: ReviewedSpan, val target: ReviewedSpan?, val level: String, val verdict: String)
    data class Metrics(
        val samples: Int,
        val sentenceSourceTotal: Int, val sentenceCorrect: Int,
        val paragraphSourceTotal: Int, val paragraphCorrect: Int,
        val falseAligned: Int, val unscorableAligned: Int, val historicalBadAligned: Int,
        val uncertainWithText: Int
    ) {
        fun assertPassed() {
            check(falseAligned == 0 && unscorableAligned == 0 && historicalBadAligned == 0) {
                "Golden red line: currentFalseAligned=$falseAligned unscorableAligned=$unscorableAligned historicalBadAligned=$historicalBadAligned"
            }
        }
        fun line() = "samples=$samples sentenceRecall=$sentenceCorrect/$sentenceSourceTotal " +
            "paragraphRecall=$paragraphCorrect/$paragraphSourceTotal currentFalseAligned=$falseAligned " +
            "unscorableAligned=$unscorableAligned historicalBadAligned=$historicalBadAligned " +
            "uncertainWithText=$uncertainWithText"
    }
    private enum class Result { PRECISE, PARAGRAPH, WRONG, UNSCORABLE }

    fun reviews(json: JSONObject, source: BookSide, target: BookSide): List<Review> {
        val samples = json.getJSONArray("samples")
        check(samples.length() > 0) { "No independent golden samples" }
        val reviews = (0 until samples.length()).map { index ->
            val sample = samples.getJSONObject(index)
            val approved = sample.getJSONObject("approved")
            val level = approved.getString("level")
            check(level in setOf("MISS", "SENTENCE", "PARAGRAPH")) { "Unknown reviewed level" }
            Review(ReviewedSpanLocator.locate(source, sample.getString("en")), when (level) {
                "MISS" -> null
                "PARAGRAPH" -> ReviewedSpanLocator.locateParagraph(target, approved.getString("zh"))
                else -> ReviewedSpanLocator.locate(target, approved.getString("zh"))
            }, level, sample.getString("verdict"))
        }
        // Overlapping independently reviewed groups must not permit choosing whichever row
        // happens to approve current output while ignoring a contradictory smaller relation.
        reviews.forEachIndexed { i, first -> reviews.drop(i + 1).filter { it.source.overlaps(first.source) }.forEach { second ->
            check((first.level == "MISS") == (second.level == "MISS")) { "Conflicting MISS/positive reviewed source spans" }
            if (first.target != null && second.target != null) {
                check(first.target.overlaps(second.target)) { "Conflicting reviewed target spans for overlapping sources" }
                if (first.level == "SENTENCE" && second.level == "SENTENCE") {
                    if (first.source.contains(second.source)) check(first.target.contains(second.target)) { "Conflicting nested reviewed spans" }
                    if (second.source.contains(first.source)) check(second.target.contains(first.target)) { "Conflicting nested reviewed spans" }
                }
            }
        } }
        return reviews
    }

    fun evaluate(json: JSONObject, document: AlignmentDocument, english: List<AlignmentChapter>, chinese: List<AlignmentChapter>): Metrics {
        val projection = CorpusPositionProjection.source(document, english)
        val source = projection.side
        val target = BookSide.build(SideKind.TARGET, chinese)
        val reviews = reviews(json, source, target)
        val targetSpans = HashMap<Int, ReviewedSpan>()
        fun targetOf(index: Int): ReviewedSpan = targetSpans.getOrPut(index) {
            ReviewedSpanLocator.target(target, projection.units[index].unit)
        }
        val outcomes = HashMap<Int, Result>()
        projection.units.forEachIndexed { index, positioned ->
            if (positioned.unit.status != AlignmentStatus.ALIGNED) return@forEachIndexed
            val related = reviews.filter { it.source.overlaps(positioned.source) }
            if (related.isEmpty()) return@forEachIndexed // The golden samples are not a whole-book truth table.
            if (related.any { it.level == "MISS" }) {
                outcomes[index] = Result.WRONG
                return@forEachIndexed
            }
            val actualTarget = targetOf(index)
            val precise = related.filter { it.level == "SENTENCE" }
            // A reviewed N:1/N:M group may be projected into N separate source units. The full
            // group must really be present, ALIGNED, and share exactly the approved target span.
            val grouped = precise.any { review ->
                if (!review.source.contains(positioned.source) || review.target != actualTarget) return@any false
                val members = projection.units.withIndex().filter { it.value.source.overlaps(review.source) }
                members.all { member -> review.source.contains(member.value.source) &&
                    member.value.unit.status == AlignmentStatus.ALIGNED && targetOf(member.index) == review.target } &&
                    members.flatMap { it.value.source.indices }.toSet() == review.source.indices.toSet()
            }
            if (grouped) {
                outcomes[index] = Result.PRECISE
                return@forEachIndexed
            }
            // A merged source unit needs independent reviews for its ENTIRE actual span.
            // The approved target union must equal the complete actual output, without gaps,
            // additions or double-credit for repeated target sentences.
            val contained = precise.filter { positioned.source.contains(it.source) }
            val approvedSource = contained.flatMap { it.source.indices }.toSet()
            if (approvedSource == positioned.source.indices.toSet()) {
                val approvedTarget = contained.flatMap { requireNotNull(it.target).indices }.toSet()
                outcomes[index] = if (approvedTarget == actualTarget.indices.toSet()) Result.PRECISE else Result.WRONG
                return@forEachIndexed
            }
            // Paragraph-only approval is deliberately separate from sentence precision. All
            // source positions must be independently approved for the SAME unique target paragraph.
            val paragraphs = related.filter { it.level == "PARAGRAPH" }
            val paragraphGroups = paragraphs.groupBy { requireNotNull(it.target) }
            val fullParagraphGroups = paragraphGroups.filterValues { group ->
                positioned.source.indices.all { sentence -> group.any { sentence in it.source.indices } }
            }
            outcomes[index] = when {
                fullParagraphGroups.keys.any { it.contains(actualTarget) } -> Result.PARAGRAPH
                fullParagraphGroups.isNotEmpty() -> Result.WRONG
                else -> Result.UNSCORABLE
            }
        }

        fun sourcePositions(level: String) = reviews.filter { it.level == level }.flatMap { it.source.indices }
            .filter { source.sentLangUnit[it] }.toSet()
        fun covered(result: Result) = projection.units.withIndex().filter { outcomes[it.index] == result }
            .flatMap { it.value.source.indices }.filter { source.sentLangUnit[it] }.toSet()
        val sentenceTotal = sourcePositions("SENTENCE")
        val paragraphTotal = sourcePositions("PARAGRAPH")
        val preciseCovered = covered(Result.PRECISE)
        val paragraphCovered = covered(Result.PARAGRAPH) + preciseCovered
        val badAligned = reviews.count { review -> review.verdict == "bad" && projection.units.any { positioned ->
            positioned.unit.status == AlignmentStatus.ALIGNED && positioned.source.overlaps(review.source)
        } }
        return Metrics(reviews.size, sentenceTotal.size, (sentenceTotal intersect preciseCovered).size,
            paragraphTotal.size, (paragraphTotal intersect paragraphCovered).size,
            covered(Result.WRONG).size, covered(Result.UNSCORABLE).size, badAligned,
            document.units.count { it.status == AlignmentStatus.UNCERTAIN && it.targetText.isNotBlank() })
    }
}
