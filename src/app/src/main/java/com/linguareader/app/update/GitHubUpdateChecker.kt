package com.linguareader.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查网络层：GET GitHub `/releases/latest`。
 *
 * 与 TTS/AI 网络层同款实现（HttpURLConnection、Dispatchers.IO、Result 包裹），
 * 不引入任何新依赖。仅返回解析结果，是否算「有更新」由 [UpdatePolicy] 判断。
 */
class GitHubUpdateChecker(
    private val repo: String = "xyu20071224-design/reader"
) {

    suspend fun checkLatest(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "https://api.github.com/repos/$repo/releases/latest"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 要求带 UA，缺省 UA 会被 403。
                connection.setRequestProperty("User-Agent", "LinguaReader")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    error("更新检查失败（HTTP ${connection.responseCode}）")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                GitHubReleaseParser.parse(body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
