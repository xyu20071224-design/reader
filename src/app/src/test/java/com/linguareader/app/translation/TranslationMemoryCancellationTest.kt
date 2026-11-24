package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TranslationMemoryCancellationTest {
    @Test
    fun `cancelling realignment during source extraction preserves the old archive`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val english = "The hobbit lived in a hole. It was a comfortable hole."
        val chinese = "哈比人住在洞里。那是个舒服的洞。"
        val directory = File(app.filesDir, "q1-t01-cancel-source").apply { mkdirs() }
        File(directory, "c0.xhtml").writeText("<html><body><p>$english</p></body></html>")
        val chapter = Chapter("Chapter", "c0.xhtml")
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val blockingChapters = object : AbstractList<Chapter>() {
            override val size: Int get() = 1
            override fun get(index: Int): Chapter {
                entered.countDown()
                check(released.await(10, TimeUnit.SECONDS)) { "source extraction fixture was not released" }
                return chapter
            }
        }
        val book = Book(
            id = "q1-t01-cancellation", title = "Cancellation fixture", author = "",
            extractedDir = directory.absolutePath, coverRelativePath = null,
            chapters = blockingChapters, addedAt = 0L
        )
        val original = TranslationMemory(
            book.id, book.title, "zh-cancellation", "译本", 17L,
            listOf(AlignedSentencePair(0, 0, english, chinese, english, chinese, 0.9f)),
            alignerVersion = 11
        )
        val archive = File(app.filesDir, "translation-memory/${book.id}.json")
        archive.parentFile!!.mkdirs()
        val originalBytes = original.toJson().toString()
        archive.writeText(originalBytes)
        val repository = TranslationMemoryRepository(app)

        val job = launch(Dispatchers.Default) { repository.realign(book) }
        try {
            assertTrue("realignment must be inside source extraction before cancellation", entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            released.countDown()
            job.join()
        }

        assertTrue(job.isCancelled)
        assertEquals("cancelled realignment must not publish a new archive", originalBytes, archive.readText())
        assertEquals(original, repository.memoryFor(book.id))
    }
}
