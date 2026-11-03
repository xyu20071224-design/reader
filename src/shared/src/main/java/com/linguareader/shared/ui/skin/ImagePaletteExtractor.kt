package com.linguareader.shared.ui.skin

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 中位切分量化（median cut）—— 把一张图的像素压成若干个主色簇。
 *
 * 为什么不直接统计「出现最多的 N 个颜色」：照片与插画每个像素都略有差异，逐色统计会
 * 得到几千个「每个只占 0.1%」的簇，占比最高的那个往往是某种抗锯齿中间色，推出来的皮肤
 * 毫无个性。中位切分按**色域体积**而不是出现频次切分，能抓到「一片天空」「一件衣服」
 * 这种人类感知上的色块。
 *
 * 纯 Kotlin，不碰 `android.graphics.Bitmap`：调用方把像素读成 [IntArray]（ARGB，
 * 与 `Bitmap.getPixels` 同序）传进来即可，测试里可以直接构造像素数组。
 */
object ImagePaletteExtractor {

    /** 直方图分桶精度：每通道 5 bit（32 档）。再细对聚类结果没有可感差异，只是更慢。 */
    private const val BUCKET_BITS = 5
    private const val BUCKET_SIZE = 1 shl BUCKET_BITS

    /** 低于这个 alpha 的像素视为「看不见」，不参与取色。 */
    private const val MIN_ALPHA = 16

    /**
     * 从像素里提取主色簇，按占比从大到小排列。
     *
     * @param pixels ARGB 像素（`0xAARRGGBB`）
     * @param maxColors 最多返回几簇（默认 8；再多对角色分配没有边际收益）
     * @return 空列表表示「整图全透明/没有可用像素」，调用方应回退到内置皮肤
     */
    fun extract(pixels: IntArray, width: Int, height: Int, maxColors: Int = 8): List<ColorCluster> {
        if (maxColors <= 0) return emptyList()
        val usable = coercePixelCount(pixels, width, height)
        if (usable == 0) return emptyList()

        val histogram = buildHistogram(pixels, usable)
        if (histogram.isEmpty()) return emptyList()

        val boxes = medianCut(histogram, maxColors)
        val total = histogram.sumOf { it.count.toLong() }
        return boxes
            .map { box -> box.toCluster(total) }
            .filter { it.share > 0.0 }
            .sortedWith(compareByDescending<ColorCluster> { it.share }.thenBy { it.color })
    }

    /** 像素数与 w*h 对不上时，只取两边都认可的部分（调用方给的数组可能偏短/偏长）。 */
    fun coercePixelCount(pixels: IntArray, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val expected = width.toLong() * height.toLong()
        return minOf(pixels.size.toLong(), expected).toInt().coerceAtLeast(0)
    }

    // ── 直方图 ───────────────────────────────────────────────────────

    private class HistEntry(val r: Int, val g: Int, val b: Int, val count: Int) {
        val key: Long get() = pack(r, g, b)
    }

    private fun pack(r: Int, g: Int, b: Int): Long =
        (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

    private fun buildHistogram(pixels: IntArray, count: Int): List<HistEntry> {
        // 分桶累积，桶内保留精确通道和以便算出真实平均值（否则颜色会被量化网格带偏）。
        val sums = HashMap<Int, LongArray>(1024)
        for (i in 0 until count) {
            val px = pixels[i]
            val a = (px ushr 24) and 0xFF
            if (a < MIN_ALPHA) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val key = ((r shr (8 - BUCKET_BITS)) shl (BUCKET_BITS * 2)) or
                ((g shr (8 - BUCKET_BITS)) shl BUCKET_BITS) or
                (b shr (8 - BUCKET_BITS))
            val acc = sums[key] ?: LongArray(4).also { sums[key] = it }
            acc[0] += r.toLong()
            acc[1] += g.toLong()
            acc[2] += b.toLong()
            acc[3] += 1L
        }
        if (sums.isEmpty()) return emptyList()
        // 排序保证同一张图每次跑都得到同一组簇（HashMap 迭代序不承诺稳定）。
        return sums.entries
            .sortedBy { it.key }
            .map { (_, acc) ->
                val n = acc[3].toInt()
                HistEntry(
                    r = (acc[0].toDouble() / n).roundToInt(),
                    g = (acc[1].toDouble() / n).roundToInt(),
                    b = (acc[2].toDouble() / n).roundToInt(),
                    count = n
                )
            }
    }

    // ── 中位切分 ─────────────────────────────────────────────────────

    private class Box(val entries: List<HistEntry>) {
        val count: Int = entries.sumOf { it.count }
        private val rangeR = entries.maxOf { it.r } - entries.minOf { it.r }
        private val rangeG = entries.maxOf { it.g } - entries.minOf { it.g }
        private val rangeB = entries.maxOf { it.b } - entries.minOf { it.b }

        /** 最长的那条边；三维都相等（单色）时为 0，说明这个盒子切不动。 */
        val longestRange: Int = maxOf(rangeR, rangeG, rangeB)

        /** 切分优先级：体积 × 占比。大而杂的盒子先切，单色的大色块留在后面。 */
        val splitPriority: Double = longestRange.toDouble() * count

        val splittable: Boolean get() = entries.size > 1 && longestRange > 0

        fun channel(i: Int): Int = when (i) {
            0 -> rangeR
            1 -> rangeG
            else -> rangeB
        }

        fun toCluster(total: Long): ColorCluster {
            var sr = 0L
            var sg = 0L
            var sb = 0L
            for (e in entries) {
                sr += e.r.toLong() * e.count
                sg += e.g.toLong() * e.count
                sb += e.b.toLong() * e.count
            }
            val n = count.coerceAtLeast(1)
            return ColorCluster(
                color = SkinColorMath.argb(
                    (sr / n).toInt().coerceIn(0, 255),
                    (sg / n).toInt().coerceIn(0, 255),
                    (sb / n).toInt().coerceIn(0, 255)
                ),
                share = count.toDouble() / total.toDouble(),
                count = count
            )
        }
    }

    private fun medianCut(histogram: List<HistEntry>, maxColors: Int): List<Box> {
        val boxes = ArrayList<Box>()
        boxes.add(Box(histogram))
        while (boxes.size < maxColors) {
            val target = boxes.filter { it.splittable }.maxByOrNull { it.splitPriority } ?: break
            val (left, right) = split(target) ?: break
            boxes.remove(target)
            boxes.add(left)
            boxes.add(right)
        }
        return boxes
    }

    /** 沿最长通道按**加权中位数**切一刀；切不开（一侧为空）返回 null。 */
    private fun split(box: Box): Pair<Box, Box>? {
        val channel = when {
            box.channel(0) >= box.channel(1) && box.channel(0) >= box.channel(2) -> 0
            box.channel(1) >= box.channel(2) -> 1
            else -> 2
        }
        val sorted = box.entries.sortedWith(
            // 同值时按其余通道兜底排序，保证结果只取决于颜色集合、与输入顺序无关。
            when (channel) {
                0 -> compareBy<HistEntry> { it.r }.thenBy { it.g }.thenBy { it.b }
                1 -> compareBy<HistEntry> { it.g }.thenBy { it.b }.thenBy { it.r }
                else -> compareBy<HistEntry> { it.b }.thenBy { it.r }.thenBy { it.g }
            }
        )
        val half = box.count / 2.0
        var acc = 0
        var cut = -1
        for (i in sorted.indices) {
            acc += sorted[i].count
            if (acc >= half) {
                cut = i
                break
            }
        }
        if (cut < 0) return null
        // 切点落在末尾时把最后一颗挪到左边，否则右边会是空盒。
        if (cut >= sorted.size - 1) cut = sorted.size - 2
        if (cut < 0) return null
        return Box(sorted.subList(0, cut + 1).toList()) to Box(sorted.subList(cut + 1, sorted.size).toList())
    }
}

/**
 * 一个主色簇。
 *
 * [share] 是它在整图（只计可见像素）里的占比，用于「谁是背景」这类判断；
 * [count] 是像素数，主要给测试与调试用。
 */
data class ColorCluster(
    val color: Long,
    val share: Double,
    val count: Int
) {
    val hsl: SkinColorMath.Hsl get() = SkinColorMath.toHsl(color)
    val hue: Double get() = hsl.hue
    val saturation: Double get() = hsl.saturation
    val lightness: Double get() = hsl.lightness
    val luminance: Double get() = SkinColorMath.relativeLuminance(color)

    /**
     * 色度（max-min，0..1）：衡量「这个颜色有多鲜」。
     *
     * 与 HSL 饱和度不同，色度在极暗/极亮处不会虚高——夜空里那片深蓝 HSL 饱和度 0.68
     * 看着很"艳"，实际色度只有 0.18。判「像不像纸」用色度更稳。
     */
    val chroma: Double
        get() {
            val r = SkinColorMath.red(color)
            val g = SkinColorMath.green(color)
            val b = SkinColorMath.blue(color)
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return (mx - mn) / 255.0
        }

    /** 冷暖度：0 冷 ～ 1 暖，灰阶返回 0.5。 */
    val warmth: Double get() = SkinColorMath.warmth(hue, saturation)

    fun contrastOver(other: Long): Double = SkinColorMath.contrastRatio(color, other)
}
