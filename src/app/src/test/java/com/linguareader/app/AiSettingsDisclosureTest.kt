package com.linguareader.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.ai.*
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AiSettingsDisclosureTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)
    private fun node(id: Int) = compose.onNodeWithText(text(id))
    private fun field(id: Int) = compose.onNode(hasText(text(id)) and hasSetTextAction())
    private fun toggle(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
        .performScrollTo().performClick()
    private val first = AiProviderProfile("p1", "First provider", "https://first.example", "fixture-key", model = "model-one")
    private val second = AiProviderProfile("p2", "Second provider", "https://second.example", "second-key", model = "model-two")

    // Host the production form/state/actions in a bounded page; AlertDialog windows cannot idle in Robolectric.
    @Composable private fun Editor(initial: AiProviderProfile, isNew: Boolean,
        onSave: (AiProviderProfile) -> Unit) {
        ProviderEditorContent(initial, isNew, true, onSave, null, {}) { title, form, confirm, dismiss ->
            Column(Modifier.fillMaxSize()) {
                title()
                Box(Modifier.weight(1f)) { form() }
                Row { dismiss(); confirm() }
            }
        }
    }

    @Test fun providerSelectionSurvivesCollapseAndIsPersisted() {
        var settings by mutableStateOf(AiSettings(enabled = true, providers = listOf(first, second),
            activeProviderId = first.id).withActiveMirrored())
        val store = AiSettingsStore(context)
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Box(Modifier.fillMaxSize()) {
                    AiTranslationSettingsBody(settings, { settings = it; store.save(it) }, true)
                }
            }
        }
        node(R.string.ai_status_configured).assertExists()
        node(R.string.aidrawer_remote_on_hint).assertExists()
        node(R.string.aidrawer_provider_add).assertDoesNotExist()
        node(R.string.ai_translation_help_body).assertDoesNotExist()
        toggle(R.string.ai_translation_help)
        node(R.string.ai_translation_help_body).assertExists()
        toggle(R.string.ai_translation_help)
        toggle(R.string.ai_manage_providers)
        compose.onNodeWithText(second.name).performScrollTo().performClick()
        toggle(R.string.ai_manage_providers)
        node(R.string.aidrawer_provider_add).assertDoesNotExist()
        node(R.string.common_save).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(second.id, settings.activeProviderId)
            assertEquals(second.apiKey, settings.activeProvider?.apiKey)
            val loaded = store.load()
            assertEquals(second.id, loaded.activeProviderId)
            assertEquals(second.baseUrl, loaded.baseUrl)
            assertEquals(second.model, loaded.model)
            // Native Android Keystore is unavailable here; secret retention is checked on the saved callback above.
        }
    }

    @Test fun editorKeepsHiddenConnectionDraftAndSavesAllFields() {
        var saved: AiProviderProfile? = null
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Editor(first, false) { saved = it }
            }
        }
        field(R.string.ai_api_key).assertExists()
        field(R.string.aidrawer_base_url_label).assertDoesNotExist()
        node(R.string.aidrawer_provider_local_key_hint).assertDoesNotExist()
        field(R.string.aidrawer_model_label).performScrollTo().performTextReplacement("updated-model")
        toggle(R.string.ai_connection_options)
        field(R.string.aidrawer_base_url_label).performScrollTo().performTextReplacement("https://changed.example")
        node(R.string.aidrawer_protocol_anthropic).performScrollTo().performClick()
        toggle(R.string.ai_connection_options)
        field(R.string.aidrawer_base_url_label).assertDoesNotExist()
        toggle(R.string.settings_setup_help)
        node(R.string.aidrawer_provider_discovery_hint).assertExists()
        toggle(R.string.settings_setup_help)
        toggle(R.string.ai_connection_options)
        field(R.string.aidrawer_base_url_label).assertTextContains("https://changed.example")
        toggle(R.string.ai_connection_options)
        node(R.string.common_save).performClick()
        compose.runOnIdle {
            assertEquals(first.copy(baseUrl = "https://changed.example", model = "updated-model",
                protocol = AiProtocol.ANTHROPIC), saved)
        }
    }

    @Test fun presetNeedsOnlyKeyAndKeepsHiddenDefaultsWhenSaved() {
        val preset = AiProviderPresets.DEEPSEEK
        val initial = AiProviderProfile("", name = text(preset.nameRes), baseUrl = preset.baseUrl,
            protocol = preset.protocol, model = preset.defaultModel)
        var saved: AiProviderProfile? = null
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Editor(initial, true) { saved = it }
            }
        }
        field(R.string.aidrawer_base_url_label).assertDoesNotExist()
        field(R.string.ai_api_key).performScrollTo().performTextReplacement("preset-fixture-key")
        node(R.string.common_save).performClick()
        compose.runOnIdle { assertEquals(initial.copy(apiKey = "preset-fixture-key"), saved) }
    }

    @Test fun newCustomProviderShowsConnectionFieldsImmediately() {
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Editor(AiProviderProfile(""), true) {}
            }
        }
        field(R.string.aidrawer_base_url_label).assertExists()
        field(R.string.aidrawer_provider_name_label).assertExists()
        node(R.string.aidrawer_provider_local_key_hint).assertDoesNotExist()
    }

    @Test fun firstSetupIsOpenButPowerOffKeepsOnlineControlsDisabled() {
        compose.setContent {
            provideLinguaTheme(SkinBuiltIns.light) {
                Box(Modifier.fillMaxSize()) {
                    AiTranslationSettingsBody(AiSettings(enabled = true), {}, false)
                }
            }
        }
        node(R.string.ai_status_power_off).assertExists()
        node(R.string.aidrawer_provider_add).assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }
}
