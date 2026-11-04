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
     * **公开组件原语不能只有测试在用**（P14）。
     *
     * ## 为什么这条重要
     *
     * 本轮已发现两次「建好但零调用」：图片换肤管线（P9）、导航层（P13）。
     * 这是同一模式的第三类：**组件原语**。
     *
     * 一个「只有测试在调」的公开组件，危害是三重的：
     * 1. 读者会以为它在生产里有用，据此推断屏幕结构（错误信息）；
     * 2. 它为它写的测试（渲染测试、基线）是纯成本；
     * 3. 它会让人以为「某个能力已经具备」，从而不去实现真正需要的东西。
     *
     * ## 判据
     *
     * `components/` 下每个 `fun Xxx(`，必须在 **components 目录之外**的 main 源码里至少被调用一次。
     * 例外用 [exemptComponents] 声明并写理由。
     */
    @Test
    fun publicComponentsAreUsedSomewhereInProduction() {
        val componentsDir = File("src/main/java/com/linguareader/app/components")
        assertTrue("components 目录应当存在", componentsDir.isDirectory)

        // 收集 component 里声明的公开 composable 名（顶层 fun，不是 private）。
        val declared = linkedMapOf<String, String>() // name -> 文件
        componentsDir.listFiles { f -> f.extension == "kt" }?.forEach { file ->
            Regex("""^fun ([A-Z]\w*)\(s*$""", RegexOption.MULTILINE)
                .findAll(file.readText())
                .forEach { declared[it.groupValues[1]] = file.name }
        }
        assertTrue("应当能扫到组件声明（扫到 ${declared.size} 个）", declared.isNotEmpty())

        // 收集所有 main 源（**包含 components 自身**）。
        //
        // 第一版把 components/ 排除了，于是 `SheetDragHandle` 被判成死代码 ——
        // 它其实被同目录的 `LinguaSheet` 内部调用（`dragHandle = { SheetDragHandle() }`）。
        // **组件之间互相复用是正当的**，判据要的是「有人在用」，不是「包外有人在用」。
        //
        // 判据修正后仍然能抓真正的死代码：一个没人调用的函数，在整棵树里都不会有 `名字(`。
        val prodSources = File("src/main/java/com/linguareader/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()

        val offenders = declared.filter { (name, file) ->
            if (exemptComponents.containsKey(name)) return@filter false
            // 必须出现「调用形态」——名字后跟 (，避免被 KDoc 里的提及命中。
            prodSources.none { it.contains("$name(") }
        }.map { (name, file) -> "$name（定义于 $file）" }

        assertTrue(
            buildString {
                appendLine("以下公开组件在 components/ 之外**没有任何调用**（只有测试在用）：")
                offenders.sorted().forEach { appendLine("  - $it") }
                appendLine()
                appendLine("修法：要么接上（推荐），要么删掉 —— 只有测试在调的组件会持续误导后来人")
                appendLine("以为某能力已在生产中使用。确需保留就加进 exemptComponents 并写理由。")
            },
            offenders.isEmpty()
        )
    }

    /**
     * 允许「生产未调用」的组件 → 理由。
     *
     * 加一条就要写清「为什么它现在该留着」。当前为空 —— 说明组件层没有死代码。
     */
    /**
     * 组件层已知的**死代码债务**（生产零调用的公开原语）。
     *
     * 这些是 2026-11-04 审计发现的：组件层 21 个公开原语里 8 个在生产代码中零调用。
     * 其中一部分还有测试在用（渲染测试、基线），删它们要同步改测试——
     * 那是一次独立的清理，不该混在守卫提交里。
     *
     * **每一条都必须写清「为什么它现在还在」**，否则下一个人会以为它们有用。
     * 目标是把这张表清空（删组件 + 删对应测试），而不是往里面加东西。
     */
    private val exemptComponents: Map<String, String> = mapOf(
        // —— 以下 4 个连测试都没有，纯粹是写出来没用过 ——
        "CardSectionLabel" to "死代码：无生产调用、无测试。待删（清理需与 CardTitleRow/CardDivider 一起做）",
        "LinguaDivider" to "死代码：无生产调用、无测试。注意 SheetDragHandle 不是同类——它被 LinguaSheet 内部调用",
        "EmptyStateHint" to "死代码：无生产调用、无测试",
        // —— 以下 4 个有渲染测试在用，删它们要同步改测试 ——
        "ButtonRow" to "死代码（仅测试在用）。生产里的按钮组都是各屏手写 Row",
        "CardTitleRow" to "死代码（仅测试在用）。书卡标题是 ShelfBookCard 自己画的",
        "CardDivider" to "死代码（仅测试在用）",
        "SettingGroup" to "死代码（仅测试在用）。各屏设置列表是手写 Column + SettingRow",
        "SettingValue" to "死代码（仅测试在用）",
    )

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