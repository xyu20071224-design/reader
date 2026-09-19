package com.linguareader.shared.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 应用侧编排：令牌、变更入队、水位、两端收敛。 */
class SyncCoordinatorTest {

    private fun record(id: String, updatedAt: Long, progress: Double = 0.0) = SyncRecord(
        collection = SyncCollections.PROGRESS,
        id = id,
        updatedAt = updatedAt,
        payload = JSONObject().put("bookId", id).put("progress", progress)
    )

    /** 假服务端 + 假传输：内部维护服务端记录表，语义与 Python 服务端一致。 */
    private class FakeServerApi : SyncApi {
        val server = LinkedHashMap<String, SyncRecord>()
        val pushBatches = mutableListOf<List<SyncRecord>>()
        var seq = 0L
        private var tokenValue = ""

        /** 只读对外，避免与 SyncApi.setToken 产生 JVM 签名冲突。 */
        val token: String get() = tokenValue

        override suspend fun login(username: String, password: String): String {
            tokenValue = "tok-" + username
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
            pushBatches.add(records)
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

    private class FakeSource(private val objects: MutableMap<String, SyncRecord> = LinkedHashMap()) : SyncSource {
        override fun snapshot(): List<SyncRecord> = objects.values.toList()

        override suspend fun apply(remote: List<SyncRecord>) {
            for (record in remote) {
                val local = objects[record.key]
                objects[record.key] = when {
                    local == null -> record
                    record.updatedAt >= local.updatedAt -> record
                    else -> SyncMerger.merge(local, record, 0L)
                }
            }
        }
    }

    private fun coordinator(
        api: FakeServerApi,
        source: SyncSource,
        state: SyncStateStore = InMemorySyncStateStore(),
        secrets: SecretStore = InMemorySecretStore()
    ) = SyncCoordinator(api, source, state, secrets, clock = { 1_000_000L })

    @Test
    fun loginStoresTokenAndRestoreSessionReinstallsIt() = runBlocking {
        val api = FakeServerApi()
        val secrets = InMemorySecretStore()
        val first = coordinator(api, FakeSource(), secrets = secrets)

        first.login("alice", "secret-password")
        assertTrue(first.hasSession())
        assertTrue(secrets.get(SyncCoordinator.TOKEN_KEY)!!.startsWith("tok-"))

        // 模拟进程重启：新 api 实例 + 同一份密钥存储
        val restartedApi = FakeServerApi()
        val restarted = coordinator(restartedApi, FakeSource(), secrets = secrets)
        assertTrue(restarted.restoreSession())
        assertEquals("tok-alice", restartedApi.token)
    }

    @Test
    fun logoutClearsSession() = runBlocking {
        val api = FakeServerApi()
        val secrets = InMemorySecretStore()
        val coord = coordinator(api, FakeSource(), secrets = secrets)
        coord.login("alice", "secret-password")
        coord.logout()
        assertFalse(coord.hasSession())
        assertEquals(null, secrets.get(SyncCoordinator.TOKEN_KEY))
    }

    @Test
    fun localChangeIsPushedOnceAndWatermarkPreventsRePush() = runBlocking {
        val api = FakeServerApi()
        val state = InMemorySyncStateStore()
        val source = FakeSource(mutableMapOf("progress/book-1" to record("book-1", 100, 0.4)))
        val coord = coordinator(api, source, state)

        coord.sync()
        assertEquals(1, api.pushBatches.size)
        assertEquals(1, api.pushBatches.first().size)

        // 第二次同步：本地没变 -> 不应再产生推送
        coord.sync()
        assertEquals(1, api.pushBatches.size)
        assertEquals(100L, state.readSynced()["progress/book-1"])
    }

    @Test
    fun secondClientReceivesFirstClientsChange() = runBlocking {
        val api = FakeServerApi()
        val a = coordinator(api, FakeSource(mutableMapOf("progress/book-1" to record("book-1", 100, 0.4))))
        val bSource = FakeSource()
        val b = coordinator(api, bSource)

        a.sync()
        b.sync()

        assertEquals(0.4, bSource.snapshot().single().payload.getDouble("progress"), 1e-6)
    }

    @Test
    fun remoteChangeIsAppliedToLocalSource() = runBlocking {
        val api = FakeServerApi()
        val bSource = FakeSource()
        val b = coordinator(api, bSource)
        // 另一端直接写服务端
        api.push(listOf(record("book-9", 500, 0.95)))

        val report = b.sync()

        assertEquals(1, report.downloaded)
        assertEquals(0.95, bSource.snapshot().single().payload.getDouble("progress"), 1e-6)
        assertEquals(500L, bSource.snapshot().single().updatedAt)
    }

    @Test
    fun fileSecretStoreRoundTrips() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lr-secret-" + System.nanoTime())
        try {
            val store = FileSecretStore(File(dir, "secrets.json"))
            assertEquals(null, store.get("sync.token"))
            store.put("sync.token", "abc")
            store.put("other", "xyz")
            assertEquals("abc", FileSecretStore(File(dir, "secrets.json")).get("sync.token"))
            store.remove("sync.token")
            assertEquals(null, store.get("sync.token"))
            assertEquals("xyz", store.get("other"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileStateStorePersistsSyncedWatermark() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lr-state-" + System.nanoTime())
        try {
            val store = FileSyncStateStore(dir)
            assertEquals(emptyMap(), store.readSynced())
            store.writeSynced(mapOf("progress/book-1" to 100L, "vocabulary/study" to 250L))
            val reopened = FileSyncStateStore(dir)
            assertEquals(100L, reopened.readSynced()["progress/book-1"])
            assertEquals(250L, reopened.readSynced()["vocabulary/study"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
