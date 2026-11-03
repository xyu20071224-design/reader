package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **设计 token 纪律**（`方案-前端重构.md` §1 硬约束 1）：
 * `components/` 与 `skin/` 以外的地方不得出现裸 `Color(0x...)`；`components/` 里
 * 不得出现裸 `RoundedCornerShape(数字)` 与裸字号。
 *
 * 为什么用源码扫描而不是靠自觉：这条约束是本方案唯一「由构造保证」的部分，
 * 一旦有人图快在组件里写死一个 `Color(0xFF8D5535)`，换肤就会在那处失效，
 * 而且**肉眼看不出来**（默认皮肤下颜色恰好是对的）。所以必须机械化。
 */
class DesignTokenDisciplineTest {

    /** :app 主源码根。测试运行在 :app 模块目录下（build/test-results 的上级）。 */
    private val appSourceRoot = File("src/main/java/com/linguareader/app")

    private fun sourcesUnder(relative: String): List<File> {
        val dir = File(appSourceRoot, relative)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** 去掉注释与字符串字面量，避免注释里的示例代码造成误报。 */
    private fun code(file: File): String {
        val raw = file.readText()
        val noBlock = raw.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        val noLine = noBlock.replace(Regex("//[^\\n]*"), "")
        // 字符串字面量（含三引号）
        return noLine.replace(Regex("\"\"\"[\\s\\S]*?\"\"\"|\"[^\"\\n]*\""), "\"\"")
    }

    @Test
    fun sourceRootExists() {
        assertTrue(
            "expected app source root at " + appSourceRoot.absolutePath + ", got " + appSourceRoot.exists(),
            appSourceRoot.isDirectory
        )
    }

    @Test
    fun componentsHaveNoRawColors() {
        val offenders = mutableListOf<String>()
        val pattern = Regex("\\bColor\\(\\s*0x")
        for (file in sourcesUnder("components")) {
            code(file).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }
        assertTrue(
            "components/ 不得出现裸 Color(0x...) —— 一律取 LocalDesignTokens.current.colors：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun componentsHaveNoRawRoundedCornerShapes() {
        val offenders = mutableListOf<String>()
        // 只抓「数字 dp 直接进形状」，允许 RoundedCornerShape(t.radii.x) 这类 token 用法。
        val pattern = Regex("RoundedCornerShape\\(\\s*(\\d+(\\.\\d+)?)\\s*\\.\\s*dp")
        for (file in sourcesUnder("components")) {
            code(file).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }
        assertTrue(
            "components/ 不得出现裸 RoundedCornerShape(数字.dp) —— 一律取 tokens.radii：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun componentsHaveNoRawFontSizes() {
        val offenders = mutableListOf<String>()
        // 裸字号：fontSize = 16.sp 这类；字号必须来自 tokens.typography。
        val pattern = Regex("fontSize\\s*=\\s*\\d+(\\.\\d+)?\\.\\s*sp")
        for (file in sourcesUnder("components")) {
            code(file).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }
        assertTrue(
            "components/ 不得出现裸字号 —— 一律取 tokens.typography：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun componentsDoNotReadMaterialThemeColorScheme() {
        // 硬约束 2：components/ 不得直接读 MaterialTheme.colorScheme。
        val offenders = mutableListOf<String>()
        val pattern = Regex("MaterialTheme\\s*\\.\\s*colorScheme")
        for (file in sourcesUnder("components")) {
            code(file).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }
        assertTrue(
            "components/ 不得读 MaterialTheme.colorScheme —— 一律取 LocalDesignTokens：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun componentsUseDesignTokens() {
        // 反向断言：这些组件确实在用 token，而不是「什么都没写所以扫描通过」。
        val files = sourcesUnder("components")
        assertTrue("expected the primitive components to exist", files.size >= 5)
        val withTokens = files.filter { code(it).contains("LocalDesignTokens.current") }
        assertTrue(
            "每个组件都应当从 LocalDesignTokens 取值，实际只有 " + withTokens.size + "/" + files.size,
            withTokens.size >= 5
        )
    }

    @Test
    fun skinPackageIsTheOnlyPlaceWithRawColors() {
        // skin/ 是允许定义原始值的地方（DesignTokens 的默认值、字重映射表），
        // 但它也不该满地都是裸色 —— 默认色值全部来自 :shared 的 SkinSpec。
        val offenders = mutableListOf<String>()
        val pattern = Regex("\\bColor\\(\\s*0x")
        for (file in sourcesUnder("skin")) {
            code(file).lineSequence().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    offenders += file.name + ":" + (i + 1) + "  " + line.trim()
                }
            }
        }
        assertTrue(
            "skin/ 只应从 :shared 的 SkinSpec 派生颜色，不得硬编码：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
