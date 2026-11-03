package com.linguareader.shared.alignment

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 长度/密度模型（设计 §5）。
 *
 * 三级估计，**全部与「句级配对结果」弱耦合**（这是它不能污染判定层的前提）：
 *
 * | 级 | 估计量 | 输入 | 用途 |
 * | --- | --- | --- | --- |
 * | 0 | `rTotal` = 全量 `Σch/Σw` | 两侧全量，剔除结构性章节 | 章节 DP 的暖启动（此时还没有配对） |
 * | 1 | `rBook` = 章节对 `ρ_ch` 的加权中位数 | 已接受的章节对总量 | 句级密度的主估计 |
 * | 2 | `rSeed` = 锚点硬钉对 `ρ` 的中位数 | 硬钉（由键唯一性决定，**不经 DP**） | 交叉校验 + 触发分段密度 |
 *
 * `σ` 的主估计集同样取自硬钉对，用 MAD 而不是标准差（§5.4）：DP 会削掉残差分布的尾部，
 * 标准差会被系统性低估。
 *
 * ## 一个实现上必须交代的取舍
 *
 * 设计 §5.4 说「硬钉对数 < 200 时退回**段落层已接受单元的残差**」。但段落层 DP 的代价函数
 * 本身要读 `z`，而 `z` 要读 `σ` —— 那条退路会形成**实现顺序上的环**（D1 依赖 C7，
 * C7 依赖 D1）。因此这里只用硬钉当估计集；硬钉不足时取钳制区间中值
 * [AlignTuning.SIGMA_DEFAULT] 并在 `notes` 里如实记一条，不假装估出了 σ。
 * 这条已报给 Lead（T3 视为「设计里不可实现的顺序依赖」），不是静默改动。
 */
internal class DensityModel(private val source: BookSide) {

    /** 第零级：全量比（剔除结构性章节后）。 */
    var rTotal: Double = AlignTuning.R_MID
        private set

    /** 第一级：章节对总量比的加权中位数。 */
    var rBook: Double = AlignTuning.R_MID
        private set

    /** 第二级：锚点硬钉对中位数（无硬钉时为 NaN）。 */
    var rSeed: Double = Double.NaN
        private set

    /** `σ₁`（残差 MAD 的稳健估计，已钳制）。 */
    var sigma1: Double = AlignTuning.SIGMA_DEFAULT
        private set

    /** 密度不可信时收紧长度门（§5.2 第三级 / §5.3 钳制）。 */
    var rConfidenceLow: Boolean = false
        private set

    /** 分段密度：`null` = 不启用。 */
    private var rSeg: DoubleArray? = null

    /** 每句所属的英文位置段（仅分段密度启用时有效）。 */
    private var sentSegment: IntArray = Bags.EMPTY_INT

    val notes: MutableList<String> = ArrayList()

    /** 是否有可用的 ρ 估计（否则只能退化）。 */
    var hasEstimate: Boolean = false
        private set

    // ──────────────────────────────────────────────────────────────
    //  第零级
    // ──────────────────────────────────────────────────────────────

    /**
     * 用整本书的全量比暖启动章节层（§5.2 第零级）：`Σ 中文字数 / Σ 英文词数`。
     *
     * ⚠️ **两侧各数各的，且这个事实被写进签名**：英文侧贡献 `englishWords`（字母词数），
     * 中文侧贡献 `chineseChars`（汉字数）。参数**具名**，就是为了让「拿英文侧的汉字数
     * （恒为 0）比英文词数」这类漏洞在调用处一眼可见——历史 bug 正是如此：`rTotal` 变成
     * `4/Σw ≈ 0.008`，章节层长度项 `cL` 全部饱和到 1，章节配对退化成只看位置，产出
     * `3:1`/`1:2` 这种荒谬跨度，`chapterMargin` 随之恒为 0。编译期抓不到，只能靠签名。
     *
     * @param chineseChars 中文章节的汉字数（**中文侧**的数组）
     * @param englishWords 英文章节的字母词数（**英文侧**的数组）
     * @param sourceStructural 英文章节的结构性标记（这些章要从分母里剔除）
     */
    fun totalRatioFromChapters(
        chineseChars: IntArray,
        englishWords: IntArray,
        sourceStructural: BooleanArray
    ) {
        var sumCh = 0L
        for (value in chineseChars) sumCh += value
        var sumW = 0L
        for (c in englishWords.indices) {
            if (c in sourceStructural.indices && sourceStructural[c]) continue
            sumW += englishWords[c]
        }
        rTotal = if (sumW > 0) (sumCh.toDouble() + AlignTuning.GAMMA) /
            (sumW.toDouble() + AlignTuning.GAMMA) else AlignTuning.R_MID
        // 合理性钳制：rTotal 只给章节层暖启动用，不必进 notes（rBook 会记钳制）。
        rTotal = rTotal.coerceIn(AlignTuning.R_MIN, AlignTuning.R_MAX)
    }

    // ──────────────────────────────────────────────────────────────
    //  第一级
    // ──────────────────────────────────────────────────────────────

    /**
     * 章节对总量比的加权中位数（§5.2 第一级）。
     *
     * @param pairChars 每个已接受章节对的中汉字数
     * @param pairWords 每个已接受章节对的中英文词数
     */
    fun computeBook(pairChars: LongArray, pairWords: LongArray) {
        if (pairChars.isEmpty()) {
            rBook = clampDensity(rTotal, "no accepted chapter pair")
            hasEstimate = false
            return
        }
        val ratios = DoubleArray(pairChars.size) { i ->
            if (pairWords[i] <= 0) Double.NaN
            else (pairChars[i].toDouble() + AlignTuning.GAMMA) /
                (pairWords[i].toDouble() + AlignTuning.GAMMA)
        }
        val median = weightedMedian(ratios, pairChars.map { it.toDouble() }.toDoubleArray())
        rBook = clampDensity(median, null)
        hasEstimate = true
        var totalWords = 0L
        for (w in pairWords) totalWords += w
        if (totalWords < AlignTuning.SHORT_BOOK_WORDS) {
            rConfidenceLow = true
            notes += "short book: only $totalWords source words, density estimate is weak"
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  第二级 + σ
    // ──────────────────────────────────────────────────────────────

    /**
     * 用锚点硬钉对做交叉校验，并在密度不均匀时启用分段密度（§5.2 第二级）。
     *
     * σ 的主估计集**只取自硬钉对**（不经 DP）；硬钉不足时用对数空间几何混合
     * 过渡到默认值（见 [estimateSigma] 与设计 §16 E3 勘误），不再有 `N >= 200` 的阶跃。
     *
     * @param pinResiduals 硬钉对的 `ρ`（即 `(ch+γ)/(w+γ)`），全部来自硬钉——不经 DP
     */
    fun computeSeed(pinResiduals: DoubleArray, chapterChars: IntArray, chapterWords: IntArray) {
        val anchorCount = pinResiduals.size
        val residualMad = if (anchorCount > 0) {
            val logs = DoubleArray(anchorCount) { ln(pinResiduals[it]) }
            madOf(logs)
        } else {
            0.0
        }
        val estimate = estimateSigma(anchorCount, residualMad)
        sigma1 = estimate.sigma1
        notes += estimate.note
        estimate.partialNote?.let { notes += it }

        if (anchorCount >= AlignTuning.SIGMA_SEED_MIN_PAIRS) {
            val sorted = pinResiduals.sorted()
            rSeed = sorted[sorted.size / 2]
            if (abs(ln(rSeed / rBook)) > AlignTuning.SEG_TRIGGER) {
                enableSegments(chapterChars, chapterWords)
            }
        } else if (anchorCount > 0) {
            notes += "rSeed: only $anchorCount hard-seed pairs, cross-check skipped"
        }
    }

    /** 分段密度（§5.2 第二级下半段）：按英文位置均分为 K 段，经验贝叶斯收缩。 */
    private fun enableSegments(chapterChars: IntArray, chapterWords: IntArray) {
        val totalWords = chapterWords.sum()
        if (totalWords <= 0) return
        val sumCh = DoubleArray(AlignTuning.SEG_COUNT)
        val sumW = DoubleArray(AlignTuning.SEG_COUNT)
        var cumulative = 0
        sentSegment = IntArray(source.sentCount)
        for (i in 0 until source.sentCount) {
            val seg = (cumulative * AlignTuning.SEG_COUNT / totalWords)
                .coerceIn(0, AlignTuning.SEG_COUNT - 1)
            sentSegment[i] = seg
            if (source.sentLangUnit[i]) {
                sumW[seg] += source.sentWords[i].toDouble()
                sumCh[seg] += source.sentChars[i].toDouble()
            }
            cumulative += source.sentWords[i]
        }
        val seg = DoubleArray(AlignTuning.SEG_COUNT) { k ->
            val raw = if (sumW[k] > 0) (sumCh[k] + AlignTuning.GAMMA * 0) / sumW[k] else rBook
            (sumW[k] * raw + AlignTuning.SEG_SHRINK_WORDS * rBook) /
                (sumW[k] + AlignTuning.SEG_SHRINK_WORDS)
        }
        rSeg = seg
        val spread = seg.maxOrNull()!! / seg.minOrNull()!!.coerceAtLeast(1e-6)
        notes += "segmented density enabled (K=${AlignTuning.SEG_COUNT}, spread=" +
            round(spread) + ", rSeed=" + round(rSeed) + ", rBook=" + round(rBook) + ")"
    }

    // ──────────────────────────────────────────────────────────────
    //  查询
    // ──────────────────────────────────────────────────────────────

    /** 某句中英文词位置对应的有效密度（分段时按句子所在段）。 */
    fun rEffOfSentence(sourceSentenceIndex: Int): Double {
        val seg = rSeg ?: return rBook
        if (sentSegment.isEmpty() || sourceSentenceIndex !in sentSegment.indices) return rBook
        return seg[sentSegment[sourceSentenceIndex]]
    }

    /** `σ(span) = max(σ 地板, σ₁ / √m_eff)`，`m_eff = max(1, wSpan/12)`（§5.4）。 */
    fun sigmaFor(wSpan: Int): Double {
        val effectiveWords = wSpan.coerceAtMost(AlignTuning.SPAN_WORDS_CAP).toDouble()
        val mEff = (effectiveWords / AlignTuning.SIGMA_M_EFF_WORDS).coerceAtLeast(1.0)
        return (sigma1 / sqrt(mEff)).coerceAtLeast(AlignTuning.SIGMA_FLOOR)
    }

    /** 观测比 `ρ = (ch+γ)/(w+γ)`（§5.4）。 */
    fun observedRatio(words: Int, chars: Int): Double =
        (chars.toDouble() + AlignTuning.GAMMA) / (words.toDouble() + AlignTuning.GAMMA)

    /**
     * 长度偏离 `z = |ln ρ − ln r_eff| / σ(span)`（§5.4）。
     *
     * 这里**不取对数之外的计算**，且只在预计算/一次性路径调用（`decide()` 读的是算好的 z）。
     */
    fun deviation(words: Int, chars: Int, totalWords: Int, rEff: Double): Double {
        val rho = observedRatio(words, chars)
        val sigma = sigmaFor(totalWords)
        return abs(ln(rho) - ln(rEff)) / sigma
    }

    // ──────────────────────────────────────────────────────────────

    private fun clampDensity(raw: Double, why: String?): Double {
        val clamped = raw.coerceIn(AlignTuning.R_MIN, AlignTuning.R_MAX)
        if (raw.isNaN() || abs(clamped - raw) > 1e-9) {
            rConfidenceLow = true
            notes += "density clamped: raw=" + round(raw) + " → " + round(clamped) +
                (if (why != null) " ($why)" else "")
        }
        return clamped
    }

    /** MAD 的稳健离散度（未乘 1.4826，乘数在 [estimateSigma] 里）。 */
    private fun madOf(logResiduals: DoubleArray): Double {
        if (logResiduals.isEmpty()) return 0.0
        val sorted = logResiduals.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = DoubleArray(sorted.size) { abs(sorted[it] - median) }
        deviations.sort()
        return deviations[deviations.size / 2]
    }

    private fun weightedMedian(values: DoubleArray, weights: DoubleArray): Double {
        val indices = values.indices.filter { !values[it].isNaN() }
        if (indices.isEmpty()) return AlignTuning.R_MID
        val sorted = indices.sortedBy { values[it] }
        var total = 0.0
        for (i in sorted) total += weights[i].coerceAtLeast(0.0)
        if (total <= 0.0) return values[sorted[sorted.size / 2]]
        var acc = 0.0
        for (i in sorted) {
            acc += weights[i].coerceAtLeast(0.0)
            if (acc >= total / 2.0) return values[i]
        }
        return values[sorted.last()]
    }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
