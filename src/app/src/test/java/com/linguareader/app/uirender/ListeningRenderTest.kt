package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.linguareader.app.AppShapes
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LinguaPalette
import com.linguareader.app.ListeningBar
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.app.tts.TtsPlaybackState
import com.linguareader.shared.tts.DegradedReason
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 听书条（[ListeningBar]）的迁移视觉回归：before / after 两张 PNG。
 *
 * ## 为什么这一屏能直接渲染整块
 *
 * 与设置类弹层不同，[ListeningBar] 就是屏幕底部的一条，不是独立窗口（不是
 * ModalBottomSheet/Dialog），所以 Robolectric 能把它整块画进位图 —— 包括它自己
 * 按阅读主题铺的底色。这是本模块唯一能做「迁移前后整块对比」的屏。
 *
 * ## 画布尺寸
 *
 * 一律 [ComposeRenderHarness.renderDp]。渲染台的 qualifier 是 xxhdpi（density 3.0），
 * 而 `render()` 收的是像素；直接传 411×891 会得到 137dp 宽的可用空间，文字被裁成半截。
 * 具体坑见 `SheetMigrationRenderTest` 的类注释。
 *
 * ## 配色是硬约束（这一屏有历史事故）
 *
 * 听书条**完全跟随阅读主题**（[ReaderTheme] 的 background/foreground/markColor），
 * 而不是外壳调色板：此前用外壳的米白/夜间两套，用户选「护眼绿」时底栏是绿的、
 * 听书条却是米白。所以下面的断言直接对着**主题色**取样，一旦有人把配色改回
 * `LocalLinguaPalette`，这里立刻红。
 *
 * 出图位置：`app/build/ui-render-probe/listening-bar*.png`（1233×2673 @ xxhdpi 宽）。
 *
 * **不是真机替代品**：拖动条手势、溢出菜单弹窗、系统导航栏 inset、动画都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ListeningRenderTest {

    /** 与 `UiPreviews` 同一份典型播放态：有当前句、有进度、非 1.0 倍速。 */
    private val playingState = TtsPlaybackState(
        bookId = "render-book",
        chapterIndex = 2,
        sentenceIndex = 17,
        sentenceCount = 84,
        currentSentence = "The lantern library keeps every lamp lit until dawn.",
        isPlaying = true,
        speechRate = 1.15f
    )

    /**
     * 听书条贴屏幕底缘：画布上面是正文（用主题背景色铺满），下面是这一条。
     *
     * 用 `Box + Alignment.BottomCenter` 复刻 `ReaderScreen` 的真实位置 —— 那里是
     * `AnimatedVisibility(modifier = Modifier.align(Alignment.BottomCenter))`。
     * 直接顶格渲染会看不到「这条到底占多高」。
     */
    private fun renderBar(
        name: String,
        theme: ReaderTheme,
        state: TtsPlaybackState = playingState,
        choosingStart: Boolean = false,
        speakingOffscreen: Boolean = false,
        exitScrollMode: Boolean = false,
        skinDark: Boolean = theme == ReaderTheme.DARK || theme == ReaderTheme.AMOLED
    ) = ComposeRenderHarness.renderDp(name, 411, 891) {
        // 外壳皮肤与阅读主题同步（真机由 MainActivity 按 storedReaderTheme 选；
        // 听书条自己另按 theme 取色，两者同时喂进去才和真机一致）。
        provideLinguaTheme(if (skinDark) SkinBuiltIns.dark else SkinBuiltIns.light) {
            MaterialTheme(
                colorScheme = colorSchemeFor(if (skinDark) DarkLinguaPalette else LightLinguaPalette),
                shapes = AppShapes
            ) {
                Box(Modifier.fillMaxSize().background(themeColor(theme.background))) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        ListeningBar(
                            state = state,
                            theme = theme,
                            onToggle = {},
                            onPrevious = {},
                            onNext = {},
                            onStop = {},
                            onRateChange = {},
                            choosingStart = choosingStart,
                            onChooseStart = {},
                            speakingOffscreen = speakingOffscreen,
                            onBackToSpeaking = {},
                            onExitScrollMode = if (exitScrollMode) ({}) else null
                        )
                    }
                }
            }
        }
    }

    private fun themeColor(hex: String) = com.linguareader.app.themeColor(hex)

    /** 统计听书条区域内「不是主题底色」的采样点，用来断言它确实画出了内容。 */
    private fun nonBackgroundPixels(bmp: android.graphics.Bitmap, theme: ReaderTheme): Int {
        val background = themeColor(theme.background).toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != background) n++
            }
        }
        return n
    }

    /**
     * 底色必须是**阅读主题**的底色，不是外壳纸色。
     *
     * 取样点在听书条内部（左下角向内 8px）：那一带没有图标（控制键居中/靠右），
     * 只有条自己的背景。
     */
    @Test
    fun barBackgroundFollowsReaderThemeNotChromePalette() {
        val theme = ReaderTheme.GREEN
        val bmp = renderBar("listening-bar-green", theme)
        val px = bmp.getPixel(8, bmp.height - 8)
        assertTrue(
            "听书条底色必须是阅读主题 #GREEN 的 " + theme.background +
                "，实际 " + ComposeRenderHarness.hexColor(px) + "（若变成纸色说明配色被改回外壳调色板）",
            px == themeColor(theme.background).toArgb()
        )
        assertTrue("听书条必须画出内容", nonBackgroundPixels(bmp, theme) > 200)
        assertTrue("出图应当落盘", File("build/ui-render-probe/listening-bar-green.png").isFile)
    }

    /** 默认态（纸张主题 + 播放中）：迁移前后逐像素对照的主图。 */
    @Test
    fun paperPlayingBarRenders() {
        val bmp = renderBar("listening-bar", ReaderTheme.PAPER)
        assertNotEquals("听书条必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.PAPER))
    }

    /** 夜间主题：强调色换成提亮变体，图标与进度条不能糊进背景。 */
    @Test
    fun darkThemeBarRenders() {
        val bmp = renderBar("listening-bar-dark", ReaderTheme.DARK)
        assertNotEquals("夜间听书条必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.DARK))
    }

    /**
     * 朗读离屏提示：整行占宽 + 「回到朗读处」，**不能**把控制行撑开。
     *
     * 历史上它是挤爆控制行的元凶（从 48dp 图标撑成 ~110dp 文字按钮，把「停止」顶出屏幕），
     * 迁移时最容易被顺手改回控制行里 —— 所以单独出图。
     */
    @Test
    fun offscreenHintRenders() {
        val bmp = renderBar(
            "listening-bar-offscreen",
            ReaderTheme.PAPER,
            state = playingState.copy(highlightBlockIndex = 12),
            speakingOffscreen = true
        )
        assertNotEquals("离屏提示必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.PAPER))
    }

    /** 降级提示（6-6）：云端失败回退系统语音时必须有可见原因。 */
    @Test
    fun degradedHintRenders() {
        val bmp = renderBar(
            "listening-bar-degraded",
            ReaderTheme.PAPER,
            state = playingState.copy(degradedReason = DegradedReason.FALLBACK_TO_SYSTEM)
        )
        assertNotEquals("降级提示必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.PAPER))
    }

    /** 滑动模式的唯一出口（「分页」）：底栏隐藏后它必须还在听书条上。 */
    @Test
    fun scrollModeExitRenders() {
        val bmp = renderBar("listening-bar-scroll-mode", ReaderTheme.SEPIA, exitScrollMode = true)
        assertNotEquals("滑动模式出口必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.SEPIA))
    }

    /** 正在选起点：上行文案换成引导语。 */
    @Test
    fun choosingStartRenders() {
        val bmp = renderBar("listening-bar-choosing-start", ReaderTheme.PAPER, choosingStart = true)
        assertNotEquals("选起点提示必须画出内容", 0, nonBackgroundPixels(bmp, ReaderTheme.PAPER))
    }
}
