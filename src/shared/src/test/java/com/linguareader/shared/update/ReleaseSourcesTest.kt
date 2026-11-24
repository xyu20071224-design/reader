package com.linguareader.shared.update

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseSourcesTest {
    private val canonical = "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v2.3.1/reader.apk"
    private val catalog = "https://mirror.example/reader/catalog.json"
    private val record = """{"id":"r26","tag":"v2.3.1","notes":"本版说明","publishedAt":"2026-11-18T03:56:21Z",
        "assets":[{"name":"reader.apk","canonicalUrl":"$canonical","url":"assets/reader.apk","bytes":3,"sha256":"${"a".repeat(64)}"}]}"""

    private fun page(releases: String = record, next: String = "null", latest: String = "\"v2.3.1\"") =
        """{"schemaVersion":1,"repository":"${ReleaseUrls.REPOSITORY}","latestTag":$latest,"releases":[$releases],"nextPage":$next}"""

    private class FakeTransport(val responses: Map<String, ReleaseHttpResponse>) : ReleaseHttpTransport {
        val calls = mutableListOf<String>()
        override suspend fun text(url: String, policy: ReleaseUrlPolicy, maxBytes: Long): ReleaseHttpResponse {
            require(policy.allows(url)); calls += url
            return responses[url] ?: error("Unexpected request")
        }
        override suspend fun download(asset: ReleaseAsset, directory: File, policy: ReleaseUrlPolicy,
            maxBytes: Long, onProgress: (Long, Long) -> Unit): File = error("Unexpected download")
    }
    private fun response(body: String, url: String = catalog, headers: Map<String, List<String>> = emptyMap()) =
        ReleaseHttpResponse(body, headers, url)

    @Test fun `github history keeps notes without apk and follows validated page cursor`() = runBlocking {
        val first = "${ReleaseUrls.GITHUB_API}?per_page=50&page=1"
        val second = "${ReleaseUrls.GITHUB_API}?per_page=50&page=2"
        val json = """[{"id":1,"tag_name":"v2.3.1","body":"notes without APK","published_at":"2026-11-18","assets":[]}]"""
        val transport = FakeTransport(mapOf(first to response(json, first,
            mapOf("link" to listOf("<$second>; rel=\"next\""))), second to response("[]", second)))
        val source = GitHubReleaseSource(transport)
        assertTrue(transport.calls.isEmpty())
        val a = source.fetchPage()
        assertEquals("notes without APK", a.entries.single().notes)
        assertEquals("2", a.nextCursor)
        assertTrue(source.fetchPage(a.nextCursor).entries.isEmpty())
    }

    @Test fun `github pagination cannot redirect metadata to another host`() = runBlocking {
        val url = "${ReleaseUrls.GITHUB_API}?per_page=50&page=1"
        val transport = FakeTransport(mapOf(url to response("[]", url,
            mapOf("Link" to listOf("<https://attacker.example/?page=2>; rel=\"next\"")))))
        assertTrue(runCatching { GitHubReleaseSource(transport).fetchPage() }.isFailure)
    }

    @Test fun `mirror preserves canonical identity and binds transfer to selected source`() = runBlocking {
        val transport = FakeTransport(mapOf(catalog to response(page())))
        val source = StaticReleaseSource(catalog, transport)
        assertTrue(transport.calls.isEmpty())
        val release = source.latestRelease()!!
        assertEquals("https://mirror.example/reader/assets/reader.apk", release.assets.single().transportUrl)
        assertEquals(canonical, release.assets.single().canonicalUrl)
        val legacy = JSONObject(release.canonicalBody())
        assertEquals(canonical, legacy.getJSONArray("assets").getJSONObject(0).getString("browser_download_url"))
        assertTrue(NativePatchCatalog.trustedAsset(release.assets.single().canonicalUrl))
        assertFalse(NativePatchCatalog.trustedAsset(release.assets.single().transportUrl))
    }

    @Test fun `mirror rejects incomplete integrity metadata and wrong release identity`() = runBlocking {
        for (badRecord in listOf(
            record.replace("\"bytes\":3", "\"bytes\":0"),
            record.replace("\"bytes\":3", "\"bytes\":3.5"),
            record.replace("a".repeat(64), "not-a-hash"),
            record.replace("releases/download/v2.3.1/", "releases/download/v2.3.0/"),
            record.replace("\"url\":\"assets/reader.apk\"", "\"url\":\"https://other.example/reader.apk\""))) {
            val source = StaticReleaseSource(catalog, FakeTransport(mapOf(catalog to response(page(badRecord)))))
            assertTrue(badRecord, runCatching { source.fetchPage() }.isFailure)
        }
    }

    @Test fun `mirror rejects unsupported catalogs and duplicate releases`() = runBlocking {
        for (body in listOf(page().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            page().replace(ReleaseUrls.REPOSITORY, "other/repo"), page("$record,$record"))) {
            val source = StaticReleaseSource(catalog, FakeTransport(mapOf(catalog to response(body))))
            assertTrue(runCatching { source.fetchPage() }.isFailure)
        }
    }

    @Test fun `mirror pagination cannot escape source and missing latest does not fake up to date`() = runBlocking {
        val bad = StaticReleaseSource(catalog, FakeTransport(mapOf(catalog to response(page(next = "\"../elsewhere.json\"")))))
        assertTrue(runCatching { bad.fetchPage() }.isFailure)
        val missing = StaticReleaseSource(catalog,
            FakeTransport(mapOf(catalog to response(page(latest = "\"v9.0.0\"")))))
        assertTrue(runCatching { missing.latestRelease() }.isFailure)
    }

    @Test fun `latest release may be on later page but cycles are rejected`() = runBlocking {
        val later = "https://mirror.example/reader/page2.json"
        val source = StaticReleaseSource(catalog, FakeTransport(mapOf(
            catalog to response(page("", "\"page2.json\"")), later to response(page(), later))))
        assertEquals("v2.3.1", source.latestRelease()!!.tag)
        val cyclic = StaticReleaseSource(catalog, FakeTransport(mapOf(
            catalog to response(page("", "\"page2.json\"")),
            later to response(page("", "\"page2.json\""), later))))
        assertTrue(runCatching { cyclic.latestRelease() }.isFailure)
    }

    @Test fun `selected mirror cannot admit traversal credentials downgrade or sibling directory`() {
        val policy = ReleaseUrls.mirror(catalog)
        assertTrue(policy.allows("https://mirror.example/reader/assets/file.apk"))
        for (url in listOf("http://mirror.example/reader/a", "https://u:p@mirror.example/reader/a",
            "https://mirror.example/reader-other/a", "https://mirror.example/reader/%2e%2e/a",
            "https://mirror.example/reader/%252e%252e/a", "https://mirror.example/reader/a%2fb",
            "https://mirror.example:444/reader/a", "https://mirror.example/reader/a?secret=1")) {
            assertFalse(url, policy.allows(url))
        }
    }
}
