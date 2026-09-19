package com.linguareader.shared.sync

import com.linguareader.shared.app.AppContext
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.data.SavedWord
import com.linguareader.shared.data.VocabularyRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 应用级（数据层）双设备收敛：真实 LibraryRepository/VocabularyRepository + 真实落盘 + 假服务端。
 * 验证 LocalSyncSource 的 snapshot/apply 与 SyncCoordinator 的水位配合。
 */
class LocalSyncSourceTest {

    private val tempRoots = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempRoots.forEach { it.deleteRecursively() }
    }

    private class MemoryPrefs : PreferencesStore {
        private val values = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private class TestAppContext(override val filesDir: File) : AppContext {
        private val stores = HashMap<String, PreferencesStore>()
        override fun prefs(name: String): PreferencesStore = stores.getOrPut(name) { MemoryPrefs() }
    }

    private class FakeServerApi : SyncApi {
        val server = LinkedHashMap<String, SyncRecord>()
        var seq = 0L
        private var tokenValue = ""
        val token: String get() = tokenValue

        override suspend fun login(username: String, password: String): String {
            tokenValue = "tok"
            return tokenValue
        }

        override fun setToken(token: String) {
            tokenValue = token
        }

        override suspend fun fullState(): PullPage = PullPage(server.values.toList(), seq, false)

        override suspend fun pull(since: Long, limit: Int): PullPage {
            val changed = server.values.filter { it.serverSeq > since }
            return PullPage(changed, changed.maxOfOrNull { it.serverSeq } ?: since, false)
        }

        override suspend fun push(records: List<SyncRecord>): PushResult {
            val applied = mutableListOf<SyncRecord>()
            val conflicts = mutableListOf<SyncRecord>()
            for (record in records) {
                val current = server[record.key]
                if (current == null || record.updatedAt > current.updatedAt) {
                    seq += 1
                    val stored = record.copy(serverSeq = seq)
                    server[record.key] = stored
                    applied.add(stored)
                } else {
                    conflicts.add(current)
                }
            }
            return PushResult(applied, conflicts, seq)
        }
    }

    private class Device(val library: LibraryRepository, val vocabulary: VocabularyRepository, val coordinator: SyncCoordinator)

    private fun device(api: FakeServerApi): Device {
        val root = File(System.getProperty("java.io.tmpdir"), "lr-device-" + System.nanoTime())
        root.mkdirs()
        tempRoots.add(root)
        val context = TestAppContext(root)
        val library = LibraryRepository(context)
        val vocabulary = VocabularyRepository(context)
        val source = LocalSyncSource(library, vocabulary)
        val coordinator = SyncCoordinator(api, source, FileSyncStateStore(File(root, "sync")), InMemorySecretStore(), clock = { 1_000_000L })
        return Device(library, vocabulary, coordinator)
    }

    private suspend fun seedBook(device: Device, chapterIndex: Int, progress: Float, updatedAt: Long) {
        val dir = File(device.library.booksDir, "book-1").apply { mkdirs() }
        device.library.registerImportedBook(
            Book(
                id = "book-1",
                title = "The Lantern Library",
                author = "Ada",
                extractedDir = dir.absolutePath,
                coverRelativePath = null,
                chapters = listOf(Chapter("One", "one.xhtml")),
                addedAt = 1L,
                chapterIndex = chapterIndex,
                progress = progress,
                progressUpdatedAt = updatedAt
            )
        )
    }

    private suspend fun seedWord(device: Device, meaning: String, level: Int, updatedAt: Long) {
        device.vocabulary.upsert(
            SavedWord(
                id = "study", headword = "study", phonetic = "", meaning = meaning,
                sentence = "He studied hard.", bookId = "book-1", bookTitle = "The Lantern Library",
                chapterTitle = "One", addedAt = 50L, reviewLevel = level, updatedAt = updatedAt
            )
        )
    }

    @Test
    fun progressAndVocabularyConvergeAcrossTwoRealDevices() = runBlocking {
        val api = FakeServerApi()
        val a = device(api)
        val b = device(api)

        seedBook(a, chapterIndex = 1, progress = 0.2f, updatedAt = 100)
        seedBook(b, chapterIndex = 5, progress = 0.6f, updatedAt = 200)
        seedWord(a, meaning = "n. 学习；研究", level = 0, updatedAt = 300)
        seedWord(b, meaning = "n. 学习", level = 3, updatedAt = 200)

        a.coordinator.sync()
        b.coordinator.sync()
        a.coordinator.sync()

        val bookA = a.library.loadBooks().single()
        val bookB = b.library.loadBooks().single()
        assertEquals(5, bookA.chapterIndex, "A 应接受 B 更晚的进度")
        assertEquals(200L, bookA.progressUpdatedAt)
        assertEquals(5, bookB.chapterIndex, "B 不应被 A 的旧进度回灌")

        val wordA = a.vocabulary.load().single()
        val wordB = b.vocabulary.load().single()
        assertEquals("n. 学习；研究", wordA.meaning, "文本取更晚的 A")
        assertEquals(3, wordA.reviewLevel, "复习等级取更大的 B")
        assertEquals(3, wordB.reviewLevel)
        assertEquals("n. 学习；研究", wordB.meaning)
    }

    @Test
    fun bookMissingLocallyIsSkippedWithoutError() = runBlocking {
        val api = FakeServerApi()
        val a = device(api)
        val b = device(api)
        seedBook(a, chapterIndex = 3, progress = 0.3f, updatedAt = 100)
        a.coordinator.sync()
        // B 没导入这本书：拉到的进度记录应被安全跳过
        val report = b.coordinator.sync()
        assertTrue(report.downloaded >= 1)
        assertEquals(0, b.library.loadBooks().size)
    }

    @Test
    fun waterMarkStopsRepushingUnchangedData() = runBlocking {
        val api = FakeServerApi()
        val a = device(api)
        seedBook(a, chapterIndex = 2, progress = 0.4f, updatedAt = 100)
        seedWord(a, meaning = "n. 学习", level = 1, updatedAt = 100)

        a.coordinator.sync()
        val serverAfterFirst = api.server.size
        a.coordinator.sync()

        assertEquals(2, serverAfterFirst)
        assertEquals(2, api.server.size, "第二次同步不应新增记录")
    }
}
