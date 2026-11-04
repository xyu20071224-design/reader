package com.linguareader.app.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **状态切片接线守卫**（P15）。
 *
 * ## 守的是什么
 *
 * P2 建了五个纯 Kotlin 状态切片（`ShellState` / `BookshelfState` / `ReaderState` /
 * `VocabState` / `SettingsState`）与 `AppUiState.split()` 投影函数，约 800 行、
 * 有反射对账测试（`AppUiStateSplitTest` 保证「每个字段至少落进一个切片，不丢失」）。
 *
 * **但 2026-11-04 核查发现：五个切片在生产代码里零引用。**
 * 屏幕仍然各自吃整个 `AppUiState`；`split()` 只有它自己和测试在调。
 *
 * 这已经是**第四次**同一类缺陷：
 * - P9 图片换肤管线（`ImageSkinBuilder` 生产零调用）
 * - P13 导航层（`AppNavHost` 零调用）
 * - P14 组件原语（8 个零调用）
 * - P15 状态切片（5 个零调用）
 *
 * 共同点：**单元测试全绿，因为测试直接 new / 直接调那些类。**
 * 反射对账测试甚至证明了「投影不丢字段」——但它证明不了「有人在用这个投影」。
 *
 * ## 判据
 *
 * `split()` 必须在生产代码里至少被调用一次，且至少一个切片类型被实际读取。
 */
class StateSliceWiringGuardTest {

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 所有 main 源（已剥注释），用于查「真的调用了」而不是「注释里提到」。 */
    private fun productionSources(): List<String> =
        File("src/main/java/com/linguareader/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "state" }
            .map { stripComments(it.readText()) }
            .toList()

    @Test
    fun splitIsCalledSomewhereInProduction() {
        val sources = productionSources()
        assertTrue("应当能扫到生产源", sources.isNotEmpty())

        val callers = sources.filter { it.contains("state.split()") }
        assertTrue(
            "`AppUiState.split()` 在生产代码里没有被调用。" +
                "P2 建的五切片（ShellState/BookshelfState/ReaderState/VocabState/SettingsState）" +
                "约 800 行，零调用时它们是纯负担，且会误导后来人以为状态已经分层。",
            callers.isNotEmpty()
        )
    }

    /**
     * 至少一个切片要被**读取字段**，而不只是构造出来。
     *
     * 只调 `split()` 然后丢掉结果，和没接一样 —— 这条防的就是那种假接线。
     */
    @Test
    fun atLeastOneSliceFieldIsActuallyRead() {
        val sources = productionSources()
        // 切片字段的访问形态：slices.shell.xxx / slices.reader.xxx 之类。
        val fieldReads = listOf("slices.shell", "slices.bookshelf", "slices.reader", "slices.vocab", "slices.settings")
            .filter { field -> sources.any { it.contains(field) } }

        assertTrue(
            "`split()` 被调用了，但没有任何切片的字段被读取 —— 那是假接线" +
                "（构造出对象立刻丢掉，和没接一样）。",
            fieldReads.isNotEmpty()
        )
    }
}