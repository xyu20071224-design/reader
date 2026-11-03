package com.linguareader.app.uirender

import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinColorMath
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片换肤的**质量闸门**（Lead 定的验收口径）。
 *
 * 背景：第一版管线能跑通，但 Lead 复验出图时暴露了两个语义错误 ——
 * 1. 一张「暖木 + 米白 + 绿植」的书桌照被分配成**深绿底**（绿植面积最大），
 *    而阅读器用户预期的是「明亮的纸」。面积最大的色簇不等于背景色。
 * 2. 「品红 + 黄 + 黑」海报的**正文文字被染成黄色**：黄在近黑底上对比度 15:1，
 *    单看对比度「合格」，但高饱和色当正文在视觉上是错的。
 *
 * 结论：**对比度达标 ≠ 视觉可用**。这两条必须显式约束，否则换任何一张图都可能出洋相。
 * 本测试是「图片换肤」这条产品能力的底线，不是风格偏好。
 */
class ImageSkinQualityGateTest {

    private fun pixels(w: Int, h: Int, block: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> block(i % w, i / w) }

    private fun luminance(color: Long): Double = SkinColorMath.relativeLuminance(color)
    private fun saturation(color: Long): Double = SkinColorMath.toHsl(color).s
    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    /** 暖木书桌照：米白光线只是一小条，木头与绿植占大部分面积。 */
    private fun warmWood() = pixels(120, 120) { x, y ->
        when {
            y < 24 -> 0xFFF3EADF.toInt()
            x < 60 -> 0xFF7B5B3A.toInt()
            else -> 0xFF3E6B58.toInt()
        }
    }

    /** 高饱和海报：品红 + 黄 + 近黑。 */
    private fun poster() = pixels(120, 120) { x, _ ->
        when {
            x < 40 -> 0xFFE0218A.toInt()
            x < 80 -> 0xFFFFD400.toInt()
            else -> 0xFF1A1A1A.toInt()
        }
    }

    // ── 门禁 1：纸色明度要符合「这是一张纸」的语义 ──────────────────

    /**
     * 纸色明度门禁：**要么明确的浅纸，要么明确的深纸，不许卡在灰区。**
     *
     * 这是**产品决定**，不是随意挑的数字，理由：
     * 长文阅读时，中灰底（0.10 < L < 0.70）两头不讨好——白天不够亮、夜里不够沉，
     * 而且它让「这本是纸」的心理模型失效（纸要么被灯照亮，要么是夜里的暗纸）。
     * 所以 [ImageSkinBuilder] 会把推导出的纸色吸附到 0.06/0.95 两端，
     * 这条测试守的就是"别有人把这个吸附逻辑去掉"。
     *
     * **如果将来真要允许中灰皮肤**（比如某个品牌指定 #808080 底），
     * 改的是这条门禁 + builder 的吸附逻辑，两处一起改，并在这里补一段为什么。
     * 到那时，功能性的「纸与正文 ≥ 7:1」仍然必须成立（见 [contrastFloorHoldsForExtremeInputs]），
     * 两条是互补的：对比度管**能不能读**，明度区间管**读起来舒不舒服**。
     */
    @Test
    fun paperIsSemanticallyWhiteOrBlackSheet() {
        for ((label, px) in listOf("暖木" to warmWood(), "海报" to poster())) {
            val spec = ImageSkinBuilder.fromImage(px, 120, 120, name = label)
            val lum = luminance(spec.colors.paper)
            val ok = lum >= 0.70 || lum <= 0.10
            assertTrue(
                "[" + label + "] paper 应落在明确的浅纸(≥0.70)或深纸(≤0.10)，" +
                    "当前亮度 " + fmt(lum) + " = " + SkinColorMath.toHex(spec.colors.paper) +
                    "。面积最大的色簇不等于背景色。",
                ok
            )
        }
    }

    @Test
    fun warmWoodImageShouldNotProduceGreenPaper() {
        // 更具体的语义：这张图的主调是"木 + 光"，不该变成绿色系纸底。
        val spec = ImageSkinBuilder.fromImage(warmWood(), 120, 120, name = "暖木书房")
        val hsl = SkinColorMath.toHsl(spec.colors.paper)
        // 绿相区 90..170，且饱和度高，说明纸底被绿植带跑了
        val isGreen = hsl.h in 90.0..170.0 && hsl.s > 0.15
        assertTrue(
            "暖木照片不该产出绿色纸底，当前 h=" + fmt(hsl.h) + " s=" + fmt(hsl.s) +
                " = " + SkinColorMath.toHex(spec.colors.paper),
            !isGreen
        )
    }

    // ── 门禁 2：正文字色必须是中性色 ────────────────────────────────

    @Test
    fun bodyInkMustBeNeutralNotSaturated() {
        val spec = ImageSkinBuilder.fromImage(poster(), 120, 120, name = "高饱和海报")
        val inkSat = saturation(spec.colors.ink)
        assertTrue(
            "正文字色必须是中性色（饱和度 ≤ 0.20），当前 " + fmt(inkSat) +
                " = " + SkinColorMath.toHex(spec.colors.ink) +
                "。高饱和色当正文，对比度再高也是错的。",
            inkSat <= 0.20
        )
    }

    @Test
    fun secondaryInkMustAlsoBeNeutral() {
        val spec = ImageSkinBuilder.fromImage(poster(), 120, 120, name = "高饱和海报")
        assertTrue(
            "次级文字色同样必须是中性色（≤0.25），当前 " + fmt(saturation(spec.colors.inkSoft)),
            saturation(spec.colors.inkSoft) <= 0.25
        )
    }

    // ── 门禁 3：强调色应该保留图片的色相特征 ────────────────────────

    @Test
    fun accentShouldCarryTheImagesCharacter() {
        val px = pixels(120, 120) { x, _ ->
            if (x < 40) 0xFFE0218A.toInt() else 0xFF1A1A1A.toInt()
        }
        val spec = ImageSkinBuilder.fromImage(px, 120, 120, name = "品红海报")
        assertTrue(
            "强调色应保留图片的色相特征（饱和度 ≥ 0.25），当前 " + fmt(saturation(spec.colors.accent)) +
                " = " + SkinColorMath.toHex(spec.colors.accent),
            saturation(spec.colors.accent) >= 0.25
        )
    }

    // ── 门禁 4：无论输入什么图，硬性对比度门槛都不能破 ──────────────

    @Test
    fun contrastFloorHoldsForExtremeInputs() {
        val inputs = listOf(
            "全白" to pixels(40, 40) { _, _ -> 0xFFFFFFFF.toInt() },
            "全黑" to pixels(40, 40) { _, _ -> 0xFF000000.toInt() },
            "中灰" to pixels(40, 40) { _, _ -> 0xFF808080.toInt() },
            "纯红" to pixels(40, 40) { _, _ -> 0xFFFF0000.toInt() },
            "纯青" to pixels(40, 40) { _, _ -> 0xFF00FFFF.toInt() }
        )
        for ((label, px) in inputs) {
            val spec: SkinSpec = ImageSkinBuilder.fromImage(px, 40, 40, name = label)
            val inkPaper = SkinColorMath.contrastRatio(spec.colors.ink, spec.colors.paper)
            assertTrue("[" + label + "] ink/paper 对比度应 ≥ 7:1，实际 " + fmt(inkPaper), inkPaper >= 7.0)
            val softPaper = SkinColorMath.contrastRatio(spec.colors.inkSoft, spec.colors.paper)
            assertTrue("[" + label + "] inkSoft/paper 对比度应 ≥ 4.5:1，实际 " + fmt(softPaper), softPaper >= 4.5)
            val onAccent = SkinColorMath.contrastRatio(spec.colors.onAccent, spec.colors.accent)
            assertTrue("[" + label + "] onAccent/accent 对比度应 ≥ 4.5:1，实际 " + fmt(onAccent), onAccent >= 4.5)
        }
    }

    // ── 门禁 5：确定性（同一张图两次推导必须完全一致）────────────────

    @Test
    fun sameImageProducesIdenticalSkin() {
        val a = ImageSkinBuilder.fromImage(warmWood(), 120, 120, name = "暖木书房")
        val b = ImageSkinBuilder.fromImage(warmWood(), 120, 120, name = "暖木书房")
        assertTrue("同一张图两次推导必须产出完全相同的 SkinSpec", a == b)
    }
}
