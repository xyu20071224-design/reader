package com.linguareader.app

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.ReaderTheme

/**
 * 纸质书配色系统，分日间/夜间两套。
 *
 * 每个语义色都通过 [LocalLinguaPalette] 取值，所以界面代码继续写 `Ink`、`Paper`
 * 这样的名字即可，切换主题不需要改调用点。
 */
internal data class LinguaPalette(
    /** 主背景：米白纸 / 夜间纸 */
    val paper: Color,
    /** 次级背景：旧纸 */
    val paperDeep: Color,
    /** 卡片表面 */
    val cardSurface: Color,
    /** 主文字：墨色 */
    val ink: Color,
    /** 次级文字 */
    val inkSoft: Color,
    /** 弱化文字 / 占位 */
    val inkFaint: Color,
    /** 主强调：棕褐 */
    val accent: Color,
    /** 深强调：按压态 */
    val accentDeep: Color,
    /** 浅强调底：选中胶囊 */
    val accentSoft: Color,
    /** 强调色上的文字（日间白字、夜间墨字，保证对比度） */
    val onAccent: Color,
    /** 点缀：书签金 */
    val gold: Color,
    /** 认识 / 掌握 */
    val success: Color,
    /** 删除 / 危险 */
    val danger: Color,
    /** 无封面书的底色 */
    val bookCoverFallback: Color,
    val isDark: Boolean
)

internal val LightLinguaPalette = LinguaPalette(
    paper = Color(0xFFF7F3EA),
    paperDeep = Color(0xFFF0E8D9),
    cardSurface = Color(0xFFFFFBF4),
    ink = Color(0xFF27231F),
    inkSoft = Color(0xFF6F665C),
    inkFaint = Color(0xFF9C938A),
    accent = Color(0xFF8D5535),
    accentDeep = Color(0xFF6F4127),
    accentSoft = Color(0xFFE7D3BC),
    onAccent = Color.White,
    gold = Color(0xFFC99B3F),
    success = Color(0xFF4E7A57),
    danger = Color(0xFFB0493E),
    bookCoverFallback = Color(0xFFE1D5C2),
    isDark = false
)

/**
 * 夜间配色：底色与正文「夜间」阅读主题（#171717/#E8E3DA）对齐，避免从暗色正文
 * 打开目录/设置时出现白屏闪光；强调色提亮到棕金以在深底上保持对比度，因此强调色
 * 上的文字改为墨色（白字在浅棕上对比度不足）。
 */
internal val DarkLinguaPalette = LinguaPalette(
    paper = Color(0xFF171717),
    paperDeep = Color(0xFF1F1D1A),
    cardSurface = Color(0xFF221F1B),
    ink = Color(0xFFE8E3DA),
    inkSoft = Color(0xFFB6ADA2),
    inkFaint = Color(0xFF8C8479),
    accent = Color(0xFFC98A5E),
    accentDeep = Color(0xFFE3AE83),
    accentSoft = Color(0xFF3A2E25),
    onAccent = Color(0xFF231F1B),
    gold = Color(0xFFD8B15C),
    success = Color(0xFF7FB08C),
    danger = Color(0xFFE0796C),
    bookCoverFallback = Color(0xFF3A342C),
    isDark = true
)

internal val LocalLinguaPalette = staticCompositionLocalOf { LightLinguaPalette }

// ── 语义色（读取当前调色板；界面代码沿用原来的名字） ──────────────────

internal val Paper: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.paper
internal val PaperDeep: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.paperDeep
internal val CardSurface: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.cardSurface
internal val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.ink
internal val InkSoft: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.inkSoft
internal val InkFaint: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.inkFaint
internal val Accent: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.accent
internal val AccentDeep: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.accentDeep
internal val AccentSoft: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.accentSoft
internal val OnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.onAccent
internal val Gold: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.gold
internal val Success: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.success
internal val Danger: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.danger
internal val BookCoverFallback: Color
    @Composable @ReadOnlyComposable get() = LocalLinguaPalette.current.bookCoverFallback

// ── 形状与层级规范 ──────────────────────────────────────────────
internal val CardShape = RoundedCornerShape(14.dp)
internal val SmallShape = RoundedCornerShape(9.dp)
internal val PillShape = RoundedCornerShape(50)

internal val AppShapes = Shapes(
    small = SmallShape,
    medium = CardShape,
    large = RoundedCornerShape(20.dp)
)

/** Material 配色方案：由调色板派生，日间/夜间共用一套映射关系。 */
internal fun colorSchemeFor(palette: LinguaPalette): ColorScheme {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = if (palette.isDark) palette.ink else palette.accentDeep,
        secondary = palette.gold,
        onSecondary = palette.onAccent,
        secondaryContainer = palette.accentSoft,
        onSecondaryContainer = if (palette.isDark) palette.ink else palette.accentDeep,
        tertiary = palette.success,
        background = palette.paper,
        onBackground = palette.ink,
        surface = palette.cardSurface,
        onSurface = palette.ink,
        surfaceVariant = palette.paperDeep,
        onSurfaceVariant = palette.inkSoft,
        outline = palette.accentSoft,
        outlineVariant = palette.paperDeep,
        error = palette.danger,
        onError = palette.onAccent
    )
}

/**
 * 外壳（书架/设置/听书条/弹层）是否走夜间：**跟随正文阅读主题**——用户把阅读主题
 * 设成「夜间」或「纯黑」，整个外壳也随之变暗；还没设过阅读主题时跟随系统深色。
 */
internal fun chromeIsDark(readerTheme: ReaderTheme?, systemDark: Boolean): Boolean = when (readerTheme) {
    null -> systemDark
    ReaderTheme.DARK, ReaderTheme.AMOLED -> true
    else -> false
}

internal fun paletteFor(readerTheme: ReaderTheme?, systemDark: Boolean): LinguaPalette =
    if (chromeIsDark(readerTheme, systemDark)) DarkLinguaPalette else LightLinguaPalette

/**
 * 全局 Snackbar 的容器/文字配色（返回 containerColor to contentColor）。
 *
 * NEUTRAL 沿用墨色容器：日间深容器浅字、夜间浅容器深字（Ink/Paper 互逆，
 * 对各自背景 ≥13:1）。成功/失败用调色板语义色 + onAccent 文字：
 * 日间白字（≥4.9:1）、夜间墨字（≥5.5:1），米色纸底上不再出现「米色条」。
 */
internal fun snackbarColorsFor(tone: StatusTone, palette: LinguaPalette): Pair<Color, Color> =
    when (tone) {
        StatusTone.NEUTRAL -> palette.ink to palette.paper
        StatusTone.SUCCESS -> palette.success to palette.onAccent
        StatusTone.DANGER -> palette.danger to palette.onAccent
    }

/** 已保存的阅读主题（与 ReaderScreen 共用 `reader_preferences`），未设置时为 null。 */
internal fun storedReaderTheme(context: Context): ReaderTheme? {
    val stored = context
        .getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
        .getString("theme", null)
        ?: return null
    return runCatching { ReaderTheme.valueOf(stored) }.getOrNull()
}
