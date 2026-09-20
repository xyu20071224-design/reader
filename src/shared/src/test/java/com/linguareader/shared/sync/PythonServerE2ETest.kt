package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 跨语言端到端：真实 Python 服务端 + 两个 Kotlin 客户端实例（各自独立的本地状态与 outbox）。
 *
 * 覆盖验收要求的行为：两个客户端之间的数据同步、离线写入后合并、三个冲突场景
 * （同一本书进度 / 同一条生词并发编辑 / 同一条术语备注并发编辑）。
 *
 * 环境不满足（无 python3 或找不到服务端脚本）时跳过，不伪装通过。
 */
class PythonServerE2ETest {

    private var process: Process? = null
    private var workDir: File? = null

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "sync-server/server.py").isFile) dir = dir.parentFile
        dir ?: File(System.getProperty("user.dir")).absoluteFile
    }

    private fun pythonAvailable(): Boolean = try {
        val probe = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0
    } catch (_: Exception) {
        false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startServer(): String {
        assumeTrue("找不到 sync-server/server.py", File(repoRoot, "sync-server/server.py").isFile)
        assumeTrue("环境无 python3，跳过跨语言 E2E", pythonAvailable())

        val dir = File(System.getProperty("java.io.tmpdir"), "lr-e2e-" + System.nanoTime())
        dir.mkdirs()
        workDir = dir
        val port = freePort()
        val script = File(repoRoot, "sync-server/server.py").absolutePath

        val create = ProcessBuilder("python3", script, "create-user", "e2e", "--password", "e2e-password-123")
            .directory(repoRoot)
            .redirectErrorStream(true)
        create.environment()["LR_SYNC_DB"] = File(dir, "sync.db").absolutePath
        create.environment()["LR_SYNC_BLOB_DIR"] = File(dir, "blobs").absolutePath
        val created = create.start()
        assertTrue(created.waitFor(20, TimeUnit.SECONDS), "create-user 超时")
        assertEquals(0, created.exitValue(), "create-user 失败：" + created.inputStream.bufferedReader().readText())

        val serve = ProcessBuilder("python3", script, "serve")
            .directory(repoRoot)
            .redirectErrorStream(true)
        serve.environment()["LR_SYNC_DB"] = File(dir, "sync.db").absolutePath
        serve.environment()["LR_SYNC_BLOB_DIR"] = File(dir, "blobs").absolutePath
        serve.environment()["LR_SYNC_HOST"] = "127.0.0.1"
        serve.environment()["LR_SYNC_PORT"] = port.toString()
        // 服务端每请求一行日志：必须落到文件，否则管道缓冲区填满会把服务端阻塞住。
        serve.redirectOutput(File(dir, "server.log"))
        process = serve.start()

        val base = "http://127.0.0.1:" + port
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            try {
                val connection = (URL(base + "/api/v1/health").openConnection() as HttpURLConnection)
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                val code = connection.responseCode
                connection.disconnect()
                if (code == 200) return base
            } catch (_: Exception) {
                Thread.sleep(150)
            }
        }
        // 失败时把服务端日志与进程状态带进断言消息：GitHub 的 ::error 注解会带上它，
        // 无日志环境也能定位（macOS 上曾出现「20 秒未就绪」但看不到原因）。
        val serverLog = File(dir, "server.log")
        val tail = if (serverLog.isFile) serverLog.readText().takeLast(800) else "(无 server.log)"
        val alive = process?.isAlive == true
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val version = runCatching {
            val probe = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
            probe.waitFor(10, TimeUnit.SECONDS)
            probe.inputStream.bufferedReader().readText().trim()
        }.getOrDefault("?")
        throw AssertionError("服务端 20 秒内未就绪（alive=$alive exit=$exit python=$version）日志尾部：$tail")
    }

    @AfterTest
    fun stopServer() {
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
        workDir?.deleteRecursively()
    }

    /** 一个客户端实例：独立 outbox/cursor + 独立本地状态，模拟一台设备。 */
    private class Client(base: String, private val merge: (SyncRecord, SyncRecord, Long) -> SyncRecord) {
        val api = HttpSyncApi(base)
        val state = InMemorySyncStateStore()
        val local = LinkedHashMap<String, SyncRecord>()

        fun startSession() = runBlocking { api.login("e2e", "e2e-password-123") }

        fun edit(record: SyncRecord) {
            local[record.key] = record
            state.writeOutbox(state.readOutbox().filterNot { it.key == record.key } + record)
        }

        fun sync(): SyncReport = runBlocking {
            SyncEngine(api, state, merge, clock = { 1_000_000L }).sync { remote ->
                for (record in remote) local[record.key] = record
            }
        }

        fun outboxSize(): Int = state.readOutbox().size
    }

    // ---- 领域适配：record <-> 模型，冲突用真实 ConflictResolver ----

    private fun progressPayload(book: Book): JSONObject = JSONObject()
        .put("bookId", book.id)
        .put("title", book.title)
        .put("chapterIndex", book.chapterIndex)
        .put("pageIndex", book.pageIndex)
        .put("progress", book.progress.toDouble())
        .put("locusBlockIndex", book.locusBlockIndex)
        .put("locusAnchor", book.locusAnchor)
        .put("ttsChapterIndex", book.ttsChapterIndex)
        .put("ttsSentenceIndex", book.ttsSentenceIndex)

    private fun bookFrom(record: SyncRecord): Book {
        val p = record.payload
        return Book(
            id = p.optString("bookId", record.id),
            title = p.optString("title"),
            author = "",
            extractedDir = File(System.getProperty("java.io.tmpdir"), record.id).absolutePath,
            coverRelativePath = null,
            chapters = emptyList(),
            addedAt = 0L,
            chapterIndex = p.optInt("chapterIndex"),
            pageIndex = p.optInt("pageIndex"),
            progress = p.optDouble("progress").toFloat(),
            locusBlockIndex = p.optInt("locusBlockIndex", -1),
            locusAnchor = p.optString("locusAnchor", Book.ANCHOR_EXACT),
            ttsChapterIndex = p.optInt("ttsChapterIndex"),
            ttsSentenceIndex = p.optInt("ttsSentenceIndex"),
            progressUpdatedAt = record.updatedAt
        )
    }

    private val progressMerge: (SyncRecord, SyncRecord, Long) -> SyncRecord = { local, remote, now ->
        val merged = ConflictResolver.mergeProgress(bookFrom(local), bookFrom(remote))
        SyncRecord(
            collection = "progress",
            id = local.id,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt, now) + 1,
            deletedAt = 0L,
            payload = progressPayload(merged)
        )
    }

    private fun vocabRecord(id: String, meaning: String, level: Int, updatedAt: Long): SyncRecord = SyncRecord(
        collection = "vocabulary",
        id = id,
        updatedAt = updatedAt,
        payload = SavedWord(
            id = id, headword = id, phonetic = "", meaning = meaning,
            sentence = "He studied hard.", bookId = "book-1", bookTitle = "A Test Book",
            chapterTitle = "One", addedAt = 100L, reviewLevel = level, updatedAt = updatedAt
        ).toJson()
    )

    private val vocabMerge: (SyncRecord, SyncRecord, Long) -> SyncRecord = { local, remote, now ->
        val merged = ConflictResolver.mergeSavedWord(SavedWord.fromJson(local.payload), SavedWord.fromJson(remote.payload))
        SyncRecord(
            collection = "vocabulary",
            id = local.id,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt, now) + 1,
            payload = merged.toJson()
        )
    }

    private val noteMerge: (SyncRecord, SyncRecord, Long) -> SyncRecord = { local, remote, now ->
        val text = ConflictResolver.mergeText(
            local.payload.optString("note"), local.updatedAt,
            remote.payload.optString("note"), remote.updatedAt
        )
        val winner = if (remote.updatedAt > local.updatedAt) remote else local
        SyncRecord(
            collection = "glossary",
            id = local.id,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt, now) + 1,
            payload = JSONObject(winner.payload.toString()).put("note", text)
        )
    }

    @Test
    fun twoClientsExchangeANewRecord() {
        val base = startServer()
        val a = Client(base, progressMerge); val b = Client(base, progressMerge)
        a.startSession(); b.startSession()

        a.edit(SyncRecord("progress", "book-1", 100, 0, progressPayload(Book(
            id = "book-1", title = "The Lantern Library", author = "", extractedDir = "/x",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L,
            chapterIndex = 3, pageIndex = 4, progress = 0.42f
        ))))
        a.sync()
        assertEquals(0, a.outboxSize())

        b.sync()
        val received = b.local["progress/book-1"]
        assertEquals(0.42, received!!.payload.getDouble("progress"), 1e-6)
        assertEquals(3, received.payload.getInt("chapterIndex"))
    }

    @Test
    fun offlineWriteMergesWithRemoteAfterReconnect() {
        val base = startServer()
        val a = Client(base, progressMerge); val b = Client(base, progressMerge)
        a.startSession(); b.startSession()

        // A 离线写入（只进 outbox，不推送）
        a.edit(SyncRecord("progress", "book-1", 100, 0, progressPayload(Book(
            id = "book-1", title = "Offline", author = "", extractedDir = "/x",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L, chapterIndex = 1, progress = 0.1f
        ))))
        // B 在线写入更晚的进度
        b.edit(SyncRecord("progress", "book-1", 200, 0, progressPayload(Book(
            id = "book-1", title = "Online", author = "", extractedDir = "/y",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L, chapterIndex = 9, progress = 0.9f
        ))))
        b.sync()

        // A 恢复联网：冲突 -> 合并（进度 LWW 取更晚）-> 重推 -> 收敛
        val report = a.sync()
        assertTrue(report.unresolved.isEmpty(), "冲突应在一轮内收敛")
        assertEquals(0, a.outboxSize())
        assertEquals(9, a.local["progress/book-1"]!!.payload.getInt("chapterIndex"))
        assertEquals(9, b.local["progress/book-1"]!!.payload.getInt("chapterIndex"))
    }

    @Test
    fun sameBookProgressConflictPicksNewerLocus() {
        val base = startServer()
        val a = Client(base, progressMerge); val b = Client(base, progressMerge)
        a.startSession(); b.startSession()

        a.edit(SyncRecord("progress", "book-7", 300, 0, progressPayload(Book(
            id = "book-7", title = "A", author = "", extractedDir = "/a",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L,
            chapterIndex = 12, pageIndex = 5, progress = 0.61f, locusBlockIndex = 88
        ))))
        b.edit(SyncRecord("progress", "book-7", 500, 0, progressPayload(Book(
            id = "book-7", title = "A", author = "", extractedDir = "/b",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L,
            chapterIndex = 20, pageIndex = 1, progress = 0.95f, locusBlockIndex = 140
        ))))
        a.sync(); b.sync(); a.sync()

        assertEquals(20, a.local["progress/book-7"]!!.payload.getInt("chapterIndex"))
        assertEquals(140, a.local["progress/book-7"]!!.payload.getInt("locusBlockIndex"))
        assertEquals(20, b.local["progress/book-7"]!!.payload.getInt("chapterIndex"))
    }

    @Test
    fun sameVocabularyWordConcurrentEditMergesFields() {
        val base = startServer()
        val a = Client(base, vocabMerge); val b = Client(base, vocabMerge)
        a.startSession(); b.startSession()

        // A 改释义（较晚），B 复习过（等级更高）——合并后两者都要保住
        a.edit(vocabRecord("study", "n. 学习；研究", level = 0, updatedAt = 400))
        b.edit(vocabRecord("study", "n. 学习", level = 3, updatedAt = 200))
        a.sync()
        b.sync()
        a.sync()

        val merged = a.local["vocabulary/study"]!!.payload
        assertEquals("n. 学习；研究", merged.getString("meaning"))
        assertEquals(3, merged.getInt("reviewLevel"))
        assertEquals(3, b.local["vocabulary/study"]!!.payload.getInt("reviewLevel"))
    }

    @Test
    fun sameGlossaryNoteConcurrentEditKeepsLatest() {
        val base = startServer()
        val a = Client(base, noteMerge); val b = Client(base, noteMerge)
        a.startSession(); b.startSession()

        a.edit(SyncRecord("glossary", "book-1::Gandalf", 100, 0, JSONObject().put("term", "Gandalf").put("note", "灰袍")))
        b.edit(SyncRecord("glossary", "book-1::Gandalf", 300, 0, JSONObject().put("term", "Gandalf").put("note", "白袍（重生后）")))
        a.sync(); b.sync(); a.sync()

        assertEquals("白袍（重生后）", a.local["glossary/book-1::Gandalf"]!!.payload.getString("note"))
        assertEquals("白袍（重生后）", b.local["glossary/book-1::Gandalf"]!!.payload.getString("note"))
    }

    // ---------- 书籍正文（blob）：真实 Python 服务端 ----------

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("lr-blob-", ".bin")
        file.writeBytes(bytes)
        return file
    }

    private fun loginApi(base: String): HttpSyncApi {
        val api = HttpSyncApi(base)
        runBlocking { api.login("e2e", "e2e-password-123") }
        return api
    }

    @Test
    fun uploadsAndDownloadsBookContent() {
        val api = loginApi(startServer())
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val source = tempFile(payload)
        try {
            val status = runBlocking { api.uploadBlob("book-blob-1", source, chunkSize = 64 * 1024) }
            assertTrue(status.complete)
            assertEquals(payload.size.toLong(), status.uploaded)

            val listed = runBlocking { api.listBlobs() }
            assertEquals(1, listed.size)
            assertEquals("book-blob-1", listed.single().bookId)
            assertEquals(HttpSyncApi.sha256Hex(payload), listed.single().sha256)

            val target = File.createTempFile("lr-download-", ".bin").apply { delete() }
            val written = runBlocking { api.downloadBlob("book-blob-1", target, chunkSize = 64 * 1024) }
            assertEquals(payload.size.toLong(), written)
            assertTrue(payload.contentEquals(target.readBytes()))
            target.delete()
        } finally {
            source.delete()
        }
    }

    @Test
    fun resumesAPartialUpload() {
        val base = startServer()
        val api = loginApi(base)
        val payload = ByteArray(200_000) { (it % 97).toByte() }
        val source = tempFile(payload)
        try {
            // 手工先传前 50_000 字节，模拟上次中断留下的 .part
            val first = payload.copyOfRange(0, 50_000)
            val connection = (URL(base + "/api/v1/blobs/book-blob-2?offset=0&total=" + payload.size)
                .openConnection() as HttpURLConnection)
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + api.currentToken())
            connection.outputStream.use { it.write(first) }
            assertEquals(200, connection.responseCode)
            connection.disconnect()

            val status = runBlocking { api.uploadBlob("book-blob-2", source, chunkSize = 32 * 1024) }
            assertTrue(status.complete, "应从服务端已有 offset 续传并完成")
        } finally {
            source.delete()
        }
    }

    @Test
    fun downloadingMissingBlobFailsLoudly() {
        val api = loginApi(startServer())
        val target = File.createTempFile("lr-missing-", ".bin").apply { delete() }
        val error = assertFailsWith<SyncException> { runBlocking { api.downloadBlob("no-such-book", target) } }
        assertEquals(404, error.status)
    }
}
