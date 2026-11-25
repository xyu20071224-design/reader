package com.linguareader.desktop

import com.linguareader.shared.update.HttpReleaseTransport
import com.linguareader.shared.update.ReleaseUrls
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

class DesktopPackRemoteAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Response(url: String, private val bytes: ByteArray) : HttpURLConnection(URL(url)) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = bytes.inputStream()
        override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Length" to listOf(bytes.size.toString()))
        override fun getHeaderField(name: String): String? = if (name.equals("Content-Length", true)) bytes.size.toString() else null
    }

    private fun asset(name: String, bytes: Long, sha: String? = null, external: Boolean = false) = JSONObject()
        .put("name", name).put("size", bytes)
        .put("browser_download_url", if (external) "https://elsewhere.example/$name"
            else "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v2/$name")
        .apply { sha?.let { put("digest", "sha256:$it") } }

    private fun release(assets: List<JSONObject>): ByteArray = JSONObject().put("id", 42).put("tag_name", "v2")
        .put("draft", false).put("prerelease", false)
        .put("assets", JSONArray(assets)).toString().toByteArray()

    @Test fun githubDiscoveryRequiresPublishedAssetOriginAndPositiveKnownSize() = runBlocking {
        var calls = 0
        val metadata = release(listOf(asset("good.lrpack", 10), asset("zero.lrpack", 0),
            asset("app.apk", 20), asset("foreign.lrpack", 30, external = true)))
        val access = DefaultDesktopPackRemoteAccess(transport = HttpReleaseTransport { url ->
            calls++; assertEquals("${ReleaseUrls.GITHUB_API}/latest", url); Response(url, metadata)
        })
        assertEquals(0, calls)
        val offers = access.githubCatalog()
        assertEquals(listOf("good.lrpack"), offers.map { it.name })
        assertEquals("github:${ReleaseUrls.REPOSITORY}", offers.single().github!!.sourceId)
        assertEquals(10L, offers.single().github!!.bytes)
        assertEquals(1, calls)
    }

    @Test fun githubDownloadKeepsDiscoveredDigestAndCanonicalTransportPolicy() = runBlocking {
        val payload = "archive transport fixture".toByteArray()
        val sha = DesktopPackFixtures.sha(payload)
        val metadata = release(listOf(asset("good.lrpack", payload.size.toLong(), sha)))
        val calls = mutableListOf<String>()
        val access = DefaultDesktopPackRemoteAccess(transport = HttpReleaseTransport { url ->
            calls += url
            Response(url, if (url.endsWith("/latest")) metadata else payload)
        })
        val offer = access.githubCatalog().single()
        val target = access.download(offer, temporary.newFolder()) { _, _ -> }
        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf("${ReleaseUrls.GITHUB_API}/latest", offer.github!!.canonicalUrl), calls)
        assertEquals(sha, offer.github.sha256)
    }

    @Test fun githubDigestFailureNeverExposesCompletedDownloadedFile() = runBlocking {
        val payload = "bad bytes".toByteArray()
        val metadata = release(listOf(asset("good.lrpack", payload.size.toLong(), "a".repeat(64))))
        val directory = temporary.newFolder()
        val access = DefaultDesktopPackRemoteAccess(transport = HttpReleaseTransport { url ->
            Response(url, if (url.endsWith("/latest")) metadata else payload)
        })
        val offer = access.githubCatalog().single()
        var rejected = false
        try { access.download(offer, directory) { _, _ -> } } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
