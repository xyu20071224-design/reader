package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppReleaseHistoryRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val publishedBuild = BuildReleaseIdentity("2.3.1", 26, "ad20253d581e486c14563122acdafe8ca6f20651")

    private class Source(override val id: String = "fixture-source") : ReleaseSource {
        override val assetPolicy = ReleaseUrlPolicy { false }
        var calls = 0
        var pages = emptyMap<String?, ReleasePage>()
        var onFetch: () -> Unit = {}
        override suspend fun fetchPage(cursor: String?): ReleasePage {
            calls++; onFetch()
            return pages[cursor] ?: error("offline")
        }
        override suspend fun latestRelease(): ReleaseRecord? = error("History never uses latest-only API")
    }

    @Before fun clearHistory() {
        context.getSharedPreferences("release_history", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("update_settings", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "release-history").deleteRecursively()
    }

    private fun updates(source: Source) = AppUpdateRepository(context, { error("Unexpected network") }, { _, _ -> source })

    @Test fun `local current published notes and read markers never fetch network`() {
        val source = Source()
        val repository = AppReleaseHistoryRepository(context, updates(source), publishedBuild)
        val first = repository.loadLocal()
        assertNotNull(first.currentNotes)
        assertTrue(first.currentNotes!!.notes.contains("灯笼图书馆"))
        assertNull(first.installation.previousVersion)
        assertEquals(InstallationChange.FIRST_OBSERVED, first.installation.change)
        assertTrue(first.unread)
        assertFalse(repository.markRead().unread)
        assertEquals(0, source.calls)
    }

    @Test fun `unpublished or dirty builds cannot present an old release as their current changes`() {
        val source = Source()
        val updates = updates(source)
        for (build in listOf(publishedBuild.copy(sourceCommit = "f".repeat(40)),
            publishedBuild.copy(sourceDirty = true), publishedBuild.copy(sourceCommit = null),
            publishedBuild.copy(versionName = "2.3.1-verify"))) {
            val view = AppReleaseHistoryRepository(context, updates, build).loadLocal()
            assertNull(view.currentNotes)
            assertTrue(view.publishedEntries.any { it.tag == "v2.3.1" })
        }
        assertEquals(0, source.calls)
    }

    @Test fun `repeated local reads and patch revisions preserve actual previous installed version`() {
        val source = Source()
        val updates = updates(source)
        AppReleaseHistoryRepository(context, updates, BuildReleaseIdentity("2.2.1", 24)).loadLocal()
        val repository = AppReleaseHistoryRepository(context, updates, publishedBuild)
        val upgraded = repository.loadLocal()
        assertEquals("2.2.1", upgraded.installation.previousVersion)
        assertEquals(InstallationChange.UPGRADE, upgraded.installation.change)
        assertEquals(upgraded.installation, repository.loadLocal().installation)
        repository.markRead(nativePatchRevision = 1)
        assertFalse(repository.loadLocal(1).unread)
        val nextPatch = repository.loadLocal(2)
        assertTrue(nextPatch.unread)
        assertEquals(upgraded.installation, nextPatch.installation)
        assertEquals(2, nextPatch.nativePatchRevision)
        assertEquals(0, source.calls)
    }

    @Test fun `mark read does not observe a new installation or advance previous version`() {
        val source = Source()
        val updates = updates(source)
        AppReleaseHistoryRepository(context, updates, BuildReleaseIdentity("2.2.1", 24)).loadLocal()
        val preferences = context.getSharedPreferences("release_history", Context.MODE_PRIVATE)
        val before = preferences.getString("installation", null)
        val repository = AppReleaseHistoryRepository(context, updates, publishedBuild)
        repository.markRead()
        assertEquals(before, preferences.getString("installation", null))
        assertEquals("2.2.1", repository.loadLocal().installation.previousVersion)
        assertEquals(0, source.calls)
    }

    @Test fun `explicit refresh saves all version pages independently from APK updates`() = runBlocking {
        val source = Source()
        fun release(tag: String) = ReleaseRecord(tag, tag, "notes for $tag", "2026-11-01")
        source.pages = mapOf(null to ReleasePage(source.id, listOf(release("v2.3.1"), release("v2.3.0")), "older"),
            "older" to ReleasePage(source.id, listOf(release("v2.2.1"), release("v2.2.0"))))
        val updates = updates(source)
        AppReleaseHistoryRepository(context, updates, BuildReleaseIdentity("2.1.0", 22)).loadLocal()
        val repository = AppReleaseHistoryRepository(context, updates, publishedBuild)
        repository.loadLocal()
        val fresh = repository.refresh()
        assertTrue(fresh.history.complete)
        assertEquals(listOf("v2.3.1", "v2.3.0", "v2.2.1", "v2.2.0"), fresh.upgrade.entries.map { it.tag })
        assertEquals(2, source.calls)
        val local = repository.loadLocal()
        assertTrue(local.history.complete)
        assertTrue(local.history.usesCachedEntries)
        assertEquals(2, source.calls)
    }

    @Test fun `same source selection generation invalidates old history result`() = runBlocking {
        val source = Source("static:https://mirror.example/reader/catalog.json")
        val updates = updates(source)
        updates.setCatalogUrl("https://mirror.example/reader/catalog.json")
        source.pages = mapOf(null to ReleasePage(source.id, emptyList()))
        source.onFetch = { updates.setCatalogUrl("https://mirror.example/reader/catalog.json") }
        val repository = AppReleaseHistoryRepository(context, updates, publishedBuild)
        repository.loadLocal()
        val result = repository.refresh()
        assertFalse(result.history.complete)
        assertTrue(HistoryIssue.SOURCE_CHANGED in result.history.issues)
        assertTrue(File(context.filesDir, "release-history").listFiles().orEmpty().isEmpty())
    }
}
