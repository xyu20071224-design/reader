package com.linguareader.shared.alignment

import kotlin.math.exp
import kotlin.math.ln

/**
 * σ 的稳健估计（设计 §5.4 + §16 E3 勘误）。
 *
 * ## 为什么不是「锚点对数 ≥ 200 就用锚点、否则退回段落层残差」
 *
 * 两个原因，都与「让判定可用」直接相关：
 *
 * 1. **依赖环**：段落层代价要读 `z`，`z` 要读 `σ`，而「段落层已接受单元的残差」要等
 *    段落层跑完——C7 依赖 D1、D1 依赖 C7，成环。
 * 2. **硬阈值会造成 σ 阶跃**：`σ₁` 钳制区间是 `[0.18, 0.60]`，从「默认 0.35」切到
 *    「估计 0.18」会让 `z` 相差 ×1.94，一个 `z = 1.5` 的单元一步跨过 `Z_LIMIT_SINGLE = 2.0`。
 *    扰动会增删锚点，某本书锚点数在 200 上下时，一次「漏译一段」就能把 203 压到 197，
 *    覆盖率测的就变成了阈值边界，而不是算法质量。
 *
 * ## 为什么是「对数空间几何混合」而不是加权平均
 *
 * `z = |ln ρ − ln r| / σ`：σ 是**乘性**作用在 z 上，不是加性。算术混合在两端的比例
 * 不对称（0.18→0.35 与 0.60→0.35 不是同一个「距离」）；对数混合在 σ 的尺度上对称，
 * 且结果必然落在两个输入之间，不会产生新极值。
 *
 * @param anchors     硬钉（全书唯一锚点）句对数
 * @param residualMad MAD(resid)，只在 `anchors > 0` 时有意义；调用方保证非负
 */
internal fun estimateSigma(anchors: Int, residualMad: Double): SigmaEstimate {
    val w = (anchors / AlignTuning.ANCHOR_FULL_CONFIDENCE.toDouble()).coerceIn(0.0, 1.0)

    val sigma = if (w >= 1.0) {
        // 纯锚点估计：几何混合在 w=1 时数学上退化为原式。显式分开写是为了让
        // 「正常路径没被改动」能被 diff 一眼看出，也便于 T5 回归对照。
        clampSigma(1.4826 * residualMad)
    } else {
        val sigmaAnchor = if (anchors > 0) {
            clampSigma(1.4826 * residualMad)
        } else {
            AlignTuning.SIGMA_DEFAULT // 无锚点时混合退化为默认值
        }
        val mixedLog = w * ln(sigmaAnchor) + (1.0 - w) * ln(AlignTuning.SIGMA_DEFAULT)
        // 钳制放在混合之后：保证 [SIGMA_MIN, SIGMA_MAX] 始终是硬界。
        exp(mixedLog).coerceIn(AlignTuning.SIGMA_MIN, AlignTuning.SIGMA_MAX)
    }

    val note = when {
        anchors >= AlignTuning.ANCHOR_FULL_CONFIDENCE ->
            "sigma=${fmt(sigma)} (anchors=$anchors, w=${fmt(w)}, anchor-only)"
        anchors > 0 ->
            "sigma=${fmt(sigma)} (anchors=$anchors, w=${fmt(w)}, blend)"
        else ->
            "sigma=${fmt(sigma)} (anchors=0, w=0.00, default)"
    }
    // w 的诊断价值：直接告诉读者「这本书的 σ 有百分之多少是猜的」。
    val partialNote = if (w < 1.0 && anchors > 0) {
        "sigma partially depends on default (w=${fmt(w)} → " +
            "${((1.0 - w) * 100).toInt()}% guessed)"
    } else {
        null
    }
    return SigmaEstimate(sigma, note, partialNote)
}

internal class SigmaEstimate(
    val sigma1: Double,
    val note: String,
    val partialNote: String? = null
)

private fun clampSigma(v: Double): Double =
    v.coerceIn(AlignTuning.SIGMA_MIN, AlignTuning.SIGMA_MAX)

private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)
