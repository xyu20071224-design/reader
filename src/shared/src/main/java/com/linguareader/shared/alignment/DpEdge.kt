package com.linguareader.shared.alignment

/**
 * DP 的一个配对边：英文句区间 `[enFrom, enFrom+spanEn)` × 中文句区间 `[zhFrom, zhFrom+spanZh)`。
 *
 * **`mergedUnits` 必须在这一层计数**（设计 §9.5）：按 `AlignedUnit` 的 `targetSpanSize > 1`
 * 去数会漏掉全部 `N:1`——它在投影后被拆成 N 个 `1:1` 单元，「靠合并硬凑覆盖率」的警报器
 * 因此会静默失灵。
 */
internal class DpEdge(
    val enFrom: Int,
    val spanEn: Int,
    val zhFrom: Int,
    val spanZh: Int,
    /** 该边的结构代价（只来自长度 + 锚点 + 位置 + 合并惩罚，不含任何内容信号）。 */
    val cost: Double,
    /** 次优局部替代解释与它的代价差（§7.6）。 */
    val margin: Double
) {
    val merged: Boolean get() = spanEn > 1 || spanZh > 1
}

/**
 * 句级/段落层共用的小工具：长度线（累计字数）上的定位与带宽。
 *
 * 带宽以「累计长度线」为中心而不是句序 —— 一段对话（20 句 60 词）与一段描写（3 句 300 词）
 * 的密度差一个数量级，用句序插值会把正常排版判成漂移（§7.7 口径 B 的正当性）。
 */
internal object LengthLine {

    /** 段落层带宽（两侧半宽）：`clamp(BASE + 2.5·√n, BASE, CAP)`。 */
    fun paragraphBand(paragraphs: Int): Int {
        val computed = AlignTuning.PARA_BAND_BASE +
            Math.ceil(2.5 * Math.sqrt(paragraphs.toDouble())).toInt()
        return computed.coerceIn(AlignTuning.PARA_BAND_BASE, AlignTuning.PARA_BAND_CAP)
    }

    /**
     * 在已排序的累计量数组里找「`cum[j] ≤ target` 的最大 j」。
     *
     * 二分查找，**不在循环体里**分配临时对象。
     */
    fun lastAtOrBelow(cumulative: IntArray, target: Double): Int {
        var low = 0
        var high = cumulative.size - 1
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cumulative[mid] <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** 累计和数组（`cum[i]` = 前 i 项之和，长度 +1）。 */
    fun cumulative(values: IntArray): IntArray {
        val out = IntArray(values.size + 1)
        var running = 0
        for (i in values.indices) {
            running += values[i]
            out[i + 1] = running
        }
        return out
    }
}
