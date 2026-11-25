package com.linguareader.desktop

import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.packs.PackLimits
import com.linguareader.shared.packs.PackRegistry
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.packs.RemotePack
import com.linguareader.shared.packs.RemotePackCatalog
import com.linguareader.shared.packs.RemotePackClient
import com.linguareader.shared.update.GitHubReleaseSource
import com.linguareader.shared.update.HttpReleaseTransport
import com.linguareader.shared.update.ReleaseAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class DesktopPackSource { SERVER, GITHUB }

/** An offer keeps the exact metadata returned by the selected discovery operation. */
data class DesktopPackOffer(
    val name: String,
    val bytes: Long,
    val server: RemotePack? = null,
    val github: ReleaseAsset? = null
) {
    init { require((server == null) != (github == null)) }
}

interface DesktopPackRemoteAccess {
    suspend fun serverCatalog(url: String): List<DesktopPackOffer>
    suspend fun githubCatalog(): List<DesktopPackOffer>
    suspend fun download(offer: DesktopPackOffer, directory: File, progress: (Long, Long) -> Unit): File
}

class DefaultDesktopPackRemoteAccess(
    private val client: RemotePackClient = RemotePackClient(),
    private val transport: HttpReleaseTransport = HttpReleaseTransport()
) : DesktopPackRemoteAccess {
    private val github = GitHubReleaseSource(transport)

    override suspend fun serverCatalog(url: String) = client.fetchCatalog(DesktopPackRepository.APP_VERSION, url)
        .getOrThrow().map { DesktopPackOffer(it.name, it.bytes, server = it) }

    override suspend fun githubCatalog(): List<DesktopPackOffer> = github.latestRelease().let { release ->
        require(!release.draft && !release.prerelease) { "GitHub 备用必须是已发布的稳定版本" }
        release.assets.filter { it.name.endsWith(".lrpack", true) && it.bytes != null &&
            it.bytes!! in 1..PackLimits.of(PackType.AUDIO).maxSourceBytes && it.sourceId == github.id }
            .map { DesktopPackOffer(it.name, it.bytes!!, github = it) }
    }

    override suspend fun download(offer: DesktopPackOffer, directory: File, progress: (Long, Long) -> Unit): File =
        if (offer.server != null) client.download(offer.server, directory, progress).getOrThrow()
        else transport.download(requireNotNull(offer.github), directory, github.assetPolicy,
            PackLimits.of(PackType.AUDIO).maxSourceBytes, progress)
}

data class DesktopPackState(
    val registry: PackRegistry = PackRegistry.EMPTY,
    val registryError: String? = null,
    val source: DesktopPackSource = DesktopPackSource.SERVER,
    val catalogUrl: String = RemotePackCatalog.DEFAULT_URL,
    val offers: List<DesktopPackOffer> = emptyList(),
    val fetched: Boolean = false,
    val busy: Boolean = false,
    val operation: String = "",
    val downloaded: Long = 0,
    val total: Long = 0,
    val status: String = ""
)

/** Constructors and page navigation never start requests. Every transfer has one explicit owner. */
class DesktopPackController(
    private val repository: DesktopPackRepository,
    private val scope: CoroutineScope,
    private val prefs: PreferencesStore,
    private val remote: DesktopPackRemoteAccess = DefaultDesktopPackRemoteAccess()
) {
    private val epoch = AtomicLong()
    private val transactions = Mutex()
    private var job: Job? = null
    private val snapshot = repository.snapshot()
    private val mutableState = MutableStateFlow(DesktopPackState(
        registry = snapshot.registry, registryError = snapshot.error,
        catalogUrl = prefs.getString("server_catalog") ?: RemotePackCatalog.DEFAULT_URL
    ))
    val state = mutableState.asStateFlow()

    fun selectSource(source: DesktopPackSource) {
        if (state.value.source == source) return
        invalidateSelection()
        mutableState.update { it.copy(source = source, status = "已切换来源，请手动获取目录") }
    }

    fun editCatalogUrl(url: String) {
        if (url == state.value.catalogUrl) return
        invalidateSelection()
        prefs.putString("server_catalog", url)
        mutableState.update { it.copy(catalogUrl = url, status = "地址已保存，请手动获取目录") }
    }

    private fun invalidateSelection() {
        epoch.incrementAndGet()
        job?.cancel()
        mutableState.update { it.copy(offers = emptyList(), fetched = false, busy = false,
            operation = "", downloaded = 0, total = 0) }
    }

    fun cancel() {
        epoch.incrementAndGet()
        job?.cancel()
        mutableState.update { it.copy(busy = false, operation = "", downloaded = 0, total = 0,
            status = "已取消。服务器包的有效断点会保留，点击下载可重试。") }
    }

    fun fetchCatalog(): Job? {
        val selected = state.value
        if (selected.busy) return null
        mutableState.update { it.copy(offers = emptyList(), fetched = false) }
        return perform("获取目录") { token ->
            val offers = if (selected.source == DesktopPackSource.SERVER) remote.serverCatalog(selected.catalogUrl)
                else remote.githubCatalog()
            publish(token) { it.copy(offers = offers, fetched = true, status = "已获取 ${offers.size} 个可下载资源包") }
        }
    }

    fun installOffer(offer: DesktopPackOffer): Job? {
        val selected = state.value
        if (selected.busy) return null
        if (!selected.fetched || offer !in selected.offers ||
            (selected.source == DesktopPackSource.SERVER) != (offer.server != null)) {
            mutableState.update { it.copy(status = "资源条目已失效，请重新获取当前来源的目录") }
            return null
        }
        return perform("下载并安装 ${offer.name}") { token ->
            val archive = remote.download(offer, File(repository.home, "pack-downloads")) { done, total ->
                publish(token) { it.copy(downloaded = done, total = total) }
            }
            coroutineContext.ensureActive()
            check(epoch.get() == token) { "资源来源已改变" }
            val expected = offer.server?.let { DesktopPackRepository.ExpectedIdentity(it.packId, it.version, it.type) }
            repository.install(archive, expected)
            publish(token) { it.copy(status = "资源包已安装。词典包可在已安装列表中切换。") }
        }
    }

    fun importLocal(file: File): Job? = perform("导入 ${file.name}") { token ->
        repository.install(file)
        publish(token) { it.copy(status = "资源包已导入") }
    }

    fun setActiveDictionary(id: String?): Job? = perform("切换词典") { token ->
        repository.setActiveDictionary(id)
        publish(token) { it.copy(status = if (id == null) "已恢复默认词典" else "词典已切换，下次查词立即生效") }
    }

    fun uninstall(id: String): Job? = perform("卸载资源包") { token ->
        repository.uninstall(id)
        publish(token) { it.copy(status = "资源包已卸载。活动词典卸载后使用默认词典。") }
    }

    fun verify(id: String): Job? = perform("验证完整性") { token ->
        repository.verify(id)
        publish(token) { it.copy(status = "资源包完整性验证通过") }
    }

    private fun publish(token: Long, transform: (DesktopPackState) -> DesktopPackState) {
        if (epoch.get() == token) mutableState.update { current ->
            if (epoch.get() == token) transform(current) else current
        }
    }

    private fun perform(label: String, block: suspend (Long) -> Unit): Job? {
        if (state.value.busy) return null
        val token = epoch.incrementAndGet()
        mutableState.update { it.copy(busy = true, operation = label, status = "$label…", downloaded = 0, total = 0) }
        return scope.launch { transactions.withLock {
            try { block(token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(token) { it.copy(status = "失败：${error.message ?: error.javaClass.simpleName}") } }
            finally {
                // Cancellation can occur just after an atomic install commit. Always show what is actually installed.
                val actual = withContext(NonCancellable + Dispatchers.IO) { repository.snapshot() }
                mutableState.update { it.copy(registry = actual.registry, registryError = actual.error) }
                publish(token) { it.copy(busy = false, operation = "") }
            }
        } }.also { job = it }
    }
}
