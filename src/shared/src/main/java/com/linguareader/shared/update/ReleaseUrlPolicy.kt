package com.linguareader.shared.update

import java.net.URI

fun interface ReleaseUrlPolicy {
    fun allows(url: String): Boolean
}

/** URL constraints are supplied by the user's selected source, never widened by remote JSON. */
object ReleaseUrls {
    const val REPOSITORY = "xyu20071224-design/reader"
    const val GITHUB_API = "https://api.github.com/repos/$REPOSITORY/releases"
    private const val RELEASE_PATH = "/$REPOSITORY/releases/download/"

    private fun uri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null &&
            it.port in setOf(-1, 443) && !it.path.orEmpty().contains('\\') &&
            it.path.orEmpty().none { c -> c.code < 32 } &&
            it.path.orEmpty().split('/').none { part -> part == "." || part == ".." } &&
            it.rawPath.orEmpty().none { c -> c.code < 32 } &&
            // Avoid encoded path separators and double-decoding ambiguity at proxies/CDNs.
            !Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(it.rawPath.orEmpty())
    }

    fun canonicalAsset(url: String, tag: String? = null, name: String? = null): Boolean {
        val u = uri(url) ?: return false
        if (u.host != "github.com" || u.query != null || !u.path.startsWith(RELEASE_PATH)) return false
        val suffix = u.path.removePrefix(RELEASE_PATH)
        if (suffix.split('/').size != 2 || suffix.split('/').any { it.isBlank() }) return false
        return (tag == null || suffix.substringBefore('/') == tag) &&
            (name == null || suffix.substringAfter('/') == name)
    }

    val gitHubApi = ReleaseUrlPolicy { url ->
        val u = uri(url)
        u != null && u.host == "api.github.com" &&
            (u.path == "/repos/$REPOSITORY/releases" || u.path == "/repos/$REPOSITORY/releases/latest") &&
            (u.query == null || u.query.split('&').all { part -> part.matches(Regex("(?:page|per_page)=[0-9]+")) })
    }

    val gitHubAssets = ReleaseUrlPolicy { url ->
        val u = uri(url)
        u != null && (canonicalAsset(url) || u.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
    }

    /** The explicitly chosen catalog fixes both origin and directory for every page/asset/redirect. */
    fun mirror(catalogUrl: String): ReleaseUrlPolicy {
        val root = requireNotNull(uri(catalogUrl)) { "Release source must be an HTTPS URL" }
        require(root.query == null && root.path.isNotBlank() && !root.path.endsWith('/')) { "Choose a catalog file URL" }
        val directory = root.path.substringBeforeLast('/', "") + "/"
        return ReleaseUrlPolicy { url ->
            val candidate = uri(url)
            candidate != null && candidate.host.equals(root.host, ignoreCase = true) &&
                effectivePort(candidate) == effectivePort(root) && candidate.query == null &&
                candidate.path.startsWith(directory)
        }
    }

    fun resolve(catalogUrl: String, path: String, policy: ReleaseUrlPolicy): String {
        val url = URI(catalogUrl).resolve(path).toString()
        require(policy.allows(url)) { "Release URL is outside the selected source" }
        return url
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
}
