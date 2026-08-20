package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M3 persistence tests (PLAN-MULTI-VOICE §5.3): the mapping is sticky across
 * calls, new characters are added incrementally, locks survive re-assignment,
 * and switching engine recomputes inside the new voice library.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceMapRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val kokoro = VoiceLibrary(
        listOf(
            VoiceInfo("af_maple", "en", "female", quality = 0.6f),
            VoiceInfo("af_sol", "en", "female"),
            VoiceInfo("am_onyx", "en", "male", quality = 0.6f),
            VoiceInfo("am_echo", "en", "male")
        ),
        engine = "server:kokoro"
    )

    private val azure = VoiceLibrary(
        listOf(
            VoiceInfo("en-US-AriaNeural", "en", "female", quality = 0.6f),
            VoiceInfo("en-US-GuyNeural", "en", "male", quality = 0.6f),
            VoiceInfo("en-US-DavisNeural", "en", "male")
        ),
        engine = "azure:chinanorth3"
    )

    private fun repository(characters: () -> List<VoiceCharacter>) = VoiceMapRepository(
        context,
        charactersProvider = { characters() },
        cooccurrenceProvider = { emptyMap() }
    )

    private fun gandalf() = VoiceCharacter("Gandalf", "male", importance = "major")
    private fun frodo() = VoiceCharacter("Frodo", "male", importance = "medium")

    @Test
    fun `assignment is persisted and reloaded`() = runBlocking {
        val repository = repository { listOf(gandalf()) }
        val assigned = repository.ensureFor("book-a", kokoro, narratorLanguages = listOf("en"))
        assertNotNull(assigned)
        assertEquals("am_onyx", assigned?.voiceFor("Gandalf"))

        val reloaded = repository.load("book-a")
        assertEquals(assigned, reloaded)
        assertEquals("book-a", reloaded?.bookId)
    }

    @Test
    fun `new characters do not move existing ones`() = runBlocking {
        var roster = listOf(gandalf())
        val repository = repository { roster }
        val first = repository.ensureFor("book-b", kokoro, narratorLanguages = listOf("en"))

        roster = listOf(gandalf(), frodo())
        val second = repository.ensureFor("book-b", kokoro, narratorLanguages = listOf("en"))

        assertEquals(first?.voiceFor("Gandalf"), second?.voiceFor("Gandalf"))
        assertEquals(first?.narratorFor("en"), second?.narratorFor("en"))
        assertNotNull(second?.voiceFor("Frodo"))
        assertTrue(second?.voiceFor("Frodo") != second?.voiceFor("Gandalf"))
    }

    @Test
    fun `a locked voice survives re-assignment`() = runBlocking {
        val repository = repository { listOf(gandalf(), frodo()) }
        repository.ensureFor("book-c", kokoro, narratorLanguages = listOf("en"))
        repository.lock("book-c", "Gandalf", "af_sol")

        val again = repository.ensureFor("book-c", kokoro, narratorLanguages = listOf("en"))
        assertEquals("af_sol", again?.voiceFor("Gandalf"))
        assertTrue(again?.isLocked("gandalf") == true)
    }

    @Test
    fun `switching engine recomputes inside the new library`() = runBlocking {
        val repository = repository { listOf(gandalf(), frodo()) }
        val onKokoro = repository.ensureFor("book-d", kokoro, narratorLanguages = listOf("en"))
        assertTrue(onKokoro?.characterVoice?.values?.all { kokoro.byId(it) != null } == true)

        val onAzure = repository.ensureFor("book-d", azure, narratorLanguages = listOf("en"))
        assertEquals("azure:chinanorth3", onAzure?.engine)
        assertTrue(onAzure?.characterVoice?.values?.all { azure.byId(it) != null } == true)
        assertEquals(onAzure, repository.load("book-d"))
    }

    @Test
    fun `an engine without a voice library keeps playback single voice`() = runBlocking {
        val repository = repository { listOf(gandalf()) }
        assertNull(repository.ensureFor("book-e", VoiceLibrary(emptyList(), "SYSTEM")))
        assertNull(repository.load("book-e"))
    }

    @Test
    fun `reserved voices are left to the manual settings`() = runBlocking {
        val repository = repository { listOf(gandalf()) }
        val map = repository.ensureFor(
            "book-f",
            kokoro,
            narratorLanguages = listOf("en"),
            reserved = setOf("am_onyx")
        )
        assertEquals("am_echo", map?.voiceFor("Gandalf"))
    }

    @Test
    fun `delete drops the mapping`() = runBlocking {
        val repository = repository { listOf(gandalf()) }
        repository.ensureFor("book-g", kokoro, narratorLanguages = listOf("en"))
        assertNotNull(repository.load("book-g"))

        repository.delete("book-g")
        assertNull(repository.load("book-g"))
    }
}
