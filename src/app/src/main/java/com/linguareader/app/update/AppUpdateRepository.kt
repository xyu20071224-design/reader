package com.linguareader.app.update

import android.content.Context
import com.linguareader.app.BuildConfig
import com.linguareader.shared.update.AppUpdateInfo
import com.linguareader.shared.update.GitHubReleaseParser
import com.linguareader.shared.update.GitHubUpdateChecker
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

/** 更新检查结论：无更新 / 有新版 / 失败。 */
sealed class UpdateCheckOutcome {
    data object UpToDate : UpdateCheckOutcome()
    data class Available(val info: AppUpdateInfo) : UpdateCheckOutcome()
    data class Failure(val message: String) : UpdateCheckOutcome()
}

/**
 * 自动更新的编排层：设置读写、检查、下载。
 *
 * 网络细节在 [GitHubUpdateChecker]，纯判断在 [UpdatePolicy]（有单测），
 * 本类只负责把三者串起来并管好本地 APK 文件。
 */
class AppUpdateRepository(
    context: Context,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {

    private val appContext = context.applicationContext
    private val checker = GitHubUpdateChecker()
    private val hotfix = NativeHotfixRuntime(appContext)
    fun nativeStatus() = hotfix.snapshot()
    fun rollbackNativePatch() = hotfix.rollback()
    suspend fun applyNativePatch(file: File, info: NativePatchInfo) = hotfix.apply(file, info)

    fun loadSettings(): AppUpdateSettings = AppUpdateSettings.load(appContext)

    fun setAutoCheck(enabled: Boolean) {
        AppUpdateSettings.save(appContext, AppUpdateSettings(autoCheckEnabled = enabled))
    }

    /** 检查远端最新 Release，与当前安装版本比较。 */
    suspend fun check(): UpdateCheckOutcome {
        return try {
            val body = checker.releaseBody().getOrThrow()
            val info = GitHubReleaseParser.parse(body)
            if (info != null && UpdatePolicy.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                UpdateCheckOutcome.Available(info)
            } else {
                val catalogUrl = NativePatchCatalog.catalogUrl(body)
                val patch = catalogUrl?.let {
                    val catalog = readCatalog(it)
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

    private suspend fun readCatalog(url: String): String = withContext(Dispatchers.IO) {
        require(NativePatchCatalog.trustedAsset(url))
        val connection = connectionFactory(url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "LinguaReader")
            connection.setRequestProperty("Cache-Control", "no-cache")
            check(connection.responseCode == 200 && connection.url.protocol == "https")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= NativePatchCatalog.MAX_CATALOG_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private suspend fun downloadNativePatch(info: NativePatchInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(NativePatchCatalog.trustedAsset(info.downloadUrl))
            require(info.assetName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.lrpatch")))
            require(info.bytes in 1..NativePatchCatalog.MAX_PATCH_BYTES)
            val dir = File(appContext.filesDir, "hotfix-downloads").apply { mkdirs() }
            // A process restart must not overwrite the file a background merge is still reading.
            val file = File.createTempFile("native-", ".lrpatch", dir)
            val connection = connectionFactory(info.downloadUrl)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "LinguaReader")
                check(connection.responseCode == 200 && connection.url.protocol == "https")
                var written = 0L
                connection.inputStream.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= info.bytes)
                        output.write(buffer, 0, count)
                        onProgress(written, info.bytes)
                    }
                } }
                check(written == info.bytes && com.linguareader.shared.importer.ImportSupport.sha256(file) == info.sha256)
                check(file.setReadOnly())
                file
            } catch (e: Throwable) { file.delete(); throw e }
            finally { connection.disconnect() }
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
    suspend fun fetchPackAssets(): Result<List<GitHubReleaseParser.PackAsset>> =
        checker.checkLatestPacks()

    /**
     * Q2-c03：把资源包资产下载到应用专属目录，随后交给
     * `PackRepository.install(Uri.fromFile(file))`。与 APK 下载同纪律：进度回调，
     * 失败/取消都删掉半截文件（半截包解压只会误导）。
     */
    suspend fun downloadPack(
        asset: GitHubReleaseParser.PackAsset,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val activeContext = coroutineContext
        runCatching {
            val dir = appContext.getExternalFilesDir("packs-download")
                ?: error("外部存储不可用")
            dir.mkdirs()
            val target = File(dir, asset.name)
            if (target.exists()) target.delete()

            val connection = URL(asset.downloadUrl).openConnection() as HttpURLConnection
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
    }
}
