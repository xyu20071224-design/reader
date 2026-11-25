package com.linguareader.shared.alignment

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Original independent texts and expectations; no algorithm-generated truth. */
class ReviewedGoldenRegressionTest {
    private val english = "An apple rests here."
    private val correct = "这里有一个苹果。"
    private val wrong = "这里有一条河流。"

    @Test fun historicalGoodVerdictDoesNotApproveCurrentWrongTarget() {
        val unit = AlignedUnit(AlignmentStatus.ALIGNED, 0, 0, english, wrong,
            english, wrong, targetIndexInParagraph = 0)
        val document = AlignmentDocument(1, "handwritten", units = listOf(unit),
            stats = AlignmentStats(sourceSentenceTotal = 1, targetSentenceTotal = 2, sourceSentencesAligned = 1, alignedUnits = 1))
        val golden = JSONObject().put("samples", JSONArray().put(JSONObject().put("en", english).put("verdict", "good")
            .put("approved", JSONObject().put("level", "SENTENCE").put("zh", correct))))
        assertFailsWith<IllegalStateException> {
            RealCorpusAcceptanceTest().replayGolden(golden, document,
                listOf(AlignmentChapter("source", listOf(english))), listOf(AlignmentChapter("target", listOf(correct, wrong))))
        }
    }

    @Test fun sharedLongPrefixDoesNotLocateTheWrongCompleteSentence() {
        val prefix = "这是两处句子共有的很长开头文字它们的前面三十个汉字完全相同但是"
        val first = prefix + "第一处的结尾。"
        val second = prefix + "第二处的结尾。"
        val target = BookSide.build(SideKind.TARGET, listOf(AlignmentChapter("original", listOf(first, second))))
        assertEquals(ReviewedSpan(1, 1), ReviewedSpanLocator.locate(target, second))
    }

    @Test fun completeMultiSentenceTruthLocatesTheEntireSpan() {
        val target = BookSide.build(SideKind.TARGET, listOf(AlignmentChapter("original", listOf(correct + wrong))))
        assertEquals(ReviewedSpan(0, 2), ReviewedSpanLocator.locate(target, correct + wrong))
    }

    @Test fun repeatedCompleteTextIsExplicitlyAmbiguous() {
        val target = BookSide.build(SideKind.TARGET, listOf(AlignmentChapter("original", listOf(correct, correct))))
        assertFailsWith<IllegalStateException> { ReviewedSpanLocator.locate(target, correct) }
    }

    @Test fun mergedUnitNeedsIndependentTruthForEveryRealSourceSentence() {
        val f = fixture()
        val document = f.document(f.unit(0, 2, 0, 2))
        val partial = f.golden(f.review(0, "SENTENCE", correct))
        val metrics = ReviewedGoldenEvaluator.evaluate(partial, document, f.en, f.zh)
        assertEquals(2, metrics.unscorableAligned)
        assertEquals(0, metrics.sentenceCorrect)
        assertFailsWith<IllegalStateException> { metrics.assertPassed() }
    }

    @Test fun completeMergedTruthUsesUniqueSourceAndTargetPositionUnions() {
        val f = fixture()
        val truth = f.golden(f.review(0, "SENTENCE", correct), f.review(1, "SENTENCE", wrong))
        val metrics = ReviewedGoldenEvaluator.evaluate(truth, f.document(f.unit(0, 2, 0, 2)), f.en, f.zh)
        assertEquals(2, metrics.sentenceSourceTotal)
        assertEquals(2, metrics.sentenceCorrect)
        metrics.assertPassed()
        val extra = ReviewedGoldenEvaluator.evaluate(truth, f.document(f.unit(0, 2, 0, 3)), f.en, f.zh)
        assertEquals(2, extra.falseAligned)
        assertFailsWith<IllegalStateException> { extra.assertPassed() }
    }

    @Test fun projectedNToOneGroupRequiresTheEntireApprovedGroupAndSharedTarget() {
        val f = fixture()
        val groupedReview = JSONObject().put("en", f.english.joinToString(" ")).put("verdict", "good")
            .put("approved", JSONObject().put("level", "SENTENCE").put("zh", correct + wrong))
        val truth = f.golden(groupedReview)
        val valid = f.document(f.unit(0, 1, 0, 2), f.unit(1, 1, 0, 2))
        ReviewedGoldenEvaluator.evaluate(truth, valid, f.en, f.zh).also {
            assertEquals(2, it.sentenceCorrect); it.assertPassed()
        }
        val incomplete = f.document(f.unit(0, 1, 0, 2), f.unit(1, 1, status = AlignmentStatus.UNALIGNED))
        assertFailsWith<IllegalStateException> {
            ReviewedGoldenEvaluator.evaluate(truth, incomplete, f.en, f.zh).assertPassed()
        }
    }

    @Test fun paragraphApprovalOnlyCreditsItsActualParagraphAndNeverSentencePrecision() {
        val f = fixture()
        val truth = f.golden(f.review(0, "PARAGRAPH", correct + wrong))
        val valid = f.document(f.unit(0, 1, 1, 1), f.unit(1, 1, status = AlignmentStatus.UNALIGNED))
        val metrics = ReviewedGoldenEvaluator.evaluate(truth, valid, f.en, f.zh)
        assertEquals(1, metrics.paragraphCorrect)
        assertEquals(0, metrics.sentenceCorrect)
        assertEquals(0, metrics.sentenceSourceTotal)
        metrics.assertPassed()
        val outside = f.document(f.unit(0, 1, 2, 1), f.unit(1, 1, status = AlignmentStatus.UNALIGNED))
        assertFailsWith<IllegalStateException> {
            ReviewedGoldenEvaluator.evaluate(truth, outside, f.en, f.zh).assertPassed()
        }
        val fragment = f.golden(f.review(0, "PARAGRAPH", correct))
        assertFailsWith<IllegalStateException> { ReviewedGoldenEvaluator.evaluate(fragment, valid, f.en, f.zh) }
    }

    @Test fun missAndHistoricalBadRedLinesRemainIndependentOfCurrentTargetCorrectness() {
        val f = fixture()
        val document = f.document(f.unit(0, 1, 0, 1), f.unit(1, 1, status = AlignmentStatus.UNALIGNED))
        val miss = f.golden(f.review(0, "MISS", ""))
        assertFailsWith<IllegalStateException> { ReviewedGoldenEvaluator.evaluate(miss, document, f.en, f.zh).assertPassed() }
        val bad = f.golden(f.review(0, "SENTENCE", correct).put("verdict", "bad"))
        val metrics = ReviewedGoldenEvaluator.evaluate(bad, document, f.en, f.zh)
        assertEquals(0, metrics.falseAligned)
        assertEquals(1, metrics.historicalBadAligned)
        assertFailsWith<IllegalStateException> { metrics.assertPassed() }
    }

    @Test fun oraclePassesCompleteActualSpansToTheScorer() {
        val review = ReviewedGoldenEvaluator.Review(ReviewedSpan(2, 3), ReviewedSpan(5, 4), "SENTENCE", "good")
        val actual = OraclePairRecallProbe().scoreReviewed(review) { sourceFrom, sourceSpan, targetFrom, targetSpan ->
            listOf(sourceFrom, sourceSpan, targetFrom, targetSpan)
        }
        assertEquals(listOf(2, 3, 5, 4), actual)
    }

    @Test fun sourceOutputOrderCanDisambiguateRepeatedParagraphsButIndependentTruthCannotGuess() {
        val en = listOf(AlignmentChapter("repeated", listOf(english, english)))
        val units = listOf(AlignedUnit(AlignmentStatus.UNALIGNED, 0, sourceParagraph = english, sourceText = english),
            AlignedUnit(AlignmentStatus.UNALIGNED, 0, sourceParagraph = english, sourceText = english))
        val document = AlignmentDocument(1, "handwritten", units = units,
            stats = AlignmentStats(sourceSentenceTotal = 2, unalignedUnits = 2))
        assertEquals(listOf(ReviewedSpan(0, 1), ReviewedSpan(1, 1)), CorpusPositionProjection.source(document, en).units.map { it.source })
        assertFailsWith<IllegalStateException> { ReviewedSpanLocator.locate(BookSide.build(SideKind.SOURCE, en), english) }
    }

    @Test fun sourceFragmentsAndChangedWordBoundariesNeverBecomeCompleteTruth() {
        val source = BookSide.build(SideKind.SOURCE, listOf(AlignmentChapter("original", listOf(english))))
        assertFailsWith<IllegalStateException> { ReviewedSpanLocator.locate(source, "An apple") }
        assertFailsWith<IllegalStateException> { ReviewedSpanLocator.locate(source, "Ana pple rests here.") }
    }

    @Test fun conflictingNestedTruthCannotBeIgnoredInFavorOfOneApprovingRow() {
        val f = fixture()
        val group = JSONObject().put("en", f.english.joinToString(" ")).put("verdict", "good")
            .put("approved", JSONObject().put("level", "SENTENCE").put("zh", correct + wrong))
        val contradictory = f.review(0, "SENTENCE", "这是第三段无关的译文。")
        assertFailsWith<IllegalStateException> {
            ReviewedGoldenEvaluator.evaluate(f.golden(group, contradictory), f.document(f.unit(0, 2, 0, 2)), f.en, f.zh)
        }
    }

    private fun fixture() = GoldenFixture(listOf(english, "A river flows there."), listOf(correct + wrong, "这是第三段无关的译文。"))
    private class GoldenFixture(val english: List<String>, chinese: List<String>) {
        val en = listOf(AlignmentChapter("source", english))
        val zh = listOf(AlignmentChapter("target", chinese))
        private val source = BookSide.build(SideKind.SOURCE, en)
        private val target = BookSide.build(SideKind.TARGET, zh)
        fun review(index: Int, level: String, approved: String) = JSONObject().put("en", english[index]).put("verdict", "good")
            .put("approved", JSONObject().put("level", level).also { if (level != "MISS") it.put("zh", approved) })
        fun golden(vararg rows: JSONObject) = JSONObject().put("samples", JSONArray(rows.toList()))
        fun unit(from: Int, span: Int, targetFrom: Int = -1, targetSize: Int = 0,
            status: AlignmentStatus = AlignmentStatus.ALIGNED): AlignedUnit {
            val p = source.sentParagraph[from]
            val t = if (targetFrom >= 0) target.sentParagraph[targetFrom] else -1
            return AlignedUnit(status, source.paraChapter[p], if (t >= 0) target.paraChapter[t] else -1,
                source.paraText[p], if (t >= 0) target.paraText[t] else "",
                ReviewedSpanLocator.text(source, ReviewedSpan(from, span)),
                if (targetFrom >= 0) ReviewedSpanLocator.text(target, ReviewedSpan(targetFrom, targetSize)) else "",
                source.sentIdxInPara[from], if (t >= 0) target.sentIdxInPara[targetFrom] else -1,
                span, targetSize)
        }
        fun document(vararg units: AlignedUnit) = AlignmentDocument(1, "independent-handwritten", units = units.toList(),
            stats = AlignmentStats(sourceSentenceTotal = source.sentLangUnit.count { it }, targetSentenceTotal = target.sentLangUnit.count { it },
                sourceSentencesAligned = units.filter { it.status == AlignmentStatus.ALIGNED }.sumOf { it.sourceSpanSize },
                alignedUnits = units.count { it.status == AlignmentStatus.ALIGNED },
                uncertainUnits = units.count { it.status == AlignmentStatus.UNCERTAIN },
                unalignedUnits = units.count { it.status == AlignmentStatus.UNALIGNED }))
    }
}
