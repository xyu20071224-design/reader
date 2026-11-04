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
     * 源码注释里不能留「等 X 实现后再换」这类表述，除非 X 确实还没实现。
     *
     * 只查已出现过实例的那一处（`VocabularyScreen` 的旧注释），
     * 因为这类句式一旦扩散就没法用文本匹配可靠判断。
     */
    @Test
    fun sourceCommentsDoNotAwaitCapabilitiesThatAlreadyExist() {
        val src = buttonsKt
        if (!src.contains("layout: TextButtonLayout")) return // 组件还没有该能力时不适用

        val vocab = read("src/main/java/com/linguareader/app/VocabularyScreen.kt")
        assertTrue(
            "VocabularyScreen 不该再写「等组件层补出 icon 版」——LinguaTextButton 既有 icon 也有 layout。" +
                "它现在应当在 CALL SITE 用 layout = INLINE 并说明理由。",
            !vocab.contains("等组件层补出 icon 版")
        )
        assertTrue(
            "VocabularyScreen 顶栏应当用 layout = TextButtonLayout.INLINE。",
            vocab.contains("TextButtonLayout.INLINE")
        )
    }
}