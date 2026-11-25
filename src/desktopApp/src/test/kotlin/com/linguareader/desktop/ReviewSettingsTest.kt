package com.linguareader.desktop

import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.ReviewScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewSettingsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun preferences(): PreferencesStore =
        DesktopAppContext(temporaryFolder.root).prefs("review_settings")

    private val custom = ReviewPace.defaultCustom().copy(
        firstDelayMillis = 24 * 3_600_000L,
        intervalMultiplier = 1.25,
        minIntervalMillis = 60 * 60_000L,
        dailyPromptLimit = 7,
        dwellMillis = 5_000L
    )

    @Test
    fun existingCustomSelectionIsRecognizedWithoutRewritingParameters() {
        val preferences = preferences()
        preferences.putString(ReviewPace.STORAGE_KEY, custom.toJson())
        preferences.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)

        val settings = DesktopReviewSettings(preferences)

        assertNull(settings.preset)
        assertEquals(custom, settings.custom)
        assertEquals(custom, ReviewPace.fromPreferences(preferences))
    }

    @Test
    fun switchingThroughEveryPresetPreservesCustomParametersAcrossReopening() {
        val preferences = preferences()
        val customJson = custom.toJson()
        preferences.putString(ReviewPace.STORAGE_KEY, customJson)
        preferences.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
        val settings = DesktopReviewSettings(preferences)

        for (mode in ReviewMode.entries) {
            settings.selectPreset(mode)

            val reopened = preferences()
            assertEquals(mode, settings.preset)
            assertEquals(mode, DesktopReviewSettings(reopened).preset)
            assertEquals(mode.toPace(), ReviewPace.fromPreferences(reopened))
            assertEquals(customJson, reopened.getString(ReviewPace.STORAGE_KEY))
        }

        val reopenedSettings = DesktopReviewSettings(preferences())
        reopenedSettings.selectCustom()

        assertNull(reopenedSettings.preset)
        assertEquals(custom, ReviewPace.fromPreferences(preferences()))
    }

    @Test
    fun savingCustomPersistsEveryParameterAndActivatesTheSchedulerPace() {
        val settings = DesktopReviewSettings(preferences())
        settings.selectPreset(ReviewMode.DILIGENT)

        settings.saveCustom(custom)

        val reopened = preferences()
        assertNull(settings.preset)
        assertEquals(ReviewPace.CUSTOM_NAME, reopened.getString(ReviewMode.PREFERENCE_KEY))
        assertEquals(custom, DesktopReviewSettings(reopened).custom)
        val effective = ReviewPace.fromPreferences(reopened)
        assertEquals(custom, effective)
        assertEquals(15 * 3_600_000L, ReviewScheduler.intervalFor(1, effective))
    }

    @Test
    fun firstRunUsesDefaultPresetAndCustomWithoutWritingPreferences() {
        val preferences = preferences()

        val settings = DesktopReviewSettings(preferences)

        assertEquals(ReviewMode.DEFAULT, settings.preset)
        assertEquals(ReviewPace.defaultCustom(), settings.custom)
        assertNull(preferences.getString(ReviewMode.PREFERENCE_KEY))
        assertNull(preferences.getString(ReviewPace.STORAGE_KEY))
        settings.selectCustom()
        assertEquals(ReviewPace.defaultCustom(), ReviewPace.fromPreferences(preferences()))
    }

    @Test
    fun invalidCustomJsonFallsBackWithoutBeingOverwrittenByPresetSwitch() {
        val preferences = preferences()
        preferences.putString(ReviewPace.STORAGE_KEY, "{invalid")
        preferences.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
        val settings = DesktopReviewSettings(preferences)

        assertNull(settings.preset)
        assertEquals(ReviewPace.defaultCustom(), settings.custom)
        settings.selectPreset(ReviewMode.IMMERSIVE)
        settings.selectCustom()

        assertEquals("{invalid", preferences().getString(ReviewPace.STORAGE_KEY))
        assertEquals(ReviewPace.defaultCustom(), ReviewPace.fromPreferences(preferences()))
    }
}
