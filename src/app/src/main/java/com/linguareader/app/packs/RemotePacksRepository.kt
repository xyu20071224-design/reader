package com.linguareader.app.packs

import com.linguareader.shared.packs.RemotePack
import com.linguareader.shared.packs.RemotePackCatalog
import com.linguareader.shared.packs.RemotePackClient
import com.linguareader.shared.update.GitHubReleaseSource
import com.linguareader.shared.update.HttpReleaseTransport
import com.linguareader.shared.update.ReleaseAsset
import com.linguareader.shared.update.ReleaseHttpTransport
import com.linguareader.shared.update.ReleaseSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Resource discovery is independent from APK releases, update preferences and release history. */
internal class RemotePacksRepository(
    private val directory: File,
    private val server: Server,
    private val github: ReleaseSource = GitHubReleaseSource(),
    private val githubTransport: ReleaseHttpTransport = HttpReleaseTransport()
) {
    interface Server {
        suspend fun catalog(): List<RemotePack>
        suspend fun download(pack: RemotePack, directory: File, progress: (Long, Long) -> Unit): File
    }

    class CatalogServer(
        private val appVersion: Int,
        private val url: String = RemotePackCatalog.DEFAULT_URL,
        private val client: RemotePackClient = RemotePackClient()
    ) : Server {
        override suspend fun catalog(): List<RemotePack> = client.fetchCatalog(appVersion, url).getOrThrow()
        override suspend fun download(pack: RemotePack, directory: File, progress: (Long, Long) -> Unit): File =
            client.download(pack, directory, progress).getOrThrow()
    }

    private class Selection(
        val origin: RemotePackOrigin,
        val packs: List<RemotePack>,
        val releaseAssets: Map<RemotePack, ReleaseAsset> = emptyMap()
    )
    private class State(val generation: Long, val selection: Selection? = null)
    private val state = AtomicReference(State(0))

    /** An invalidated or failed discovery never authorizes an old row for download. */
    fun invalidate() { begin() }

    private fun begin(): State {
        while (true) {
            val previous = state.get()
            val next = State(previous.generation + 1)
            if (state.compareAndSet(previous, next)) return next
        }
    }

    private fun clear(generation: Long) {
        while (true) {
            val current = state.get()
            if (current.generation != generation) return
            if (state.compareAndSet(current, State(generation))) return
        }
    }

    suspend fun load(origin: RemotePackOrigin): Result<List<RemotePack>> {
        val request = begin()
        return try {
            val selected = if (origin == RemotePackOrigin.SERVER) {
                Selection(origin, server.catalog().map { it.copy(mirrors = it.mirrors.toList()) })
            } else {
                val release = github.latestRelease()
                val assets = if (release == null || release.draft || release.prerelease) emptyList() else
                    release.assets.filter { it.name.endsWith(".lrpack", ignoreCase = true) && (it.bytes ?: 0) > 0 }
                val pairs = assets.map { asset ->
                    require(asset.sourceId == github.id) { "Resource belongs to another source" }
                    val pack = RemotePack(asset.name, asset.canonicalUrl, requireNotNull(asset.bytes), asset.sha256)
                    RemotePackCatalog.validate(pack)
                    pack to asset
                }
                require(pairs.map { it.first.name }.distinct().size == pairs.size) { "Duplicate resource names" }
                Selection(origin, pairs.map { it.first }, pairs.toMap())
            }
            coroutineContext.ensureActive()
            check(state.compareAndSet(request, State(request.generation, selected))) {
                "Resource source changed; refresh the list"
            }
            Result.success(selected.packs.map { it.copy(mirrors = it.mirrors.toList()) })
        } catch (cancelled: CancellationException) {
            clear(request.generation)
            throw cancelled
        } catch (error: Exception) {
            clear(request.generation)
            Result.failure(error)
        }
    }

    suspend fun download(pack: RemotePack, onProgress: (Long, Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            var completed: File? = null
            var ownsTemporary = false
            try {
                val snapshot = state.get()
                val selected = requireNotNull(snapshot.selection) { "Refresh the resource list before downloading" }
                val owned = requireNotNull(selected.packs.firstOrNull { it == pack }) {
                    "Resource is not in the selected catalog"
                }
                check(state.get() === snapshot) { "Resource source changed; refresh the list" }
                val progress: (Long, Long) -> Unit = { done, total ->
                    if (state.get() !== snapshot) throw CancellationException("Resource source changed")
                    onProgress(done, total)
                }
                completed = if (selected.origin == RemotePackOrigin.SERVER) {
                    server.download(owned, File(directory, "server"), progress)
                } else {
                    ownsTemporary = true
                    githubTransport.download(requireNotNull(selected.releaseAssets[owned]), File(directory, "github"),
                        github.assetPolicy, RemotePackCatalog.MAX_PACK_BYTES, progress)
                }
                coroutineContext.ensureActive()
                check(state.get() === snapshot) { "Resource source changed; refresh the list" }
                Result.success(requireNotNull(completed))
            } catch (cancelled: CancellationException) {
                // Server files are content-addressed caches shared by later requests.
                if (ownsTemporary) completed?.delete()
                throw cancelled
            } catch (error: Exception) {
                if (ownsTemporary) completed?.delete()
                Result.failure(error)
            }
        }
}
