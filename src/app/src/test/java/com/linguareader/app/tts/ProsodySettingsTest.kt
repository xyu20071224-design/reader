package com.linguareader.app.tts

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.tts.ProsodyConfiguration
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.data.Book
import org.robolectric.Robolectric
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProsodySettingsTest {
    @Test fun defaultsAndUnknownValuesUseOriginalWithoutGrantingRegeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("tts_prosody", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ProsodyPlanner.Mode.ORIGINAL, TtsPlaybackController.prosodyMode(context))
        assertFalse(ProsodyConfiguration().allowAudioPackRegeneration)
        context.getSharedPreferences("tts_prosody", Context.MODE_PRIVATE).edit().putString("mode", "future").commit()
        assertEquals(ProsodyPlanner.Mode.ORIGINAL, TtsPlaybackController.prosodyMode(context))
    }

    @Test fun savingWithoutAListeningSessionDoesNotStartAServiceOrPersistPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).clearStartedServices()
        assertEquals(null, TtsPlaybackService.state.value.bookId)
        TtsPlaybackController.setProsodyMode(app, ProsodyPlanner.Mode.PHRASES, allowAudioPackRegeneration = true)
        assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.prosodyMode(app))
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
        assertEquals(mapOf("mode" to "PHRASES"), app.getSharedPreferences("tts_prosody", Context.MODE_PRIVATE).all)
        assertFalse(ProsodyConfiguration().allowAudioPackRegeneration)
        TtsPlaybackController.setProsodyMode(app, ProsodyPlanner.Mode.ORIGINAL)
    }

    @Test fun controllerPassesSavedModeToAnActiveStandbyServiceWithoutStartingPlayback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceController = Robolectric.buildService(TtsPlaybackService::class.java).create()
        val service = serviceController.get()
        try {
            ProsodySettings.saveMode(app, ProsodyPlanner.Mode.ORIGINAL)
            shadowOf(app).clearStartedServices()
            val book = Book("standby-prosody", "Book", "", "/tmp", null, emptyList(), 0L)
            TtsPlaybackController.startStandby(app, book, 0)
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            assertEquals(book.id, TtsPlaybackController.state.value.bookId)
            assertFalse(TtsPlaybackController.state.value.isPlaying)
            TtsPlaybackController.setProsodyMode(app, ProsodyPlanner.Mode.PHRASES)
            val update = shadowOf(app).nextStartedService
            assertTrue(update != null, "An existing session must receive the saved mode")
            service.onStartCommand(update, 0, 2)
            assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.state.value.prosodyMode)
            assertFalse(TtsPlaybackController.state.value.isPlaying)
            TtsPlaybackController.setProsodyMode(app, ProsodyPlanner.Mode.ORIGINAL)
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 3)
            assertEquals(ProsodyPlanner.Mode.ORIGINAL, TtsPlaybackController.state.value.prosodyMode)
            assertFalse(TtsPlaybackController.state.value.isPlaying)
        } finally {
            serviceController.destroy()
            assertEquals(null, TtsPlaybackController.state.value.bookId)
        }
    }

    @Test fun combinedSettingsSaveWhileIdlePersistsModeWithoutAServiceAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).clearStartedServices()
        assertEquals(null, TtsPlaybackController.state.value.bookId)
        TtsPlaybackController.onCloudSettingsChanged(app, ProsodyPlanner.Mode.PHRASES)
        assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.prosodyMode(app))
        assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.state.value.prosodyMode)
        assertTrue(shadowOf(app).allStartedServices.isEmpty())
        TtsPlaybackController.onCloudSettingsChanged(app, ProsodyPlanner.Mode.ORIGINAL)
    }

    @Test fun combinedSettingsSaveSendsOnlyOneReplacementActionToAnActiveSession() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val serviceController = Robolectric.buildService(TtsPlaybackService::class.java).create()
        val service = serviceController.get()
        try {
            ProsodySettings.saveMode(app, ProsodyPlanner.Mode.ORIGINAL)
            shadowOf(app).clearStartedServices()
            val book = Book("combined-prosody", "Book", "", "/tmp", null, emptyList(), 0L)
            TtsPlaybackController.startStandby(app, book, 0)
            service.onStartCommand(shadowOf(app).nextStartedService, 0, 1)
            shadowOf(app).clearStartedServices()
            TtsPlaybackController.onCloudSettingsChanged(app, ProsodyPlanner.Mode.PHRASES)
            val action = shadowOf(app).nextStartedService
            assertTrue(action != null)
            assertEquals(null, shadowOf(app).nextStartedService, "Only one service action is allowed")
            service.onStartCommand(action, 0, 2)
            assertEquals(ProsodyPlanner.Mode.PHRASES, TtsPlaybackController.state.value.prosodyMode)
            assertFalse(TtsPlaybackController.state.value.isPlaying)
        } finally { serviceController.destroy() }
    }
}
