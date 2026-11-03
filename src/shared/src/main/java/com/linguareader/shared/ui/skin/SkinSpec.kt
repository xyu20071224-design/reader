package com.linguareader.shared.ui.skin

import org.json.JSONObject

/**
 * 皮肤的**唯一可序列化真相**（`方案-前端重构.md` §2.2）。
 *
 * 落到 `:shared` 而不是 `:app`：换肤模型必须是纯 Kotlin —— Android 侧出 Compose
 * `DesignTokens`，桌面侧将来出等价的渲染 token，两边对同一份 JSON 说同一种话。
 * 因此本文件**只允许 `org.json` 与 `java.*`**，禁 `android.*` / `androidx.*`。
 *
 * 颜色一律用 [Long] 存 `0xAARRGGBB`（见 [SkinColorMath]），不用 `android.graphics.Color`，
 * 也不用 Compose 的 `Color` —— 那两者都会把 `:shared` 拖成 Android-only。
 *
 * 命名沿用现有 `LinguaPalette`（`ThemeColors.kt`）的语义，只是补上了它没有的
 * `successOn` / `dangerOn` / `scrim`：那些在现有代码里是「拿 onAccent 凑」的，
 * 一旦换肤拿掉米色底就会翻车，这里显式建模。
 */
data class SkinSpec(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val colors: SkinColors,
    val typography: SkinTypography,
    val spacing: SkinSpacing,
    val radii: SkinRadii,
    val motion: SkinMotion,
    val elevation: SkinElevation,
    /** 来源：内置 / 图片推导 / 手写。只用于 UI 回显与调试，不参与渲染。 */
    val origin: SkinOrigin = SkinOrigin.MANUAL
) {
    /** 序列化后的 JSON 比这大就说明哪里膨胀了，{\.}冒烟测试与设置页粘贴框都靠它兜底。 */
    fun toJson(): String = SkinJson.encode(this)

    companion object {
        /** JSON schema 版本：字段语义发生不兼容变化时 +1，老 JSON 仍按当前语义尽力解析。 */
        const val SCHEMA_VERSION = 1

        fun fromJson(json: String): SkinSpec = SkinJson.decode(json)
        fun fromJson(obj: JSONObject): SkinSpec = SkinJson.decode(obj)
    }
}

/**
 * 语义色表，与现有 `LinguaPalette` 对齐并扩展。
 *
 * 三组「基础色 + on 色」是不变量（由 [SkinValidator] 强制）：
 * - [ink] 在 [paper]/[paperDeep]/[cardSurface] 上 ≥ 4.5:1
 * - [inkSoft] 同上 ≥ 4.5:1
 * - [onAccent] 在 [accent] 上 ≥ 4.5:1
 */
data class SkinColors(
    // ── 底 ──
    /** 主背景：日间米白纸 / 夜间暗纸。 */
    val paper: Long,
    /** 次级背景：分区底、未选中胶囊。 */
    val paperDeep: Long,
    /** 卡片表面：浮在 [paper] 之上的容器。 */
    val cardSurface: Long,

    // ── 字 ──
    /** 主文字：墨色。 */
    val ink: Long,
    /** 次级文字（副标题、说明）。 */
    val inkSoft: Long,
    /** 弱化文字 / 占位符；**仍须 ≥ 3:1**，不作为可读正文使用。 */
    val inkFaint: Long,

    // ── 强调 ──
    /** 主强调：按钮、选中态、链接。 */
    val accent: Long,
    /** 深强调：按压态。 */
    val accentDeep: Long,
    /** 浅强调底：选中胶囊、轻量 chip 背景。 */
    val accentSoft: Long,
    /** 强调色上的文字，与 [accent] ≥ 4.5:1。 */
    val onAccent: Long,

    // ── 点缀 / 语义 ──
    /** 点缀金：**只作图形/描边**，当文字用请取 [goldOnPaper]。 */
    val gold: Long,
    /** 需要当文字显示的金色（必须 ≥ 4.5:1）。 */
    val goldOnPaper: Long,
    val success: Long,
    /** [success] 上的文字。 */
    val successOn: Long,
    val danger: Long,
    /** [danger] 上的文字。 */
    val dangerOn: Long,
    /** 警告 / 待办。 */
    val warning: Long,
    /** 描边：卡片边、分隔线。 */
    val outline: Long,
    /** 遮罩：弹层背后的压暗层（带 alpha）。 */
    val scrim: Long,
    /** 无封面书的底色。 */
    val bookCoverFallback: Long
) {
    /** 背景类表面，`ink`/`inkSoft` 的对比度是对着它们审的。 */
    fun surfaces(): List<Long> = listOf(paper, paperDeep, cardSurface)

    fun withSurfaces(
        paper: Long = this.paper,
        paperDeep: Long = this.paperDeep,
        cardSurface: Long = this.cardSurface
    ): SkinColors = copy(paper = paper, paperDeep = paperDeep, cardSurface = cardSurface)
}

/**
 * 排版梯：5 个角色 × (字号/行高/字重/字距)。
 *
 * 为什么是 5 个而不是 Material 的 15 个：现有代码里 271 处 `typography.xxx` 实际只用到
 * 标题/小标题/正文/辅助/标签五种观感，多出来的档位只会让迁移时「到底该用哪个」变成
 * 个人品味问题。单位是 sp，行高为倍数（与 `ReaderPreferences.lineHeight` 同口径）。
 *
 * 字族用**平台无关的族名**（`serif`/`sans`/`mono` + 可选具体字体名），Android 侧映射成
 * `FontFamily`，桌面侧映射成 Swing/JCEF 的 CSS 栈。
 */
data class SkinTypography(
    val displaySizeSp: Double,
    val displayLineHeight: Double,
    val displayWeight: Int,
    val displayLetterSpacingSp: Double,
    val displayFamily: String,

    val titleSizeSp: Double,
    val titleLineHeight: Double,
    val titleWeight: Int,
    val titleLetterSpacingSp: Double,
    val titleFamily: String,

    val bodySizeSp: Double,
    val bodyLineHeight: Double,
    val bodyWeight: Int,
    val bodyLetterSpacingSp: Double,
    val bodyFamily: String,

    val captionSizeSp: Double,
    val captionLineHeight: Double,
    val captionWeight: Int,
    val captionLetterSpacingSp: Double,
    val captionFamily: String,

    val labelSizeSp: Double,
    val labelLineHeight: Double,
    val labelWeight: Int,
    val labelLetterSpacingSp: Double,
    val labelFamily: String
) {
    companion object {
        /** 与现有视觉最接近的一套（Material 3 的 bodyLarge/titleMedium 量级）。 */
        fun default(): SkinTypography = SkinTypography(
            displaySizeSp = 28.0, displayLineHeight = 1.25, displayWeight = 600,
            displayLetterSpacingSp = 0.0, displayFamily = SkinFontFamily.SERIF,
            titleSizeSp = 18.0, titleLineHeight = 1.3, titleWeight = 600,
            titleLetterSpacingSp = 0.0, titleFamily = SkinFontFamily.SERIF,
            bodySizeSp = 15.0, bodyLineHeight = 1.5, bodyWeight = 400,
            bodyLetterSpacingSp = 0.0, bodyFamily = SkinFontFamily.SANS,
            captionSizeSp = 13.0, captionLineHeight = 1.4, captionWeight = 400,
            captionLetterSpacingSp = 0.0, captionFamily = SkinFontFamily.SANS,
            labelSizeSp = 12.0, labelLineHeight = 1.3, labelWeight = 600,
            labelLetterSpacingSp = 0.4, labelFamily = SkinFontFamily.SANS
        )
    }
}

/** 字族的平台无关符号；具体映射在平台侧（Android 见 `app/skin/SkinFonts.kt`）。 */
object SkinFontFamily {
    const val SERIF = "serif"
    const val SANS = "sans"
    const val MONO = "mono"
    val VALID: Set<String> = setOf(SERIF, SANS, MONO)

    fun isValid(value: String): Boolean = value.trim().lowercase() in VALID
}

/**
 * 间距梯（dp），替代现有 545 处散装 dp。
 *
 * 六档：xs=4 / s=8 / m=16 / l=24 / xl=32 / xxl=48，是 4dp 栅格的子集；[gutter] 单独给
 * 「屏幕左右留白」用 —— 现有代码里它常年是 20/24 混用，收成一档才谈得上统一。
 */
data class SkinSpacing(
    val xs: Double,
    val s: Double,
    val m: Double,
    val l: Double,
    val xl: Double,
    val xxl: Double,
    val gutter: Double
) {
    companion object {
        fun default(): SkinSpacing = SkinSpacing(
            xs = 4.0, s = 8.0, m = 16.0, l = 24.0, xl = 32.0, xxl = 48.0, gutter = 20.0
        )
    }
}

/** 圆角梯（dp）。[pill] 用一个很大的值而不是 50%，平台侧自行夹取。 */
data class SkinRadii(
    val sm: Double,
    val md: Double,
    val lg: Double,
    val pill: Double
) {
    companion object {
        /** 与现有 `SmallShape`(9) / `CardShape`(14) 对齐。 */
        fun default(): SkinRadii = SkinRadii(sm = 9.0, md = 14.0, lg = 20.0, pill = 999.0)
    }
}

/**
 * 动效（毫秒 + 缓动曲线符号）。
 *
 * [easing] 用符号（`standard`/`decelerate`/`accelerate`/`linear`）而非贝塞尔控制点：
 * Compose 与 Web 的曲线类型不同，存控制点反而要求两端各自复刻一套解析。
 */
data class SkinMotion(
    val fastMillis: Int,
    val normalMillis: Int,
    val slowMillis: Int,
    val easing: String
) {
    companion object {
        const val EASE_STANDARD = "standard"
        const val EASE_DECELERATE = "decelerate"
        const val EASE_ACCELERATE = "accelerate"
        const val EASE_LINEAR = "linear"
        val VALID_EASING: Set<String> = setOf(EASE_STANDARD, EASE_DECELERATE, EASE_ACCELERATE, EASE_LINEAR)

        fun default(): SkinMotion = SkinMotion(120, 220, 360, EASE_STANDARD)
    }
}

/**
 * 层级（dp 阴影）。纸质风格默认全 0 —— 卡片靠描边与色阶分层，不靠投影，
 * 这样换到 AMOLED/深色也不会出现「灰雾」。
 */
data class SkinElevation(
    val none: Double,
    val low: Double,
    val high: Double,
    /** 是否使用阴影；false 时平台侧一律画描边。 */
    val useShadow: Boolean
) {
    companion object {
        fun default(): SkinElevation = SkinElevation(none = 0.0, low = 0.0, high = 0.0, useShadow = false)
    }
}

/**
 * 皮肤来源。图片推出来的皮肤要能自证「我不是手写的」，也方便设置页回显推导参数。
 */
enum class SkinOrigin(val wire: String) {
    BUILT_IN("built_in"),
    IMAGE("image"),
    MANUAL("manual");

    companion object {
        fun fromWire(value: String?): SkinOrigin =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: MANUAL
    }
}
