package com.linguareader.app.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 页码输入的解析（0-based 换算 + 越界拒绝）。
 *
 * ## 为什么这个测试住在 `reader` 包里
 *
 * 它测的 `parsePageInput` 在 [ReaderPageJumpDialog] 那个文件里，包名是
 * `com.linguareader.app.reader`。测试**必须同包**，否则：
 *
 * - 跑 `testDebugUnitTest --tests 'com.linguareader.app.reader.*'`（阅读页回归网）
 *   时**根本不会执行它**；
 * - 那两个用例是 `parsePageInput` 的唯一覆盖，等于回归网漏了两个洞。
 *
 * 2026-11-04 阅读页拆解时正是这样漏掉过一次：函数被搬进 `reader` 包，
 * 测试文件留在 `com.linguareader.app`，定向测试的用例数从「以为的 200」
 * 变成真实的 198 —— 差的 2 个就是这里。
 *
 * **规矩**：搬产品代码时，把它的测试一起搬，别让包名对不上。
 */
class ReaderPageJumpTest {
    @Test
    fun validPageParsesToZeroBasedIndex() {
        assertEquals(0, parsePageInput("1", 5))
        assertEquals(4, parsePageInput("5", 5))
        assertEquals(2, parsePageInput("3", 3))
    }

    @Test
    fun outOfRangeOrNonNumericInputIsRejected() {
        assertNull(parsePageInput("0", 5))
        assertNull(parsePageInput("6", 5))
        assertNull(parsePageInput("abc", 5))
        assertNull(parsePageInput("", 5))
        assertNull(parsePageInput("2", 0))
    }
}
