package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.VocabularyScreen
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.shared.data.SavedWord
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 生词本**整屏**截图夹具：专门用来做迁移 before / after 并排对照。
 *
 * 关键约束：本文件只依赖 `VocabularyScreen(...)` 的**公开参数签名**，
 * 不引用任何迁移过程中新增的符号（`VocabularyContent` / `vocabularyUiState`）。
 * 因此它对新旧两版实现都能编译——这正是「同一个夹具、同一份数据、只换实现」
 * 能得到可并排对照的两张图的前提。
 *
 * 出图流程：
 * ```
 * git stash push <只 stash 实现与新增测试>   # 回到迁移前
 * <run VocabScreenShotTest>                # 产出 vocab-screen.png = before
 * git stash pop                            # 恢复迁移后
 * <run VocabScreenShotTest>                # 产出 vocab-screen.png = after
 * ```
 * 两次都是 `vocab-screen.png`（同名同尺寸同内容），复制成 `-before` / `-after` 并排看即可。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VocabScreenShotTest {

    private val now = 1_700_000_000_000L

    private fun word(
        id: String,
        headword: String,
        meaning: String,
        aiMeaning: String,
        nextReviewAt: Long,
        reviewLevel: Int
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

    @Test
    fun fullScreenShot() {
        val bmp = ComposeRenderHarness.renderDp("vocab-screen", 411, 891) {
            CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        VocabularyScreen(
                            words = listOf(
                                word("1", "ephemeral", "adj. 短暂的；转瞬即逝的", "（语境）转瞬即逝的", now - 1, 0),
                                word("2", "resilient", "adj. 有韧性的；能恢复的", "", now + 86_400_000L, 7),
                                word("3", "serendipity", "n. 机缘巧合", "", now + 3 * 86_400_000L, 0)
                            ),
                            reviewPreset = ReviewMode.DEFAULT,
                            customReview = ReviewPace.defaultCustom(),
                            reminders = ReviewReminders.DEFAULT,
                            onReviewModeChange = {},
                            onCustomReviewChange = {},
                            onRemindersChange = {},
                            onRemove = {},
                            onReview = { _, _, _ -> },
                            onExport = {},
                            onSpeak = {}
                        )
                    }
                }
            }
        }
        val paper = LightLinguaPalette.paper.toArgb()
        var nonPaper = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                if (bmp.getPixel(x, y) != paper) nonPaper++
            }
        }
        assertTrue("整屏必须画出词卡（实际 $nonPaper 个非纸色采样点）", nonPaper > 1000)
        val out = File("build/ui-render-probe/vocab-screen.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
    }
}
