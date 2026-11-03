package com.linguareader.app.uirender

/**
 * 渲染测试用的合成图工具（:app 侧副本）。
 *
 * `:shared` 里也有一个同名的 `TestImages`，但它在 `:shared` 的 **test** 源集里，
 * 而 test 源集不参与跨模块发布 —— `:app` 的测试编译期根本看不到它。
 * 所以这里复制一份最小实现（十行），而不是把 `:shared` 的测试代码打成 fixture。
 */
internal object RenderTestImages {

    /** 逐像素造图；[colorAt] 收到 (x, y)，返回 ARGB Int。 */
    fun synthetic(w: Int, h: Int, colorAt: (x: Int, y: Int) -> Int): IntArray =
        IntArray(w * h) { i -> colorAt(i % w, i / w) }

    /** 纯色图。 */
    fun solid(w: Int, h: Int, argb: Int): IntArray = IntArray(w * h) { argb }
}
