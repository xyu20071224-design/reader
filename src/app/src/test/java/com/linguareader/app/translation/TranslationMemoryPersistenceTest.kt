package com.linguareader.app.translation

import android.app.Application
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class TranslationMemoryPersistenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val english = "The hobbit lived in a hole. It was a comfortable hole."
    private val chinese = "哈比人住在洞里。那是个舒服的洞。"

    private data class Fixture(val book: Book, val archive: File, val memory: TranslationMemory, val bytes: String)

    private fun fixture(id: String): Fixture {
        val source = File(app.filesDir, "q1-t01-persistence/$id").apply { mkdirs() }
        File(source, "c0.xhtml").writeText("<html><body><p>$english</p></body></html>")
        val book = Book(
            id = id, title = "Persistence fixture", author = "", extractedDir = source.absolutePath,
            coverRelativePath = null, chapters = listOf(Chapter("Chapter", "c0.xhtml")), addedAt = 0L
        )
        val memory = TranslationMemory(
            id, book.title, "zh-$id", "译本", 17L,
            listOf(AlignedSentencePair(0, 0, english, chinese, english, chinese, 0.9f)), alignerVersion = 11
        )
        val archive = File(app.filesDir, "translation-memory/$id.json")
        archive.parentFile!!.mkdirs()
        val bytes = memory.toJson().toString()
        archive.writeText(bytes)
        return Fixture(book, archive, memory, bytes)
    }

    /** 先向 AtomicFile 的真实暂存文件写部分字节，再抛 I/O 错误，不触碰用户文件。 */
    private fun faultingArchive() = TranslationMemoryArchive { file ->
        object : AtomicFile(file) {
            override fun startWrite(): FileOutputStream {
                val original = super.startWrite()
                return object : FileOutputStream(original.fd) {
                    override fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        original.write(bytes, offset, minOf(length, 8))
                        throw IOException("fixture interrupted after a partial archive write")
                    }
                    override fun close() {
                        try { super.close() } finally { original.close() }
                    }
                }
            }
        }
    }

    @Test
    fun `partial write failure preserves both old archive and cached lookup`() = runBlocking {
        val fixture = fixture("q1-t01-write-failure")
        val repository = TranslationMemoryRepository(app, faultingArchive())
        val lookup = WordLookup("hole", english, english, 28, 0f, 0f)
        val before = repository.lookup(fixture.book, 0, lookup)

        assertThrows(IOException::class.java) {
            runBlocking { repository.replaceSentenceTranslation(fixture.book.id, 0, "尚未提交的新译文。") }
        }

        assertEquals(fixture.bytes, fixture.archive.readText())
        assertEquals(fixture.memory, repository.memoryFor(fixture.book.id))
        assertEquals(before, repository.lookup(fixture.book, 0, lookup))
        assertEquals(fixture.memory, TranslationMemoryRepository(app).memoryFor(fixture.book.id))
    }

    @Test
    fun `cancellation at the archive commit checkpoint restores the old bytes`() {
        val fixture = fixture("q1-t01-commit-cancel")
        val storage = TranslationMemoryArchive()
        assertThrows(CancellationException::class.java) {
            storage.write(fixture.archive, "new archive bytes") { throw CancellationException("fixture cancelled before commit") }
        }
        assertEquals(fixture.bytes, storage.read(fixture.archive))
        assertFalse(File(fixture.archive.path + ".bak").exists())
        assertFalse(File(fixture.archive.path + ".new").exists())
    }

    @Test
    fun `an unsuccessful commit cannot be reported as saved or replace the cached lookup`() = runBlocking {
        val fixture = fixture("q1-t01-uncommitted-write")
        val storage = TranslationMemoryArchive { file ->
            object : AtomicFile(file) {
                // 平台 finishWrite 无返回状态，底层 rename 失败可能仅日志而不抛异常。
                // 精确模拟“流关闭，但新文件没有发布”，而不是制造真实磁盘损坏。
                override fun finishWrite(stream: FileOutputStream?) { stream?.close() }
            }
        }
        val repository = TranslationMemoryRepository(app, storage)
        val lookup = WordLookup("hole", english, english, 28, 0f, 0f)
        val before = repository.lookup(fixture.book, 0, lookup)

        assertThrows(IOException::class.java) {
            runBlocking { repository.replaceSentenceTranslation(fixture.book.id, 0, "没有成功提交的新译文。") }
        }

        assertEquals(fixture.memory, repository.memoryFor(fixture.book.id))
        assertEquals(fixture.bytes, fixture.archive.readText())
        assertEquals(before, repository.lookup(fixture.book, 0, lookup))
    }

    @Test
    fun `an interrupted legacy backup is recovered before version inspection`() = runBlocking {
        val fixture = fixture("q1-t01-backup-recovery")
        val backup = File(fixture.archive.path + ".bak")
        assertTrue(fixture.archive.renameTo(backup))
        // 旧 Android AtomicFile 可能留下一个存在但未写完的新主文件。
        fixture.archive.writeText("{\"alignerVersion\":${TranslationAligner.VERSION}")
        val repository = TranslationMemoryRepository(app)

        assertTrue(repository.hasMemory(fixture.book.id))
        assertTrue(repository.isMemoryOutdated(fixture.book.id))
        assertEquals(fixture.memory, repository.memoryFor(fixture.book.id))
        assertEquals(fixture.bytes, fixture.archive.readText())
        assertFalse(backup.exists())
    }

    @Test
    fun `backup without a base file still counts as available memory`() = runBlocking {
        val fixture = fixture("q1-t01-backup-only")
        assertTrue(fixture.archive.renameTo(File(fixture.archive.path + ".bak")))
        val repository = TranslationMemoryRepository(app)
        assertTrue(repository.hasMemory(fixture.book.id))
        assertEquals(fixture.memory, repository.memoryFor(fixture.book.id))
    }

    @Test
    fun `successful realignment publishes the current version and survives reopening`() = runBlocking {
        val fixture = fixture("q1-t01-successful-upgrade")
        val repository = TranslationMemoryRepository(app)
        val updated = repository.realign(fixture.book)!!

        assertEquals(TranslationAligner.VERSION, updated.alignerVersion)
        assertFalse(repository.isMemoryOutdated(fixture.book.id))
        assertEquals(updated, TranslationMemoryRepository(app).memoryFor(fixture.book.id))
        val lookup = repository.lookup(fixture.book, 0, WordLookup("hole", "The hobbit lived in a hole.", english, 23, 0f, 0f))
        assertEquals("哈比人住在洞里。", lookup?.chinese)
    }

    @Test
    fun `active archive transaction files are owned and removed with the book`() = runBlocking {
        val fixture = fixture("q1-t01-owned-backups")
        val auxiliaries = listOf(".bak", ".new", ".tmp").map { suffix ->
            File(fixture.archive.path + suffix).apply { writeText(fixture.bytes) }
        }
        val repository = TranslationMemoryRepository(app)
        assertTrue(repository.orphans(listOf(fixture.book)).none { it == fixture.archive || it in auxiliaries })

        repository.remove(fixture.book)

        assertFalse(repository.hasMemory(fixture.book.id))
        assertTrue(auxiliaries.none { it.exists() })
    }
}
