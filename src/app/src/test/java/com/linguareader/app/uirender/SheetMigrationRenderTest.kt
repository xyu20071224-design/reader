package com.linguareader.app.uirender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.linguareader.app.AppShapes
import com.linguareader.app.LightLinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.Paper
import com.linguareader.app.ShelfAppearanceContent
import com.linguareader.app.UpdateSheetContent
import com.linguareader.app.colorSchemeFor
import com.linguareader.shared.update.AppUpdatePhase
import com.linguareader.shared.update.AppUpdateUiState
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置类弹层迁移的视觉回归：before / after 两张 PNG。
 *
 * ## 为什么只渲染内容体
 *
 * `ModalBottomSheet` 在 Robolectric 里画不进位图（它走 Dialog/Window 那条路），
 * 所以迁移前/后对比只能对内容体做。内容体本身正是用户真正看到的东西——
 * 外壳（圆角、拖柄、遮罩）由 `LinguaSheet` 统一，不参与逐屏对比。
 *
 * ## 画布尺寸必须由 density 反算（踩过的坑）
 *
 * 渲染台的 qualifier 是 `xxhdpi`（density 3.0），如果按 `render(name, 540, 960)`
 * 传**像素**，Compose 拿到的可用宽度只有 540px ÷ 3 = **180dp**——内容按 180dp 排版，
 * 长一点的文案就是「半截单词」+「纸色在内容底部戛然而止」。
 * 那不是屏幕的问题，是夹具把画布尺寸当成了 dp。
 * 正确做法：先用当前 density 把 411dp × 891dp 换算成像素，再交给渲染台。
 *
 * 出图位置：app/build/ui-render-probe/sheet-*.png
 *
 * **这不是真机替代品**：阴影、弹性下滑、状态栏 insets 都测不到。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SheetMigrationRenderTest {

    private fun renderSheetContent(name: String, content: @Composable ColumnScope.() -> Unit) =
        ComposeRenderHarness.renderDp(name, 411, 891) {
            CompositionLocalProvider(LocalLinguaPalette provides LightLinguaPalette) {
                MaterialTheme(colorScheme = colorSchemeFor(LightLinguaPalette), shapes = AppShapes) {
                    // 纸色铺满整块画布：弹层在真机上是整屏 sheet，纸色不会在内容底部断开。
                    Box(Modifier.fillMaxSize().background(Paper)) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp)
                        ) {
                            // 标题由 LinguaSheet 的 title 槽渲染，夹具里补同一份，图才和真机一致。
                            content()
                        }
                    }
                }
            }
        }

    /**
     * 造一个该阶段的真实状态。
     *
     * ERROR 必须带上错误文案：生产代码里 `AppViewModel` 进 ERROR 时**总是**同时写
     * `error`（`AppViewModel.kt:1149/1168`），夹具里传 null 会画出「Update failed:」
     * 后面空一截——那是夹具失真，不是实现丢文案。
     */
    private fun update(phase: AppUpdatePhase) = AppUpdateUiState(
        phase = phase,
        error = if (phase == AppUpdatePhase.ERROR) "connection reset by peer" else null
    )

    private fun countNonPaper(bmp: android.graphics.Bitmap, maxY: Int): Int {
        val paper = LightLinguaPalette.paper.toArgb()
        var n = 0
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until minOf(maxY, bmp.height) step 3) {
                if (bmp.getPixel(x, y) != paper) n++
            }
        }
        return n
    }

    // ── ShelfAppearanceSheet ──────────────────────────────────────

    private fun appearance(presetId: String?, image: String? = null) =
        com.linguareader.app.ShelfAppearance(
            presetId = presetId,
            imageFile = image,
            dimOpacity = 0.35f
        )

    @Test
    fun shelfAppearanceSheetContentRenders() {
        val bmp = renderSheetContent("sheet-appearance") {
            ShelfAppearanceContent(
                appearance = appearance(presetId = null),
                onAppearanceChange = {},
                animSpeed = com.linguareader.app.UiAnimSpeed.NORMAL,
                onAnimSpeedChange = {},
                selectedTheme = com.linguareader.shared.data.ReaderTheme.PAPER,
                onSelectTheme = {},
                darkChrome = false,
                imageStatus = null,
                onImageStatusShown = {},
                onPickImage = {},
                onReset = {}
            )
        }
        val nonPaper = countNonPaper(bmp, bmp.height / 2)
        assertTrue("外观弹层必须画出预设色块与主题选择器（$nonPaper 个非纸色采样点）", nonPaper > 200)
    }

    @Test
    fun shelfAppearanceShowsTheDisabledPresetHintOnDarkChrome() {
        // 深色外壳下预设不可用、并且必须**说明原因**（审查 5-1）——这条文案是产品承诺，
        // 迁移时最容易在换组件的过程中被顺手丢掉。
        val dark = renderSheetContent("sheet-appearance-dark-chrome") {
            ShelfAppearanceContent(
                appearance = appearance(presetId = "green"),
                onAppearanceChange = {},
                animSpeed = com.linguareader.app.UiAnimSpeed.SLOW,
                onAnimSpeedChange = {},
                selectedTheme = com.linguareader.shared.data.ReaderTheme.DARK,
                onSelectTheme = {},
                darkChrome = true,
                imageStatus = null,
                onImageStatusShown = {},
                onPickImage = {},
                onReset = {}
            )
        }
        val light = renderSheetContent("sheet-appearance-light-chrome") {
            ShelfAppearanceContent(
                appearance = appearance(presetId = "green"),
                onAppearanceChange = {},
                animSpeed = com.linguareader.app.UiAnimSpeed.SLOW,
                onAnimSpeedChange = {},
                selectedTheme = com.linguareader.shared.data.ReaderTheme.PAPER,
                onSelectTheme = {},
                darkChrome = false,
                imageStatus = null,
                onImageStatusShown = {},
                onPickImage = {},
                onReset = {}
            )
        }
        // 深色外壳多一行说明文案 → 内容更高。用像素差异间接守住「说明没被丢掉」。
        val darkNonPaper = countNonPaper(dark, dark.height / 2)
        val lightNonPaper = countNonPaper(light, light.height / 2)
        assertTrue(
            "深色外壳必须比浅色多画出禁用说明（dark=$darkNonPaper / light=$lightNonPaper）",
            darkNonPaper > lightNonPaper
        )
    }

    @Test
    fun updateSheetContentRenders() {
        val bmp = renderSheetContent("sheet-update-idle") {
            UpdateSheetContent(
                update = update(AppUpdatePhase.IDLE),
                canInstall = false,
                onAutoCheckChange = {},
                onCheckNow = {},
                onDownload = {},
                onCancelDownload = {},
                onGrantInstallPermission = {}
            )
        }
        val nonPaper = countNonPaper(bmp, bmp.height / 3)
        assertTrue("内容体必须画出东西（实际 $nonPaper 个非纸色采样点）", nonPaper > 50)
    }

    @Test
    fun everyUpdatePhaseRenders() {
        for (phase in AppUpdatePhase.entries) {
            val bmp = renderSheetContent("sheet-update-" + phase.name.lowercase()) {
                UpdateSheetContent(
                    update = update(phase).copy(progress = 42, autoCheckEnabled = true),
                    canInstall = true,
                    onAutoCheckChange = {},
                    onCheckNow = {},
                    onDownload = {},
                    onCancelDownload = {},
                    onGrantInstallPermission = {}
                )
            }
            assertNotEquals(
                "阶段 " + phase.name + " 必须渲染出内容",
                0,
                countNonPaper(bmp, bmp.height / 3)
            )
        }
    }
}
