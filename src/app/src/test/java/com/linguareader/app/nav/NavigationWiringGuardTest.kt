package com.linguareader.app.nav

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **导航层接线守卫**（P13）。
 *
 * ## 守的是什么
 *
 * 2026-11-04 发现：P2 建好的整套导航层（`Route` / `NavStack` / `OverlayStack` /
 * `AppNavHost` / `NavHostLite` / `OverlayHost`，约 1000 行、20+ 单测）
 * **在生产代码里零调用** —— `MainActivity` 用 `if (currentBook == null)` 一个隐式布尔切屏。
 *
 * 这跟 P9 的图片换肤是**同一类缺陷**：能力建好了、测试全绿、但没人用。
 * 后果是双重的：
 * 1. 用户拿不到这个能力；
 * 2. **「这套设计是否成立」永远无法被验证** —— 单测证明的是「它能工作」，
 *    不是「它在真实 App 里被正确使用」。
 *
 * 这类缺陷**单测抓不到**：测试直接 new 那些类，当然全绿。
 * 抓它需要断言「生产代码里至少有一个人在用它」。
 *
 * ## 判据
 *
 * `MainActivity.kt`（App 的唯一入口 composable 所在）必须引用导航层。
 * 判据刻意只说「有引用」——具体怎么用是设计问题，不该由测试规定。
 */
class NavigationWiringGuardTest {

    private val mainActivity get() = File("src/main/java/com/linguareader/app/MainActivity.kt")

    /**
     * 去掉注释后再判断「是否真的调用了」。
     *
     * **这个函数是必要的，不是洁癖**：第一版守卫直接用 `src.contains("AppNavHost")`，
     * 结果被 MainActivity 里一句解释性的 KDoc 命中了 —— 测试通过，但那个类根本
     * 没有被调用。**注释里提到 ≠ 代码在用**，这和「定义了但没接线」是同一个坑。
     */
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

    @Test
    fun mainActivityUsesTheNavigationLayer() {
        assertTrue("MainActivity.kt 应当存在", mainActivity.isFile)
        val src = stripComments(mainActivity.readText())

        assertTrue(
            "MainActivity 必须在**代码里**（不是注释里）接入导航层（P13）。" +
                "若确实要移除，请先说明替代方案" +
                "（当前 nav 包 1000+ 行、20+ 单测，零调用时它们是纯负担）。",
            src.contains("rememberAppNavState")
        )
        assertTrue(
            "MainActivity 必须至少引用一个 Route（书架或阅读页），否则导航栈是空的。",
            src.contains("Route.Bookshelf") || src.contains("Route.Reader")
        )
    }

    /**
     * 反向：导航层的公开入口不该只剩自己的测试在调。
     *
     * 这条只查 `AppNavHost` —— 它是 P2 设计的「唯一接入点」，
     * 如果连它都没人用，那套壳就是纯库存。
     */
    @Test
    fun appNavHostIsReferencedOutsideItsOwnPackage() {
        val root = File("src/main/java/com/linguareader/app")
        val outside = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "nav" }
            .filter { stripComments(it.readText()).contains("AppNavHost(") }
            .toList()

        assertTrue(
            "`AppNavHost` 是 P2 设计的唯一接入点，但当前没有任何 nav 包外的文件引用它。" +
                "要么把它接上（推荐），要么删掉它 —— 留着不用的壳会持续误导后来人" +
                "以为导航已经接通。",
            outside.isNotEmpty()
        )
    }
}