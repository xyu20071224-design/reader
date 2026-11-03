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
import com.linguareader.app.ReviewSettingsContent
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 复习设置弹层的迁移视觉回归（P3 第 3 屏 · 第一笔）。
 *
 * 与两个设置弹层同一套打法：`ModalBottomSheet` 在 Robolectric 里画不进位图，
 * 所以对比的是**内容体**。外壳（纸色、圆角、拖柄、底部留白）由 `LinguaSheet`
 * 统一，不参与逐屏对比。
 *
 * 出图位置：app/build/ui-render-probe/review-settings.png（1233×2673，411×891dp @ xxhdpi）
 *
 * **不是真机替代品**：通知权限系统弹窗、SAF、拖拽关闭都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewSettingsRenderTest {

    private fun hasContent(bmp: android.graphics.Bitmap): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until minOf(bmp.height, 800) step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    @Test
    fun settingsSheetContentRenders() {
        val bmp = ComposeRenderHarness.renderDp("review-settings", 411, 891) {
            CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        ReviewSettingsContent(
                            preset = ReviewMode.GENTLE,
                            custom = ReviewPace.defaultCustom(),
                            reminders = ReviewReminders.DEFAULT,
                            editingCustom = false,
                            notificationDenied = false,
                            onSelectPreset = {},
                            onToggleNotification = {},
                            onChangeReminders = {},
                            onChangeCustom = {},
                            onStartCustomEdit = {}
                        )
                    }
                }
            }
        }
        assertNotEquals("复习设置必须画出提醒行与节奏卡片", 0, hasContent(bmp))
        val out = File("build/ui-render-probe/review-settings.png")
        assertTrue("出图应当落盘：" + out.absolutePath, out.isFile)
    }
}
