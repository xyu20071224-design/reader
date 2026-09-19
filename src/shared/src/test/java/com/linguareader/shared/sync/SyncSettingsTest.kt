package com.linguareader.shared.sync

import com.linguareader.shared.app.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsTest {

    private class MemoryPrefs : PreferencesStore {
        private val values = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun defaultsToDisabledWithNoServer() {
        val settings = SyncSettings.load(MemoryPrefs())
        assertFalse(settings.enabled)
        assertEquals("", settings.serverUrl)
        assertEquals("", settings.username)
        assertEquals("", settings.pinnedCertSha256)
    }

    @Test
    fun roundTripsThroughPreferences() {
        val prefs = MemoryPrefs()
        SyncSettings.save(prefs, SyncSettings(
            enabled = true,
            serverUrl = "https://203.0.113.10:8787",
            username = "alice",
            pinnedCertSha256 = "AA:BB:CC"
        ))

        val loaded = SyncSettings.load(prefs)
        assertTrue(loaded.enabled)
        assertEquals("https://203.0.113.10:8787", loaded.serverUrl)
        assertEquals("alice", loaded.username)
        assertEquals("AA:BB:CC", loaded.pinnedCertSha256)
    }

    @Test
    fun corruptStoredJsonFallsBackToDefaults() {
        val prefs = MemoryPrefs()
        prefs.putString("settings", "{not json")
        assertFalse(SyncSettings.load(prefs).enabled)
    }
}
