package com.linguareader.app.skin

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token —— Compose 侧的**唯一视觉真相**（`方案-前端重构.md` §1 skin 层）。
 *
 * 由 [com.linguareader.shared.ui.skin.SkinSpec] 经 [SkinRegistry.toDesignTokens] 派生。
 * `components/` 与 `screens/` 只允许从这里取值，禁止裸 `Color(0x...)` / 裸
 * `RoundedCornerShape(数字)` / 裸字号 —— 这条约束由 `DesignTokenDisciplineTest` 静态扫描守着。
 *
 * 为什么 :shared 不直接给这个类：`:shared` 必须纯 Kotlin（桌面复用），而 Compose 的
 * `Color`/`TextStyle`/`Dp` 是 Android+Compose 专有类型。分层就落在这里：`:shared` 存
 * 「数字与 hex」，`:app` 把它们翻译成 Compose 对象。
 */
data class DesignTokens(
    val skinId: String,
    val skinName: String,
    val isDark: Boolean,
    val colors: TokenColors,
    val typography: TokenTypography,
    val spacing: TokenSpacing,
    val radii: TokenRadii,
    val motion: TokenMotion,
    val elevation: TokenElevation
) {
    /** 卡片形状：现有 `CardShape` 的语义位（新代码用 [radii] 里的 md）。 */
    val cardShape: RoundedCornerShape get() = RoundedCornerShape(radii.md)
    val smallShape: RoundedCornerShape get() = RoundedCornerShape(radii.sm)
    val largeShape: RoundedCornerShape get() = RoundedCornerShape(radii.lg)
    val pillShape: RoundedCornerShape get() = RoundedCornerShape(radii.pill)

    /**
     * 图标尺寸。顶栏动作、行内图标统一用它 —— 现有代码里同一类图标在 20/22/24 之间随机，
     * 排在一行时肉眼可见地不齐。
     */
    val iconSize: Dp get() = 24.dp

    /** 可点行 / 按钮的最小高度（≤48dp 会低于无障碍触达建议，也容易误触）。 */
    val controlHeight: Dp get() = 48.dp

    /** 细分割线厚度。 */
    val hairline: Dp get() = 1.dp

    /** 弹层形状：只有上面两个角是圆的（贴着屏幕底边）。 */
    val sheetShape: RoundedCornerShape
        get() = RoundedCornerShape(topStart = radii.lg, topEnd = radii.lg)

    /**
     * 语义色做 chip 底色时的透明度。
     *
     * 用「语义色的低透明度」而不是另配一份浅色：图片皮肤推出来的语义色千变万化，
     * 手配浅色永远配不齐；混一层纸上透明度则天然跟着皮肤走。
     */
    val chipWashAlpha: Float get() = if (isDark) 0.22f else 0.14f

    /** 空状态图标的圆形底盘直径。 */
    val emptyStateIconSize: Dp get() = 72.dp

    /** 说明文字的最大行宽（超过这个宽度阅读舒适度下降）。 */
    val proseMaxWidth: Dp get() = 320.dp
}

/** 语义色（Compose 版）。字段与 `:shared` 的 `SkinColors` 一一对应。 */
data class TokenColors(
    val paper: Color,
    val paperDeep: Color,
    val cardSurface: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val gold: Color,
    val goldOnPaper: Color,
    val success: Color,
    val successOn: Color,
    val danger: Color,
    val dangerOn: Color,
    val warning: Color,
    val outline: Color,
    val scrim: Color,
    val bookCoverFallback: Color
) {
    /** 三种背景表面，需要「在哪个底上审对比度」时用。 */
    val surfaces: List<Color> get() = listOf(paper, paperDeep, cardSurface)
}

/**
 * 排版梯：5 个角色。
 *
 * 现有代码里 271 处 `MaterialTheme.typography.xxx` 实际只用出五种观感，
 * 收成 5 档是为了让「这段该用哪个」不再是个人品味问题。
 */
data class TokenTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val label: TextStyle
) {
    /** 全部角色，做「字号是否被 token 覆盖」的全量检查时用。 */
    val all: List<TextStyle> get() = listOf(display, title, body, caption, label)
}

/** 间距梯（dp）。六档 + 屏幕左右留白 [gutter]，替代现有 545 处散装 dp。 */
data class TokenSpacing(
    val xs: Dp,
    val s: Dp,
    val m: Dp,
    val l: Dp,
    val xl: Dp,
    val xxl: Dp,
    val gutter: Dp
) {
    /** 组件内部内边距的常用组合。 */
    val cardPadding: Dp get() = m
    val sheetPadding: Dp get() = l
    val rowGap: Dp get() = s
}

/** 圆角梯（dp）。 */
data class TokenRadii(
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val pill: Dp
)

/**
 * 动效（毫秒 + 缓动）。
 *
 * 缓动用 [Easing] 而非字符串：字符串是 `:shared` 的存储格式，走到 Compose 就必须
 * 翻译成可执行的曲线，否则组件里还得再 `when` 一次。
 */
data class TokenMotion(
    val fastMillis: Int,
    val normalMillis: Int,
    val slowMillis: Int,
    val easing: Easing
) {
    val fastSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float>
        get() = androidx.compose.animation.core.tween(fastMillis, easing = easing)
    val normalSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float>
        get() = androidx.compose.animation.core.tween(normalMillis, easing = easing)
}

/**
 * 层级。纸质风格默认全 0：卡片靠描边与色阶分层，不靠投影 —— 这样换到纯黑也不会
 * 出现「灰雾」，而 Material 的默认 elevation 在深色下恰好是最难看的地方。
 */
data class TokenElevation(
    val none: Dp,
    val low: Dp,
    val high: Dp,
    val useShadow: Boolean
) {
    /** 卡片该用的高度：不用阴影时恒为 0（改用描边）。 */
    val card: Dp get() = if (useShadow) low else none
}

// ── 固定映射表 ────────────────────────────────────────────────────

/** `:shared` 的字族符号 → Compose [FontFamily]。 */
internal object TokenFonts {
    fun of(family: String): FontFamily = when (family.trim().lowercase()) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
}

/** 数值字重 → [FontWeight]。FontWeight 的构造器不公开，只能查表取最近档。 */
internal fun fontWeightOf(weight: Int): FontWeight = when {
    weight <= 150 -> FontWeight.Thin
    weight <= 250 -> FontWeight.ExtraLight
    weight <= 350 -> FontWeight.Light
    weight <= 450 -> FontWeight.Normal
    weight <= 550 -> FontWeight.Medium
    weight <= 650 -> FontWeight.SemiBold
    weight <= 750 -> FontWeight.Bold
    weight <= 850 -> FontWeight.ExtraBold
    else -> FontWeight.Black
}

/**
 * 缓动符号 → [Easing]。曲线取值对齐 Material 3 的 emphasized 系列：
 * standard = (0.2, 0, 0, 1)、decelerate = (0, 0, 0, 1)、accelerate = (0.3, 0, 1, 1)。
 */
internal object TokenEasing {
    fun of(name: String): Easing = when (name.trim().lowercase()) {
        "decelerate" -> CubicBezierEasing(0f, 0f, 0f, 1f)
        "accelerate" -> CubicBezierEasing(0.3f, 0f, 1f, 1f)
        "linear" -> LinearEasing
        else -> CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

// ── 内部构造助手（只给 SkinRegistry 用） ───────────────────────────

/**
 * 从 `:shared` 的排版数值造一个 [TextStyle]。
 *
 * 行高在 `:shared` 里存的是**倍数**（1.65 = 1.65 倍字号，与
 * `ReaderPreferences.lineHeight` 同口径），到 Compose 这里要换成绝对行高，
 * 所以乘上字号再转 sp。
 */
internal fun typeStyle(
    sizeSp: Double,
    lineHeight: Double,
    weight: Int,
    letterSpacingSp: Double,
    family: String
): TextStyle = TextStyle(
    fontFamily = TokenFonts.of(family),
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * lineHeight).sp,
    fontWeight = fontWeightOf(weight),
    letterSpacing = letterSpacingSp.sp
)
