package com.linguareader.app.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinColors
import com.linguareader.shared.ui.skin.SkinMotion
import com.linguareader.shared.ui.skin.SkinRadii
import com.linguareader.shared.ui.skin.SkinSpec
import com.linguareader.shared.ui.skin.SkinSpacing
import com.linguareader.shared.ui.skin.SkinTypography

/**
 * [SkinSpec] → [DesignTokens] 的翻译器，以及当前皮肤的持有处。
 *
 * 三段式管线的第三段（`方案-前端重构.md` §2.1）：图片 → SkinSpec → **DesignTokens** → 组件。
 * 翻译是纯函数（除 [LocalDesignTokens] 这个 CompositionLocal），所以可以直接单测：
 * 「同一个 hex 进来的确变成了同一个 Color」不需要起 Activity。
 */
object SkinRegistry {

    /** 出厂默认皮肤（与现有日间观感逐字一致）。 */
    val default: SkinSpec get() = SkinBuiltIns.light

    /** 全部内置皮肤，设置页按这个顺序列。 */
    val builtIns: List<SkinSpec> get() = SkinBuiltIns.all

    fun byId(id: String?): SkinSpec? = SkinBuiltIns.byId(id)

    /** 核心翻译：一份 SkinSpec → 一份 [DesignTokens]。 */
    fun toDesignTokens(spec: SkinSpec): DesignTokens = DesignTokens(
        skinId = spec.id,
        skinName = spec.name,
        isDark = spec.isDark,
        colors = toColors(spec.colors),
        typography = toTypography(spec.typography),
        spacing = toSpacing(spec.spacing),
        radii = toRadii(spec.radii),
        motion = toMotion(spec.motion),
        elevation = TokenElevation(
            none = spec.elevation.none.dp,
            low = spec.elevation.low.dp,
            high = spec.elevation.high.dp,
            useShadow = spec.elevation.useShadow
        )
    )

    fun toColors(c: SkinColors): TokenColors = TokenColors(
        paper = Color(c.paper),
        paperDeep = Color(c.paperDeep),
        cardSurface = Color(c.cardSurface),
        ink = Color(c.ink),
        inkSoft = Color(c.inkSoft),
        inkFaint = Color(c.inkFaint),
        accent = Color(c.accent),
        accentDeep = Color(c.accentDeep),
        accentSoft = Color(c.accentSoft),
        onAccent = Color(c.onAccent),
        gold = Color(c.gold),
        goldOnPaper = Color(c.goldOnPaper),
        success = Color(c.success),
        successOn = Color(c.successOn),
        danger = Color(c.danger),
        dangerOn = Color(c.dangerOn),
        warning = Color(c.warning),
        outline = Color(c.outline),
        scrim = Color(c.scrim),
        bookCoverFallback = Color(c.bookCoverFallback)
    )

    fun toTypography(t: SkinTypography): TokenTypography = TokenTypography(
        display = typeStyle(
            t.displaySizeSp, t.displayLineHeight, t.displayWeight, t.displayLetterSpacingSp, t.displayFamily
        ),
        title = typeStyle(
            t.titleSizeSp, t.titleLineHeight, t.titleWeight, t.titleLetterSpacingSp, t.titleFamily
        ),
        body = typeStyle(
            t.bodySizeSp, t.bodyLineHeight, t.bodyWeight, t.bodyLetterSpacingSp, t.bodyFamily
        ),
        caption = typeStyle(
            t.captionSizeSp, t.captionLineHeight, t.captionWeight, t.captionLetterSpacingSp, t.captionFamily
        ),
        label = typeStyle(
            t.labelSizeSp, t.labelLineHeight, t.labelWeight, t.labelLetterSpacingSp, t.labelFamily
        )
    )

    fun toSpacing(s: SkinSpacing): TokenSpacing = TokenSpacing(
        xs = s.xs.dp,
        s = s.s.dp,
        m = s.m.dp,
        l = s.l.dp,
        xl = s.xl.dp,
        xxl = s.xxl.dp,
        gutter = s.gutter.dp
    )

    fun toRadii(r: SkinRadii): TokenRadii = TokenRadii(
        sm = r.sm.dp,
        md = r.md.dp,
        lg = r.lg.dp,
        pill = r.pill.dp
    )

    fun toMotion(m: SkinMotion): TokenMotion = TokenMotion(
        fastMillis = m.fastMillis,
        normalMillis = m.normalMillis,
        slowMillis = m.slowMillis,
        easing = TokenEasing.of(m.easing)
    )
}

/**
 * 当前设计 token。**组件的唯一取值入口**。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：皮肤切换是极少发生的整树
 * 变化，static 版本不做细粒度订阅，读取开销更低（与现有 `LocalLinguaPalette` 一致）。
 */
val LocalDesignTokens = staticCompositionLocalOf { SkinRegistry.toDesignTokens(SkinRegistry.default) }

/** 取当前 token 的便捷入口：`tokens.colors.ink`。 */
val tokens: DesignTokens
    @Composable get() = LocalDesignTokens.current
