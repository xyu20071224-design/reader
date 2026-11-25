package com.linguareader.shared.alignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Handwritten expected outcomes test the evaluator; no engine output is used as golden truth. */
class CorpusExecutionTest {
    private val pairs = listOf("An apple rests here." to "这里有一个苹果。", "A river flows there." to "那里有一条河流。")
    private val source = listOf(AlignmentChapter("Original evaluator fixture", pairs.map { it.first }))

    @Test fun mergedSourceCountsTwoSentencesAndMatchesBothTruthTargets() {
        val document = document(unit(pairs.joinToString(" ") { it.first }, pairs.joinToString(" ") { it.second }, span = 2))
        val metrics = CorpusExecution.aiMetrics(document, source, pairs)
        assertEquals(2, metrics.alignedSentences)
        assertEquals(2, metrics.correctSentences)
        assertEquals(1.0, metrics.coverage)
        assertEquals(1.0, metrics.precision)
        metrics.assertApprovedAiTargets()
    }

    @Test fun wrongMergedTargetCountsBothFalseSourceSentencesAndFailsTheRedLine() {
        val document = document(unit(pairs.joinToString(" ") { it.first }, "完全不同的译文。", span = 2))
        val metrics = CorpusExecution.aiMetrics(document, source, pairs)
        assertEquals(2, metrics.falseAligned)
        assertEquals(0.0, metrics.precision)
        assertFailsWith<IllegalStateException> { metrics.assertApprovedAiTargets() }
    }

    @Test fun missingTruthCannotBeSilentlyCountedAsCorrectOrJudgedByOrdinal() {
        val document = document(unit(pairs[0].first, pairs[0].second, span = 1))
        val metrics = CorpusExecution.aiMetrics(document, source, listOf(pairs[1]))
        assertEquals(1, metrics.unscorableAligned)
        assertEquals(0, metrics.falseAligned) // Unscorable is distinct from a proven wrong translation.
        assertFailsWith<IllegalStateException> { metrics.assertApprovedAiTargets() }
        assertNull(CorpusExecution.expectedTarget("An apple", pairs))
        assertEquals(pairs[1].second, CorpusExecution.expectedTarget(pairs[1].first, pairs))
    }

    @Test fun approvedCoverageBoundaryIsEnforcedWithoutARealBookThreshold() {
        CorpusExecution.AiMetrics(10, 9, 9, 0, 0).assertApprovedAiTargets()
        assertFailsWith<IllegalStateException> { CorpusExecution.AiMetrics(10, 8, 8, 0, 0).assertApprovedAiTargets() }
        assertFailsWith<IllegalStateException> { CorpusExecution.AiMetrics(10, 0, 0, 0, 0).assertApprovedAiTargets() }
    }

    @Test fun emptyDocumentAndStaleStatisticsFailInsteadOfPrintingSuccess() {
        assertFailsWith<IllegalStateException> { CorpusExecution.requireDocument(AlignmentDocument(1, "fixture")) }
        val document = document(unit(pairs[0].first, pairs[0].second))
        assertFailsWith<IllegalStateException> { CorpusExecution.requireDocument(document.copy(stats = document.stats.copy(alignedUnits = 0))) }
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(document.copy(stats = document.stats.copy(sourceSentenceTotal = 1)), source, pairs) }
    }

    @Test fun ambiguousTruthFailsRatherThanChoosingFirstMatch() {
        assertFailsWith<IllegalStateException> { CorpusExecution.expectedTarget(pairs[0].first, listOf(pairs[0], pairs[0])) }
    }

    @Test fun firstSentenceCannotClaimTwoSentenceCoverageByForgingSpanAndStatistics() {
        val forged = document(unit(pairs[0].first, pairs[0].second, span = 2))
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(forged, source, pairs).assertApprovedAiTargets() }
    }

    @Test fun duplicateFirstSentenceCannotStandInForTheMissingSecondSentence() {
        val forged = document(unit(pairs[0].first, pairs[0].second), unit(pairs[0].first, pairs[0].second))
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(forged, source, pairs).assertApprovedAiTargets() }
    }

    @Test fun completeTextMustAgreeWithItsRealParagraphAndSentenceIndex() {
        val misplaced = unit(pairs[0].first, pairs[0].second).copy(sourceIndexInParagraph = 1)
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(document(misplaced), source, pairs) }
        val fragment = unit("An apple", pairs[0].second)
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(document(fragment), source, pairs) }
        val changedWordBoundary = unit("Ana pple rests here.", pairs[0].second)
        assertFailsWith<IllegalStateException> { CorpusExecution.aiMetrics(document(changedWordBoundary), source, pairs) }
    }

    private fun unit(english: String, chinese: String, span: Int = 1) = AlignedUnit(
        AlignmentStatus.ALIGNED, sourceChapter = 0, sourceParagraph = pairs[0].first,
        sourceText = english, targetText = chinese, sourceSpanSize = span
    )
    private fun document(vararg units: AlignedUnit): AlignmentDocument {
        // A one-sentence ALIGNED output still explicitly accounts for the other real sentence.
        val all = units.toList() + if (units.sumOf { it.sourceSpanSize } == 1) listOf(AlignedUnit(
            AlignmentStatus.UNALIGNED, 0, sourceParagraph = pairs[1].first, sourceText = pairs[1].first,
            skipReason = AlignmentSkipReason.VERIFY_FAILED)) else emptyList()
        return AlignmentDocument(1, "handwritten-evaluator-fixture", units = all,
            stats = AlignmentStats(sourceSentenceTotal = 2, targetSentenceTotal = 2,
                sourceSentencesAligned = units.sumOf { it.sourceSpanSize }, alignedUnits = units.size,
                unalignedUnits = all.size - units.size))
    }
}
