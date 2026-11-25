package com.linguareader.desktop

import com.linguareader.shared.packs.RemotePack
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.update.ReleaseAsset
import com.linguareader.shared.update.ReleaseUrls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPackControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Remote : DesktopPackRemoteAccess {
        var catalogCalls = 0
        var githubCalls = 0
        var downloadCalls = 0
        var catalogs: suspend (String) -> List<DesktopPackOffer> = { emptyList() }
        var github: suspend () -> List<DesktopPackOffer> = { emptyList() }
        var transfer: suspend (DesktopPackOffer, (Long, Long) -> Unit) -> File = { _, _ -> error("unexpected download") }
        override suspend fun serverCatalog(url: String): List<DesktopPackOffer> { catalogCalls++; return catalogs(url) }
        override suspend fun githubCatalog(): List<DesktopPackOffer> { githubCalls++; return github() }
        override suspend fun download(offer: DesktopPackOffer, directory: File, progress: (Long, Long) -> Unit): File {
            downloadCalls++; return transfer(offer, progress)
        }
    }

    private fun offer() = DesktopPackOffer("dictionary.lrpack", 100,
        server = RemotePack("dictionary.lrpack", "https://example.org/dictionary.lrpack", 100,
            "a".repeat(64), packId = "test-dictionary", version = "1.0.0", type = PackType.DICTIONARY))

    @Test fun constructingAndSelectingPageSourceNeverRequestsNetwork() = runTest {
        val home = temporary.newFolder(); val remote = Remote()
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.selectSource(DesktopPackSource.GITHUB)
        controller.selectSource(DesktopPackSource.SERVER)
        assertEquals(0, remote.catalogCalls + remote.githubCalls + remote.downloadCalls)
        assertFalse(controller.state.value.fetched)
        assertTrue(controller.state.value.registry.packs.isEmpty())
    }

    @Test fun explicitCatalogDownloadProgressAndInstallPublishActualRegistry() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        val file = DesktopPackFixtures.dictionary(home)
        remote.catalogs = { listOf(selected) }
        remote.transfer = { request, progress -> assertEquals(selected, request); progress(50, 100); file }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join()
        assertEquals(listOf(selected), controller.state.value.offers)
        controller.installOffer(selected)!!.join()
        assertEquals(1, remote.catalogCalls); assertEquals(1, remote.downloadCalls)
        assertEquals(50L, controller.state.value.downloaded)
        assertEquals("test-dictionary", controller.state.value.registry.activeDictionary)
        assertFalse(controller.state.value.busy)
    }

    @Test fun cancelTransferRetainsOfferForExplicitRetryAndNeverInstallsPartialFile() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        val started = CompletableDeferred<Unit>()
        val file = DesktopPackFixtures.dictionary(home)
        remote.catalogs = { listOf(selected) }
        remote.transfer = { _, progress ->
            progress(10, 100)
            if (remote.downloadCalls == 1) { started.complete(Unit); CompletableDeferred<Unit>().await() }
            file
        }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join()
        val canceled = controller.installOffer(selected)!!
        started.await(); controller.cancel(); canceled.join()
        assertTrue(canceled.isCancelled)
        assertTrue(controller.state.value.registry.packs.isEmpty())
        assertEquals(listOf(selected), controller.state.value.offers)
        controller.installOffer(selected)!!.join()
        assertEquals(2, remote.downloadCalls)
        assertEquals(1, controller.state.value.registry.packs.size)
    }

    @Test fun oldCatalogIgnoringCancellationCannotOverwriteNewUrlResult() = runTest {
        val home = temporary.newFolder(); val remote = Remote()
        val oldStarted = CompletableDeferred<Unit>(); val releaseOld = CompletableDeferred<Unit>()
        val selected = offer(); val newer = selected.copy(name = "new.lrpack", server = selected.server!!.copy(name = "new.lrpack"))
        remote.catalogs = { url ->
            if (url.contains("old.example")) {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
                listOf(selected)
            } else listOf(newer)
        }
        val prefs = DesktopAppContext(home).prefs("packs")
        val controller = DesktopPackController(DesktopPackRepository(home), this, prefs, remote)
        controller.editCatalogUrl("https://old.example/index.json")
        val old = controller.fetchCatalog()!!; oldStarted.await()
        controller.editCatalogUrl("https://new.example/index.json")
        assertTrue(controller.state.value.offers.isEmpty())
        val new = controller.fetchCatalog()!!
        releaseOld.complete(Unit); old.join(); new.join()
        assertEquals(listOf(newer), controller.state.value.offers)
        assertEquals("https://new.example/index.json", DesktopAppContext(home).prefs("packs").getString("server_catalog"))
    }

    @Test fun switchSourceRejectsPreviouslyDiscoveredServerAssetAndLateProgress() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        var lateProgress: ((Long, Long) -> Unit)? = null
        val started = CompletableDeferred<Unit>()
        remote.catalogs = { listOf(selected) }
        remote.transfer = { _, progress ->
            lateProgress = progress; started.complete(Unit); CompletableDeferred<Unit>().await(); error("unreachable")
        }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join()
        val old = controller.installOffer(selected)!!; started.await()
        controller.selectSource(DesktopPackSource.GITHUB); old.join()
        lateProgress!!(999, 1000)
        assertEquals(0L, controller.state.value.downloaded)
        assertNull(controller.installOffer(selected))
        assertEquals(1, remote.downloadCalls)
        assertTrue(controller.state.value.registry.packs.isEmpty())
    }

    @Test fun modifiedMetadataCannotReuseDiscoveredSelection() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        remote.catalogs = { listOf(selected) }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join()
        assertNull(controller.installOffer(selected.copy(bytes = 1)))
        assertNull(controller.installOffer(selected.copy(server = selected.server!!.copy(downloadUrl = "https://evil.example/file.lrpack"))))
        assertEquals(0, remote.downloadCalls)
    }

    @Test fun failedRefreshClearsDiscoveredAssetsInsteadOfUsingOldCatalog() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        remote.catalogs = { listOf(selected) }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join()
        remote.catalogs = { throw java.io.IOException("offline") }
        controller.fetchCatalog()!!.join()
        assertTrue(controller.state.value.offers.isEmpty())
        assertFalse(controller.state.value.fetched)
        assertNull(controller.installOffer(selected)); assertEquals(0, remote.downloadCalls)
        assertTrue(controller.state.value.status.contains("失效"))
    }

    @Test fun downloadedArchiveMustMatchServerPackIdentity() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val selected = offer()
        remote.catalogs = { listOf(selected) }
        remote.transfer = { _, _ -> DesktopPackFixtures.dictionary(home, id = "substitution") }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.fetchCatalog()!!.join(); controller.installOffer(selected)!!.join()
        assertTrue(controller.state.value.registry.packs.isEmpty())
        assertTrue(controller.state.value.status.contains("身份"))
    }

    @Test fun githubBackupDownloadsOnlyTheExactDiscoveredReleaseAsset() = runTest {
        val home = temporary.newFolder(); val remote = Remote()
        val asset = ReleaseAsset("github:${ReleaseUrls.REPOSITORY}", "dict.lrpack",
            "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v2/dict.lrpack", bytes = 100)
        val selected = DesktopPackOffer(asset.name, 100, github = asset)
        remote.github = { listOf(selected) }
        remote.transfer = { request, _ -> assertEquals(asset, request.github); DesktopPackFixtures.dictionary(home) }
        val controller = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        controller.selectSource(DesktopPackSource.GITHUB)
        controller.fetchCatalog()!!.join()
        assertNull(controller.installOffer(selected.copy(github = asset.copy(bytes = 101))))
        controller.installOffer(selected)!!.join()
        assertEquals(1, remote.githubCalls); assertEquals(0, remote.catalogCalls); assertEquals(1, remote.downloadCalls)
        assertEquals(1, controller.state.value.registry.packs.size)
    }

    @Test fun cancelDuringLocalCommitRollsBackAndNextImportPublishesLatestState() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val repository = DesktopPackRepository(home)
        val first = DesktopPackFixtures.dictionary(home, "first")
        val second = DesktopPackFixtures.dictionary(home, "second")
        val controller = DesktopPackController(repository, this, DesktopAppContext(home).prefs("packs"), remote)
        repository.beforePublication = { controller.cancel() }
        val canceled = controller.importLocal(first)!!; canceled.join()
        assertTrue(canceled.isCancelled)
        assertTrue(controller.state.value.registry.packs.isEmpty())
        repository.beforePublication = {}
        controller.importLocal(second)!!.join()
        assertEquals(listOf("second"), controller.state.value.registry.packs.map { it.packId })
        assertFalse(controller.state.value.busy)
        assertEquals(0, remote.catalogCalls + remote.downloadCalls)
    }

    @Test fun reopeningControllerListsInstalledPacksWithoutCatalogRequest() = runTest {
        val home = temporary.newFolder(); val remote = Remote(); val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home))
        val reopened = DesktopPackController(DesktopPackRepository(home), this, DesktopAppContext(home).prefs("packs"), remote)
        assertEquals(1, reopened.state.value.registry.packs.size)
        assertEquals("test-dictionary", reopened.state.value.registry.activeDictionary)
        assertEquals(0, remote.catalogCalls + remote.githubCalls + remote.downloadCalls)
    }
}
