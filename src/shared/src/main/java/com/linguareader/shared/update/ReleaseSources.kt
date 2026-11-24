package com.linguareader.shared.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class GitHubReleaseSource(
    private val transport: ReleaseHttpTransport = HttpReleaseTransport()
) : ReleaseSource {
    override val id = "github:${ReleaseUrls.REPOSITORY}"
    override val assetPolicy = ReleaseUrls.gitHubAssets

    override suspend fun fetchPage(cursor: String?): ReleasePage {
        val page = cursor?.toIntOrNull() ?: if (cursor == null) 1 else error("Invalid release cursor")
        require(page in 1..100_000)
        val response = transport.text("${ReleaseUrls.GITHUB_API}?per_page=50&page=$page",
            ReleaseUrls.gitHubApi, HttpReleaseTransport.MAX_METADATA_BYTES)
        val array = JSONArray(response.body)
        require(array.length() <= 100) { "Too many releases in a page" }
        val records = (0 until array.length()).map { parse(array.getJSONObject(it)) }
        val link = response.headers.entries.firstOrNull { it.key.equals("Link", ignoreCase = true) }
            ?.value?.joinToString(",").orEmpty()
        val nextUrl = Regex("<([^>]+)>\\s*;\\s*rel=\"?next\"?").find(link)?.groupValues?.get(1)
        val next = nextUrl?.let {
            require(ReleaseUrls.gitHubApi.allows(it)) { "Unexpected release pagination source" }
            val nextPage = URI(it).query.orEmpty().split('&').singleOrNull { value -> value.startsWith("page=") }
                ?.substringAfter('=')?.toIntOrNull()
            require(nextPage != null && nextPage > page && nextPage <= 100_000) { "Invalid next release page" }
            nextPage.toString()
        }
        return ReleasePage(id, records, next)
    }

    override suspend fun latestRelease(): ReleaseRecord = parse(JSONObject(transport.text(
        "${ReleaseUrls.GITHUB_API}/latest", ReleaseUrls.gitHubApi,
        HttpReleaseTransport.MAX_METADATA_BYTES).body))

    private fun parse(json: JSONObject): ReleaseRecord {
        val tag = json.getString("tag_name").also { require(it.isNotBlank()) }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        require(assets.length() <= 500)
        val parsed = (0 until assets.length()).mapNotNull { index ->
            val a = assets.getJSONObject(index)
            val name = a.optString("name")
            val canonical = a.optString("browser_download_url")
            if (!ReleaseUrls.canonicalAsset(canonical, tag, name)) return@mapNotNull null
            val bytes = a.opt("size")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ReleaseAsset(id, name, canonical, bytes = bytes, sha256 = digest)
        }
        require(parsed.map { it.name }.distinct().size == parsed.size) { "Duplicate release assets" }
        return ReleaseRecord(json.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: "tag:$tag", tag,
            json.optString("body").takeUnless { it == "null" }.orEmpty(),
            json.optString("published_at").takeUnless { it == "null" }.orEmpty(),
            json.optString("target_commitish"), json.optBoolean("draft"), json.optBoolean("prerelease"), parsed)
    }
}

/** Only construction from an explicitly selected catalog is supported; catalogs cannot add origins. */
class StaticReleaseSource(
    val catalogUrl: String,
    private val transport: ReleaseHttpTransport = HttpReleaseTransport()
) : ReleaseSource {
    override val id = "static:$catalogUrl"
    override val assetPolicy = ReleaseUrls.mirror(catalogUrl)

    override suspend fun fetchPage(cursor: String?): ReleasePage = load(cursor).first

    override suspend fun latestRelease(): ReleaseRecord? {
        var loaded = load(null)
        val tag = loaded.second
        val seen = mutableSetOf<String>()
        repeat(100) {
            loaded.first.entries.firstOrNull { tag != null && it.tag == tag }?.let { release ->
                require(!release.draft && !release.prerelease) { "Latest release must be a published stable release" }
                return release
            }
            if (tag == null) require(loaded.first.entries.none { !it.draft && !it.prerelease }) {
                "Catalog claims no latest release but contains published stable releases"
            }
            val next = loaded.first.nextCursor ?: if (tag == null) return null
                else error("Latest release is missing from the catalog")
            require(seen.add(next)) { "Release catalog contains a pagination cycle" }
            loaded = load(next)
        }
        error("Release catalog has too many pages")
    }

    private suspend fun load(cursor: String?): Pair<ReleasePage, String?> {
        val url = if (cursor == null) catalogUrl else ReleaseUrls.resolve(catalogUrl, cursor, assetPolicy)
        val json = JSONObject(transport.text(url, assetPolicy, HttpReleaseTransport.MAX_METADATA_BYTES).body)
        require(json.get("schemaVersion").toString() == "1") { "Unsupported release catalog version" }
        require(json.getString("repository") == ReleaseUrls.REPOSITORY) { "Wrong release repository" }
        if (cursor == null) require(json.has("latestTag")) { "Catalog must declare its latest stable release" }
        val releases = json.getJSONArray("releases")
        require(releases.length() <= 100) { "Too many releases in a catalog page" }
        val entries = (0 until releases.length()).map { parse(releases.getJSONObject(it)) }
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate release IDs" }
        require(entries.map { it.tag }.distinct().size == entries.size) { "Duplicate release tags" }
        val next = json.opt("nextPage")?.takeIf { it != JSONObject.NULL }?.let {
            require(it is String && it.isNotBlank()) { "Invalid next catalog page" }
            ReleaseUrls.resolve(catalogUrl, it, assetPolicy)
        }
        val latest = json.opt("latestTag")?.takeIf { it != JSONObject.NULL }?.let {
            require(it is String && it.isNotBlank()) { "Invalid latest release tag" }
            it
        }
        return ReleasePage(id, entries, next) to latest
    }

    private fun parse(json: JSONObject): ReleaseRecord {
        val tag = json.getString("tag").also { require(it.isNotBlank()) }
        val array = json.getJSONArray("assets")
        require(array.length() <= 500)
        val assets = (0 until array.length()).map { index ->
            val a = array.getJSONObject(index)
            val name = a.getString("name")
            val canonical = a.getString("canonicalUrl")
            require(ReleaseUrls.canonicalAsset(canonical, tag, name)) { "Asset does not match its original release" }
            val bytes = a.get("bytes").toString().toLongOrNull()
            require(bytes != null && bytes > 0) { "Mirrored assets require an exact byte count" }
            ReleaseAsset(id, name, canonical,
                ReleaseUrls.resolve(catalogUrl, a.getString("url"), assetPolicy), bytes, a.getString("sha256"))
        }
        require(assets.map { it.name }.distinct().size == assets.size) { "Duplicate release assets" }
        return ReleaseRecord(json.getString("id").also { require(it.isNotBlank()) }, tag,
            json.getString("notes"), json.getString("publishedAt"), json.optString("targetCommitish"),
            json.optBoolean("draft"), json.optBoolean("prerelease"), assets)
    }
}
