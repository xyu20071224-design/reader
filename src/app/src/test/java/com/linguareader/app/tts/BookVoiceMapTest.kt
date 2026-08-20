package com.linguareader.app.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 voice-map tests (PLAN-MULTI-VOICE §9): JSON round trip, speaker lookup
 * (including the narration language fallback) and user locking.
 */
class BookVoiceMapTest {

    private val map = BookVoiceMap(
        bookId = "book-1",
        narrator = mapOf("en" to "af_maple", "zh" to "zf_001"),
        characterVoice = mapOf("Gandalf" to "am_onyx", "Frodo" to "am_echo"),
        userLocked = setOf("frodo"),
        engine = "server:kokoro"
    )

    @Test
    fun `voice map json round trip`() {
        assertEquals(map, BookVoiceMap.fromJson(JSONObject(map.toJson().toString())))
    }

    @Test
    fun `speaker lookup is case insensitive`() {
        assertEquals("am_onyx", map.voiceFor("Gandalf"))
        assertEquals("am_onyx", map.voiceFor("gandalf"))
        assertEquals("am_onyx", map.voiceFor(" Gandalf "))
    }

    @Test
    fun `narration follows the sentence language`() {
        assertEquals("af_maple", map.voiceFor("narrator", "en"))
        assertEquals("zf_001", map.voiceFor("narrator", "zh"))
        // Unknown language degrades to English, then to anything configured.
        assertEquals("af_maple", map.voiceFor("narrator", "fr"))
        assertEquals(
            "zf_001",
            BookVoiceMap("b", narrator = mapOf("zh" to "zf_001")).voiceFor("narrator", "fr")
        )
    }

    @Test
    fun `unknown speakers fall through to the caller default`() {
        assertNull(map.voiceFor("dialogue"))
        assertNull(map.voiceFor("Sauron"))
        assertNull(map.voiceFor(""))
        assertNull(BookVoiceMap("b").voiceFor("narrator"))
    }

    @Test
    fun `locking pins a voice and unlocking releases it`() {
        val locked = BookVoiceMap("b").lock("Gandalf", "zm_009")
        assertEquals("zm_009", locked.voiceFor("Gandalf"))
        assertTrue(locked.isLocked("gandalf"))

        val relocked = locked.lock("gandalf", "am_onyx")
        assertEquals("am_onyx", relocked.voiceFor("Gandalf"))
        assertEquals(1, relocked.characterVoice.size)

        val released = relocked.unlock("Gandalf")
        assertTrue(!released.isLocked("gandalf"))
        // The voice stays until the next assignment moves it.
        assertEquals("am_onyx", released.voiceFor("Gandalf"))
    }

    @Test
    fun `narrator locking is per language`() {
        val locked = BookVoiceMap("b").lockNarrator("zh", "zf_001")
        assertEquals("zf_001", locked.voiceFor("narrator", "zh"))
        assertTrue(locked.isLocked(BookVoiceMap.narratorKey("zh")))
        assertTrue(!locked.isLocked(BookVoiceMap.narratorKey("en")))
    }

    @Test
    fun `blank input never creates junk entries`() {
        val untouched = BookVoiceMap("b")
        assertEquals(untouched, untouched.lock("", "af_maple"))
        assertEquals(untouched, untouched.lock("Gandalf", ""))
        assertTrue(untouched.isEmpty)
    }
}
