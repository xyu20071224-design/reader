package com.linguareader.shared.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 同步引擎与本地状态的核心用例：推送/拉取/离线/冲突重推/游标与 outbox 持久化。
 * 三个「冲突场景」的领域语义在 ConflictResolverTest；这里验证引擎的冲突闭环。
 */
class SyncEngineTest {

    private fun record(id: String, updatedAt: Long, progress: Double = 0.0, deletedAt: Long = 0L) = SyncRecord(
        collection = "progress",
        id = id,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        payload = JSONObject().put("progress", progress)
    )

    private class FakeApi : SyncApi {
        val pushes = mutableListOf<List<SyncRecord>>()
        val conflictPlan = ArrayDeque<List<SyncRecord>>()
        val pullPages = ArrayDeque<PullPage>()
        var failPush = false

        override suspend fun login(username: String, password: String): String = "fake-token"

        override suspend fun fullState(): PullPage = PullPage(emptyList(), 0, false)

        override suspend fun pull(since: Long, limit: Int): PullPage =
            if (pullPages.isEmpty()) PullPage(emptyList(), since, false) else pullPages.removeFirst()

        override suspend fun push(records: List<SyncRecord>): PushResult {
            pushes.add(records)
            if (failPush) throw SyncException("offline")
            val conflicts = if (conflictPlan.isEmpty()) emptyList() else conflictPlan.removeFirst()
            return if (conflicts.isEmpty()) {
                PushResult(records, emptyList(), records.size.toLong())
            } else {
                PushResult(emptyList(), conflicts, 0L)
            }
        }
    }

    /** 冲突合并：整条 LWW，并把版本戳推进到必然大于双方，保证下一轮能胜出。 */
    private val lastWriteWins: (SyncRecord, SyncRecord, Long) -> SyncRecord = { local, remote, now ->
        val winner = if (remote.updatedAt > local.updatedAt) remote else local
        winner.copy(updatedAt = maxOf(local.updatedAt, remote.updatedAt, now) + 1)
    }

    @Test
    fun pushesOutboxAndClearsIt() = runBlocking {
        val api = FakeApi()
        val state = InMemorySyncStateStore()
        state.writeOutbox(listOf(record("book-1", 100)))

        val report = SyncEngine(api, state, lastWriteWins).sync()

        assertEquals(1, report.uploaded)
        assertEquals(0, report.pending)
        assertEquals(0, state.readOutbox().size)
        assertEquals("book-1", api.pushes.single().single().id)
    }

    @Test
    fun offlinePushKeepsOutboxForRetry() = runBlocking {
        val api = FakeApi().apply { failPush = true }
        val state = InMemorySyncStateStore()
        state.writeOutbox(listOf(record("book-1", 100)))

        assertFailsWith<SyncException> { SyncEngine(api, state, lastWriteWins).sync() }

        assertEquals(1, state.readOutbox().size)
        assertEquals(0L, state.readCursor())
    }

    @Test
    fun conflictIsMergedAndRepushedUntilConverged() = runBlocking {
        val api = FakeApi()
        // 第一次推送撞上服务端更新的版本；合并后第二次推送应被接受。
        api.conflictPlan.add(listOf(record("book-1", 200, progress = 0.8)))
        val state = InMemorySyncStateStore()
        state.writeOutbox(listOf(record("book-1", 100, progress = 0.2)))

        val report = SyncEngine(api, state, lastWriteWins, clock = { 1000L }).sync()

        assertEquals(2, api.pushes.size)
        assertTrue(report.unresolved.isEmpty())
        assertEquals(0, report.pending)
        val secondPush = api.pushes[1].single()
        assertTrue(secondPush.updatedAt > 200, "merged record must outrank the server version")
        assertEquals(0.8, secondPush.payload.getDouble("progress"))
    }

    @Test
    fun conflictRoundsExhaustedKeepsPendingAndReportsIt() = runBlocking {
        val api = FakeApi()
        // 服务端永远返回一个更旧的「冲突」，合并也无法收敛 -> 达到上限后停下。
        repeat(6) { api.conflictPlan.add(listOf(record("book-1", 1))) }
        val state = InMemorySyncStateStore()
        state.writeOutbox(listOf(record("book-1", 100)))

        val report = SyncEngine(api, state, lastWriteWins, clock = { 1000L }, maxConflictRounds = 3).sync()

        assertEquals(3, api.pushes.size)
        assertEquals(1, report.unresolved.size)
        assertEquals(1, report.pending)
        assertEquals(1, state.readOutbox().size)
    }

    @Test
    fun pullAppliesRemotePagesAndAdvancesCursor() = runBlocking {
        val api = FakeApi()
        api.pullPages.add(PullPage(listOf(record("book-1", 10)), nextSeq = 1, hasMore = true))
        api.pullPages.add(PullPage(listOf(record("book-2", 20)), nextSeq = 2, hasMore = false))
        val state = InMemorySyncStateStore()
        val applied = mutableListOf<SyncRecord>()

        val report = SyncEngine(api, state, lastWriteWins).sync { applied.addAll(it) }

        assertEquals(2, report.downloaded)
        assertEquals(2L, report.cursor)
        assertEquals(2L, state.readCursor())
        assertEquals(listOf("book-1", "book-2"), applied.map { it.id })
    }

    @Test
    fun cursorPersistsAcrossSyncsSoNothingIsPulledTwice() = runBlocking {
        val api = FakeApi()
        api.pullPages.add(PullPage(listOf(record("book-1", 10)), nextSeq = 7, hasMore = false))
        val state = InMemorySyncStateStore()
        val engine = SyncEngine(api, state, lastWriteWins)

        engine.sync()
        api.pullPages.add(PullPage(emptyList(), nextSeq = 7, hasMore = false))
        val second = engine.sync()

        assertEquals(0, second.downloaded)
        assertEquals(7L, second.cursor)
    }

    @Test
    fun tombstonesTravelThroughTheEngine() = runBlocking {
        val api = FakeApi()
        api.pullPages.add(PullPage(listOf(record("book-1", 30, deletedAt = 30)), nextSeq = 1, hasMore = false))
        val state = InMemorySyncStateStore()
        var seen: SyncRecord? = null

        SyncEngine(api, state, lastWriteWins).sync { seen = it.single() }

        assertEquals(30L, seen?.deletedAt)
    }

    @Test
    fun fileStateStoreRoundTripsOutboxAndCursor() {
        val dir = File(System.getProperty("java.io.tmpdir"), "lr-sync-store-" + System.nanoTime())
        try {
            val store = FileSyncStateStore(dir)
            assertEquals(0L, store.readCursor())
            assertEquals(emptyList(), store.readOutbox())

            store.writeCursor(42)
            store.writeOutbox(listOf(record("book-1", 100), record("book-2", 200)))

            val reopened = FileSyncStateStore(dir)
            assertEquals(42L, reopened.readCursor())
            val outbox = reopened.readOutbox()
            assertEquals(listOf("book-1", "book-2"), outbox.map { it.id })
            assertEquals(200L, outbox[1].updatedAt)
        } finally {
            dir.deleteRecursively()
        }
    }
}
