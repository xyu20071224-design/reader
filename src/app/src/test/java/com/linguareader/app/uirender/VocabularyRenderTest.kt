package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.VocabularyContent
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.vocabularyUiState
import com.linguareader.shared.data.SavedWord
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 生词本迁移的视觉回归（P3 第三屏）：渲染**纯展示层**的三种状态。
 *
 * 整屏 before / after 截图在单独的 [VocabScreenShotTest] 里——那个文件只依赖
 * `VocabularyScreen(...)` 的公开参数签名，不引用迁移中新增的任何符号，
 * 所以它能对新旧两版实现都编译通过，同一个夹具直接产出一对可并排的图。
 * 本文件则专注「列表 / 空库 / 搜不到」三种态的渲染回归。
 *
 * 出图位置：app/build/ui-render-probe/vocab-{list,empty,no-match}.png（1233×2673，411×891dp @ xxhdpi）
 *
 * **不是真机替代品**：滚动、点击、SAF 都覆盖不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VocabularyRenderTest {

    private val now = 1_700_000_000_000L

    private fun word(
        id: String,
        headword: String,
        meaning: String = "n. 释义",
        aiMeaning: String = "",
        nextReviewAt: Long = 0L,
        reviewLevel: Int = 0
    ) = SavedWord(
        id = id,
        headword = headword,
        phonetic = "ˈhɛdwɜːd",
        meaning = meaning,
        aiMeaning = aiMeaning,
        aiSource = "语境",
        sentence = "The word appears in this sentence for context.",
        bookId = "book-1",
        bookTitle = "The Hobbit",
        chapterTitle = "Chapter 1",
        addedAt = now - 1_000_000L,
        reviewLevel = reviewLevel,
        nextReviewAt = nextReviewAt
    )

    /** 三种态共用的样本词表：有到期的、有已掌握的、有普通的。 */
    private val sampleWords = listOf(
        word("1", "ephemeral", "adj. 短暂的；转瞬即逝的", aiMeaning = "（语境）转瞬即逝的", nextReviewAt = now - 1),
        word("2", "resilient", "adj. 有韧性的；能恢复的", reviewLevel = 7, nextReviewAt = now + 86_400_000L),
        word("3", "serendipity", "n. 机缘巧合", nextReviewAt = now + 3 * 86_400_000L)
    )

    @Composable
    private fun themed(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
            MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                Box(Modifier.fillMaxSize().background(Paper)) { content() }
            }
        }
    }

    private fun hasContent(bmp: android.graphics.Bitmap): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    @Test
    fun populatedListRenders() {
        val bmp = ComposeRenderHarness.renderDp("vocab-list", 411, 891) {
            themed {
                VocabularyContent(
                    state = vocabularyUiState(sampleWords, query = "", now = now),
                    onQueryChange = {},
                    onReviewClick = {},
                    onExportClick = {},
                    onSettingsClick = {},
                    onSpeak = {},
                    onRemove = {}
                )
            }
        }
        assertTrue("生词本列表必须画出词卡与统计条（实际 ${hasContent(bmp)} 个非纸色采样点）", hasContent(bmp) > 1000)
        assertAgainstBaseline("screen-vocab-list", bmp)
    }

    @Test
    fun emptyLibraryShowsTheGuidanceState() {
        val bmp = ComposeRenderHarness.renderDp("vocab-empty", 411, 891) {
            themed {
                VocabularyContent(
                    state = vocabularyUiState(emptyList(), query = "", now = now),
                    onQueryChange = {},
                    onReviewClick = {},
                    onExportClick = {},
                    onSettingsClick = {},
                    onSpeak = {},
                    onRemove = {}
                )
            }
        }
        // 空态仍然要画标题/统计/搜索框，只是列表位换成引导 —— 不能整屏空白。
        assertNotEquals("空生词本不能渲染成整屏纯纸色", 0, hasContent(bmp))
        assertAgainstBaseline("screen-vocab-empty", bmp)
    }

    @Test
    fun noMatchStateRenders() {
        val bmp = ComposeRenderHarness.renderDp("vocab-no-match", 411, 891) {
            themed {
                VocabularyContent(
                    state = vocabularyUiState(sampleWords, query = "zzz-no-such-word", now = now),
                    onQueryChange = {},
                    onReviewClick = {},
                    onExportClick = {},
                    onSettingsClick = {},
                    onSpeak = {},
                    onRemove = {}
                )
            }
        }
        assertNotEquals("搜不到时也必须渲染内容（搜索框 + 空态引导）", 0, hasContent(bmp))
        assertAgainstBaseline("screen-vocab-no-match", bmp)
    }

    /** 与视觉基线比对（P8）。基线在 src/app/src/test/resources/visual-baseline/。 */
    private fun assertAgainstBaseline(name: String, bmp: android.graphics.Bitmap) {
        val result = VisualBaseline.compare(name, bmp)
        val summary = VisualBaseline.describe(name, result)
        println(summary)
        org.junit.Assert.assertTrue(summary, result.passed)
    }
}