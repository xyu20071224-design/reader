package com.linguareader.app.uirender

import java.io.File

/**
 * 硬编码样式扫描器（重构护栏）。
 *
 * 用途：`方案-前端重构.md` 的硬约束说「screens/ 与 components/ 不得出现裸
 * `Color(0x...)` / 裸 `RoundedCornerShape(数字)` / 裸字号」。这条靠自觉守不住——
 * 项目现在有 545 处硬编码 dp、33 处裸颜色。这里把它变成可断言的基线：
 *
 * - 先记录迁移前的数字（基线）；
 * - 每迁一屏，该文件的数字必须**只减不增**；
 * - 全部迁完后，白名单外的文件必须是 0。
 *
 * 它不是纯样式洁癖：硬编码值绕过了换肤系统，换一张图皮肤时这些地方不会跟着变，
 * 视觉上就是"换了皮但没换干净"。
 */
object HardcodedStyleScanner {

    /** 一行里出现的裸颜色字面量（`Color(0x...)` / `Color.Red`…）。 */
    private val RAW_COLOR = Regex("""Color\(0x[0-9A-Fa-f]{6,8}\)""")

    /** 裸圆角（`RoundedCornerShape(<数字>.dp)` 或 `(\d+)`）。 */
    private val RAW_RADIUS = Regex("""RoundedCornerShape\(\s*\d+""")

    /** 裸字号（`fontSize = \d+.sp`）。 */
    private val RAW_FONT_SIZE = Regex("""fontSize\s*=\s*\d+(\.\d+)?\.sp""")

    /** 硬编码 dp 字面量。 */
    private val RAW_DP = Regex("""\b\d+(\.\d+)?\.dp\b""")

    data class FileStats(
        val file: String,
        val rawColor: Int,
        val rawRadius: Int,
        val rawFontSize: Int,
        val rawDp: Int
    ) {
        val total: Int get() = rawColor + rawRadius + rawFontSize + rawDp
    }

    /** 扫描一个 .kt 文件；注释与字符串里的不算。 */
    fun scan(file: File): FileStats {
        val body = file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        return FileStats(
            file = file.name,
            rawColor = RAW_COLOR.findAll(body).count(),
            rawRadius = RAW_RADIUS.findAll(body).count(),
            rawFontSize = RAW_FONT_SIZE.findAll(body).count(),
            rawDp = RAW_DP.findAll(body).count()
        )
    }

    fun scanDir(dir: File): List<FileStats> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { scan(it) }
            .sortedByDescending { it.total }
            .toList()

    /** 汇总成人能读的表。 */
    fun report(stats: List<FileStats>): String = buildString {
        appendLine("file".padEnd(34) + "color   radius  fontSize  dp   total")
        appendLine("-".repeat(72))
        for (s in stats) {
            if (s.total == 0) continue
            appendLine(
                s.file.padEnd(34) +
                    s.rawColor.toString().padEnd(8) +
                    s.rawRadius.toString().padEnd(8) +
                    s.rawFontSize.toString().padEnd(10) +
                    s.rawDp.toString().padEnd(5) +
                    s.total
            )
        }
        val sum = stats.sumOf { it.total }
        appendLine("-".repeat(72))
        appendLine("TOTAL hardcoded style tokens: " + sum)
    }
}
