package com.linguareader.app.skin

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.ShelfAppearance
import com.linguareader.app.ShelfAppearanceSheet
import com.linguareader.app.UiAnimSpeed
import com.linguareader.app.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 点击真实设置弹层，检查生产主题入口下发的颜色，而非只检查选择 ID。
 * 使用足够高的画布容纳外观表单；本测试验证选择事件与主题接线，不验证手机滚动布局。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1800dp-mdpi")
class SkinSelectionEffectTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val generation = mutableStateOf(0)
    private val readerTheme = mutableStateOf<ReaderTheme?>(ReaderTheme.PAPER)
    private val systemDark = mutableStateOf(false)
    private lateinit var actual: Colors

    private data class Colors(
        val id: String,
        val paper: Color,
        val ink: Color,
        val legacyPaper: Color,
        val materialBackground: Color,
        val materialSurface: Color,
        val materialInk: Color,
        val primary: Color
    )

    @Before fun reset() { SkinStore.clearCustom(context) }

    private fun launch() {
        compose.setContent {
            key(generation.value) {
                // 与 Activity 一样：重建时从持久化初始化，事件回调更新运行时状态。
                var selectedSkin by remember { mutableStateOf(SkinStore.selected(context)) }
                AppSkinTheme(selectedSkin, readerTheme.value, systemDark.value) {
                    val tokens = LocalDesignTokens.current
                    val legacy = LocalLinguaPalette.current
                    val material = MaterialTheme.colorScheme
                    SideEffect {
                        actual = Colors(tokens.skinId, tokens.colors.paper, tokens.colors.ink,
                            legacy.paper, material.background, material.surface,
                            material.onBackground, material.primary)
                    }
                    ShelfAppearanceSheet(
                        appearance = ShelfAppearance(),
                        onAppearanceChange = {},
                        animSpeed = UiAnimSpeed.NORMAL,
                        onAnimSpeedChange = {},
                        onReaderThemeChange = { readerTheme.value = it },
                        onSkinChange = { selectedSkin = it },
                        onDismiss = {}
                    )
                }
            }
        }
    }

    private fun assertColors(skin: SkinSpec) = compose.runOnIdle {
        assertEquals(skin.id, actual.id)
        assertEquals(Color(skin.colors.paper), actual.paper)
        assertEquals(Color(skin.colors.ink), actual.ink)
        assertEquals(actual.paper, actual.legacyPaper)
        assertEquals(actual.paper, actual.materialBackground)
        assertEquals(Color(skin.colors.cardSurface), actual.materialSurface)
        assertEquals(actual.ink, actual.materialInk)
        assertEquals(Color(skin.colors.accent), actual.primary)
    }

    @Test fun clickingEachBuiltInChangesActualColorsAndSurvivesRecreation() {
        launch()
        for (skin in listOf(SkinBuiltIns.dark, SkinBuiltIns.amoled, SkinBuiltIns.light)) {
            compose.onNodeWithContentDescription(skin.name).assertIsDisplayed().performClick()
            assertColors(skin)
            compose.runOnIdle { assertEquals(skin.id, SkinStore.selected(context)?.id) }
            // 丢掉全部 remember 状态，重新读盘；不能依赖上一次回调中的对象。
            compose.runOnIdle { generation.value++ }
            assertColors(skin)
        }
    }

    @Test fun explicitLightOverridesDarkReadingThemeAndSystem() {
        readerTheme.value = ReaderTheme.DARK
        systemDark.value = true
        SkinStore.selectBuiltIn(context, SkinBuiltIns.light.id)
        launch()
        assertColors(SkinBuiltIns.light)
    }

    @Test fun noSelectionStillFollowsReadingThemeAndSystem() {
        launch()
        assertColors(SkinBuiltIns.light)
        compose.runOnIdle { readerTheme.value = ReaderTheme.DARK }
        assertColors(SkinBuiltIns.dark)
        compose.runOnIdle { readerTheme.value = null; systemDark.value = true }
        assertColors(SkinBuiltIns.dark)
        compose.runOnIdle { systemDark.value = false }
        assertColors(SkinBuiltIns.light)
    }

    @Test fun builtInReplacesImageSkinAndClearsItsJson() {
        val image = SkinBuiltIns.light.copy(id = "image-test", name = "测试图片")
        SkinStore.selectCustom(context, image)
        launch()
        assertColors(image)
        compose.onNodeWithContentDescription(SkinBuiltIns.amoled.name)
            .assertIsDisplayed().performClick()
        assertColors(SkinBuiltIns.amoled)
        compose.runOnIdle {
            assertNull(SkinStore.currentCustom(context))
            assertNull(context.getSharedPreferences("app_skin", Context.MODE_PRIVATE)
                .getString("selected_skin_json", null))
        }
    }
}
