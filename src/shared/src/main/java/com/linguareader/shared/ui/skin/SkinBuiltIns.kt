package com.linguareader.shared.ui.skin

/**
 * 内置皮肤。
 *
 * [light] / [dark] 的每个 hex 都**逐字照抄**现有 `ThemeColors.kt` 的
 * `LightLinguaPalette` / `DarkLinguaPalette` —— 迁移期必须做到「换上新地基后像素不变」，
 * 否则一旦哪里对不上，排查会把时间耗在「到底是迁移错了还是本来就这样」上。
 * 新语义色（successOn / dangerOn / warning / outline / scrim）按同色族补齐，并在
 * 注释里写上它们的对比度依据。
 */
object SkinBuiltIns {

    /**
     * 日间纸感：米白纸 + 墨色字 + 棕褐强调。
     *
     * 关键对比度（WCAG 相对亮度实算，非目测）：
     * - ink / paper = 14.08:1、inkSoft / paperDeep = 4.62:1、inkFaint / paperDeep = 4.71:1
     * - onAccent(白) / accent = 6.03:1、goldOnPaper / paper = 6.08:1
     * - successOn(白) / success = 5.66:1、dangerOn(白) / danger = 5.60:1
     */
    val light = SkinSpec(
        id = "paper-light",
        name = "纸·日间",
        isDark = false,
        colors = SkinColors(
            paper = 0xFFF7F3EAL,
            paperDeep = 0xFFF0E8D9L,
            cardSurface = 0xFFFFFBF4L,
            ink = 0xFF27231FL,
            inkSoft = 0xFF6F665CL,
            inkFaint = 0xFF6C655FL,
            accent = 0xFF8D5535L,
            accentDeep = 0xFF6F4127L,
            accentSoft = 0xFFE7D3BCL,
            onAccent = 0xFFFFFFFFL,
            gold = 0xFFC99B3FL,
            goldOnPaper = 0xFF7A5420L,
            success = 0xFF487050L,
            // 白字在 #487050 上 5.66:1；沿用 onAccent 的观感但显式建模，换肤时不至于丢失约束。
            successOn = 0xFFFFFFFFL,
            danger = 0xFFAC483DL,
            // 白字在 #AC483D 上 5.60:1。
            dangerOn = 0xFFFFFFFFL,
            // 警示棕褐：#8D5535(accent) 与它太近会让「警告」和「强调」分不开，故另取色相更偏
            // 橙的 #8A5D10（paperDeep 上 4.72:1、paper 上 5.19:1）。
            warning = 0xFF8A5D10L,
            // 描边取 accentSoft 加深一档，纸底上要有可见边界但又不抢眼。
            outline = 0xFFD9C4AAL,
            // 36% 黑：压暗弹层背景，足够区分层级又不至于把内容彻底看不见。
            scrim = 0x5C000000L,
            bookCoverFallback = 0xFFE1D5C2L
        ),
        typography = SkinTypography.default(),
        spacing = SkinSpacing.default(),
        radii = SkinRadii.default(),
        motion = SkinMotion.default(),
        elevation = SkinElevation.default(),
        origin = SkinOrigin.BUILT_IN
    )

    /**
     * 夜间纸感：底色与正文「夜间」阅读主题（#171717/#E8E3DA）对齐，避免从暗色正文
     * 打开目录/设置时出现白屏闪光。
     */
    val dark = SkinSpec(
        id = "paper-dark",
        name = "纸·夜间",
        isDark = true,
        colors = SkinColors(
            paper = 0xFF171717L,
            paperDeep = 0xFF1F1D1AL,
            cardSurface = 0xFF221F1BL,
            ink = 0xFFE8E3DAL,
            inkSoft = 0xFFB6ADA2L,
            inkFaint = 0xFF8F877BL,
            accent = 0xFFC98A5EL,
            accentDeep = 0xFFE3AE83L,
            accentSoft = 0xFF3A2E25L,
            onAccent = 0xFF231F1BL,
            gold = 0xFFD8B15CL,
            goldOnPaper = 0xFFD8B15CL,
            success = 0xFF7FB08CL,
            // 深色下 success 已是浅色，其上必须用墨色（白字只有 2.2:1）。
            successOn = 0xFF171717L,
            danger = 0xFFE0796CL,
            dangerOn = 0xFF171717L,
            // 深底上的警示黄：#E0B65C 在 paper 上 9.40:1。
            warning = 0xFFE0B65CL,
            outline = 0xFF3A342CL,
            // 深色下遮罩要更重，否则和本身就暗的底分不开；successOn/dangerOn 用 #171717（墨色），
            // 因为深色下这两个语义色本身是浅色，白字只有 2.47/2.95:1。
            scrim = 0x99000000L,
            bookCoverFallback = 0xFF3A342CL
        ),
        typography = SkinTypography.default(),
        spacing = SkinSpacing.default(),
        radii = SkinRadii.default(),
        motion = SkinMotion.default(),
        elevation = SkinElevation.default(),
        origin = SkinOrigin.BUILT_IN
    )

    /** 纯黑（AMOLED）：省电且无「灰雾」，卡片靠描边分层。 */
    val amoled = SkinSpec(
        id = "amoled",
        name = "纯黑",
        isDark = true,
        colors = dark.colors.copy(
            paper = 0xFF000000L,
            paperDeep = 0xFF0A0A0AL,
            cardSurface = 0xFF141414L,
            outline = 0xFF2A2622L,
            scrim = 0xCC000000L,
            bookCoverFallback = 0xFF2A2622L
        ),
        typography = SkinTypography.default(),
        spacing = SkinSpacing.default(),
        radii = SkinRadii.default(),
        motion = SkinMotion.default(),
        elevation = SkinElevation.default(),
        origin = SkinOrigin.BUILT_IN
    )

    /** 全部内置皮肤（设置页列表顺序）。 */
    val all: List<SkinSpec> = listOf(light, dark, amoled)

    fun byId(id: String?): SkinSpec? = all.firstOrNull { it.id == id }
}

/** JSON 解析缺色时的回退板：按 isDark 选一套，保证任何残缺 JSON 也能渲染出可用界面。 */
internal val SkinSpec.colorFallback: SkinColors
    get() = if (isDark) SkinBuiltIns.dark.colors else SkinBuiltIns.light.colors
