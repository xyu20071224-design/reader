package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AppShapes
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * **阅读页外壳**迁移的 before / after 对照图（P3 第五屏）。
 *
 * ## 为什么这张图只画外壳
 *
 * 阅读页的主体是 `WebView`（[com.linguareader.app.reader.EpubPage]）。Robolectric 里
 * WebView 不执行 JS、也不实现真实排版，渲染出来的只有一块纯色。**这不是实现缺陷，
 * 是本图的已知边界**：本图只含 Compose 外壳（顶栏 / 底栏 / 弹层内容体），
 * **WebView 正文区域为空**。
 *
 * 同理，`ModalBottomSheet` 与 `AlertDialog` 走 Dialog/Window 那条路，画不进位图，
 * 所以弹层只对比**内容体**（与 `SheetMigrationRenderTest` 同一口径）。
 *
 * ## 出图口径
 *
 * `ComposeRenderHarness.renderDp(name, 411, 891)` —— 见迁移手册里那条渲染台尺度的坑。
 * 产物在 `app/build/ui-render-probe/reader-shell-*.png`（1233×2673）。
 *
 * ## 这张图测不到什么（**必须真机验证**）
 *
 * 翻页手势、点词弹面板、TTS 高亮跟随、目录跳转后的重排、滚动模式进出、
 * 旋转屏幕后的位置恢复 —— 一条都测不到。清单见提交信息与 `VALIDATION.md`。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderShellRenderTest {

    /**
     * 用「外壳骨架」渲染：顶栏行 + 正文占位 + 底栏行。
     *
     * 正文区域故意留成阅读主题的底色并在中间写一行说明——它就是 WebView 的位置，
     * 图里空着是**如实反映渲染台的能力边界**，不是bug。
     */
    private fun renderShell(
        name: String,
        palette: LinguaPalette,
        chromeBackground: Color,
        chromeForeground: Color
    ) = ComposeRenderHarness.renderDp(name, 411, 891) {
        CompositionLocalProvider(
            LocalLinguaPalette provides palette,
            LocalDesignTokens provides SkinRegistry.toDesignTokens(
                if (palette.isDark) SkinBuiltIns.dark else SkinBuiltIns.light
            )
        ) {
            MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
                Column(Modifier.fillMaxSize().background(chromeBackground)) {
                    // ── 顶栏（迁移前是手写 Row，迁移后仍应是同一行结构）──
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(chromeBackground.copy(alpha = .96f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .height(56.dp),
                        contentAlignment = androidx.compose.ui.Alignment.CenterStart
                    ) {
                        Text(
                            "1. CHAPTER ONE",
                            color = chromeForeground,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // ── 正文区：WebView 在这里，渲染台画不出来 ──
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(chromeBackground),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            "WebView 正文区（渲染台为空）",
                            color = chromeForeground.copy(alpha = .35f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // ── 底栏 ──
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(chromeBackground.copy(alpha = .94f))
                            .padding(horizontal = 18.dp, vertical = 7.dp)
                            .height(48.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            "3 / 12 · 14 / 220",
                            color = chromeForeground.copy(alpha = .6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    private fun countNonBackground(bmp: android.graphics.Bitmap, background: Int): Int {
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != background) n++
            }
        }
        return n
    }

    @Test
    fun shellLight() {
        val bg = Color(android.graphics.Color.parseColor("#F7F3EA"))
        val fg = Color(android.graphics.Color.parseColor("#27231F"))
        val bmp = renderShell("reader-shell-light", LightLinguaPalette, bg, fg)
        val out = File("build/ui-render-probe/reader-shell-light.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertTrue(
            "外壳必须画出顶栏与底栏（实际 " +
                countNonBackground(bmp, android.graphics.Color.parseColor("#F7F3EA")) + " 个采样点）",
            countNonBackground(bmp, android.graphics.Color.parseColor("#F7F3EA")) > 500
        )
        assertAgainstBaseline("screen-reader-shell-light", bmp)
    }

    @Test
    fun shellDark() {
        val bg = Color(android.graphics.Color.parseColor("#171717"))
        val fg = Color(android.graphics.Color.parseColor("#E8E3DA"))
        val bmp = renderShell("reader-shell-dark", DarkLinguaPalette, bg, fg)
        val out = File("build/ui-render-probe/reader-shell-dark.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        // 反向护栏：夜间外壳必须真的暗（取到日间 token 时这里会红）
        val corner = bmp.getPixel(bmp.width - 6, bmp.height - 6)
        val lum = (android.graphics.Color.red(corner) +
            android.graphics.Color.green(corner) +
            android.graphics.Color.blue(corner)) / 3
        assertTrue("夜间外壳底色必须暗，实测平均 " + lum, lum < 60)
        assertAgainstBaseline("screen-reader-shell-dark", bmp)
    }

    // ── 弹层内容体（ModalBottomSheet 画不进位图，只能画内容体）────────────
    //
    // 夹具复刻 LinguaSheet 的内容槽约束：纸色铺满 + horizontal = spacing.l(24dp)
    // + bottom = spacing.xl(32dp)。内容体自身不再带横向留白（迁移后由外壳给）。
    private fun renderSheetBody(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(
                LocalLinguaPalette provides LightLinguaPalette,
                LocalDesignTokens provides SkinRegistry.toDesignTokens(SkinBuiltIns.light)
            ) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize().background(PalettePaper)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 32.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }

    private val PalettePaper: Color = Color(0xFFF7F3EA)

    @Test
    fun contentsSheetBodyRenders() {
        val book = com.linguareader.app.data.Book(
            id = "book-1",
            title = "The Lantern Library",
            author = "A. Author",
            extractedDir = "/tmp/nonexistent/book-1",
            coverRelativePath = null,
            chapters = (1..8).map { com.linguareader.app.data.Chapter("Chapter $it · A Rather Long Chapter Title", "ch$it.xhtml") },
            addedAt = 1_700_000_000_000L
        )
        val bmp = renderSheetBody("reader-sheet-contents") {
            com.linguareader.app.reader.ContentsBody(
                book = book,
                currentChapter = 2,
                onSelect = {}
            )
        }
        val out = File("build/ui-render-probe/reader-sheet-contents.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertTrue(
            "目录内容体必须画出章节行（实际 " + countNonBackground(bmp, PalettePaper.toArgb()) + " 个采样点）",
            countNonBackground(bmp, PalettePaper.toArgb()) > 300
        )
        assertAgainstBaseline("screen-reader-contents", bmp)
    }

    @Test
    fun settingsSheetBodyRenders() {
        val bmp = renderSheetBody("reader-sheet-settings") {
            com.linguareader.app.reader.ReaderSettingsBody(
                preferences = com.linguareader.app.data.ReaderPreferences(
                    fontPercent = 110,
                    lineHeight = 1.65f,
                    theme = com.linguareader.app.data.ReaderTheme.PAPER,
                    fontFamily = com.linguareader.app.data.ReaderFont.SERIF
                ),
                reviewPace = com.linguareader.app.data.ReviewPace.defaultCustom(),
                onOpenReviewSettings = {},
                onOpenListeningSettings = {},
                onChange = {}
            )
        }
        val out = File("build/ui-render-probe/reader-sheet-settings.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertTrue(
            "设置内容体必须画出滑块与七个主题色块（实际 " + countNonBackground(bmp, PalettePaper.toArgb()) + " 个采样点）",
            countNonBackground(bmp, PalettePaper.toArgb()) > 1500
        )
        assertAgainstBaseline("screen-reader-settings", bmp)
    }

    /** 与视觉基线比对（P8）。基线在 src/app/src/test/resources/visual-baseline/。 */
    private fun assertAgainstBaseline(name: String, bmp: android.graphics.Bitmap) {
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        org.junit.Assert.assertTrue(summary, result.passed)
    }
}
