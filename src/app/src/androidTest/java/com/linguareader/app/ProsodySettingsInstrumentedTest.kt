package com.linguareader.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.linguareader.app.skin.provideLinguaTheme
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsEngineMode
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.ui.skin.SkinBuiltIns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production settings content, persistence and idle service boundary, without paid synthesis. */
@RunWith(AndroidJUnit4::class)
class ProsodySettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    @Test fun savingPhrasePausesPersistsModeAndKeepsIdlePlaybackIdle() {
        val original = CloudTtsSettings.load(context)
        val oldMode = TtsPlaybackController.prosodyMode(context)
        var saved = false
        try {
            assertNull("Use an idle isolated test session", TtsPlaybackController.state.value.bookId)
            CloudTtsSettings.save(context, original.copy(mode = TtsEngineMode.SYSTEM))
            TtsPlaybackController.setProsodyMode(context, ProsodyPlanner.Mode.ORIGINAL)
            compose.setContent {
                provideLinguaTheme(SkinBuiltIns.light) {
                    Box(Modifier.fillMaxSize()) {
                        ListeningSettingsBody(onDismiss = null, onSaved = { saved = true })
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.tts_prosody_phrases)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.common_save)).performScrollTo().performClick()
            compose.waitUntil(10_000) { saved }
            assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.prosodyMode(context))
            assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.state.value.prosodyMode)
            assertNull(TtsPlaybackController.state.value.bookId)
        } finally {
            CloudTtsSettings.save(context, original)
            TtsPlaybackController.setProsodyMode(context, oldMode)
        }
    }
}
