package com.linguareader.shared.update

import org.json.JSONObject
import java.net.URI

/** Discovery metadata is untrusted; the signed Tinker package is checked again before applying. */
data class NativePatchInfo(
    val baseId: String,
    val baseVersionCode: Int,
    val revision: Int,
    val assetName: String,
    val downloadUrl: String,
    val sha256: String,
    val bytes: Long,
    val notes: String
)

object NativePatchCatalog {
    const val FILE_NAME = "native-patches.json"
    const val MAX_PATCH_BYTES = 64L * 1024 * 1024
    const val MAX_CATALOG_BYTES = 128 * 1024

    fun trustedAsset(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host == "github.com" && u.port == -1 &&
            u.userInfo == null && u.fragment == null && u.query == null &&
            u.path.startsWith("/xyu20071224-design/reader/releases/download/") &&
            !u.path.split('/').any { it == ".." || it == "." }
    }.getOrDefault(false)

    fun catalogUrl(releaseBody: String): String? {
        val assets = JSONObject(releaseBody).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == FILE_NAME) {
                val url = a.getString("browser_download_url")
                require(trustedAsset(url)) { "Invalid hotfix catalog source" }
                return url
            }
        }
        return null
    }

    fun select(body: String, releaseBody: String, baseId: String, baseCode: Int, highestRevision: Int): NativePatchInfo? {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES)
        val catalog = JSONObject(body)
        require(catalog.getInt("schemaVersion") == 1) { "Unsupported hotfix catalog" }
        val assets = JSONObject(releaseBody).getJSONArray("assets")
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
        val entries = catalog.getJSONArray("patches")
        require(entries.length() <= 100)
        return (0 until entries.length()).mapNotNull { index ->
            val p = entries.getJSONObject(index)
            if (p.getString("baseId") != baseId || p.getInt("baseVersionCode") != baseCode) return@mapNotNull null
            val revision = p.getInt("revision")
            require(revision > 0)
            if (revision <= highestRevision) return@mapNotNull null
            val name = p.getString("assetName")
            require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}\\.lrpatch")))
            val asset = requireNotNull(byName[name]) { "Hotfix asset missing" }
            val url = asset.getString("browser_download_url")
            require(trustedAsset(url))
            val sha = p.getString("sha256")
            require(sha.matches(Regex("[0-9a-f]{64}")))
            val bytes = p.getLong("bytes")
            require(bytes in 1..MAX_PATCH_BYTES && bytes == asset.getLong("size"))
            NativePatchInfo(baseId, baseCode, revision, name, url, sha, bytes, p.optString("notes"))
        }.maxByOrNull { it.revision }
    }
}
