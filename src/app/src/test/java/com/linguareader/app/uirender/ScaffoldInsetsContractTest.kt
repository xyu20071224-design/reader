package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **外壳必须处理窗口 insets** —— 一条只能靠读源码守住的契约。
 *
 * 背景（2026-11-04 实际差点踩的坑）：项目 "targetSdk = 35"，Android 15+ 强制
 * edge-to-edge。迁移书架屏时若把 M3 "Scaffold"（自带
 * "windowInsets = systemBars"）换成自建 "LinguaScaffold"，而后者不读任何
 * "WindowInsets"，后果是：
 * - 顶栏顶进状态栏，标题被状态栏图标压住；
 * - 列表最后一行伸到手势条底下。
 *
 * **这个缺陷渲染台和单测都测不出来**：Robolectric 里 "WindowInsets" 恒为 0，
 * 出图永远「正常」。所以只能退而求其次，用源码契约守住：
 * 外壳组件必须显式引用 "statusBars" 与 "navigationBars"。
 *
 * 这条测试在有人「为了简化」删掉那两行 insets 时会红，并指向本注释里的原因。
 * 真机验收仍然必须做——它证明的是「代码里有这个意图」，不是「真机上看对了」。
 *
 * ⚠️ 断言用的是 `contains`（纯文本匹配，不做语法分析），所以**注释里也别写带括号的
 * `systemBarsPadding()`**：写进 KDoc 一样会被判为「用了它」。第一次落地时
 * `LinguaScaffold` 的注释里就写了这个词、测试当场误红——要提这个词请不带括号地写。
 */
class ScaffoldInsetsContractTest {

    private val scaffoldFile = File(
        "src/main/java/com/linguareader/app/components/LinguaScaffold.kt"
    )

    @Test
    fun scaffoldHandlesStatusBarAndNavigationBarInsets() {
        assertTrue("找不到 " + scaffoldFile.absolutePath, scaffoldFile.isFile)
        val src = scaffoldFile.readText()

        assertTrue(
            "LinguaScaffold 必须给顶栏加 WindowInsets.statusBars —— 否则 targetSdk 35 的 " +
                "edge-to-edge 下，顶栏会顶进状态栏。见本类注释。",
            src.contains("WindowInsets.statusBars")
        )
        assertTrue(
            "LinguaScaffold 必须给内容区加 WindowInsets.navigationBars —— 否则列表最后一行" +
                "会伸到手势条底下。见本类注释。",
            src.contains("WindowInsets.navigationBars")
        )
        assertTrue(
            "不要用 systemBarsPadding()：它会同时吃左右 insets，横屏/折叠屏下与 " +
                "ReaderScreen 的既有写法不一致。用 statusBars + navigationBars 分开。",
            !src.contains("systemBarsPadding()")
        )
    }

    @Test
    fun bookshelfDoesNotRegressToRawScaffoldWithoutInsets() {
        // 书架是第一个从 M3 Scaffold 迁到 LinguaScaffold 的屏。迁完后如果又有人
        // 手写一层 Surface 外壳，insets 会再次丢失——这条挡的是那件事。
        val bookshelf = File("src/main/java/com/linguareader/app/BookshelfScreen.kt")
        assertTrue("找不到 " + bookshelf.absolutePath, bookshelf.isFile)
        val src = bookshelf.readText()
        assertTrue(
            "书架应使用 LinguaScaffold（它已内建 insets）。若确实改回手写外壳，" +
                "必须同时补 windowInsetsPadding —— 并更新本测试说明原因。",
            src.contains("LinguaScaffold")
        )
    }
}
