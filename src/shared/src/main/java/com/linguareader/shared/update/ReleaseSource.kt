package com.linguareader.shared.update

import org.json.JSONArray
import org.json.JSONObject

/** Published identity stays canonical even when the selected source transports mirrored bytes. */
data class ReleaseAsset(
    val sourceId: String,
    val name: String,
    val canonicalUrl: String,
    val transportUrl: String = canonicalUrl,
    val bytes: Long? = null,
    val sha256: String? = null
) {
    init {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 })
        require(bytes == null || bytes >= 0)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
        require(transportUrl == canonicalUrl || (bytes != null && bytes > 0 && sha256 != null)) {
            "Mirrored assets require an exact size and SHA-256"
        }
    }
}

/** A release remains useful as history even when it has no installable APK. */
data class ReleaseRecord(
    val id: String,
    val tag: String,
    val notes: String,
    val publishedAt: String,
    val targetCommitish: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList()
) {
    val versionName: String get() = tag.removePrefix("v").removePrefix("V")

    /** Compatibility boundary: legacy update/patch parsers see only original GitHub identities. */
    fun canonicalBody(): String = JSONObject().apply {
        put("id", id)
        put("tag_name", tag)
        put("body", notes)
        put("published_at", publishedAt)
        put("target_commitish", targetCommitish)
        put("draft", draft)
        put("prerelease", prerelease)
        put("assets", JSONArray().apply {
            assets.forEach { asset -> put(JSONObject().apply {
                put("name", asset.name)
                put("browser_download_url", asset.canonicalUrl)
                asset.bytes?.let { put("size", it) }
                asset.sha256?.let { put("digest", "sha256:$it") }
            }) }
        })
    }.toString()

    fun asset(canonicalUrl: String): ReleaseAsset =
        assets.singleOrNull { it.canonicalUrl == canonicalUrl }
            ?: throw IllegalArgumentException("Asset is not part of the selected release")
}

data class ReleasePage(
    val sourceId: String,
    val entries: List<ReleaseRecord>,
    /** Opaque to consumers. Null means a complete final page; failures throw rather than fake EOF. */
    val nextCursor: String? = null
)

interface ReleaseSource {
    val id: String
    val assetPolicy: ReleaseUrlPolicy
    suspend fun fetchPage(cursor: String? = null): ReleasePage
    suspend fun latestRelease(): ReleaseRecord?
}
