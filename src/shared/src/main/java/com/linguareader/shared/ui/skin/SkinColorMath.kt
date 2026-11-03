package com.linguareader.shared.ui.skin

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ARGB 颜色算术 —— 纯 Kotlin，**不依赖 `android.graphics.Color`**（`:shared` 硬约束）。
 *
 * 约定：颜色一律用 [Long] 存 `0xAARRGGBB`（高位 alpha，与 Android 的 `Color` / 位图
 * `getPixels` 同序）。用 Long 而非 Int 是为了让 `0xFF000000` 这类字面量不出现负数，
 * 序列化成 hex 时也不会冒出 `#ffffffff` 之外的符号问题。
 *
 * 所有对比度计算走 WCAG 2.x 的**相对亮度**定义（sRGB 先线性化再加权），与
 * `ThemeColors.kt` 里历史注释引用的口径一致，可直接对账。
 */
object SkinColorMath {

    const val BLACK: Long = 0xFF000000L
    const val WHITE: Long = 0xFFFFFFFFL
    const val TRANSPARENT: Long = 0x00000000L

    /** `#RRGGBB` 里 alpha 为 0xFF 时用的掩码。 */
    private const val RGB_MASK: Long = 0x00FFFFFFL

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Long =
        ((a and 0xFF).toLong() shl 24) or
            ((r and 0xFF).toLong() shl 16) or
            ((g and 0xFF).toLong() shl 8) or
            (b and 0xFF).toLong()

    fun alpha(color: Long): Int = ((color ushr 24) and 0xFFL).toInt()
    fun red(color: Long): Int = ((color ushr 16) and 0xFFL).toInt()
    fun green(color: Long): Int = ((color ushr 8) and 0xFFL).toInt()
    fun blue(color: Long): Int = (color and 0xFFL).toInt()

    fun isOpaque(color: Long): Boolean = alpha(color) == 0xFF

    fun opaque(color: Long): Long = color or 0xFF000000L

    fun withAlpha(color: Long, alpha: Int): Long {
        val a = alpha.coerceIn(0, 255)
        return (color and RGB_MASK) or (a.toLong() shl 24)
    }

    fun withAlpha(color: Long, alpha: Double): Long =
        withAlpha(color, (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt())

    /** `#RRGGBB`（不透明）或 `#AARRGGBB`（带透明）。序列化用这个。 */
    fun toHex(color: Long): String = if (isOpaque(color)) {
        String.format(Locale.ROOT, "#%02X%02X%02X", red(color), green(color), blue(color))
    } else {
        String.format(
            Locale.ROOT,
            "#%02X%02X%02X%02X",
            alpha(color), red(color), green(color), blue(color)
        )
    }

    /**
     * 解析 `#RGB` / `#RRGGBB` / `#AARRGGBB`（`#` 可省，大小写不敏感）。
     *
     * 认不出就返回 null —— 调用方决定是回退默认值还是报错，这里不擅自猜。
     */
    fun parseHex(text: String?): Long? {
        if (text == null) return null
        val body = text.trim().removePrefix("#")
        if (body.isEmpty()) return null
        if (!body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return when (body.length) {
            3 -> {
                val r = body[0].digitToInt(16) * 17
                val g = body[1].digitToInt(16) * 17
                val b = body[2].digitToInt(16) * 17
                argb(r, g, b)
            }
            6 -> argb(
                body.substring(0, 2).toInt(16),
                body.substring(2, 4).toInt(16),
                body.substring(4, 6).toInt(16)
            )
            8 -> (body.toLong(16) and 0xFFFFFFFFL)
            else -> null
        }
    }

    // ── WCAG 对比度 ──────────────────────────────────────────────────

    /** sRGB 单通道 → 线性光。 */
    fun toLinear(channel: Int): Double {
        val s = channel.coerceIn(0, 255) / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** WCAG 相对亮度（0.0 黑 ～ 1.0 白）。忽略 alpha，调用方先自行合成。 */
    fun relativeLuminance(color: Long): Double =
        0.2126 * toLinear(red(color)) +
            0.7152 * toLinear(green(color)) +
            0.0722 * toLinear(blue(color))

    /** WCAG 对比度，范围 1.0 ～ 21.0。 */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return if (la >= lb) (la + 0.05) / (lb + 0.05) else (lb + 0.05) / (la + 0.05)
    }

    // ── HSL ─────────────────────────────────────────────────────────

    /** 色相 0..360、饱和度 0..1、明度 0..1。 */
    data class Hsl(val hue: Double, val saturation: Double, val lightness: Double) {
        /** [hue] 的短名（`h`/`s`/`l` 是色彩领域的通用记法，调用方读起来更像算式）。 */
        val h: Double get() = hue
        val s: Double get() = saturation
        val l: Double get() = lightness
    }

    fun toHsl(color: Long): Hsl {
        val r = red(color) / 255.0
        val g = green(color) / 255.0
        val b = blue(color) / 255.0
        val mx = max(r, max(g, b))
        val mn = Math.min(r, Math.min(g, b))
        val l = (mx + mn) / 2.0
        val d = mx - mn
        if (d == 0.0) return Hsl(0.0, 0.0, l)
        val s = if (l > 0.5) d / (2.0 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6.0 else 0.0))
            g -> ((b - r) / d + 2.0)
            else -> ((r - g) / d + 4.0)
        } * 60.0
        return Hsl(h % 360.0, s, l)
    }

    fun fromHsl(hue: Double, saturation: Double, lightness: Double, alpha: Int = 255): Long {
        val h = ((hue % 360.0) + 360.0) % 360.0
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        if (s == 0.0) {
            val v = (l * 255.0).roundToInt()
            return argb(v, v, v, alpha)
        }
        val q = if (l < 0.5) l * (1.0 + s) else l + s - l * s
        val p = 2.0 * l - q
        fun channel(t: Double): Int {
            var tt = t
            if (tt < 0.0) tt += 1.0
            if (tt > 1.0) tt -= 1.0
            val v = when {
                tt < 1.0 / 6.0 -> p + (q - p) * 6.0 * tt
                tt < 0.5 -> q
                tt < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - tt) * 6.0
                else -> p
            }
            return (v * 255.0).roundToInt().coerceIn(0, 255)
        }
        return argb(
            channel(h / 360.0 + 1.0 / 3.0),
            channel(h / 360.0),
            channel(h / 360.0 - 1.0 / 3.0),
            alpha
        )
    }

    /** 只换明度，保留色相/饱和度。 */
    fun withLightness(color: Long, lightness: Double): Long {
        val hsl = toHsl(color)
        return fromHsl(hsl.hue, hsl.saturation, lightness, alpha(color))
    }

    /** 明度整体平移 delta（用于「二级表面跟随主表面一起变亮/变暗」）。 */
    fun shiftLightness(color: Long, delta: Double): Long {
        val hsl = toHsl(color)
        return fromHsl(hsl.hue, hsl.saturation, (hsl.lightness + delta).coerceIn(0.0, 1.0), alpha(color))
    }

    /** 黑白两种文字里，在 [surface] 上能拿到的最好对比度。 */
    fun bestTextContrast(surface: Long): Double =
        max(contrastRatio(BLACK, surface), contrastRatio(WHITE, surface))

    // ── 混合 ────────────────────────────────────────────────────────

    /** sRGB 空间线性插值：t=0 取 [a]，t=1 取 [b]。alpha 一并插值。 */
    fun mix(a: Long, b: Long, t: Double): Long {
        val f = t.coerceIn(0.0, 1.0)
        val g = 1.0 - f
        return argb(
            (red(a) * g + red(b) * f).roundToInt(),
            (green(a) * g + green(b) * f).roundToInt(),
            (blue(a) * g + blue(b) * f).roundToInt(),
            (alpha(a) * g + alpha(b) * f).roundToInt()
        )
    }

    /** 标准 source-over 合成（[top] 盖在 [bottom] 上）。 */
    fun blendOver(top: Long, bottom: Long): Long {
        val ta = alpha(top) / 255.0
        if (ta >= 1.0) return top
        if (ta <= 0.0) return bottom
        val ba = alpha(bottom) / 255.0
        val outA = ta + ba * (1.0 - ta)
        if (outA <= 0.0) return TRANSPARENT
        fun ch(t: Int, b: Int) = (((t * ta) + b * ba * (1.0 - ta)) / outA).roundToInt().coerceIn(0, 255)
        return argb(
            ch(red(top), red(bottom)),
            ch(green(top), green(bottom)),
            ch(blue(top), blue(bottom)),
            (outA * 255.0).roundToInt()
        )
    }

    // ── 对比度修正 ──────────────────────────────────────────────────

    /** 明度扫描的分辨率：512 档 ≈ 0.2% 一步，肉眼无差、耗时也可忽略。 */
    private const val LIGHTNESS_STEPS = 512

    /**
     * 把 [foreground] 的明度调到与 [background] 的对比度 ≥ [target]，**保留色相与饱和度**。
     *
     * 选「改动最小」的明度档，所以 [foreground] 已经达标时原样返回（幂等）。
     * 实在达不到（目标 > 背景能给的物理上限）就退回纯黑/纯白中对比更高的一侧。
     */
    fun ensureContrast(foreground: Long, background: Long, target: Double): Long {
        if (contrastRatio(foreground, background) >= target) return foreground
        val a = alpha(foreground)
        val hsl = toHsl(foreground)
        var bestLightness = Double.NaN
        var bestDelta = Double.MAX_VALUE
        for (i in 0..LIGHTNESS_STEPS) {
            val l = i.toDouble() / LIGHTNESS_STEPS
            val candidate = fromHsl(hsl.hue, hsl.saturation, l, a)
            if (contrastRatio(candidate, background) < target) continue
            val delta = abs(l - hsl.lightness)
            if (delta < bestDelta) {
                bestDelta = delta
                bestLightness = l
            }
        }
        if (bestLightness.isNaN()) {
            return if (contrastRatio(BLACK, background) >= contrastRatio(WHITE, background)) {
                withAlpha(BLACK, a)
            } else {
                withAlpha(WHITE, a)
            }
        }
        return fromHsl(hsl.hue, hsl.saturation, bestLightness, a)
    }

    /**
     * 保证 [surface] 上**存在**某种黑白文字能达到 [target]（阈值取两端更优者）。
     *
     * 灰阶/低对比图常见坑：纸色卡在 #8C8C8C 附近时，黑白两边都够不到 4.5:1，
     * 只调文字是死路——必须把背景本身推开。推的方向是「离极值更近、改动更小」的那侧。
     */
    fun ensureSurfaceSupports(surface: Long, target: Double): Long {
        val best = max(contrastRatio(BLACK, surface), contrastRatio(WHITE, surface))
        if (best >= target) return surface
        val towardDark = contrastRatio(WHITE, surface) > contrastRatio(BLACK, surface)
        val hsl = toHsl(surface)
        val a = alpha(surface)
        var fallback = surface
        var bestDelta = Double.MAX_VALUE
        for (i in 0..LIGHTNESS_STEPS) {
            val l = if (towardDark) hsl.lightness - hsl.lightness * i / LIGHTNESS_STEPS
            else hsl.lightness + (1.0 - hsl.lightness) * i / LIGHTNESS_STEPS
            val candidate = fromHsl(hsl.hue, hsl.saturation, l, a)
            if (max(contrastRatio(BLACK, candidate), contrastRatio(WHITE, candidate)) >= target) {
                val delta = abs(l - hsl.lightness)
                if (delta < bestDelta) {
                    bestDelta = delta
                    fallback = candidate
                }
                break
            }
        }
        return fallback
    }

    /**
     * 冷暖度：0 = 冷（青蓝一侧），1 = 暖（红橙黄一侧）。
     *
     * 以 40°（橙）为暖极，角距离 180° 即最冷。灰阶（饱和度为 0）返回 0.5 —— 「不冷不暖」
     * 比「假装暖」更诚实，也让调用方能识别「这张图没有色彩倾向」。
     */
    fun warmth(hue: Double, saturation: Double): Double {
        if (saturation < 1e-6) return 0.5
        val d = abs(((hue - 40.0) % 360.0 + 540.0) % 360.0 - 180.0)
        return 1.0 - d / 180.0
    }
}
