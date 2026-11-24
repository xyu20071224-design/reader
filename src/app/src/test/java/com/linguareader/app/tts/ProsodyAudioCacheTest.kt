package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.tts.TtsUtterance
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProsodyAudioCacheTest {
    private val source = TtsUtterance(0, 0, 1, "narrator", "A long first phrase, followed by a second longer phrase.", 0, 0)
    private val policy = ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5)
    private fun file(cache: TtsAudioCache, book: String, chapter: Int = 0): File {
        val units = ProsodyPlanner.plan(source, 1f, policy)
        return cache.phraseFileFor(book, chapter, source, units, 0, "voice", "engine")
    }
    private fun payload(file: File, stamp: Long): File = file.apply {
        parentFile!!.mkdirs(); writeBytes(ByteArray(20)); setLastModified(stamp)
    }

    @Test fun phraseEntriesAreCountedAndTheirAccessMarkerControlsLruEviction() {
        val cache = TtsAudioCache(ApplicationProvider.getApplicationContext())
        val oldestWaveform = payload(file(cache, "prosody-lru-a"), 1_000)
        val unaccessed = payload(file(cache, "prosody-lru-b"), 2_000)
        assertEquals(40L, cache.totalBytes())
        cache.markAccessed(oldestWaveform)
        val accessed = cache.entries().single { it.bookId == "prosody-lru-a" }
        assertEquals(oldestWaveform.parentFile, accessed.dir)
        assertTrue(File(accessed.dir, TtsAudioCache.ACCESS_MARKER).isFile)
        assertTrue(accessed.lastAccessed > 2_000)
        assertEquals(20L, cache.trimTo(20))
        assertTrue(oldestWaveform.isFile)
        assertFalse(unaccessed.exists())
        assertEquals(20L, cache.totalBytes())
    }

    @Test fun currentlyProtectedChapterKeepsBothOriginalAndPhraseEntries() {
        val cache = TtsAudioCache(ApplicationProvider.getApplicationContext())
        val phrase = payload(file(cache, "protected", 1), 1_000)
        val original = payload(cache.fileFor("protected", 1, 0, 0, "voice", "engine"), 1_000)
        val other = payload(file(cache, "other", 0), 2_000)
        assertEquals(20L, cache.trimTo(1, protectBookId = "protected", protectChapterIndex = 1))
        assertTrue(phrase.exists())
        assertTrue(original.exists())
        assertFalse(other.exists())
    }

    @Test fun deletingABookRemovesBothNamespacesButPreservesOtherBooks() = runBlocking {
        val cache = TtsAudioCache(ApplicationProvider.getApplicationContext())
        val phrase = payload(file(cache, "delete-me"), 1_000)
        val original = payload(cache.fileFor("delete-me", 0, 0, 0, "voice", "engine"), 1_000)
        val other = payload(file(cache, "keep-me"), 1_000)
        cache.deleteBookData(Book("delete-me", "", "", "/tmp", null, emptyList(), 0L))
        assertFalse(phrase.exists())
        assertFalse(original.exists())
        assertTrue(other.exists())
        assertEquals(20L, cache.totalBytes())
    }
}
