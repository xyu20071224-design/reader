package com.linguareader.app.update

import android.content.Context
import com.linguareader.app.BuildConfig
import com.linguareader.shared.update.AppUpdateInfo
import com.linguareader.shared.update.GitHubReleaseParser
import com.linguareader.shared.update.GitHubReleaseSource
import com.linguareader.shared.update.HttpReleaseTransport
import com.linguareader.shared.update.ReleaseHttpTransport
import com.linguareader.shared.update.ReleaseAsset
import com.linguareader.shared.update.ReleaseRecord
import com.linguareader.shared.update.ReleaseSource
import com.linguareader.shared.update.ReleaseUrls
import com.linguareader.shared.update.StaticReleaseSource
import com.linguareader.shared.update.UpdatePolicy
import com.linguareader.shared.update.NativePatchCatalog
import com.linguareader.shared.update.NativePatchInfo
import com.linguareader.app.hotfix.NativeHotfixRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** 更新检查结论：无更新 / 有新版 / 失败。 */
sealed class UpdateCheckOutcome {
    data object UpToDate : UpdateCheckOutcome()
    data class Available(val info: AppUpdateInfo) : UpdateCheckOutcome()
    data class Failure(val message: String) : UpdateCheckOutcome()
}

/**
 * 自动更新的编排层：设置读写、检查、下载。
 *
 * 网络细节在 [ReleaseSource]，纯判断在 [UpdatePolicy]（有单测），
 * 本类只负责把三者串起来并管好本地 APK 文件。
 */
class AppUpdateRepository(
    context: Context,
    private val connectionFactory: (String) -> HttpURLConnection,
    private val sourceFactory: (AppUpdateSettings, ReleaseHttpTransport) -> ReleaseSource
) {
    // Preserve AppUpdateRepository(context) { url -> connection } source compatibility.
    constructor(context: Context,
        connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
    ) : this(context, connectionFactory, { settings, transport ->
        settings.catalogUrl?.let { StaticReleaseSource(it, transport) } ?: GitHubReleaseSource(transport)
    })

    private val appContext = context.applicationContext
    private val transport = HttpReleaseTransport(connectionFactory)
    private data class Selection(val source: ReleaseSource, val release: ReleaseRecord, val epoch: Long)
    private val sourceEpoch = AtomicLong()
    @Volatile private var updateSelection: Selection? = null
    @Volatile private var packSelection: Selection? = null
    private val hotfix = NativeHotfixRuntime(appContext)
    fun nativeStatus() = hotfix.snapshot()
    fun rollbackNativePatch() = hotfix.rollback()
    suspend fun applyNativePatch(file: File, info: NativePatchInfo) = hotfix.apply(file, info)

    fun loadSettings(): AppUpdateSettings = AppUpdateSettings.load(appContext)

    /** Read-only source factory shared with release history; constructing a source never fetches. */
    fun currentSource(): ReleaseSource = sourceFactory(loadSettings(), transport)

    /** Changes even when the user switches away and back to the same catalog URL. */
    fun sourceGeneration(): Long = sourceEpoch.get()

    fun setAutoCheck(enabled: Boolean) {
        AppUpdateSettings.save(appContext, loadSettings().copy(autoCheckEnabled = enabled))
    }

    /** Caller cancels active download/UI state before switching. Installed resources are untouched. */
    fun setCatalogUrl(value: String?) {
        val catalog = value?.trim()?.takeIf { it.isNotEmpty() }?.let { URI(it).toASCIIString() }
        catalog?.let { ReleaseUrls.mirror(it) }
        sourceEpoch.incrementAndGet()
        AppUpdateSettings.save(appContext, loadSettings().copy(catalogUrl = catalog))
        updateSelection = null
        packSelection = null
    }

    /** 检查远端最新 Release，与当前安装版本比较。 */
    suspend fun check(): UpdateCheckOutcome {
        return try {
            val epoch = sourceEpoch.get()
            val source = currentSource()
            val release = source.latestRelease()
            requireCurrent(source, epoch)
            if (release == null) { updateSelection = null; return UpdateCheckOutcome.UpToDate }
            val selection = Selection(source, release, epoch)
            requireCurrent(selection)
            updateSelection = selection
            val body = release.canonicalBody()
            val info = GitHubReleaseParser.parse(body)
            if (info != null && UpdatePolicy.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                UpdateCheckOutcome.Available(info)
            } else {
                val catalogUrl = NativePatchCatalog.catalogUrl(body)
                val patch = catalogUrl?.let {
                    val catalog = readCatalog(it, selection)
                    NativePatchCatalog.select(catalog, body, BuildConfig.HOTFIX_BASE_ID,
                        BuildConfig.VERSION_CODE, hotfix.snapshot().highestRevision)
                }
                if (patch == null) UpdateCheckOutcome.UpToDate else {
                    val release = org.json.JSONObject(body)
                    UpdateCheckOutcome.Available(AppUpdateInfo(
                        tag = release.getString("tag_name"), versionName = BuildConfig.VERSION_NAME,
                        apkName = patch.assetName, downloadUrl = patch.downloadUrl,
                        releaseNotes = patch.notes, publishedAt = release.optString("published_at"), nativePatch = patch))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { UpdateCheckOutcome.Failure(e.message ?: "更新检查失败") }
    }

    private fun requireCurrent(source: ReleaseSource, epoch: Long) {
        require(sourceEpoch.get() == epoch && currentSource().id == source.id) {
            "Release source changed; refresh before downloading"
        }
    }

    private fun requireCurrent(selection: Selection) = requireCurrent(selection.source, selection.epoch)

    private suspend fun downloadDiscovered(selection: Selection, url: String, directory: File,
        limit: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        requireCurrent(selection)
        val asset = selection.release.asset(url)
        require(asset.sourceId == selection.source.id) { "Asset belongs to another release source" }
        val file = transport.download(asset, directory, selection.source.assetPolicy, limit, onProgress)
        try { requireCurrent(selection); return file }
        catch (t: Throwable) { file.delete(); throw t }
    }

    private suspend fun readCatalog(url: String, selection: Selection): String = withContext(Dispatchers.IO) {
        require(NativePatchCatalog.trustedAsset(url))
        val file = downloadDiscovered(selection, url, File(appContext.filesDir, "hotfix-downloads"),
            NativePatchCatalog.MAX_CATALOG_BYTES.toLong())
        try { file.readText(Charsets.UTF_8) } finally { file.delete() }
    }

    private suspend fun downloadNativePatch(info: NativePatchInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(NativePatchCatalog.trustedAsset(info.downloadUrl))
            require(info.assetName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.lrpatch")))
            require(info.bytes in 1..NativePatchCatalog.MAX_PATCH_BYTES)
            val selection = updateSelection
            val epoch = sourceEpoch.get()
            val source = currentSource()
            val asset = if (selection != null) {
                requireCurrent(selection)
                selection.release.asset(info.downloadUrl).also {
                    require(it.sourceId == source.id)
                    require(it.bytes == null || it.bytes == info.bytes)
                    require(it.sha256 == null || it.sha256 == info.sha256)
                }.copy(bytes = info.bytes, sha256 = info.sha256)
            } else {
                require(loadSettings().catalogUrl == null) { "Refresh the mirror before downloading" }
                ReleaseAsset(source.id, info.assetName, info.downloadUrl, bytes = info.bytes, sha256 = info.sha256)
            }
            val file = transport.download(asset, File(appContext.filesDir, "hotfix-downloads"),
                source.assetPolicy, NativePatchCatalog.MAX_PATCH_BYTES, onProgress)
            try {
                if (selection != null) requireCurrent(selection)
                else require(sourceEpoch.get() == epoch) { "Release source changed during download" }
                check(file.setReadOnly())
                file
            } catch (e: Throwable) { file.delete(); throw e }
        }

    /**
     * 下载 [info] 的 APK 到应用专属外部目录（免存储权限）。已存在的旧文件先删，
     * 避免半截文件被误安装。进度按百分比回调；协程取消时立刻停止并删掉半截文件。
     */
    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        info.nativePatch?.let { patch ->
            return@withContext try { Result.success(downloadNativePatch(patch, onProgress)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        updateSelection?.let { selection ->
            return@withContext try {
                Result.success(downloadDiscovered(selection, info.downloadUrl,
                    appContext.getExternalFilesDir("updates") ?: error("外部存储不可用"), MAX_APK_BYTES, onProgress))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        if (loadSettings().catalogUrl != null) return@withContext Result.failure(
            IllegalStateException("Refresh the mirror before downloading"))
        // runCatching/use 的 lambda 不是 suspend，取不到 coroutineContext，
        // 在 withContext 的 suspend 作用域先捕获再往里传。
        val activeContext = coroutineContext
        runCatching {
            val dir = appContext.getExternalFilesDir("updates")
                ?: error("外部存储不可用")
            val target = File(dir, info.apkName)
            if (target.exists()) target.delete()

            val connection = connectionFactory(info.downloadUrl)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                if (connection.responseCode !in 200..299) {
                    error("下载失败（HTTP ${connection.responseCode}）")
                }
                val total = connection.contentLengthLong
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastReported = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            activeContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(written, total)
                                }
                            }
                        }
                    }
                }
                activeContext.ensureActive()
                check(written > 0) { "下载内容为空" }
                target
            } catch (t: Throwable) {
                // 半截 APK 留着只会误导下一次安装，无论失败还是取消都清掉。
                if (target.exists()) target.delete()
                throw t
            } finally {
                connection.disconnect()
            }
        }.also { result ->
            // runCatching 会把取消吞成 failure；取消不是失败，原样抛回结构化并发。
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    /** Q2-c03：列出远端 Release 里的资源包资产。只在用户显式点击「从 GitHub 获取」时调用。 */
    suspend fun fetchPackAssets(): Result<List<GitHubReleaseParser.PackAsset>> = try {
        val epoch = sourceEpoch.get()
        val source = currentSource()
        val release = source.latestRelease()
        requireCurrent(source, epoch)
        if (release == null) { packSelection = null; Result.success(emptyList()) }
        else {
            val selection = Selection(source, release, epoch)
            requireCurrent(selection)
            packSelection = selection
            Result.success(GitHubReleaseParser.parsePackAssets(release.canonicalBody()))
        }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    /**
     * Q2-c03：把资源包资产下载到应用专属目录，随后交给
     * `PackRepository.install(Uri.fromFile(file))`。与 APK 下载同纪律：进度回调，
     * 失败/取消都删掉半截文件（半截包解压只会误导）。
     */
    suspend fun downloadPack(
        asset: GitHubReleaseParser.PackAsset,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        packSelection?.let { selection ->
            return@withContext try {
                Result.success(downloadDiscovered(selection, asset.downloadUrl,
                    appContext.getExternalFilesDir("packs-download") ?: error("外部存储不可用"), MAX_PACK_BYTES, onProgress))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        if (loadSettings().catalogUrl != null) return@withContext Result.failure(
            IllegalStateException("Refresh the mirror before downloading"))
        val activeContext = coroutineContext
        runCatching {
            val dir = appContext.getExternalFilesDir("packs-download")
                ?: error("外部存储不可用")
            dir.mkdirs()
            val target = File(dir, asset.name)
            if (target.exists()) target.delete()

            val connection = connectionFactory(asset.downloadUrl)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "LinguaReader")
                if (connection.responseCode !in 200..299) {
                    error("下载失败（HTTP ${connection.responseCode}）")
                }
                val total = connection.contentLengthLong
                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastReported = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            activeContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(written, total)
                                }
                            }
                        }
                    }
                }
                activeContext.ensureActive()
                check(written > 0) { "下载内容为空" }
                target
            } catch (t: Throwable) {
                if (target.exists()) target.delete()
                throw t
            } finally {
                connection.disconnect()
            }
        }.also { result ->
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_PACK_BYTES = 2L * 1024 * 1024 * 1024
    }
}
