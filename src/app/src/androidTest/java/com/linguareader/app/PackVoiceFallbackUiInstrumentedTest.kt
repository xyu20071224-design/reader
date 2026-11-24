package com.linguareader.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguareader.app.multivoice.VoiceRow
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.MiMoTtsBackend
import com.linguareader.app.tts.PackVoiceUnavailableException
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.TtsPlaybackState
import com.linguareader.app.tts.VoiceAudition
import com.linguareader.app.tts.MultiVoiceSupport
import com.linguareader.app.tts.TtsLanguage
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PackVoiceFallbackUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()
    private val missing = "pack:uninstalled-ui-fixture/tone"

    @Test fun actualMissingPackageFallbackIsVisibleInPlaybackAndSettings() {
        TtsPlaybackController.onVoiceResourcesChanged()
        try {
            val settings = CloudTtsSettings(mode = TtsEngineMode.MIMO, mimoEnVoice = missing)
            assertEquals(CloudTtsSettings.DEFAULT_MIMO_EN_VOICE, MiMoTtsBackend(settings, context).voiceFor("Hello."))
            val notice = requireNotNull(TtsPlaybackController.packVoiceFallback.value)
            assertTrue(notice.usedFallback)
            compose.setContent {
                provideLinguaTheme(SkinBuiltIns.light) {
                    Column(Modifier.fillMaxSize()) {
                        ListeningBar(TtsPlaybackState(bookId = "voice-feedback-fixture"), ReaderTheme.PAPER,
                            modifier = Modifier.testTag("voice-feedback-bar"), onToggle = {}, onPrevious = {},
                            onNext = {}, onStop = {}, onRateChange = {}, packVoiceHint = packVoiceFallbackText(notice))
                        Box(Modifier.weight(1f).testTag("voice-feedback-settings")) {
                            ListeningSettingsBody(onDismiss = null, onSaved = {})
                        }
                    }
                }
            }
            val text = context.getString(R.string.voice_pack_missing_fallback)
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("voice-feedback-bar"))).assertIsDisplayed()
            compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("voice-feedback-settings"))).assertIsDisplayed()
        } finally { TtsPlaybackController.onVoiceResourcesChanged() }
    }

    @Test fun missingSelectionKeepsItsIntentAndPreviewFailureUsesLocalizedFeedback() {
        try {
            val failure = runBlocking {
                VoiceAudition.play(context, CloudTtsSettings(mode = TtsEngineMode.MIMO), missing, "Hello.")
            }.exceptionOrNull()
            assertTrue(failure is PackVoiceUnavailableException)
            assertEquals(context.getString(R.string.voice_pack_missing_audition), packVoiceFailureText(context, failure))
            val label = context.getString(R.string.voice_pack_unavailable_preserved)
            compose.setContent {
                provideLinguaTheme(SkinBuiltIns.light) {
                    VoiceRow("Narrator", missing, true, false, {}, {}, {}, currentVoiceLabel = label)
                }
            }
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithText(missing).assertDoesNotExist()
        } finally { TtsPlaybackController.onVoiceResourcesChanged() }
    }

    @Test fun realMultiVoiceReloadDisplaysThePreservedMissingPackageInsteadOfItsPlaybackFallback() {
        assertTrue(context.packageName.endsWith(".verify"))
        val id = "missing-voice-ui-" + UUID.randomUUID()
        val mapFile = File(context.filesDir, "voice_maps/$id.json")
        val repository = MultiVoiceSupport.voiceMapRepository(context)
        val book = Book(id, "Missing voice UI fixture", "Test", context.filesDir.absolutePath,
            null, emptyList(), System.currentTimeMillis())
        try {
            runBlocking { repository.lockNarrator(id, TtsLanguage.ENGLISH, missing) }
            compose.setContent {
                provideLinguaTheme(SkinBuiltIns.light) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MultiVoiceSection(CloudTtsSettings(mode = TtsEngineMode.MIMO, multiVoiceEnabled = true),
                            onSettingsChange = {}, books = listOf(book), preselectedBook = book)
                    }
                }
            }
            val label = context.getString(R.string.voice_pack_unavailable_preserved)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(missing).assertDoesNotExist()
            assertEquals(missing, runBlocking { repository.load(id) }?.narratorFor(TtsLanguage.ENGLISH))
        } finally { mapFile.delete(); TtsPlaybackController.onVoiceResourcesChanged() }
    }
}
