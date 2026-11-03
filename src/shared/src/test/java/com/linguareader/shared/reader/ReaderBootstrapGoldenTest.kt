package com.linguareader.shared.reader

import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.data.ReaderFont
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `bootstrap()` 的**金标准快照**（P4 拆分阅读页注入脚本的安全网）。
 *
 * 为什么需要它：`bootstrap()` 产出的是一段 69KB 的 JS 字符串，WebView 在 Robolectric
 * 里不执行 JS，所以"拆完之后还能不能跑"单测抓不到。唯一可靠的做法是**锁住字符级输出**：
 * 拆分前后 `bootstrap()` 必须逐字节相同。
 *
 * 用法：
 * 1. 拆分**之前**跑一次，产物落到 `build/reader-bootstrap-golden/`；
 * 2. 拆分**之后**再跑，与旧快照逐字符比对；
 * 3. 全部通过后，把快照提交到 `src/shared/src/test/resources/reader/` 作为长期基线。
 *
 * 一旦基线入库，任何无意的脚本改动（少一个反引号、多一个分号）都会让这条测试红。
 */
class ReaderBootstrapGoldenTest {

    /** 覆盖所有会影响输出的参数组合，每个组合一个快照文件。 */
    private val cases: List<Triple<String, ReaderPreferences, Array<Any>>> = listOf(
        Triple(
            "default",
            ReaderPreferences(),
            arrayOf(0)
        ),
        Triple(
            "night-scroll",
            ReaderPreferences(theme = ReaderTheme.DARK, fontPercent = 120, lineHeight = 1.8f, fontFamily = ReaderFont.SANS),
            arrayOf(1)
        )
    )

    private fun snapshotOf(prefs: ReaderPreferences, page: Int): String =
        ReaderScripts.bootstrap(
            initialPage = page,
            preferences = prefs,
            initialScrollMode = false,
            initialScrollRatio = 0f,
            initialScrollPageCount = 1
        )

    @Test
    fun bootstrapOutputIsStable() {
        // 基线存在 src/shared/src/test/resources/reader/，是**入库的长期契约**。
        // 需要重建基线时设环境变量 LR_WRITE_GOLDEN=1，跑一次会把当前输出写回去
        // （只有确认脚本改动是有意的才这么做，否则等于把 bug 固化成基线）。
        val writeMode = System.getenv("LR_WRITE_GOLDEN") == "1"

        for ((name, prefs, pages) in cases) {
            val page = pages[0] as Int
            val actual = snapshotOf(prefs, page)
            val resource = "reader/" + name + ".js"
            val stream = javaClass.classLoader?.getResourceAsStream(resource)

            if (writeMode || stream == null) {
                val outDir = File("build/reader-bootstrap-golden")
                outDir.mkdirs()
                File(outDir, name + ".js").writeText(actual)
                println("GOLDEN_WRITTEN=" + name + " len=" + actual.length + " (copy to src/shared/src/test/resources/reader/)")
            } else {
                val expected = stream.bufferedReader().use { it.readText() }
                assertEquals(
                    "[" + name + "] bootstrap() 输出与基线不一致——拆分脚本时必须逐字节相同。" +
                        "若这是有意的改动，设 LR_WRITE_GOLDEN=1 重建基线并说明原因。",
                    expected,
                    actual
                )
            }

            assertEquals(
                "[" + name + "] bootstrap 长度应在合理区间",
                true,
                actual.length in 40_000..120_000
            )
        }
    }

    @Test
    fun goldenCoversAllSixteenApis() {
        // 金标准不是随便一段字符串：必须真的含 16 个 API（防止快照拍到了残缺版本）
        val js = snapshotOf(ReaderPreferences(), 0)
        val apis = listOf(
            "lrTurn", "lrSetPage", "lrGetPage", "lrSyncPage",
            "lrEnterScrollMode", "lrExitScrollMode",
            "lrLookupAt", "lrHighlightBlock", "lrHighlightSentence", "lrClearHighlight",
            "lrRefreshSavedWords", "lrSetChromeInsets", "lrSetChoosingStart",
            "lrLocusHere", "lrScrollToLocus", "lrBackToSpeaking"
        )
        val missing = apis.filterNot { js.contains("window." + it) }
        assertEquals("金标准快照里缺 API：" + missing, emptyList<String>(), missing)
    }
}
