package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **迁移覆盖守卫**（P10）：防止再有屏幕「被漏掉」。
 *
 * ## 为什么需要它
 *
 * P3 的屏幕清单是**手工列的**，按「文件行数大」排序。结果漏了 5 个真屏幕：
 * `AiProviderSettings`（551 行）、`GlossaryEditor`（434）、`SyncDetailScreen`（390）、
 * `LaunchPromptDialog`（169）、`ReaderThemePicker`（101）。
 *
 * 它们行数不够大所以没进清单，但都是用户会看到的界面 —— **在深色下与已迁移的屏明显不一致**。
 * 这类「漏掉」靠人工复查不可靠：清单是人写的，复查的人会照着同一个清单看。
 *
 * ## 判据
 *
 * 对 `src/main/java/com/linguareader/app/` 下每个 `.kt`：
 * 如果它**看起来是一个 UI 文件**（含 `@Composable`），那它的「旧调色板引用数」必须低于阈值。
 *
 * 旧调色板 = `ThemeColors.kt` 里那批顶层 `Color` 取值（`Ink` / `Accent` / `Paper` …）。
 * 它们仍然存在是**有意的**：`ThemeColors.kt` 本身要用、`MainActivity` 要从它派生 palette。
 * 但**具体屏幕不该再直接引用**——那是「还没迁」的标志。
 *
 * ## 为什么是「数量」而不是「零」
 *
 * 有些文件合法地用到一两个：比如 `BookshelfScreen` 里 `ShelfNoticeDialog` 的语义色，
 * 或者某处需要与 `ReaderTheme` 对齐。**判据钉太死会逼人写无意义的绕行**。
 * 所以用「超过 N 个就报警」——它抓的是「整屏没迁」这个量级，不是零星借用。
 *
 * ## 这个测试不会自己变绿
 *
 * 它读的是**源码文本**，不是编译产物。将来真有问题时，唯一的修法是把那个屏迁移掉，
 * 或者把它加进 [EXEMPT] 并写明理由 —— **加豁免必须留下书面理由**，否则下一个人不知道为什么会豁免。
 */
class MigrationCoverageGuardTest {

    /** 旧调色板的符号名（`ThemeColors.kt` 里那批顶层 `Color`）。 */
    private val oldPaletteSymbols = listOf(
        "Ink", "InkSoft", "InkFaint",
        "Accent", "AccentDeep", "AccentSoft", "OnAccent",
        "Paper", "PaperDeep", "CardSurface",
        "Gold", "GoldOnPaper", "Success", "Danger"
    )

    /**
     * 允许超出阈值的文件 → 理由。
     *
     * **加一条就要写清「为什么这个文件该直接用旧调色板」。**
     * 目前是空的 —— 说明全项目已经没有「整屏没迁」的文件了。
     */
    private val exempt: Map<String, String> = mapOf(
        // 调色板的**定义处**：它本身就是那批 Color 的来源，不是"未迁移"。
        "ThemeColors.kt" to "旧调色板的定义文件，它必须包含这些符号",
        // 阅读页外壳：顶栏/底栏/听书条必须跟 ReaderTheme（护眼绿主题下米白横杠
        // 横在绿色正文上是真机事故），刻意不跟皮肤 token。理由见文件 KDoc 与
        // 「贴正文的跟 ReaderTheme，浮在上面的跟 token」那条判据。
        "ReaderScreen.kt" to "阅读页外壳刻意跟随 ReaderTheme，不跟皮肤 token",
    )

    /**
     * 单个文件允许的旧调色板引用上限。超过就是「整屏没迁」。
     *
     * 定在 4 而不是 12：12 太松，能容忍「迁了一半」的文件——而「迁了一半」在深色下
     * 恰好是最难看的状态（一半跟皮肤、一半不跟）。4 足以容忍零星借用（如语义色对齐），
     * 又能抓住任何量级的半迁移。
     */
    private val allowedPerFile = 4

    @Test
    fun noComposableFileIsLeftEntirelyOnLegacyPalette() {
        val root = File("src/main/java/com/linguareader/app")
        assertTrue("源码目录不存在：${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                // 只看 UI 文件：含 @Composable 的才算。
                if (!text.contains("@Composable")) return@forEach
                if (exempt.containsKey(file.name)) return@forEach

                val stripped = stripCommentsAndStrings(text)
                val count = oldPaletteSymbols.sumOf { symbol ->
                    Regex("(?<![\\w.])${Regex.escape(symbol)}(?![\\w.])").findAll(stripped).count()
                }
                if (count > allowedPerFile) {
                    offenders += "${file.name}: $count 处旧调色板引用（上限 $allowedPerFile）"
                }
            }

        assertTrue(
            buildString {
                appendLine("以下 UI 文件疑似「整屏未迁移到设计系统」：")
                offenders.sorted().forEach { appendLine("  - $it") }
                appendLine()
                appendLine("修法：把该文件的 MaterialTheme.typography / 旧调色板 / 裸 dp")
                appendLine("换成 LocalDesignTokens，或加进 exempt 并写明理由。")
            },
            offenders.isEmpty()
        )
    }

    /**
     * 去掉注释与字符串字面量再统计。
     *
     * 不去掉会误报：KDoc 里写着「原实现是 `Ink`」这类说明性文字很常见。
     * （`HardcodedStyleScanner` 出于同样理由也这么做——那里是为了不把注释里的 `24.dp` 算进硬编码。）
     *
     * ## 为什么手写扫描而不是正则（这一版踩过 StackOverflowError）
     *
     * 第一版用的是 `Regex("\"(\\\\\"|[^\"\\\\])*\"")`，在长字符串字面量（尤其是
     * 一整个屏幕的 KDoc 里那种成段中文）上会**灾难性回溯**——栈直接爆掉，
     * 报的是 `StackOverflowError` 而不是断言失败，看上去像"测试框架坏了"。
     *
     * 现在的写法是**单次线性扫描**：逐字符走一遍，用 inString / inLineComment /
     * inBlockComment 三个状态决定是否折叠成空格。O(n)、无回溯、行为可预测。
     * 这个测试只需要"别把注释里的词算进去"，不需要精确到转义序列的语义，
     * 所以扫描版本完全够用。
     */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                // 行注释：吃到行尾（保留换行，行号不偏移）
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                // 块注释：吃到 */
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                // 字符串字面量：吃到未转义的收尾引号
                c == '"' -> {
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) { i += 2; continue }
                        if (src[i] == '"') { i++; break }
                        i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}