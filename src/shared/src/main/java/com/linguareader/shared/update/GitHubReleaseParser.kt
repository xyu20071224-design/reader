package com.linguareader.shared.update

import org.json.JSONObject

/** 一次更新检查的结果：远端最新 Release 的关键信息。 */
data class AppUpdateInfo(
    /** Release tag，如 `v1.6.0`。 */
    val tag: String,
    /** 由 tag 去掉 v 前缀得到的版本名，与 BuildConfig.VERSION_NAME 同构。 */
    val versionName: String,
    /** Release 资产里的 APK 文件名。 */
    val apkName: String,
    /** APK 下载直链（github.com/.../releases/download/...）。 */
    val downloadUrl: String,
    /** Release 说明正文（markdown 纯文本展示）。 */
    val releaseNotes: String,
    /** ISO 8601 发布时间，仅展示用。 */
    val publishedAt: String
)

/**
 * 纯逻辑：把 `/releases/latest` 的 JSON 响应解析成 [AppUpdateInfo]。
 *
 * 没有可下载的 .apk 资产（比如只发了 tag 忘传包）返回 null，调用方当作「无更新」。
 */
object GitHubReleaseParser {

    /** Release 里的一个资源包资产（`.lrpack`）。 */
    data class PackAsset(
        val name: String,
        val downloadUrl: String,
        /** 资产字节数；GitHub 未给时为 0。 */
        val bytes: Long
    )

    /**
     * 纯逻辑：列出 Release JSON 里的全部 `.lrpack` 资产（Q2-c03：把 GitHub Releases
     * 当作资源包分发源）。
     *
     * 与 [parse] 一样只解析文本、**不联网**——离线优先的边界由调用方守住：不配置
     * 更新源就永远不会走到这里。没有 `.lrpack`（或字段不全）的资产被跳过。
     */
    fun parsePackAssets(body: String): List<PackAsset> = runCatching {
        val json = JSONObject(body)
        val assets = json.optJSONArray("assets") ?: return@runCatching emptyList()
        (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(PACK_EXTENSION, ignoreCase = true) }
            .mapNotNull { asset ->
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.isBlank() || url.isBlank()) {
                    null
                } else {
                    PackAsset(name = name, downloadUrl = url, bytes = asset.optLong("size", 0L))
                }
            }
    }.getOrDefault(emptyList())

    fun parse(body: String): AppUpdateInfo? = runCatching {
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        if (tag.isBlank()) return@runCatching null
        val assets = json.optJSONArray("assets") ?: return@runCatching null
        val asset = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return@runCatching null
        val downloadUrl = asset.optString("browser_download_url")
        if (downloadUrl.isBlank()) return@runCatching null
        AppUpdateInfo(
            tag = tag,
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            apkName = asset.optString("name"),
            downloadUrl = downloadUrl,
            releaseNotes = json.optString("body"),
            publishedAt = json.optString("published_at")
        )
    }.getOrNull()
}

/** 资源包资产扩展名（Q2-c03）。 */
private const val PACK_EXTENSION = ".lrpack"
