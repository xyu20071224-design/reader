package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **组件能力 ↔ 文档 一致性守卫**（P11）。
 *
 * ## 守的是什么
 *
 * 2026-11-04 发生过两次「判据过期误导后来人」：
 * 1. `19cd2a4` 写「窄横向槽位 ❌ 不换」——当时正确（组件还没有布局变体），
 *    但 `01461c0` 加了 `TextButtonLayout.INLINE` 之后，它变成**误导**：
 *    会把后来人挡在一件现在完全能做的事外面；
 * 2. 同一轮里 `VocabularyScreen` 的注释写着「等组件层补出 icon 版再换」，
 *    而组件早就有 `icon` 了。
 *
 * **共同点：判据/注释不像代码，它没有测试。**代码过期了编译器会喊，
 * 文档过期了只会静静地误导下一个人。
 *
 * ## 这个测试怎么守
 *
 * 它把「组件确实具备某能力」与「文档里是否还写着『等它实现』」连起来：
 * 如果源码里 `LinguaTextButton` 有 `layout` 参数，那手册与源码注释里就**不该还留着
 * 『不能换』『等补出变体』这类结论**。
 *
 * 判据刻意写得**窄**：只查几条已知会互相影响的具体表述，
 * 不做「NLP 级」模糊匹配——那样的测试会因为误报被关掉，等于没有。
 */
class ComponentCapabilityDocGuardTest {

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("应当存在：$path", f.isFile)
        return f.readText()
    }

    /** 组件源码（相对 :app 模块根）。 */
    private val buttonsKt get() = read("src/main/java/com/linguareader/app/components/Buttons.kt")

    /** 迁移手册（相对 :app 模块根的两级上级 = 仓库根）。 */
    private val playbook get() = read("../../手册-P3屏幕迁移.md")

    /**
     * `LinguaTextButton` 已具备 `layout` 变体时，手册不能再说窄横向槽位「不能换」。
     *
     * 反向也成立：如果哪天真把 `layout` 删了，这条测试会提醒「手册需要改回来」。
     */
    @Test
    fun playbookMatchesActualButtonCapabilities() {
        val src = buttonsKt
        val hasLayout = src.contains("layout: TextButtonLayout")
        val hasTone = src.contains("tone: TextButtonTone")
        val hasIcon = src.contains("icon: ImageVector?")

        val doc = playbook

        if (hasLayout) {
            assertTrue(
                "组件已有 layout 变体，手册不该再说窄横向槽位『不换』。" +
                    "若确实要写例外，请在手册里写明该例外，并同步更新本测试。",
                !doc.contains("窄横向槽位** | ❌")
            )
            assertTrue(
                "组件已有 layout 变体，手册应当提到 TextButtonLayout.INLINE。",
                doc.contains("TextButtonLayout.INLINE")
            )
        }

        if (hasTone) {
            assertTrue(
                "组件已有 tone 参数，手册必须提到 TextButtonTone.DANGER。",
                doc.contains("TextButtonTone.DANGER")
            )
        }

        if (hasIcon) {
            assertTrue(
                "组件已有 icon 参数，手册若还写「只能纯文字」就是过期信息。",
                !doc.contains("components/ 目前只有纯文字版")
            )
        }
    }

    /**
     * **反向扫描**：全 main 源树里不能留「组件层缺口」类注释，若那个缺口已补齐。
     *
     * ## 为什么这是另一个方向的 grep（2026-11-04 的实证）
     *
     * 正向 grep 是「关于 LinguaTextButton 有什么说明」——搜组件名就能找到。
     * 但**缺口注释里往往不写组件名**，写的是「组件层还没有…」「等组件补」「目前只有纯文字版」。
     * 搜 `LinguaTextButton` 一个都抓不到。
     *
     * 所以这里搜的是**句式**而不是名字。两种 grep 方向不同，都要做。
     *
     * ## 判据为什么保持窄
     *
     * 只认几种**确定表达「缺能力」**的句式。宁可漏（靠 code review 补），
     * 不要因为宽泛匹配误报而被关掉 —— 一个被关掉的守卫等于没有。
     */
    @Test
    fun noStaleCapabilityGapCommentsInMainSources() {
        val src = buttonsKt
        val hasLayout = src.contains("layout: TextButtonLayout")
        val hasIcon = src.contains("icon: ImageVector?")
        if (!(hasLayout && hasIcon)) return // 能力还没补齐时不适用

        // 这些句式一旦出现，几乎一定是在说「组件缺某能力、先用本地实现兜着」。
        val gapPhrases = listOf(
            "等组件层补出",
            "等组件补",
            "组件层还没有",
            "组件层只有纯文字版",
            "目前只有纯文字版",
            "没有『图标+文字』原语",
            "最值得下一轮补"
        )

        val root = File("src/main/java/com/linguareader/app")
        assertTrue("源码目录不存在：${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    val isComment = line.trimStart().startsWith("//") ||
                        line.trimStart().startsWith("*") ||
                        line.trimStart().startsWith("/*")
                    if (!isComment) return@forEachIndexed
                    // 排除两类**不是缺口声明**的行：
                    //  1) 在同一行内就否定了缺口（「而不是等组件补能力」）；
                    //  2) 说明这条理由已过时（「~~…~~ ← 该理由已过时」）。
                    // 后者是修正后的正确写法 —— 正是我们鼓励的，不能反被判红。
                    val negatedInLine = line.contains("而不是") || line.contains("不是等") ||
                        line.contains("已过时") || line.contains("已存在") ||
                        line.contains("已补齐") || line.contains("不再成立") ||
                        line.contains("~~")
                    if (negatedInLine) return@forEachIndexed
                    gapPhrases.firstOrNull { line.contains(it) }?.let { phrase ->
                        offenders += "${file.name}:${idx + 1} 含缺口句式「$phrase」→ $line"
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine("以下注释仍在描述「组件层缺口」，但那个缺口已补齐：")
                offenders.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("修法：要么删掉这段注释，要么把它改成「真实为什么不换」（如几何冲突、")
                appendLine("结构不同），而不是「等组件实现」。后者的寿命极短，会静静误导下一个人。")
            },
            offenders.isEmpty()
        )
    }
}