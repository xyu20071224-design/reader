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
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨平台端到端里的 **Linux 客户端**一步（由 sync-server/e2e-cross-platform.sh 驱动）。
 *
 * 环境变量：
 * - LR_SYNC_BASE 服务端地址（如 http://127.0.0.1:8799）
 * - LR_SYNC_STEP seed | verify
 * - LR_SYNC_USER / LR_SYNC_PASS 账号
 *
 * 未设置 LR_SYNC_BASE 时整体跳过（不影响常规测试与 CI）。
 */
class HostSideSyncStepTest {

    private class MemoryPrefs : PreferencesStore {
        private val values = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private class TempContext(override val filesDir: File) : AppContext {
        private val stores = HashMap<String, PreferencesStore>()
        override fun prefs(name: String): PreferencesStore = stores.getOrPut(name) { MemoryPrefs() }
    }

    private fun root(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "lr-host-client-" + System.nanoTime())
        dir.mkdirs()
        return dir
    }

    private fun seedBook(library: LibraryRepository, chapterIndex: Int, progress: Float, updatedAt: Long) {
        val dir = File(library.booksDir, "book-1").apply { mkdirs() }
        runBlocking {
            library.registerImportedBook(
                Book(
                    id = "book-1", title = "The Lantern Library", author = "Ada",
                    extractedDir = dir.absolutePath, coverRelativePath = null,
                    chapters = listOf(Chapter("One", "one.xhtml")), addedAt = 1L,
                    chapterIndex = chapterIndex, progress = progress, progressUpdatedAt = updatedAt
                )
            )
        }
    }

    @Test
    fun linuxClientStep() {
        val base = System.getenv("LR_SYNC_BASE")
        assumeTrue("未设置 LR_SYNC_BASE，跳过跨平台宿主客户端步骤", !base.isNullOrBlank())
        val step = System.getenv("LR_SYNC_STEP") ?: "seed"
        val user = System.getenv("LR_SYNC_USER") ?: "e2e"
        val pass = System.getenv("LR_SYNC_PASS") ?: "e2e-password-123"

        when (step) {
            "seed" -> {
                val context = TempContext(root())
                val library = LibraryRepository(context)
                val vocabulary = VocabularyRepository(context)
                val now = System.currentTimeMillis()
                runBlocking {
                    seedBook(library, chapterIndex = 3, progress = 0.3f, updatedAt = now)
                    vocabulary.upsert(
                        SavedWord(
                            id = "lantern", headword = "lantern", phonetic = "", meaning = "n. 灯笼",
                            sentence = "The lantern glowed.", bookId = "book-1",
                            bookTitle = "The Lantern Library", chapterTitle = "One",
                            addedAt = now, reviewLevel = 0, updatedAt = now
                        )
                    )
                    val coordinator = SyncCoordinator(
                        HttpSyncApi(base),
                        LocalSyncSource(library, vocabulary),
                        FileSyncStateStore(File(context.filesDir, "sync")),
                        InMemorySecretStore()
                    )
                    coordinator.login(user, pass)
                    val report = coordinator.sync()
                    println("[seed] uploaded=" + report.uploaded + " pending=" + report.pending)
                    assertEquals(0, report.pending, "seed 阶段不应残留待推送")

                    // 第三个冲突场景：术语备注（Linux 先写一条较早的）
                    val api = HttpSyncApi(base)
                    api.login(user, pass)
                    val note = SyncRecord(
                        collection = SyncCollections.GLOSSARY,
                        id = "book-1::lantern",
                        updatedAt = now,
                        payload = JSONObject()
                            .put("bookId", "book-1").put("term", "lantern").put("kind", "word")
                            .put("note", "Linux 备注")
                    )
                    val pushed = api.push(listOf(note))
                    assertEquals(1, pushed.applied.size, "seed 的术语备注应被服务端接受")
                }
            }

            "verify" -> {
                val context = TempContext(root())
                val library = LibraryRepository(context)
                val vocabulary = VocabularyRepository(context)
                runBlocking {
                    // 本机先有这本书，远端进度才落得下来（正文不同步）
                    seedBook(library, chapterIndex = 0, progress = 0f, updatedAt = 0L)
                    val coordinator = SyncCoordinator(
                        HttpSyncApi(base),
                        LocalSyncSource(library, vocabulary),
                        FileSyncStateStore(File(context.filesDir, "sync")),
                        InMemorySecretStore()
                    )
                    coordinator.login(user, pass)
                    coordinator.sync()
                }
                val book = runBlocking { library.loadBooks() }.single()
                assertEquals(9, book.chapterIndex, "Linux 端应看到 Android 推上来的更晚进度")
                val lantern = runBlocking { vocabulary.load() }.firstOrNull { it.id == "lantern" }
                assertTrue(lantern != null, "Linux 端应看到 Android 合并后的生词")
                assertEquals("n. 灯笼；提灯", lantern!!.meaning, "文本取更晚的 Android 版本")
                assertTrue(lantern.reviewLevel >= 3, "复习等级取较大的 Android 版本")

                // 第三个冲突场景：Linux 应看到 Android 更晚的术语备注
                val api = HttpSyncApi(base)
                val note = runBlocking {
                    api.login(user, pass)
                    api.pull(0).records.firstOrNull {
                        it.collection == SyncCollections.GLOSSARY && it.id == "book-1::lantern"
                    }
                }
                assertTrue(note != null, "Linux 应拉到术语备注记录")
                assertEquals("Android 备注（更晚）", note!!.payload.getString("note"), "备注取更晚的 Android 版本")
            }

            else -> throw IllegalArgumentException("未知 step: " + step)
        }
    }
}
