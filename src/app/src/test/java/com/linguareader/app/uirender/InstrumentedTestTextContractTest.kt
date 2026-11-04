package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **仪器测试的文案契约守卫**（P24）。
 *
 * ## 守的是什么
 *
 * 本项目有 4 个 UI 交互仪器测试（`BookshelfSmokeTest` / `LaunchPromptUiTest` /
 * `ReaderAcceptanceTest` / `ReviewUiTest`），它们**按中文文案定位节点**：
 *
 * ```kotlin
 * composeRule.onNodeWithText("从一本英文书开始").assertIsDisplayed()
 * ```
 *
 * 这些测试需要设备才能跑，而**当前无设备**（`HypervisorPresent=False`、`adb devices` 为空），
 * 所以它们一次都没跑过。本轮重构替换了大量组件（`SectionHeader` → 手写、
 * `SettingRow` 加动画、9 个死组件删除），**组件替换可能让某条文案不再被渲染** ——
 * 而这在编译期完全看不出来：`strings.xml` 里 key 还在，只是没人显示了。
 *
 * ## 这条测试怎么守
 *
 * 把「仪器测试断言的中文文案」与「`strings.xml` 里存在的文案」做交集检查。
 * 如果某条文案从资源里消失了，这条会红 —— 那是比设备上跑失败更早的信号。
 *
 * ## 它覆盖不到什么（重要）
 *
 * **它只能证明「文案还在资源里」，不能证明「它被渲染出来了」。**
 * 组件替换导致的「文案不再显示」它抓不到 —— 那仍然只能靠设备。
 * 这条测试的价值是把「资源缺失」这类**最便宜发现**的错误提前，不是替代设备验证。
 */
class InstrumentedTestTextContractTest {

    private val androidTestDir = File("src/androidTest/java/com/linguareader/app")
    private val stringsZh = File("src/main/res/values/strings.xml")

    /** 从 androidTest 源码里抽出所有 `onNodeWithText("...")` 的字面量。 */
    private fun assertedTexts(): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        androidTestDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val texts = Regex("onNodeWithText\\(\\s*\"([^\"]+)\"\\s*\\)")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .toList()
                if (texts.isNotEmpty()) result[file.name] = texts
            }
        return result
    }

    @Test
    fun everyTextAssertedByInstrumentedTestsStillExistsInResources() {
        assertTrue("androidTest 目录应当存在", androidTestDir.isDirectory)
        assertTrue("strings.xml 应当存在", stringsZh.isFile)

        val asserted = assertedTexts()
        assertTrue("应当能从 androidTest 里抽到文案断言（抽到 0 组说明正则失效了）", asserted.isNotEmpty())

        val resources = stringsZh.readText()
        val missing = asserted.flatMap { (file, texts) ->
            texts.filter { !resourceCanProduce(it, resources) }.map { "$file: \"$it\"" }
        }.distinct()

        assertTrue(
            buildString {
                appendLine("以下仪器测试断言的中文文案在 strings.xml 里找不到：")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("这些测试需要设备才能跑（当前无设备），所以文案缺失不会在 CI 暴露。")
                appendLine("修法：要么恢复该文案，要么同步改仪器测试的断言。")
            },
            missing.isEmpty()
        )
    }

    /**
     * `strings.xml` 能否**产出**这段文案（考虑格式化占位符）。
     *
     * 第一版直接 `resources.contains(text)`，于是 4 条全被误报：
     *
     * | 断言文本 | 实际资源 |
     * | --- | --- |
     * | 版本更新 1.2.0 | `版本更新 %1$s` |
     * | 复习时约剩 85% 记忆 | `复习时约剩 %1$d%% 记忆` |
     * | 有 3 个词可快速复习 | `<plurals 有 %1$d 个词可快速复习>` |
     *
     * 这些是**格式化字符串与 plurals**，字面量永远不可能直接包含它们。
     * 所以判据改成：把文本里的**数字与百分号**当作占位符通配，再看资源里有没有同形串。
     *
     * 另有一条完全放行的情形：**测试自造数据**（如 `Acceptance Book`），
     * 它本来就不该出现在资源里 —— 用 [testFabricatedTexts] 显式列出。
     */
    private fun resourceCanProduce(text: String, resources: String): Boolean {
        if (resources.contains(text)) return true
        if (testFabricatedTexts.contains(text)) return true

        // 把文本切成「字母/汉字片段」，要求每个片段都能在资源里找到。
        // 数字、空格、标点一律忽略 —— 它们正是格式化占位符的位置。
        //
        // 例："版本更新 1.2.0" → 片段 [版本更新]，资源里有 `版本更新 %1$s` ✓
        //     "复习时约剩 85% 记忆" → 片段 [复习时约剩, 记忆]，资源里有 `复习时约剩 %1$d%% 记忆` ✓
        val fragments = Regex("[\\p{IsHan}\\p{L}]+").findAll(text).map { it.value }.toList()
        if (fragments.isEmpty()) return true
        return fragments.all { fragment -> resources.contains(fragment) }
    }

    /**
     * 仪器测试里**自造的数据**（不该出现在资源里）。
     *
     * 加一条要能说清「这是测试造的数据，不是应用文案」。
     */
    private val testFabricatedTexts: Set<String> = setOf(
        // 测试用例自己创建的示例书名。
        "Acceptance Book"
    )

    /**
     * 反向：确认这条测试**确实扫到了**几个测试文件 ——
     * 防止「目录变化导致扫到 0 个文件」时它假绿。
     */
    @Test
    fun scanCoversTheFourUiInteractionTests() {
        val files = assertedTexts().keys
        for (name in listOf(
            "BookshelfSmokeTest.kt",
            "LaunchPromptUiTest.kt",
            "ReaderAcceptanceTest.kt",
            "ReviewUiTest.kt"
        )) {
            assertTrue(
                "应当从 $name 抽到文案断言。抽不到说明该文件被移走或断言方式变了，" +
                    "这条守卫需要同步更新（否则它会永远绿）。",
                files.contains(name)
            )
        }
    }
}