package com.linguareader.app.skin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **皮肤运行时生效链路守卫**（P19）。
 *
 * ## 守的是什么
 *
 * P9 接通了换肤管线（持久化 + 图片导入 + 用户入口），但 2026-11-04 复核发现**链路只通了一半**：
 *
 * | 环节 | 状态 |
 * | --- | --- |
 * | `SkinStore` 持久化 | ✅ |
 * | `ImageSkinImporter` Uri 解码 | ✅ |
 * | `SkinPickerSection` 用户入口 | ✅ 能点 |
 * | **选中后通知外壳** | ❌ **回调是默认空实现 `{}`** |
 * | **外壳更新 customSkin 状态** | ❌ **`customSkin` 只在初始化时读一次** |
 *
 * 后果：**用户能选皮肤、能选图、能生成皮肤，界面颜色一点不变。**
 * 这是「接了一半」比「完全没接」更难发现的形态——入口在、有反馈、有持久化，
 * 只有「立刻生效」这一段断了，而它只有在真机上点一下才会暴露。
 *
 * ## 判据（三段链，缺一段就是断）
 *
 * 1. `MainActivity` 的 `customSkin` 必须**被赋值**（不是只有 `by remember { }` 初始化）；
 * 2. `ShelfAppearanceSheet` 必须调用 `onSkinChange(...)`；
 * 3. `BookshelfScreen` 必须把 `onSkinChanged` 继续往上传。
 */
class SkinRuntimeEffectGuardTest {

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("应当存在：$path", f.isFile)
        return f.readText()
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                // 行注释
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                // 块注释
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                // ⚠️ 字符串字面量必须整段跳过，否则 "image/*" 里的 /* 会被当成块注释开头，
                // 从那里一直吃到文件末尾的 */（或干脆吃到结尾），把中间的真实代码全删掉。
                // 第一版漏了这个分支，导致 onSkinChange(null) 明明在源码里却检不到。
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src[i]).append(src[i + 1])
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') { i++; break }
                        i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private val mainActivity get() = stripComments(read("src/main/java/com/linguareader/app/MainActivity.kt"))
    private val appearanceSheet get() = stripComments(read("src/main/java/com/linguareader/app/ShelfAppearanceSheet.kt"))
    private val bookshelf get() = stripComments(read("src/main/java/com/linguareader/app/BookshelfScreen.kt"))

    @Test
    fun mainActivityAssignsCustomSkinFromCallback() {
        // 「var customSkin by remember { ... }」只是初始化；必须有赋值语句才算接线。
        val src = mainActivity
        assertTrue(
            "MainActivity 里 `customSkin` 必须被二次赋值（皮肤选中后立刻生效）。" +
                "如果只有 `var customSkin by remember { mutableStateOf(...) }`，" +
                "那用户选了皮肤界面不会变 —— 这正是 P19 修掉的缺陷。",
            Regex("customSkin\\s*=\\s*it").containsMatchIn(src) ||
                Regex("customSkin\\s*=\\s*[a-zA-Z]").containsMatchIn(src)
        )
    }

    @Test
    fun appearanceSheetInvokesOnSkinChange() {
        val src = appearanceSheet
        assertTrue(
            "ShelfAppearanceSheet 必须调用 `onSkinChange(...)`，否则选中皮肤后外壳收不到通知。",
            src.contains("onSkinChange(")
        )
        // 三条具体路径都要通知外壳，缺一条就是「某个入口选了皮肤但界面不变」：
        // 1) 选图片皮肤（skinLauncher 回调里）
        // 2) 选内置皮肤
        // 3) 移除自定义皮肤
        //
        // 这里**不用「数出现次数」**——本文件自己的注释与断言文案里就有这些字符串，
        // 数出现次数会被自己命中（第一版就是这样得出 3 处的假绿）。
        // 改成查三种**调用实参**的具体形态，每一种都对应一条真实路径。
        val delegatesImageSkin = src.contains("onSkinChange(spec)")
        val delegatesBuiltIn = src.contains("onSkinChange(null)")
        assertTrue(
            "缺少「选图片皮肤后通知外壳」——用户选了图，界面不会变色。",
            delegatesImageSkin
        )
        assertTrue(
            "缺少「选内置皮肤 / 移除自定义皮肤后通知外壳」——用户点了，界面不会变色。",
            delegatesBuiltIn
        )
    }

    @Test
    fun bookshelfForwardsSkinChangeUpward() {
        val src = bookshelf
        assertTrue(
            "BookshelfScreen 必须把 `onSkinChanged` 继续传给 ShelfAppearanceSheet，" +
                "否则弹层里的选择到不了外壳。",
            src.contains("onSkinChange = onSkinChanged")
        )
    }
}