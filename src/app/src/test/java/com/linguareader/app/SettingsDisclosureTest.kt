package com.linguareader.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 使用手机画布验证入口可达和配置保留，不调用网络或真实 TTS。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsDisclosureTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun row(id: Int) = compose.onNodeWithText(context.getString(id))
    private fun toggle(id: Int) {
        compose.onNode(hasText(context.getString(id)) and hasClickAction())
            .performScrollTo().performClick()
    }
    private fun field(id: Int) = compose.onNode(hasSetTextAction() and hasText(context.getString(id)))

    @Test fun darkBackgroundExplainsDisabledPresetsOnlyWhenExpanded() {
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ShelfAppearanceContent(ShelfAppearance(), {}, UiAnimSpeed.NORMAL, {},
                        ReaderTheme.DARK, {}, true, null, {}, {}, {})
                }
            }
        }
        row(R.string.shelf_preset_dark_disabled).assertDoesNotExist()
        toggle(R.string.appearance_shelf_background)
        row(R.string.shelf_preset_dark_disabled).performScrollTo().assertIsDisplayed()
        row(R.string.shelf_preset_green).assertIsNotEnabled()
    }

    @Test fun appearanceGroupsRemainReachableAndKeepEdits() {
        var appearance by mutableStateOf(ShelfAppearance())
        var theme by mutableStateOf(ReaderTheme.PAPER)
        var speed by mutableStateOf(UiAnimSpeed.NORMAL)
        var imagePicks = 0
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ShelfAppearanceContent(appearance, { appearance = it }, speed, { speed = it },
                        theme, { theme = it }, false, null, {}, { imagePicks++ },
                        { appearance = ShelfAppearance() })
                }
            }
        }
        row(R.string.skin_section_title).assertIsDisplayed()
        row(R.string.shelf_background_pick_image).assertDoesNotExist()
        row(R.string.skin_custom_hint).assertDoesNotExist()
        toggle(R.string.settings_image_tips)
        row(R.string.skin_custom_hint).assertExists()
        toggle(R.string.settings_image_tips)
        toggle(R.string.appearance_shelf_background)
        row(R.string.shelf_preset_green).performScrollTo().performClick()
        row(R.string.shelf_background_pick_image).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("green", appearance.presetId); assertEquals(1, imagePicks) }
        toggle(R.string.appearance_shelf_background)
        row(R.string.shelf_background_pick_image).assertDoesNotExist()
        toggle(R.string.appearance_shelf_background)
        row(R.string.shelf_background_reset).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, appearance.presetId) }
        toggle(R.string.appearance_reading_page)
        row(R.string.reader_theme_sepia).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderTheme.SEPIA, theme) }
        toggle(R.string.shelf_anim_speed_title)
        row(R.string.shelf_anim_speed_off).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UiAnimSpeed.OFF, speed) }
    }

    @Test fun listeningHiddenFieldsSurviveCollapseEngineSwitchAndSave() {
        CloudTtsSettings.save(context, CloudTtsSettings(mode = TtsEngineMode.OPENAI_COMPAT,
            serverUrl = "http://localhost:9999", serverZhVoice = "original-zh"))
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { ListeningSettingsBody(onDismiss = null, onSaved = {}) }
            }
        }
        field(R.string.tts_server_url_label).assertExists()
        field(R.string.tts_server_en_voice_label).assertDoesNotExist()
        field(R.string.tts_server_narrator_label).assertDoesNotExist()
        row(R.string.tts_server_voice_hint).assertDoesNotExist()
        row(R.string.tts_cloud_notice).assertExists()
        toggle(R.string.settings_language_voices)
        field(R.string.tts_server_en_voice_label).performScrollTo().performTextReplacement("new-en")
        toggle(R.string.settings_language_voices)
        toggle(R.string.settings_language_voices)
        field(R.string.tts_server_en_voice_label).assertTextContains("new-en")
        toggle(R.string.settings_language_voices)
        toggle(R.string.settings_role_voices)
        field(R.string.tts_server_narrator_label).performScrollTo().performTextReplacement("narrator")
        toggle(R.string.settings_role_voices)
        row(R.string.tts_engine_mimo).performScrollTo().performClick()
        field(R.string.tts_mimo_style_label).assertDoesNotExist()
        toggle(R.string.tts_mimo_style_label)
        field(R.string.tts_mimo_style_label).performScrollTo().performTextReplacement("warm")
        row(R.string.tts_engine_openai_compat).performScrollTo().performClick()
        toggle(R.string.tts_cache_section)
        row(R.string.tts_cache_clear).performScrollTo().assertIsDisplayed()
        toggle(R.string.tts_cache_section)
        toggle(R.string.settings_multi_voice)
        row(R.string.multivoice_title).assertExists()
        toggle(R.string.settings_multi_voice)
        row(R.string.common_save).performScrollTo().performClick()
        compose.runOnIdle {
            val saved = CloudTtsSettings.load(context)
            assertEquals(TtsEngineMode.OPENAI_COMPAT, saved.mode)
            assertEquals("new-en", saved.serverEnVoice)
            assertEquals("original-zh", saved.serverZhVoice)
            assertEquals("narrator", saved.narratorVoice)
            assertEquals("warm", saved.mimoStyleInstruction)
        }
    }
}
