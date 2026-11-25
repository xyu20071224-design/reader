package com.linguareader.app.packs

import com.linguareader.shared.packs.RemotePack
import com.linguareader.shared.update.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemotePacksRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val serverPack = RemotePack("server.lrpack", "https://packs.example/server.lrpack", 3,
        "0".repeat(64), packId = "dictionary", version = "1")
    private val githubAsset = ReleaseAsset("github:${ReleaseUrls.REPOSITORY}", "github.lrpack",
        "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v99.0.0/github.lrpack",
        bytes = 3, sha256 = "1".repeat(64))

    private class Server(var rows: List<RemotePack>) : RemotePacksRepository.Server {
        var catalogs = 0
        var downloads = 0
        var list: suspend () -> List<RemotePack> = { rows }
        var transfer: suspend (RemotePack, File, (Long, Long) -> Unit) -> File = { pack, directory, progress ->
            directory.mkdirs(); progress(3, 3); File(directory, pack.name).apply { writeText("abc") }
        }
        override suspend fun catalog(): List<RemotePack> { catalogs++; return list() }
        override suspend fun download(pack: RemotePack, directory: File, progress: (Long, Long) -> Unit): File {
            downloads++; return transfer(pack, directory, progress)
        }
    }
    private class Source(var assets: List<ReleaseAsset>) : ReleaseSource {
        var calls = 0
        override val id = "github:${ReleaseUrls.REPOSITORY}"
        override val assetPolicy = ReleaseUrls.gitHubAssets
        override suspend fun latestRelease(): ReleaseRecord {
            calls++; return ReleaseRecord("fixture", "v99.0.0", "", "", assets = assets)
        }
        override suspend fun fetchPage(cursor: String?) = error("No history pagination for resource discovery")
    }
    private class Transport : ReleaseHttpTransport {
        var asset: ReleaseAsset? = null
        var calls = 0
        override suspend fun text(url: String, policy: ReleaseUrlPolicy, maxBytes: Long) = error("Unexpected metadata request")
        override suspend fun download(asset: ReleaseAsset, directory: File, policy: ReleaseUrlPolicy,
            maxBytes: Long, onProgress: (Long, Long) -> Unit): File {
            calls++; this.asset = asset; assertTrue(policy.allows(asset.transportUrl)); directory.mkdirs()
            onProgress(3, 3); return File(directory, "selected.lrpack").apply { writeText("abc") }
        }
    }
    private fun repository(server: Server, source: Source = Source(listOf(githubAsset)), transport: Transport = Transport()) =
        RemotePacksRepository(temporary.newFolder(), server, source, transport)

    @Test fun constructionIsOfflineAndServerDiscoveryDoesNotReadReleaseMetadata() = runBlocking {
        val server = Server(listOf(serverPack)); val source = Source(listOf(githubAsset))
        val repo = repository(server, source)
        assertEquals(0, server.catalogs); assertEquals(0, source.calls)
        assertEquals(listOf(serverPack), repo.load(RemotePackOrigin.SERVER).getOrThrow())
        assertEquals(1, server.catalogs); assertEquals(0, source.calls)
    }
    @Test fun aServerRowMustBeDiscoveredBeforeItCanDownload() = runBlocking {
        val server = Server(listOf(serverPack)); val repo = repository(server)
        assertTrue(repo.download(serverPack) { _, _ -> }.isFailure)
        assertEquals(0, server.downloads)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        assertEquals("abc", repo.download(serverPack) { _, _ -> }.getOrThrow().readText())
        assertEquals(1, server.downloads)
    }
    @Test fun changingAnyDiscoveredIdentityFieldRejectsTheRowWithoutOpeningTheNetwork() = runBlocking {
        val server = Server(listOf(serverPack)); val repo = repository(server)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        for (changed in listOf(serverPack.copy(downloadUrl = "https://other.example/server.lrpack"),
            serverPack.copy(sha256 = "f".repeat(64)), serverPack.copy(bytes = 4), serverPack.copy(version = "2"))) {
            assertTrue(repo.download(changed) { _, _ -> }.isFailure)
        }
        assertEquals(0, server.downloads)
    }
    @Test fun aFailedRefreshClearsThePreviousDownloadAuthorization() = runBlocking {
        val server = Server(listOf(serverPack)); val repo = repository(server)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        server.list = { error("offline") }
        assertTrue(repo.load(RemotePackOrigin.SERVER).isFailure)
        assertTrue(repo.download(serverPack) { _, _ -> }.isFailure)
        assertEquals(0, server.downloads)
    }
    @Test fun changingThePublicMirrorListCannotMutateThePrivateDiscoverySnapshot() = runBlocking {
        val pack = serverPack.copy(mirrors = mutableListOf("https://one.example/pack.lrpack", "https://two.example/pack.lrpack"))
        val server = Server(listOf(pack)); val repo = repository(server)
        val publicRow = repo.load(RemotePackOrigin.SERVER).getOrThrow().single()
        (publicRow.mirrors as MutableList<String>)[0] = "https://other.example/pack.lrpack"
        assertTrue(repo.download(publicRow) { _, _ -> }.isFailure)
        assertEquals(0, server.downloads)
    }
    @Test fun switchingToGitHubCannotReuseAServerRowAndPassesTheOriginalReleaseAsset() = runBlocking {
        val server = Server(listOf(serverPack)); val source = Source(listOf(githubAsset)); val transport = Transport()
        val repo = repository(server, source, transport)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        val selected = repo.load(RemotePackOrigin.GITHUB).getOrThrow().single()
        assertTrue(repo.download(serverPack) { _, _ -> }.isFailure)
        assertEquals("abc", repo.download(selected) { _, _ -> }.getOrThrow().readText())
        assertSame(githubAsset, transport.asset)
        assertEquals(0, server.downloads); assertEquals(1, transport.calls)
    }
    @Test fun aLateCatalogCannotOverwriteTheNewSourceSelection() = runBlocking {
        val server = Server(listOf(serverPack)); val source = Source(listOf(githubAsset)); val transport = Transport()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.list = { entered.complete(Unit); release.await(); listOf(serverPack) }
        val repo = repository(server, source, transport)
        val old = async { repo.load(RemotePackOrigin.SERVER) }; entered.await()
        val current = repo.load(RemotePackOrigin.GITHUB).getOrThrow().single()
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertTrue(repo.download(current) { _, _ -> }.isSuccess)
        assertSame(githubAsset, transport.asset)
    }
    @Test fun invalidatingDuringTransferRejectsTheResultAndRetainsTheVerifiedContentCache() = runBlocking {
        val server = Server(listOf(serverPack)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var file: File? = null
        server.transfer = { pack, dir, _ ->
            dir.mkdirs(); file = File(dir, pack.name).apply { writeText("abc") }
            entered.complete(Unit); release.await(); requireNotNull(file)
        }
        val repo = repository(server)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        val old = async { repo.download(serverPack) { _, _ -> } }; entered.await()
        repo.invalidate(); release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertTrue("Content-addressed server cache is not an exclusive request temporary", requireNotNull(file).exists())
    }
    @Test fun cancellingADownloadPropagatesAndAllowsAnExplicitRetryOfTheSameSelection() = runBlocking {
        val server = Server(listOf(serverPack)); val repo = repository(server)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        val normal = server.transfer
        server.transfer = { _, _, _ -> throw CancellationException("user cancelled") }
        try { repo.download(serverPack) { _, _ -> }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        server.transfer = normal
        assertTrue(repo.download(serverPack) { _, _ -> }.isSuccess)
    }
    @Test fun anOldCompletionMustNotDeleteACacheFileAlreadyReusedByTheNewSelection() = runBlocking {
        val server = Server(listOf(serverPack)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        server.transfer = { pack, dir, _ ->
            dir.mkdirs()
            val file = File(dir, pack.name).apply { if (!exists()) writeText("abc") }
            if (++calls == 1) { entered.complete(Unit); release.await() }
            file
        }
        val repo = repository(server)
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        val old = async { repo.download(serverPack) { _, _ -> } }; entered.await()
        repo.load(RemotePackOrigin.SERVER).getOrThrow()
        val current = repo.download(serverPack) { _, _ -> }.getOrThrow()
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertTrue("A stale request deleted the current download's verified content cache", current.isFile)
        assertEquals("abc", current.readText())
    }
    @Test fun changingAPublicRowAfterMembershipValidationCannotChangeTheTransferMirrors() = runBlocking {
        val original = listOf("https://one.example/pack.lrpack", "https://two.example/pack.lrpack")
        val server = Server(listOf(serverPack.copy(mirrors = original)))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var observed = emptyList<String>()
        server.transfer = { pack, dir, _ ->
            entered.complete(Unit); release.await(); observed = pack.mirrors.toList()
            dir.mkdirs(); File(dir, pack.name).apply { writeText("abc") }
        }
        val repo = repository(server)
        val public = repo.load(RemotePackOrigin.SERVER).getOrThrow().single()
        val download = async { repo.download(public) { _, _ -> } }; entered.await()
        (public.mirrors as MutableList<String>)[0] = "https://other.example/pack.lrpack"
        release.complete(Unit)
        assertTrue(download.await().isSuccess)
        assertEquals(original, observed)
    }
    @Test fun cancelledOldDiscoveryCannotClearANewerGitHubSelection() = runBlocking {
        val server = Server(listOf(serverPack)); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        server.list = { withContext(NonCancellable) { entered.complete(Unit); release.await(); listOf(serverPack) } }
        val transport = Transport(); val repo = repository(server, transport = transport)
        val old = launch { repo.load(RemotePackOrigin.SERVER) }; entered.await()
        val current = repo.load(RemotePackOrigin.GITHUB).getOrThrow().single()
        old.cancel(); release.complete(Unit); old.join()
        assertTrue(repo.download(current) { _, _ -> }.isSuccess)
        assertEquals(1, transport.calls)
    }
    @Test fun anAssetFromAnotherReleaseSourceCannotBeLabelledAsGitHub() = runBlocking {
        val server = Server(listOf(serverPack)); val source = Source(listOf(githubAsset.copy(sourceId = "other")))
        val repo = repository(server, source)
        assertTrue(repo.load(RemotePackOrigin.GITHUB).isFailure)
        assertTrue(repo.download(serverPack) { _, _ -> }.isFailure)
    }
    @Test fun unknownGitHubSizesAreNotEnabledAsVerifiableDownloads() = runBlocking {
        val repo = repository(Server(emptyList()), Source(listOf(githubAsset.copy(bytes = null), githubAsset.copy(bytes = 0))))
        assertEquals(emptyList<RemotePack>(), repo.load(RemotePackOrigin.GITHUB).getOrThrow())
    }
}
