package com.linguareader.shared.ui.skin

/**
 * 测试用的**合成图**工具。
 *
 * 用像素数组而不是真 PNG：断言可以写成「红色必须占 50%」这种硬话，
 * 也不需要在测试期引入图像解码依赖（`:shared` 的依赖规则很紧）。
 */
object TestImages {

    /** 纯色图。 */
    fun solid(w: Int, h: Int, argb: Int): IntArray = IntArray(w * h) { argb }

    /** 逐像素造图；[colorAt] 收到 (x, y)。 */
    fun synthetic(w: Int, h: Int, colorAt: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> colorAt(i % w, i / w) }

    /** 上下两条横带，各占一半。 */
    fun twoBands(w: Int, h: Int, top: Int, bottom: Int): IntArray =
        synthetic(w, h) { _, y -> if (y < h / 2) top else bottom }

    /** 由整数通道构造 ARGB。 */
    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
