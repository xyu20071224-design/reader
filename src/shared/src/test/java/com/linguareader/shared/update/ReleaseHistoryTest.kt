package com.linguareader.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReleaseHistoryTest {
    private fun release(version: String, notes: String = "notes $version", id: String = version,
        draft: Boolean = false, prerelease: Boolean = false) =
        ReleaseRecord(id, version, notes, "2026-11-01", draft = draft, prerelease = prerelease)

    private class Source(override val id: String = "source-a", var pages: Map<String?, ReleasePage>) : ReleaseSource {
        override val assetPolicy = ReleaseUrlPolicy { false }
        var calls = 0
        var onFetch: () -> Unit = {}
        override suspend fun fetchPage(cursor: String?): ReleasePage {
            calls++; onFetch()
            return pages[cursor] ?: error("offline")
        }
        override suspend fun latestRelease(): ReleaseRecord? = error("History must use pagination")
    }

    @Test fun `version ordering is numeric and rejects unknown syntax without integer overflow`() {
        assertTrue(ReleaseVersion.parse("v1.10")!! > ReleaseVersion.parse("1.9.9")!!)
        assertEquals(0, ReleaseVersion.parse("01.02.0")!!.compareTo(ReleaseVersion.parse("1.2")!!))
        assertTrue(ReleaseVersion.parse("9999999999999999.1")!! > ReleaseVersion.parse("2147483647.9")!!)
        assertNull(ReleaseVersion.parse("nightly"))
        assertNull(ReleaseVersion.parse("1.2-rc.1"))
    }

    @Test fun `two pages de duplicate releases filter channels and order versions rather than dates`() = runBlocking {
        val source = Source(pages = mapOf(
            null to ReleasePage("source-a", listOf(release("v1.10"), release("v1.9"), release("v2.0", draft = true)), "next"),
            "next" to ReleasePage("source-a", listOf(release("v1.9"), release("v1.8"), release("v2.1", prerelease = true)))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source })
            assertTrue(repository.loadLocal().entries.isEmpty())
            assertEquals(0, source.calls)
            val snapshot = repository.refresh()
            assertTrue(snapshot.complete)
            assertEquals(listOf("v1.10", "v1.9", "v1.8"), snapshot.entries.map { it.tag })
            assertEquals(2, source.calls)
            assertTrue(repository.loadLocal().complete)
            assertEquals(2, source.calls)
            val upgrade = ReleaseHistoryPolicy.upgrade(snapshot,
                InstallationHistory(BuildReleaseIdentity("1.10", 10), "1.8", InstallationChange.UPGRADE))
            assertEquals(listOf("v1.10", "v1.9"), upgrade.entries.map { it.tag })
            assertTrue(upgrade.complete)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `first observation invents no prior install and restarts preserve actual upgrade range`() {
        val first = InstallationHistoryPolicy.observe(null, BuildReleaseIdentity("1.8", 8))
        assertNull(first.previousVersion)
        assertEquals(InstallationChange.FIRST_OBSERVED, first.change)
        val upgraded = InstallationHistoryPolicy.observe(first, BuildReleaseIdentity("1.10", 10))
        assertEquals("1.8", upgraded.previousVersion)
        assertEquals(InstallationChange.UPGRADE, upgraded.change)
        assertEquals(upgraded, InstallationHistoryPolicy.observe(upgraded, upgraded.current))
        assertEquals(InstallationChange.REBUILD, InstallationHistoryPolicy.observe(upgraded,
            upgraded.current.copy(sourceCommit = "a".repeat(40))).change)
        assertEquals(InstallationChange.DOWNGRADE,
            InstallationHistoryPolicy.observe(upgraded, BuildReleaseIdentity("1.9", 9)).change)
    }

    @Test fun `partial refresh remains incomplete and cannot overwrite previous complete offline cache`() = runBlocking {
        val source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source }, now = { 100L })
            assertTrue(repository.refresh().complete)
            source.pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.1")), "unavailable"))
            val partial = repository.refresh()
            assertFalse(partial.complete)
            assertTrue(partial.usesCachedEntries)
            assertTrue(HistoryIssue.FETCH_FAILED in partial.issues)
            assertEquals(listOf("v1.1", "v1.0"), partial.entries.map { it.tag })
            val offline = repository.loadLocal()
            assertTrue(offline.complete)
            assertEquals(listOf("v1.0"), offline.entries.map { it.tag })
        } finally { dir.deleteRecursively() }
    }

    @Test fun `source caches are isolated and corrupt cache becomes an honest local empty state`() = runBlocking {
        var source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source })
            repository.refresh()
            source = Source("source-b", emptyMap())
            assertTrue(repository.loadLocal().entries.isEmpty())
            assertEquals(0, source.calls)
            repository.cacheFile(source.id).writeText("truncated cache")
            val local = repository.loadLocal()
            assertFalse(local.complete)
            assertTrue(HistoryIssue.CACHE_INVALID in local.issues)
            assertEquals(0, source.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `pagination cycles and conflicting duplicate IDs cannot become complete snapshots`() = runBlocking {
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val source = Source(pages = mapOf(
                null to ReleasePage("source-a", listOf(release("v1.0")), "same"),
                "same" to ReleasePage("source-a", listOf(release("v1.0")), "same")))
            val repository = ReleaseHistoryRepository(dir, { source })
            assertTrue(HistoryIssue.PAGINATION_CYCLE in repository.refresh().issues)
            source.pages = mapOf(
                null to ReleasePage("source-a", listOf(release("v1.0")), "next"),
                "next" to ReleasePage("source-a", listOf(release("v1.0", notes = "edited while paging"))))
            val conflict = repository.refresh()
            assertFalse(conflict.complete)
            assertTrue(HistoryIssue.CONFLICTING_RELEASE in conflict.issues)
            assertFalse(repository.cacheFile(source.id).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `unknown stable tag does not silently count as complete version range`() {
        val snapshot = ReleaseHistorySnapshot("source", listOf(release("nightly").let(ReleaseHistoryEntry::from)), true, 1)
        val range = ReleaseHistoryPolicy.upgrade(snapshot,
            InstallationHistory(BuildReleaseIdentity("2.0", 20), "1.0", InstallationChange.UPGRADE))
        assertFalse(range.complete)
        assertTrue(HistoryIssue.INVALID_VERSION in range.issues)
    }

    @Test fun `same source reselection prevents in flight history from entering offline cache`() = runBlocking {
        var epoch = 0L
        val source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        source.onFetch = { epoch++ }
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source }, { epoch })
            val result = repository.refresh()
            assertFalse(result.complete)
            assertTrue(HistoryIssue.SOURCE_CHANGED in result.issues)
            assertFalse(repository.cacheFile(source.id).exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `cancelled refresh propagates cancellation without touching offline archive`() = runBlocking {
        val source = Source(pages = emptyMap())
        source.onFetch = { throw CancellationException("cancelled") }
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val result = runCatching { ReleaseHistoryRepository(dir, { source }).refresh() }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `invalid configured source cannot crash startup local history read`() = runBlocking {
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { error("invalid configured URL") })
            assertTrue(HistoryIssue.INVALID_SOURCE in repository.loadLocal().issues)
            assertTrue(HistoryIssue.INVALID_SOURCE in repository.refresh().issues)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `source switch after preparing cache prevents publication and cleans temporary file`() = runBlocking {
        var epoch = 0L
        val source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source }, { epoch })
            repository.beforeCachePublication = { repository.invalidate(); epoch++ }
            val result = repository.refresh()
            assertEquals(setOf(HistoryIssue.SOURCE_CHANGED), result.issues)
            assertFalse(repository.cacheFile(source.id).exists())
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `new refresh at publication boundary supersedes old request without replacing new cache`() = runBlocking {
        val source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source })
            val prepared = CompletableDeferred<Unit>()
            val resume = java.util.concurrent.CountDownLatch(1)
            var first = true
            repository.beforeCachePublication = {
                if (first) {
                    first = false
                    prepared.complete(Unit)
                    check(resume.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            val old = async { repository.refresh() }
            prepared.await()
            source.pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.1"))))
            val newer = repository.refresh()
            resume.countDown()
            assertTrue(newer.complete)
            assertEquals(setOf(HistoryIssue.SOURCE_CHANGED), old.await().issues)
            assertEquals(listOf("v1.1"), repository.loadLocal().entries.map { it.tag })
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `cancellation at cache publication is rethrown and preserves prior archive`() = runBlocking {
        val source = Source(pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.0")))))
        val dir = Files.createTempDirectory("release-history-").toFile()
        try {
            val repository = ReleaseHistoryRepository(dir, { source })
            repository.refresh()
            source.pages = mapOf(null to ReleasePage("source-a", listOf(release("v1.1"))))
            repository.beforeCachePublication = { throw CancellationException("publication cancelled") }
            assertTrue(runCatching { repository.refresh() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf("v1.0"), repository.loadLocal().entries.map { it.tag })
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }

}
