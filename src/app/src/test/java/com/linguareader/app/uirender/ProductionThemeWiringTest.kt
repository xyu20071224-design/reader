package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **生产接线必须真的下发设计 token** —— 与 `ScaffoldInsetsContractTest` 同款的源码契约。
 *
 * ## 这条测试挡的是什么
 *
 * `LocalDesignTokens` 是 `staticCompositionLocalOf`，有默认值：
 * `SkinRegistry.toDesignTokens(SkinRegistry.default)` —— 也就是**恒日间**。
 * 默认值让「忘了下发」不会崩溃、不会报错、甚至看起来一切正常：
 * 日间模式下组件拿到的恰好就是对的颜色。
 *
 * 2026-11-04 实测到的真实缺陷：`MainActivity` 只下发了 `LocalLinguaPalette`，
 * 没下发 `LocalDesignTokens`。于是所有已迁移的屏（6 个弹层 + 生词本 + 复习屏）
 * 在**深色外壳**下取到的是日间 token：近黑字落在近黑底上、弹层纸白刺眼。
 * 探针实证：只 provide `DarkLinguaPalette` 渲染 `LinguaScaffold`，
 * 角落像素是 #F7F3EA（日间纸色）而不是 `DarkLinguaPalette.paper` = #171717。
 *
 * ## 为什么渲染台与单测都没抓到
 *
 * 所有 uirender 夹具（`ComponentRenderTest` / `ImageSkinRenderTest` / 各屏 RenderTest）
 * 都是**自己显式** `CompositionLocalProvider(LocalDesignTokens provides tokens)` 的——
 * 它们测的是组件读 token 读得对不对，**测不到生产代码有没有把 token 发下来**。
 * 渲染台默认也只跑日间。所以只能靠读源码守。
 *
 * 这条测试证明的是「接线存在」；**观感是否真的正确仍需真机看**（深色模式开一次）。
 */
class ProductionThemeWiringTest {

    private val mainActivity = File("src/main/java/com/linguareader/app/MainActivity.kt")

    private fun code(file: File): String {
        val raw = file.readText()
        val noBlock = raw.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock.replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun mainActivityProvidesDesignTokens() {
        assertTrue("找不到 " + mainActivity.absolutePath, mainActivity.isFile)
        val src = code(mainActivity)
        assertTrue(
            "MainActivity 必须下发 LocalDesignTokens —— 否则所有已迁移的屏在深色外壳下" +
                "会取到恒日间的默认 token（近黑字落近黑底、弹层纸白刺眼）。" +
                "见本类注释。",
            src.contains("LocalDesignTokens provides")
        )
        assertTrue(
            "MainActivity 也必须继续下发 LocalLinguaPalette（旧 API 兼容层依赖它）。",
            src.contains("LocalLinguaPalette provides")
        )
        assertTrue(
            "token 的明暗必须跟随调色板同一条判定（palette.isDark / chromeIsDark），" +
                "不能另写一套日夜逻辑，否则会出现「外壳夜里、组件日间」的第二类错配。",
            src.contains("SkinRegistry.toDesignTokens") &&
                src.contains("palette.isDark")
        )
    }

    @Test
    fun tokenDefaultIsStillLight() {
        // 反向护栏：默认值恒日间这件事本身是设计（LocalDesignTokens 的 fallback），
        // 不是缺陷；缺陷是「依赖了默认值」。这里把它写进断言，避免有人为了绕开
        // 接线问题去改默认值，让深色模式在别处悄悄变样。
        val registry = File("src/main/java/com/linguareader/app/skin/SkinRegistry.kt")
        assertTrue("找不到 " + registry.absolutePath, registry.isFile)
        assertTrue(
            "LocalDesignTokens 的默认值应保持 SkinRegistry.default（日间）；" +
                "深色必须靠 provide 下发，不能靠改默认值。",
            code(registry).contains("staticCompositionLocalOf { SkinRegistry.toDesignTokens(SkinRegistry.default) }")
        )
    }
}
