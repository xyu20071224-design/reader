package com.linguareader.shared.ui.skin

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一张图片 → 一套 [SkinSpec]（`方案-前端重构.md` §2.1 的第二段）。
 *
 * 管线：**量化**（[ImagePaletteExtractor]）→ **角色分配**（本文件）→ **WCAG 收敛**
 * （[SkinValidator.conform]）。三段都可单测。
 *
 * ## 三条硬规则（都是踩过坑之后写下的）
 *
 * 1. **面积最大的色簇 ≠ 背景色。** 一张「米白光线 + 木桌 + 绿植」的书桌照里，绿植往往
 *    面积最大，但用户期望的纸是**明亮的米白**。所以画布要按「像不像一张纸」打分，
 *    而不是按像素占比：明度靠极端、饱和度低、避开中间调。
 * 2. **正文字色必须中性。** 只守住 4.5:1 对比度是不够的——纯黄在近黑底上有 15:1，
 *    对比度满分、视觉灾难。ink / inkSoft / inkFaint **一律从纸色推导中性色**，
 *    绝不采用图片里的彩色簇。只有 accent / gold / success / danger / warning 才允许有彩。
 * 3. **语义色不跟图片走色相。** 成功必须绿、危险必须红。这三色按固定色相生成，
 *    只继承明暗阵营——「删除」按钮飘成绿色比不好看严重得多。
 */
object ImageSkinBuilder {

    /** 背景饱和度上限：图里那片「荧光绿草地」直接当 UI 底会晃眼，压到这个值以下才像纸。 */
    private const val MAX_BACKGROUND_SATURATION = 0.30

    /** 画布候选的明度门槛：要么够亮（当浅纸），要么够暗（当深纸），中间调一律不要。 */
    private const val PAPER_LIGHT_MIN_LIGHTNESS = 0.86
    private const val PAPER_DARK_MAX_LIGHTNESS = 0.13

    /** 正文的目标明度（两种阵营各一个），中性推导的落点。 */
    private const val INK_DARK_LIGHTNESS = 0.12
    private const val INK_LIGHT_LIGHTNESS = 0.92

    /** 正文字色的饱和度上限——中性色定义，超过就是「彩色正文」。 */
    private const val INK_MAX_SATURATION = 0.08

    /** 强调色至少要和底色拉开这么多对比度（按钮/链接要能看清）。 */
    private const val ACCENT_MIN_CONTRAST = 3.0

    // ── 形状与层次的推导边界（见 deriveCharacter）────────────────────

    /** 圆角缩放系数的下限：图片再「硬」也不会把卡片推成直角。 */
    private const val RADII_MIN = 0.55

    /** 圆角缩放系数的上限：图片再「软」也不会推成全胶囊（可点面积与对齐会坏）。 */
    private const val RADII_MAX = 1.35

    /** 层次强度低于此值时不加阴影，改用描边（弱层次 + 阴影会显得脏）。 */
    private const val SHADOW_THRESHOLD = 0.35

    /** 判断「这是不是一张纸」时，各维度的权重（合计 1.0）。 */
    private const val PAPERS_SUITABILITY_WEIGHT_SATURATION = 0.34
    private const val PAPERS_SUITABILITY_WEIGHT_LIGHTNESS = 0.10
    private const val PAPERS_SUITABILITY_WEIGHT_HUE = 0.28
    private const val PAPERS_SUITABILITY_WEIGHT_SHARE = 0.13
    private const val PAPERS_SUITABILITY_WEIGHT_NEUTRALITY = 0.15

    /**
     * 判「深色皮肤」的门槛：图里必须**真的有一片暗区**。
     *
     * 早先用「整图加权平均亮度 < 0.42」判阵营，一张「木桌 + 绿植 + 米白窗光」的照片
     * 平均亮度算出来是 0.26，于是被判成深色，米白那一片暖光根本进不了候选，
     * 最后落在深棕纸上——用户预期明明是明亮的纸。
     *
     * 换成「暗区占比」：只有图中确实存在成片近黑（L ≤ 0.20）且占比够大，才做深色皮肤。
     * 这符合直觉：夜空照、深色海报该出深色皮肤；中等明度的日光照片该出浅色皮肤。
     */
    private const val DARK_REGION_MAX_LIGHTNESS = 0.20
    private const val DARK_REGION_MIN_SHARE = 0.15

    /**
     * 从像素直接构建。
     *
     * @param pixels ARGB 像素数组（`Bitmap.getPixels` 的输出即可）
     * @param name 展示名（设置页回显用）
     * @param id 稳定 id；不传则按提取出的色簇指纹生成，同一张图永远是同一个 id
     */
    fun fromImage(
        pixels: IntArray,
        width: Int,
        height: Int,
        name: String = "图片皮肤",
        id: String? = null
    ): SkinSpec {
        val clusters = ImagePaletteExtractor.extract(pixels, width, height)
        return fromClusters(clusters, name = name, id = id ?: fingerprint(clusters))
    }

    /**
     * 从图片的**视觉性格**推导形状与层次（P20）。
     *
     * ## 为什么需要这个
     *
     * P1 建的 [SkinSpec] 有六个维度，但 [fromClusters] 只用图片推导了 `colors`，
     * 其余五个维度全部写死 `default()`。结果是**图片只能换颜色，不能换风格** ——
     * 而目标 10 要的是「快速接受由图片提供的风格」。
     *
     * 五维全推是不对的（字体、间距、动效与图片没有可信的对应关系，硬推等于编），
     * 但**两个维度有真实对应**：
     *
     * - **圆角**：图片的「柔和度」。大片低饱和、明度渐变平滑的图（晨雾、柔光书桌）→ 更圆；
     *   高对比、硬边界、饱和的图（海报、夜景霓虹）→ 更方。
     * - **阴影**：图片的「层次强度」。色簇离散度大（前景/背景分得开）→ 用阴影加强层次；
     *   色簇集中（一片纯色）→ 用描边而非阴影，否则浮起来的东西没有依托。
     *
     * ## 推导是有界的
     *
     * 每种取值都夹在 [RADII_RANGE] / [SHADOW_RANGE] 里 —— **图片只能在这个范围内表达倾向**，
     * 不能把 UI 推成全圆或全方。理由：极端形状会破坏可点面积与对齐关系（48dp 按钮变胶囊、
     * 卡片变圆形），那不是「风格」，那是坏掉。
     *
     * @param clusters 已量化的色簇
     * @param isDark 已判定的明暗阵营
     */
    internal fun deriveCharacter(
        clusters: List<ColorCluster>,
        isDark: Boolean
    ): Character {
        if (clusters.isEmpty()) return Character.flat()

        // 柔和度 = 低饱和 + 明度分布窄（渐变更平滑）的组合，0..1。
        val avgSat = clusters.sumOf { it.saturation } / clusters.size
        val lightnessSpread = (clusters.maxOf { it.lightness } - clusters.minOf { it.lightness })
        val softness = ((1.0 - avgSat.coerceIn(0.0, 1.0)) * 0.65 +
            (1.0 - lightnessSpread.coerceIn(0.0, 1.0)) * 0.35).coerceIn(0.0, 1.0)

        // 层次强度 = 色簇离散度（前景与背景分得多开）。
        val hierarchy = lightnessSpread.coerceIn(0.0, 1.0)

        // 圆角：softness 0..1 → 范围两端。
        val radiusScale = RADII_MIN + (RADII_MAX - RADII_MIN) * softness
        // 阴影：0.35 以下用描边（层次本来就弱，再加阴影会脏）；以上按层次强度给高度。
        val useShadow = hierarchy >= SHADOW_THRESHOLD
        val shadowScale = if (useShadow) {
            ((hierarchy - SHADOW_THRESHOLD) / (1.0 - SHADOW_THRESHOLD)).coerceIn(0.0, 1.0)
        } else 0.0

        return Character(
            radiusScale = radiusScale,
            useShadow = useShadow,
            shadowScale = shadowScale,
            isDark = isDark
        )
    }

    /** 图片推导出的「性格」，只含形状与层次两件事。 */
    internal data class Character(
        /** 圆角缩放系数，落在 [RADII_MIN]..[RADII_MAX]。 */
        val radiusScale: Double,
        /** 是否用阴影表达层次（否则用描边）。 */
        val useShadow: Boolean,
        /** 阴影强度 0..1；[useShadow] 为 false 时无意义。 */
        val shadowScale: Double,
        val isDark: Boolean
    ) {
        fun radii(): SkinRadii {
            val d = SkinRadii.default()
            return SkinRadii(
                sm = (d.sm * radiusScale).coerceIn(2.0, 10.0),
                md = (d.md * radiusScale).coerceIn(4.0, 18.0),
                lg = (d.lg * radiusScale).coerceIn(6.0, 26.0),
                pill = d.pill
            )
        }

        fun elevation(): SkinElevation {
            val d = SkinElevation.default()
            if (!useShadow) {
                // 层次弱：不用阴影，靠描边区分（`useShadow = false` 时组件层会画 outline）。
                return SkinElevation(none = 0.0, low = 0.0, high = 0.0, useShadow = false)
            }
            return SkinElevation(
                none = d.none,
                low = d.low * (0.6 + 0.4 * shadowScale),
                high = d.high * (0.5 + 0.5 * shadowScale),
                useShadow = true
            )
        }

        companion object {
            fun flat() = Character(radiusScale = 1.0, useShadow = false, shadowScale = 0.0, isDark = false)
        }
    }

    /** 已是色簇列表时走这里（导入管线可以先自己量化；测试也直接用这个入口）。 */
    fun fromClusters(
        clusters: List<ColorCluster>,
        name: String = "图片皮肤",
        id: String = "image"
    ): SkinSpec {
        // 一个可用像素都没有（全透明）：退回内置皮肤，而不是抛异常——
        // 「选错了一张透明图」不该让设置页崩掉。
        if (clusters.isEmpty()) {
            return SkinBuiltIns.light.copy(id = id, name = name, origin = SkinOrigin.IMAGE)
        }

        val mapping = assignRoles(clusters)
        val colors = SkinValidator.conform(mapping.colors)
        // 形状与层次也从图片推导（见 deriveCharacter 的 KDoc：只有这两维有可信对应）。
        val character = deriveCharacter(clusters, mapping.isDark)
        return SkinSpec(
            id = id,
            name = name,
            isDark = mapping.isDark,
            colors = colors,
            typography = SkinTypography.default(),
            spacing = SkinSpacing.default(),
            radii = character.radii(),
            motion = SkinMotion.default(),
            elevation = character.elevation(),
            origin = SkinOrigin.IMAGE
        )
    }

    /** 角色分配的结果，额外带出「为什么这么分」的依据，方便调试与测试断言。 */
    data class Mapping(
        val isDark: Boolean,
        val colors: SkinColors,
        /** 被当作画布（背景）的那一簇。 */
        val canvasCluster: ColorCluster,
        /** 被当作强调色的那一簇；灰阶图没有这一项（走合成色）。 */
        val accentCluster: ColorCluster?,
        /** 图片整体加权亮度。 */
        val averageLuminance: Double
    )

    /**
     * 把色簇映射成语义角色。**不修改**对比度——那是 [SkinValidator.conform] 的事，
     * 这里只负责「谁当谁」。
     */
    fun assignRoles(clusters: List<ColorCluster>): Mapping {
        require(clusters.isNotEmpty()) { "clusters must not be empty" }

        val totalShare = clusters.sumOf { it.share }.coerceAtLeast(1e-9)
        val averageLuminance = clusters.fold(0.0) { acc, c -> acc + c.luminance * (c.share / totalShare) }
        val isDark = isDarkImage(clusters)

        // ① 画布：**不是**面积最大的那个，而是「最像一张纸」的那个。
        val canvasCluster = pickCanvas(clusters, isDark)
        val paper = buildPaper(canvasCluster, isDark)
        val paperDeep = shiftForLayer(paper, isDark, deep = true)
        val cardSurface = shiftForLayer(paper, isDark, deep = false)

        // ② 正文字色：**强制中性**，从纸色推。绝不采用图片里的彩色簇——
        //    彩色的正文哪怕 15:1 也是错的。
        val ink = neutralInk(paper, isDark, INK_DARK_LIGHTNESS, INK_LIGHT_LIGHTNESS, INK_MAX_SATURATION)
        val inkSoft = neutralInk(paper, isDark, 0.34, 0.74, INK_MAX_SATURATION)
        val inkFaint = neutralInk(paper, isDark, 0.48, 0.60, INK_MAX_SATURATION)

        // ③ 强调色：保留图片的色相特征（这是「换上我这张图」的核心感知）。
        val accentCandidate = pickAccent(clusters, paper)
        val accent = accentCandidate?.first?.color?.let {
            SkinColorMath.ensureContrast(it, paper, ACCENT_MIN_CONTRAST)
        } ?: synthesiseAccent(canvasCluster)

        val accentDeep = if (isDark) {
            SkinColorMath.shiftLightness(accent, 0.14)
        } else {
            SkinColorMath.shiftLightness(accent, -0.14)
        }
        val accentSoft = SkinColorMath.mix(accent, paper, 0.82)
        val onAccent = bestTextOn(accent)

        // ④ 语义色：固定色相，只继承明度阵营（见类注释第 3 条）。
        val success = semanticColor(SUCCESS_HUE, 0.40, isDark)
        val danger = semanticColor(DANGER_HUE, 0.52, isDark)
        val warning = semanticColor(WARNING_HUE, 0.62, isDark)

        // ⑤ 点缀金：图里最暖的高饱和簇；没有就按固定暖色合成。
        val goldCluster = clusters
            .filter { it.saturation >= 0.20 }
            .maxByOrNull { it.warmth * 0.6 + it.saturation * 0.4 }
        val gold = goldCluster?.color?.let {
            SkinColorMath.ensureContrast(it, paper, 1.8)
        } ?: SkinColorMath.fromHsl(GOLD_HUE, 0.55, if (isDark) 0.66 else 0.46)
        val goldOnPaper = SkinColorMath.ensureContrast(gold, paper, SkinValidator.TEXT_MIN)

        val colors = SkinColors(
            paper = paper,
            paperDeep = paperDeep,
            cardSurface = cardSurface,
            ink = ink,
            inkSoft = inkSoft,
            inkFaint = inkFaint,
            accent = accent,
            accentDeep = accentDeep,
            accentSoft = accentSoft,
            onAccent = onAccent,
            gold = gold,
            goldOnPaper = goldOnPaper,
            success = success,
            successOn = bestTextOn(success),
            danger = danger,
            dangerOn = bestTextOn(danger),
            warning = warning,
            outline = SkinColorMath.mix(paper, ink, if (isDark) 0.22 else 0.16),
            // 遮罩：暗色下更重，避免和本来就暗的底糊在一起。
            scrim = if (isDark) 0x99000000L else 0x5C000000L,
            bookCoverFallback = SkinColorMath.mix(paper, ink, if (isDark) 0.10 else 0.07)
        )
        return Mapping(
            isDark = isDark,
            colors = colors,
            canvasCluster = canvasCluster,
            accentCluster = accentCandidate?.first,
            averageLuminance = averageLuminance
        )
    }

    // ── 画布选择 ─────────────────────────────────────────────────────

    /**
     * 判深色皮肤：图里是否**成片地暗**（见 [DARK_REGION_MIN_SHARE]）。
     *
     * 只数「近黑且占比不小」的色簇，不看整体平均亮度 —— 后者会被一张日光照片里
     * 居多的中调木色/绿植拉低，从而误判成深色。
     */
    private fun isDarkImage(clusters: List<ColorCluster>): Boolean {
        val darkShare = clusters
            .filter { it.lightness <= DARK_REGION_MAX_LIGHTNESS }
            .sumOf { it.share }
        return darkShare >= DARK_REGION_MIN_SHARE
    }

    /**
     * 挑「最像一张纸」的色簇。
     *
     * 打分三件事：
     * - **饱和度低**（纸是中性色，绿植/海报不是）—— 权重最高；
     * - **明度靠极端**（浅纸要够亮、深纸要够暗，中间调的"纸"两头不讨好）；
     * - **面积大**（同分时大的更像背景，权重最低）。
     *
     * 明暗阵营由整图加权亮度决定，所以深色夜空的深蓝会正确地当深纸。
     */
    private fun pickCanvas(clusters: List<ColorCluster>, isDark: Boolean): ColorCluster {
        // 先看有没有「极端明度」的候选；有就在其中挑，没有才退而求其次用全集。
        // 这一步是「暖木照不该出绿纸」的关键：米白（L≈0.92, S≈0.08）在极端集里，
        // 绿植（L≈0.33）不在，于是绿植根本进不了候选。
        val extremes = clusters.filter { c ->
            if (isDark) c.lightness <= PAPER_DARK_MAX_LIGHTNESS
            else c.lightness >= PAPER_LIGHT_MIN_LIGHTNESS
        }
        val pool = extremes.ifEmpty { clusters }

        return pool.maxByOrNull { c ->
            val saturationScore = 1.0 - min(1.0, c.saturation / 0.5)
            // 明度极端度：离「该阵营的理想明度」越近分越高。
            val idealLightness = if (isDark) 0.06 else 0.95
            val lightnessScore = 1.0 - min(1.0, kotlin.math.abs(c.lightness - idealLightness) / 0.35)
            val shareScore = min(1.0, c.share * 4.0)
            // 中性度：纸几乎不含色度。这一维把「深棕的木桌」与「米白的窗光」区分开——
            // 两者的饱和度和明度都可以很接近，但色度差一截。
            val neutralityScore = 1.0 - min(1.0, c.chroma / 0.30)
            PAPERS_SUITABILITY_WEIGHT_SATURATION * saturationScore +
                PAPERS_SUITABILITY_WEIGHT_LIGHTNESS * lightnessScore +
                PAPERS_SUITABILITY_WEIGHT_HUE * hueNeutrality(c) +
                PAPERS_SUITABILITY_WEIGHT_SHARE * shareScore +
                PAPERS_SUITABILITY_WEIGHT_NEUTRALITY * neutralityScore
        }!!
    }

    /**
     * 色相「像纸」的程度：**避开两个「有生命力」的色区** —— 绿（100–170°）与青（170–200°）。
     *
     * 这一维是「暖木书桌照被绿植带跑」的直接解药。实测那张图里木色 (#7B5B3A) 与
     * 绿植 (#3E6B58) 的明度几乎相同（0.355 vs 0.331）、饱和度还更低，
     * 只按饱和度+明度打分时绿植会赢（0.475 vs 0.352），纸底就被染绿了。
     * 而人类看一张「木桌 + 绿植 + 米白窗光」的照片时，绝不会认为纸该是绿的。
     */
    private fun hueNeutrality(c: ColorCluster): Double {
        if (c.saturation < 0.10) return 1.0   // 近中性色：纸的典型形态，满分
        return when (c.hue) {
            in 80.0..205.0 -> 0.0             // 绿 / 青：植物、草地、水面 —— 最不像纸
            in 60.0..80.0, in 205.0..250.0 -> 0.35
            else -> 1.0                       // 暖色系与紫红：可以是木、砖、布、墙
        }
    }

    /** 底色：保色相、压饱和度、把明度钉进「能当纸」的区间。 */
    private fun buildPaper(canvas: ColorCluster, isDark: Boolean): Long {
        val hsl = canvas.hsl
        val saturation = min(hsl.saturation, MAX_BACKGROUND_SATURATION)
        val lightness = if (isDark) {
            min(hsl.lightness, PAPER_DARK_MAX_LIGHTNESS)
        } else {
            max(hsl.lightness, PAPER_LIGHT_MIN_LIGHTNESS)
        }
        return SkinColorMath.fromHsl(hsl.hue, saturation, lightness)
    }

    /**
     * 二级表面：卡片永远比纸「亮一档」（两种阵营下都是），旧纸则往相反方向压一档。
     * 沿用现有调色板的事实（日间 card #FFFBF4 > paper #F7F3EA；夜间 card #221F1B > paper #171717）。
     */
    private fun shiftForLayer(paper: Long, isDark: Boolean, deep: Boolean): Long = when {
        deep && !isDark -> SkinColorMath.shiftLightness(paper, -0.045)
        deep && isDark -> SkinColorMath.shiftLightness(paper, 0.035)
        !deep -> SkinColorMath.shiftLightness(paper, if (isDark) 0.045 else 0.025)
        else -> paper
    }

    // ── 正文：中性推导 ────────────────────────────────────────────────

    /**
     * 从纸色推导**中性**正文字色。
     *
     * 保留纸色的色相（这样深蓝纸上会得到一层极淡的冷墨，而不是死灰），
     * 但把饱和度压到 [maxSaturation] 以下——中性色的定义就是这个。
     */
    private fun neutralInk(
        paper: Long,
        isDark: Boolean,
        darkLightness: Double,
        lightLightness: Double,
        maxSaturation: Double
    ): Long {
        val hsl = SkinColorMath.toHsl(paper)
        val lightness = if (isDark) lightLightness else darkLightness
        // 越暗的墨，可用的饱和度越小（深色下饱和度更显眼）。
        val saturation = min(maxSaturation, hsl.saturation * 0.25)
        return SkinColorMath.fromHsl(hsl.hue, saturation, lightness)
    }

    // ── 强调色选择 ───────────────────────────────────────────────────

    /**
     * 挑强调色：饱和度要高、面积别太小、和底色拉得开。
     *
     * @return (色簇, 得分) 或 null（灰阶图没有合格候选）
     */
    private fun pickAccent(clusters: List<ColorCluster>, paper: Long): Pair<ColorCluster, Double>? =
        clusters
            .map { c ->
                val contrast = c.contrastOver(paper)
                val score = c.saturation * (0.35 + 0.65 * min(1.0, c.share * 6.0)) *
                    (if (contrast >= ACCENT_MIN_CONTRAST) 1.0 else 0.45)
                Triple(c, score, contrast)
            }
            .filter { (_, _, contrast) -> contrast >= 1.6 }
            .maxByOrNull { (_, score, _) -> score }
            ?.takeIf { (cluster, score, _) -> cluster.saturation >= 0.18 && score > 0.05 }
            ?.let { (cluster, score, _) -> cluster to score }

    /** 灰阶图的强调色：固定暖褐，避免「整张 UI 只剩灰」。 */
    private fun synthesiseAccent(canvas: ColorCluster): Long {
        val hue = if (canvas.saturation < 0.06) 28.0 else canvas.hue
        return SkinColorMath.fromHsl(hue, 0.52, 0.42)
    }

    /** 语义色：固定色相 + 按明暗阵营选明度。 */
    private fun semanticColor(hue: Double, saturation: Double, isDark: Boolean): Long =
        SkinColorMath.fromHsl(hue, saturation, if (isDark) 0.68 else 0.34)

    /** 底色上的最佳文字色：黑白里对比更高的那个。 */
    private fun bestTextOn(background: Long): Long =
        if (SkinColorMath.contrastRatio(SkinColorMath.WHITE, background) >=
            SkinColorMath.contrastRatio(SkinColorMath.BLACK, background)
        ) {
            SkinColorMath.WHITE
        } else {
            SkinColorMath.BLACK
        }

    /**
     * 同一张图 → 同一个 id。取前 4 个色簇的 hex 拼起来做 32 位 FNV-1a，
     * 不用 [String.hashCode]（不同 JVM 版本虽稳定，但 FNV 更不容易撞）。
     */
    private fun fingerprint(clusters: List<ColorCluster>): String {
        if (clusters.isEmpty()) return "image-empty"
        var hash = 0x811C9DC5L
        for (c in clusters.take(4)) {
            for (ch in SkinColorMath.toHex(c.color)) {
                hash = (hash xor ch.code.toLong()) * 0x01000193L
                hash = hash and 0xFFFFFFFFL
            }
        }
        return "image-" + java.lang.Long.toHexString(hash).padStart(8, '0')
    }

    // 固定色相（HSL 角度）。成功=草绿、危险=偏朱红、警告=琥珀、点缀=暖金。
    private const val SUCCESS_HUE = 140.0
    private const val DANGER_HUE = 6.0
    private const val WARNING_HUE = 35.0
    private const val GOLD_HUE = 45.0

    /** 仅供测试/调试：一眼看清打了个什么分。 */
    fun describe(mapping: Mapping): String = buildString {
        append("isDark=").append(mapping.isDark)
        append(" avgLum=").append((mapping.averageLuminance * 100).roundToInt() / 100.0)
        append(" canvas=").append(SkinColorMath.toHex(mapping.canvasCluster.color))
        append("(").append((mapping.canvasCluster.share * 100).roundToInt()).append("%)")
        append(" accent=")
            .append(mapping.accentCluster?.let { SkinColorMath.toHex(it.color) } ?: "synthesised")
    }
}