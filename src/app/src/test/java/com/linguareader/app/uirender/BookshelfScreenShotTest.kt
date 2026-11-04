package com.linguareader.app.uirender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.linguareader.app.AppShapes
import com.linguareader.app.AppUiState
import com.linguareader.app.BookshelfScreen
import com.linguareader.app.DarkLinguaPalette
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.UiAnimSpeed
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.update.AppUpdateUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * **书架整屏截图夹具**：只用来做迁移 before / after 并排对照（P3 第四屏）。
 *
 * ## 为什么它能同时编过 before 与 after
 *
 * 本文件只依赖 `BookshelfScreen(...)` 的**公开参数签名**与 `AppUiState` 的字段，
 * 不引用迁移过程中新增的任何符号。P3 的既定约束就是「本轮不改那 54 个参数」，
 * 所以同一个夹具、同一份数据、只换实现，就能产出可并排的两张图。
 *
 * 出图流程（**不用 git stash**：这个 worktree 有并行同事，stash 会收走他们的改动）：
 * ```
 * git show HEAD:src/app/.../BookshelfScreen.kt > 临时文件；覆盖工作区那一份   # 回到迁移前
 * <run BookshelfScreenShotTest>                                        # = before
 * 从临时文件还原工作区                                                  # 回到迁移后
 * <run BookshelfScreenShotTest>                                        # = after
 * ```
 * 两次都产出同名同尺寸的 PNG，复制成 `-before` / `-after` 并排看即可。
 *
 * ## 出图位置
 * app/build/ui-render-probe/bookshelf-{list,empty}-{light,dark}.png（1233×2673，411×891dp @ xxhdpi）
 *
 * ## 夹具的两处诚实取舍（不是实现缺陷）
 * - 书封面文件不存在：三本书都走「无封面 → 渐变 + 书名」分支，那条分支正是书卡的主体；
 *   真实有封面的书只是把这块换成图，不影响排版对照。
 * - 弹层与对话框不参与出图：`ModalBottomSheet` 与 `AlertDialog` 走 Window/Dialog
 *   那条路，Robolectric 画不进这张位图（与 6 个弹层的迁移夹具同一限制）。
 *
 * **不是真机替代品**：滚动、长按、SAF、弹层拖拽都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BookshelfScreenShotTest {

    private val now = 1_700_000_000_000L

    /** 三本书：一本读了 42%（带进度环）、一本有 AI 语境且带译本（档案过期）、一本全新。 */
    private val sampleBooks = listOf(
        Book(
            id = "book-1",
            title = "The Hobbit",
            author = "J. R. R. Tolkien",
            extractedDir = "/tmp/nonexistent/book-1",
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "ch1.xhtml")),
            addedAt = now - 3_000_000L,
            progress = 0.42f
        ),
        Book(
            id = "book-2",
            title = "Pride and Prejudice",
            author = "Jane Austen",
            extractedDir = "/tmp/nonexistent/book-2",
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "ch1.xhtml")),
            addedAt = now - 2_000_000L,
            translationBookId = "translation-2",
            translationTitle = "傲慢与偏见"
        ),
        Book(
            id = "book-3",
            title = "Walden",
            author = "Henry David Thoreau",
            extractedDir = "/tmp/nonexistent/book-3",
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "ch1.xhtml")),
            addedAt = now - 1_000_000L
        )
    )

    private fun state(books: List<Book>) = AppUiState(
        books = books,
        loading = false,
        aiSettings = AiSettings(enabled = true),
        aiStatuses = mapOf(
            "book-1" to AiBookStatus(ready = true),
            "book-2" to AiBookStatus(generating = true)
        ),
        // 版本闸门：book-2 的对照档案是旧对齐器写的，书卡上要有可见提示。
        outdatedTranslations = setOf("book-2"),
        update = AppUpdateUiState()
    )

    private fun countNonPaper(bmp: android.graphics.Bitmap, paper: Int): Int {
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    /** 渲染整屏。dark 只切换外壳深浅，与生产里 `paletteFor(readerTheme, systemDark)` 同口径。 */
    private fun renderBookshelf(
        name: String,
        dark: Boolean,
        books: List<Book>
    ): android.graphics.Bitmap {
        val palette = if (dark) DarkLinguaPalette else LightLinguaPalette
        val tokens = SkinRegistry.toDesignTokens(if (dark) SkinBuiltIns.dark else SkinBuiltIns.light)
        return ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(
                LocalLinguaPalette provides palette,
                LocalDesignTokens provides tokens
            ) {
                MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize()) {
                        BookshelfScreen(
                            state = state(books),
                            onImport = {},
                            onOpen = {},
                            onDelete = {},
                            onAttachTranslation = { _, _ -> },
                            onDetachTranslation = {},
                            onPrepareAiTranslation = {},
                            onStartAiTranslation = { _, _, _ -> },
                            onCancelAiTranslation = {},
                            onDismissAiTranslationPrepare = {},
                            onPrepareManualTranslation = {},
                            onDismissManualTranslationPrepare = {},
                            onExportManualTranslation = { _, _ -> },
                            onTakeManualExport = { null },
                            onManualExportReady = {},
                            onCancelManualExport = {},
                            onImportManualResults = { _, _ -> },
                            onClearManualImportRejected = {},
                            onAiSettingsChange = {},
                            onLoadGlossary = { bookId: String -> BookGlossary(bookId) },
                            onAddGlossary = { bookId: String, _: String, _: String -> BookGlossary(bookId) },
                            onUpdateGlossary = { bookId: String, _: com.linguareader.app.ai.GlossaryEntry ->
                                BookGlossary(bookId)
                            },
                            onRemoveGlossary = { bookId: String, _: String -> BookGlossary(bookId) },
                            onRemoveWord = {},
                            onReviewModeChange = {},
                            onCustomReviewChange = {},
                            onRemindersChange = {},
                            onReviewWord = { _: String, _: Boolean, done: (Boolean) -> Unit -> done(true) },
                            onExportVocabulary = {},
                            onSpeak = {},
                            update = AppUpdateUiState(),
                            onCheckForUpdate = {},
                            onAutoCheckChange = {},
                            onDownloadUpdate = {},
                            onCancelUpdateDownload = {},
                            onDismissMessage = {},
                            uiAnimSpeed = UiAnimSpeed.NORMAL,
                            onAnimSpeedChange = {},
                            onReaderThemeChanged = {}
                        )
                    }
                }
            }
        }
    }

    /**
     * 与视觉基线比对（P8）。基线在 `src/app/src/test/resources/visual-baseline/`。
     *
     * 这是**整屏级别**的回归网：书架的 678 行里任何一个组件被改动、任何一处间距/配色
     * 偏移，只要画到像素上就会让这条红。比「出图后人眼看」强一个量级。
     */
    private fun assertAgainstBaseline(name: String, bmp: android.graphics.Bitmap) {
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        assertTrue(summary, result.passed)
    }

    @Test
    fun populatedShelfLight() {
        val bmp = renderBookshelf("bookshelf-list-light", dark = false, books = sampleBooks)
        val content = countNonPaper(bmp, LightLinguaPalette.paper.toArgb())
        assertTrue("书架必须画出三张书卡与顶栏（实际 $content 个非纸色采样点）", content > 2000)
        val out = File("build/ui-render-probe/bookshelf-list-light.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertAgainstBaseline("screen-bookshelf-list-light", bmp)
    }

    @Test
    fun populatedShelfDark() {
        val bmp = renderBookshelf("bookshelf-list-dark", dark = true, books = sampleBooks)
        assertTrue(
            "夜间书架必须画出内容",
            countNonPaper(bmp, DarkLinguaPalette.paper.toArgb()) > 2000
        )
        // 反向护栏：夜间底色必须真的很暗。若取到日间 token（#F7F3EA），这条会红——
        // 那正是 MainActivity 漏下发 LocalDesignTokens 时的症状。
        val corner = bmp.getPixel(bmp.width - 6, bmp.height - 6)
        val lum = (android.graphics.Color.red(corner) +
            android.graphics.Color.green(corner) +
            android.graphics.Color.blue(corner)) / 3
        assertTrue("夜间书架底色必须暗，实测平均 $lum（若是 247 说明取到了日间 token）", lum < 60)
        val out = File("build/ui-render-probe/bookshelf-list-dark.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertAgainstBaseline("screen-bookshelf-list-dark", bmp)
    }

    @Test
    fun emptyShelfLight() {
        val bmp = renderBookshelf("bookshelf-empty-light", dark = false, books = emptyList())
        assertTrue(
            "空书架必须画出引导（图标 + 标题 + 说明 + 按钮）",
            countNonPaper(bmp, LightLinguaPalette.paper.toArgb()) > 1000
        )
        val out = File("build/ui-render-probe/bookshelf-empty-light.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
        assertAgainstBaseline("screen-bookshelf-empty-light", bmp)
    }
}
