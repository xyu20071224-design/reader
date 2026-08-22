package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M5c tests (PLAN-MULTI-VOICE §13.5): the three-state SYSTEM-engine guidance
 * mapping and the PackageManager probe collapsing failures to "not installed".
 */
@RunWith(RobolectricTestRunner::class)
class SystemTtsEnginesTest {

    @Test
    fun `guideState maps the three guidance states`() {
        assertEquals(
            SystemTtsEngines.Guide.RECOMMENDED,
            SystemTtsEngines.guideState(SystemTtsEngines.GOOGLE_TTS_PACKAGE, googleTtsInstalled = true)
        )
        // Installed but not active → suggest switching (never auto-switch).
        assertEquals(
            SystemTtsEngines.Guide.SWITCH_AVAILABLE,
            SystemTtsEngines.guideState("com.iflytek.speechsuite", googleTtsInstalled = true)
        )
        assertEquals(
            SystemTtsEngines.Guide.NOT_INSTALLED,
            SystemTtsEngines.guideState("com.iflytek.speechsuite", googleTtsInstalled = false)
        )
        // A blank pointer (device never configured an engine) is not Google.
        assertEquals(
            SystemTtsEngines.Guide.NOT_INSTALLED,
            SystemTtsEngines.guideState("", googleTtsInstalled = false)
        )
    }

    @Test
    fun `probe collapses missing package and blank name to false`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertFalse(SystemTtsEngines.isGoogleTtsInstalled(context))
        assertFalse(SystemTtsEngines.isInstalled(context, ""))
    }
}
