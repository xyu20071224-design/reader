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
import com.linguareader.app.ReviewCardContent
import com.linguareader.app.colorSchemeFor
import com.linguareader.shared.data.SavedWord
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 复习主界面的迁移视觉回归（P3 第 3 屏 · 第二笔）。
 *
 * 三张图覆盖卡片真正会出现的三种态：
 * - 正面（未揭晓，只有「显示释义」）
 * - 背面（已揭晓，两个评分按钮）
 * - 全部复习完（结算态）
 *
 * 与其余已迁弹层同一套打法：只渲染**内容体**，外壳（纸色/圆角/拖柄）由 `LinguaSheet`
 * 统一，不参与逐屏对比。
 *
 * 出图位置：app/build/ui-render-probe/review-card-{front,back,done}.png（1233×2673）
 *
 * **不是真机替代品**：评分回调的异步往返、拖拽关闭、TTS 发声都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewCardRenderTest {

    private val sample = SavedWord(
        id = "w-1",
        headword = "ephemeral",
        phonetic = "ˈɛhɛmərəl",
        meaning = "adj. 短暂的；转瞬即逝的",
        aiMeaning = "（语境）转瞬即逝的",
        aiSource = "《The Hobbit》",
        sentence = "The ephemeral beauty of the lantern festival faded before dawn.",
        bookId = "book-1",
        bookTitle = "The Hobbit",
        chapterTitle = "Chapter 1",
        addedAt = 1_700_000_000_000L,
        reviewLevel = 2,
        nextReviewAt = 1_700_000_000_000L
    )

    private fun hasContent(bmp: android.graphics.Bitmap): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until minOf(bmp.height, 900) step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    private fun renderCard(name: String, allDone: Boolean, revealed: Boolean) {
        val bmp = ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        ReviewCardContent(
                            word = sample,
                            cardIndex = 0,
                            deckSize = 12,
                            completedCount = if (allDone) 12 else 0,
                            allDone = allDone,
                            revealed = revealed,
                            busy = false,
                            onDismissRequest = {},
                            onReveal = {},
                            onSpeak = {},
                            onFinish = {}
                        )
                    }
                }
            }
        }
        assertNotEquals("$name 必须画出内容", 0, hasContent(bmp))
        val out = File("build/ui-render-probe/$name.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
    }

    @Test
    fun cardFrontRenders() = renderCard("review-card-front", allDone = false, revealed = false)

    @Test
    fun cardBackRenders() = renderCard("review-card-back", allDone = false, revealed = true)

    @Test
    fun cardDoneStateRenders() = renderCard("review-card-done", allDone = true, revealed = false)
}
