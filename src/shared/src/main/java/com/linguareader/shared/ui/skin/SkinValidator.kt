package com.linguareader.shared.ui.skin

/**
 * 对比度审计与自动修正 —— 「图片推出来的皮肤不会丑到读不了」的那道闸。
 *
 * 阈值取 WCAG 2.1 AA：
 * - 正文 / 次要文字：[TEXT_MIN] = 4.5:1
 * - 弱化文字、大字：[LARGE_TEXT_MIN] = 3.0:1
 *
 * 修正的方向是**保色相、只挪明度**（见 [SkinColorMath.ensureContrast]），因为从图片里
 * 推出来的皮肤一旦丢掉色相，用户就认不出「这是我选的那张图」。
 *
 * 收敛顺序是「先背景后前景」，且在背景阶段额外做一件事：**把三个表面统一到同一个明暗
 * 阵营**。原因很硬——单一 ink 色不可能同时压住一个很亮的底和一个很暗的底；图片推出来的
 * 表面一旦跨阵营，后面怎么调文字都会有一边掉下去。
 */
object SkinValidator {

    const val TEXT_MIN = 4.5
    const val LARGE_TEXT_MIN = 3.0

    /** 明暗阵营分界线（WCAG 相对亮度）。 */
    private const val CAMP_SPLIT = 0.5

    /** 前景修正的迭代上限；正常 1～2 轮就收敛，给到 8 是防御异常输入。 */
    private const val MAX_FIX_PASSES = 8

    /**
     * 一次审计里发现的问题。
     *
     * [fixed] 是自动修正后的值；为 null 表示该问题无法靠调明度解决（例如背景本身
     * 卡在黑白都够不到 4.5:1 的灰区），只能由调用方换色。
     */
    data class Issue(
        val kind: Kind,
        val actual: Double,
        val required: Double,
        val fixed: Long? = null
    ) {
        enum class Kind {
            INK_ON_PAPER,
            INK_ON_PAPER_DEEP,
            INK_ON_CARD,
            INK_SOFT_ON_PAPER,
            INK_SOFT_ON_PAPER_DEEP,
            INK_SOFT_ON_CARD,
            INK_FAINT_ON_PAPER,
            ON_ACCENT,
            SUCCESS_ON,
            DANGER_ON,
            GOLD_ON_PAPER
        }
    }

    /** 只读取对比度，不改任何东西。 */
    fun audit(colors: SkinColors): List<Pair<Issue.Kind, Double>> =
        requiredPairs(colors).map { p -> p.kind to SkinColorMath.contrastRatio(p.foreground, p.background) }

    /** 合规返回 true。 */
    fun isCompliant(colors: SkinColors): Boolean = violations(colors).isEmpty()

    /** 所有不达标项。 */
    fun violations(colors: SkinColors): List<Issue> {
        val out = ArrayList<Issue>()
        for (p in requiredPairs(colors)) {
            val actual = SkinColorMath.contrastRatio(p.foreground, p.background)
            val need = requiredFor(p.kind)
            if (actual < need) out.add(Issue(p.kind, actual, need))
        }
        return out
    }

    /**
     * 自动修正到合规。幂等：已经合规的皮肤原样返回。
     *
     * 步骤：
     * 1. 每个表面都要「黑白里至少一种能到 4.5:1」（灰阶图的死结在这里解开）；
     * 2. 三个表面统一到同一明暗阵营；
     * 3. 逐色对**全部**表面修正，循环到不动点；
     * 4. 强调/语义色上的文字单独对各自的背景修正。
     */
    fun conform(colors: SkinColors): SkinColors {
        val surfaces = harmonise(listOf(colors.paper, colors.paperDeep, colors.cardSurface))
        val paper = surfaces[0]
        val paperDeep = surfaces[1]
        val cardSurface = surfaces[2]

        fun fixText(color: Long, target: Double): Long {
            var fixed = color
            var previous = Long.MIN_VALUE
            for (pass in 0 until MAX_FIX_PASSES) {
                if (fixed == previous) break
                previous = fixed
                // 每一轮只修「当前最差的那个表面」——对它的修正通常会顺带解决其余的，
                // 一两轮就到不动点。
                val worst = surfaces.minByOrNull { SkinColorMath.contrastRatio(fixed, it) } ?: break
                if (SkinColorMath.contrastRatio(fixed, worst) >= target) break
                fixed = SkinColorMath.ensureContrast(fixed, worst, target)
            }
            return fixed
        }

        return colors.copy(
            paper = paper,
            paperDeep = paperDeep,
            cardSurface = cardSurface,
            ink = fixText(colors.ink, TEXT_MIN),
            inkSoft = fixText(colors.inkSoft, TEXT_MIN),
            // 弱化文字按 3:1（WCAG 非正文阈值）；它不承担正文可读性。
            inkFaint = fixText(colors.inkFaint, LARGE_TEXT_MIN),
            onAccent = SkinColorMath.ensureContrast(colors.onAccent, colors.accent, TEXT_MIN),
            successOn = SkinColorMath.ensureContrast(colors.successOn, colors.success, TEXT_MIN),
            dangerOn = SkinColorMath.ensureContrast(colors.dangerOn, colors.danger, TEXT_MIN),
            goldOnPaper = fixText(colors.goldOnPaper, TEXT_MIN),
            // 描边是分隔线而非文字：和纸底能看出边界即可，太强反而不像纸。
            outline = if (SkinColorMath.contrastRatio(colors.outline, paper) >= 1.15) {
                colors.outline
            } else {
                SkinColorMath.mix(paper, colors.ink, 0.16)
            }
        )
    }

    /** 按需修正后再审计一次，返回仍未解决的问题（正常情况下应为空）。 */
    fun conformAndVerify(colors: SkinColors): Pair<SkinColors, List<Issue>> {
        val fixed = conform(colors)
        return fixed to violations(fixed)
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 把一组表面推成「同阵营 + 各自黑白可达 4.5:1」。
     *
     * 阵营按平均亮度定：平均偏亮就全推到亮侧，反之全推到暗侧。只平移明度，不重算整套
     * 配色，守住图片带来的色相。
     */
    private fun harmonise(surfaces: List<Long>): List<Long> {
        var work = surfaces.map { SkinColorMath.ensureSurfaceSupports(it, TEXT_MIN) }
        val meanLum = work.sumOf { SkinColorMath.relativeLuminance(it) } / work.size
        val wantDark = meanLum < CAMP_SPLIT
        work = work.map { color ->
            val lum = SkinColorMath.relativeLuminance(color)
            val sameCamp = if (wantDark) lum < CAMP_SPLIT else lum >= CAMP_SPLIT
            if (sameCamp) color else flipCamp(color, wantDark)
        }
        // 翻阵营之后可能又掉进灰区，再兜一次。
        return work.map { SkinColorMath.ensureSurfaceSupports(it, TEXT_MIN) }
    }

    /** 把一个表面挪到目标阵营，尽量少动（贴到刚刚跨过分界线就停）。 */
    private fun flipCamp(color: Long, wantDark: Boolean): Long {
        val hsl = SkinColorMath.toHsl(color)
        for (i in 0..256) {
            val l = if (wantDark) {
                hsl.lightness * (1.0 - i / 256.0)
            } else {
                hsl.lightness + (1.0 - hsl.lightness) * i / 256.0
            }
            val candidate = SkinColorMath.fromHsl(hsl.hue, hsl.saturation, l, SkinColorMath.alpha(color))
            val lum = SkinColorMath.relativeLuminance(candidate)
            if (wantDark && lum < CAMP_SPLIT) return candidate
            if (!wantDark && lum >= CAMP_SPLIT) return candidate
        }
        return if (wantDark) SkinColorMath.BLACK else SkinColorMath.WHITE
    }

    private fun requiredFor(kind: Issue.Kind): Double = when (kind) {
        Issue.Kind.INK_FAINT_ON_PAPER -> LARGE_TEXT_MIN
        else -> TEXT_MIN
    }

    /**
     * 全部「文字/背景」对。用内部小类型：这些对只在内部流转，单独造公开类型
     * 反而要在外部多维护一份。
     */
    private fun requiredPairs(colors: SkinColors): List<RequiredPair> = listOf(
        RequiredPair(Issue.Kind.INK_ON_PAPER, colors.ink, colors.paper),
        RequiredPair(Issue.Kind.INK_ON_PAPER_DEEP, colors.ink, colors.paperDeep),
        RequiredPair(Issue.Kind.INK_ON_CARD, colors.ink, colors.cardSurface),
        RequiredPair(Issue.Kind.INK_SOFT_ON_PAPER, colors.inkSoft, colors.paper),
        RequiredPair(Issue.Kind.INK_SOFT_ON_PAPER_DEEP, colors.inkSoft, colors.paperDeep),
        RequiredPair(Issue.Kind.INK_SOFT_ON_CARD, colors.inkSoft, colors.cardSurface),
        RequiredPair(Issue.Kind.INK_FAINT_ON_PAPER, colors.inkFaint, colors.paper),
        RequiredPair(Issue.Kind.ON_ACCENT, colors.onAccent, colors.accent),
        RequiredPair(Issue.Kind.SUCCESS_ON, colors.successOn, colors.success),
        RequiredPair(Issue.Kind.DANGER_ON, colors.dangerOn, colors.danger),
        RequiredPair(Issue.Kind.GOLD_ON_PAPER, colors.goldOnPaper, colors.paper)
    )

    private data class RequiredPair(
        val kind: Issue.Kind,
        val foreground: Long,
        val background: Long
    )
}
