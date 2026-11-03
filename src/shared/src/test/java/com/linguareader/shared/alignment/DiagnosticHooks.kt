package com.linguareader.shared.alignment

/**
 * **诊断钩子**（仅测试用，生产代码路径不经过它）。
 *
 * 存在的理由：`decide()` 的输入 [UnitFeatures] 与 [VerifyLayer] 的中间量都是
 * `internal`，被 [IndependentAligner] 封装在 `align()` 内部。要做「阻断原因直方图」
 * 这类诊断，需要在不改动生产逻辑、不给生产代码开测试后门的前提下拿到这些量。
 *
 * **约束**：本文件只读、只打印，不参与任何判定，不被任何生产代码引用。
 */
internal object DiagnosticHooks {

    /** 把 V1 通道与「词典可覆盖词比例」这两件事一次算清（Lead 的三个核对点）。 */
    fun v1Diagnostics(book: SyntheticBilingualFixture.FixtureBook): List<String> {
        val lexicon = OracleLexicon(book)
        val source = BookSide.build(SideKind.SOURCE, book.source)
        val target = BookSide.build(SideKind.TARGET, book.translation)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val anchors = AnchorLayer(source, target, density)
        anchors.build()
        val verify = VerifyLayer(source, target, density, lexicon)
        verify.build()

        val lines = ArrayList<String>(16)
        lines += "hardPins=${anchors.pins.size} (合成语料无数字/专名锚点 → 0 是预期)"
        lines += "verify.notes=${verify.notes}"

        var available = 0
        var recallPass = 0
        var twoSidedPass = 0
        val coverableRatios = ArrayList<Double>()
        val recalls = ArrayList<Double>()
        val allWordTotals = ArrayList<Int>()
        val coverableTotals = ArrayList<Int>()

        val n = minOf(source.sentCount, target.sentCount)
        for (i in 0 until n) {
            val score = verify.score(i, 1, i, 1)
            if (score.available) {
                available++
                recalls.add(score.recall)
                if (score.recall >= AlignTuning.VERIFY_RECALL_MIN) recallPass++
                if (score.recall >= AlignTuning.VERIFY_RECALL_MIN &&
                    (score.precision >= AlignTuning.VERIFY_PRECISION_MIN ||
                        score.lift >= AlignTuning.VERIFY_LIFT_MIN)
                ) twoSidedPass++
            }
            // 分母诊断：本句英文词里有多少是「词典查得到的」
            val words = contentWords(source.sentText[i])
            var coverable = 0
            for (word in words) if (lexicon.chineseCandidates(word).isNotEmpty()) coverable++
            allWordTotals.add(words.size)
            coverableTotals.add(coverable)
            if (words.isNotEmpty()) coverableRatios.add(coverable.toDouble() / words.size)
        }

        lines += "正确配对 n=$n：V1 available=$available，recall 过=$recallPass，" +
            "recall+precision|lift 双分支过=$twoSidedPass"
        if (recalls.isNotEmpty()) {
            recalls.sort()
            lines += "recall 分布（available 的那些）：p10=${fmt(pct(recalls, 0.1))} " +
                "p50=${fmt(pct(recalls, 0.5))} p90=${fmt(pct(recalls, 0.9))} " +
                "（门槛 ${AlignTuning.VERIFY_RECALL_MIN}）"
        }
        if (coverableRatios.isNotEmpty()) {
            coverableRatios.sort()
            lines += "「词典查得到的英文词 / 本句全部英文词」：min=${fmt(pct(coverableRatios, 0.0))} " +
                "p50=${fmt(pct(coverableRatios, 0.5))} mean=${fmt(coverableRatios.average() * 100)}%"
        }
        lines += "英文词合计=${allWordTotals.sum()}，其中词典可查=${coverableTotals.sum()}" +
            "（可查率 ${if (allWordTotals.sum() == 0) "n/a" else fmt(coverableTotals.sum() * 100.0 / allWordTotals.sum())}%）"
        return lines
    }

    private fun pct(sorted: List<Double>, p: Double): Double =
        sorted[(sorted.size - 1).let { (it * p).toInt().coerceIn(0, it) }] * 100.0

    private fun fmt(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()

    private fun contentWords(text: String): List<String> {
        val result = ArrayList<String>(8)
        var i = 0
        while (i < text.length) {
            if (TextScan.isLetter(text[i])) {
                var j = i + 1
                while (j < text.length && (TextScan.isLetter(text[j]) ||
                        text[j] == '\'' || text[j] == '-')
                ) j++
                val word = text.substring(i, j).lowercase()
                if (word.length >= 2) result.add(word)
                i = j
            } else i++
        }
        return result
    }
}
