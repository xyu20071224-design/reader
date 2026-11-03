package com.linguareader.app.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.linguareader.app.LinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec

/**
 * **旧 API 的兼容适配层**。
 *
 * 背景：`ThemeColors.kt` 里的 `LinguaPalette`（15 个语义色）在 40+ 个文件里被引用
 * （`InkSoft` 单独就有 122 处）。P3 逐屏迁移期间不可能一次全改完，所以旧名字必须继续
 * 可用，但**不能再是第二份真值** —— 否则「皮肤」与「调色板」迟早各说各话，
 * 那正是 `方案-前端重构.md` §0 里「主题分裂」那一行的成因。
 *
 * 因此真值收敛到一处：[LocalDesignTokens]。
 * - `ThemeColors.kt` 的 `LightLinguaPalette`/`DarkLinguaPalette` 改成
 *   `paletteForSkin(SkinBuiltIns.light/dark)`（[paletteForSkin]），逐值相等；
 * - 新增 [provideLinguaTheme]，让 `LocalDesignTokens` 与 `LocalLinguaPalette`
 *   **同时**下发，两者永远同源。
 *
 * 这个文件**不声明** `LinguaPalette` 类型本身 —— 类型仍归 `ThemeColors.kt`，
 * 避免出现两个同名类型让 `internal` 可见性在同一模块里打架。
 */

/** 设计 token → 旧调色板（新增色见 [TokenColors] 里没有的三项语义色的对应关系）。 */
internal fun paletteOf(tokens: DesignTokens): LinguaPalette {
    val c = tokens.colors
    return LinguaPalette(
        paper = c.paper,
        paperDeep = c.paperDeep,
        cardSurface = c.cardSurface,
        ink = c.ink,
        inkSoft = c.inkSoft,
        inkFaint = c.inkFaint,
        accent = c.accent,
        accentDeep = c.accentDeep,
        accentSoft = c.accentSoft,
        onAccent = c.onAccent,
        gold = c.gold,
        goldOnPaper = c.goldOnPaper,
        success = c.success,
        danger = c.danger,
        bookCoverFallback = c.bookCoverFallback,
        isDark = tokens.isDark
    )
}

/** [paletteOf] 的 SkinSpec 重载；`ThemeColors.kt` 的两套常量用它构造。 */
internal fun paletteForSkin(spec: SkinSpec): LinguaPalette = paletteOf(SkinRegistry.toDesignTokens(spec))

/**
 * 一次性下发设计 token 与兼容调色板。**测试夹具专用。**
 *
 * ⚠️ **生产接线不在也绝不能在这里**：真正的那处是 `MainActivity.onCreate` 里的
 * `CompositionLocalProvider(LocalDesignTokens provides ..., LocalLinguaPalette provides ...)`
 * ——它必须按运行时的明暗算 token，而这个函数只收一个静态 `SkinSpec`。
 *
 * 曾经这里被写成「P3 只需把 MainActivity 的那行换成 provideLinguaTheme(skin)」，
 * 结果没人换、也没人发现 `LocalDesignTokens` 从来没有下发过：`LocalDesignTokens.current`
 * 一路走默认值（恒日间），深色外壳下所有已迁移的屏都取到了日间色。
 * 渲染台测试全部**显式** provide token，所以这条断线在测试里完全隐形。
 * 现在由 `ProductionThemeWiringTest` 读 `MainActivity.kt` 源码守着。
 *
 * 保留本函数是因为三个渲染夹具在用（ListeningRenderTest /
 * ListeningSettingsRenderTest / LegacyListeningSettingsProbe）；
 * 它**不是**生产入口，别在里面加逻辑。
 */
@Composable
internal fun provideLinguaTheme(
    spec: SkinSpec,
    content: @Composable () -> Unit
) {
    val tokens = SkinRegistry.toDesignTokens(spec)
    CompositionLocalProvider(
        LocalDesignTokens provides tokens,
        LocalLinguaPalette provides paletteOf(tokens),
        content = content
    )
}

/** 内置皮肤对应的旧调色板（日间/夜间），供 `paletteFor()` 与测试复用。 */
internal val BuiltInLightPalette: LinguaPalette get() = paletteForSkin(SkinBuiltIns.light)
internal val BuiltInDarkPalette: LinguaPalette get() = paletteForSkin(SkinBuiltIns.dark)
