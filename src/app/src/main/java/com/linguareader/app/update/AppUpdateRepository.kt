package com.linguareader.app.update

import android.content.Context
import com.linguareader.app.BuildConfig
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
class AppUpdateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val checker = GitHubUpdateChecker()

    fun loadSettings(): AppUpdateSettings = AppUpdateSettings.load(appContext)

    fun setAutoCheck(enabled: Boolean) {
        AppUpdateSettings.save(appContext, AppUpdateSettings(autoCheckEnabled = enabled))
    }

    /** 检查远端最新 Release，与当前安装版本比较。 */
    suspend fun check(): UpdateCheckOutcome {
        val result = checker.checkLatest()
        val info = result.getOrElse {
            return UpdateCheckOutcome.Failure(it.message ?: "更新检查失败")
        }
            ?: return UpdateCheckOutcome.Failure("远端 Release 未附带 APK")
        return if (UpdatePolicy.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
            UpdateCheckOutcome.Available(info)
        } else {
            UpdateCheckOutcome.UpToDate
        }
    }

    /**
     * 下载 [info] 的 APK 到应用专属外部目录（免存储权限）。已存在的旧文件先删，
     * 避免半截文件被误安装。进度按百分比回调；协程取消时立刻停止并删掉半截文件。
     */
    suspend fun download(
        info: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        // runCatching/use 的 lambda 不是 suspend，取不到 coroutineContext，
        // 在 withContext 的 suspend 作用域先捕获再往里传。
        val activeContext = coroutineContext
        runCatching {
            val dir = appContext.getExternalFilesDir("updates")
                ?: error("外部存储不可用")
            val target = File(dir, info.apkName)
            if (target.exists()) target.delete()

            val connection = URL(info.downloadUrl).openConnection() as HttpURLConnection
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

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
