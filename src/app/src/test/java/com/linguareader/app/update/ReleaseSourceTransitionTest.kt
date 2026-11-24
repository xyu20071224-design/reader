package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.update.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Source-transition probes reproduced verbatim from the independent integration audit. */
@RunWith(RobolectricTestRunner::class)
class ReleaseSourceTransitionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val catalog = "https://audit.example/reader/catalog.json"
    private val canonical = "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/v99.0.0/audit.lrpack"
    private val transfer = "https://audit.example/reader/assets/audit.lrpack"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun settings() { context.getSharedPreferences("update_settings", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun metadata(bytes: ByteArray): ByteArray {
        val asset = JSONObject().put("name", "audit.lrpack").put("canonicalUrl", canonical)
            .put("url", "assets/audit.lrpack").put("bytes", bytes.size).put("sha256", sha(bytes))
        val release = JSONObject().put("id", "audit-release").put("tag", "v99.0.0")
            .put("notes", "audit").put("publishedAt", "2026-11-24T00:00:00Z")
            .put("assets", JSONArray().put(asset))
        return JSONObject().put("schemaVersion", 1).put("repository", ReleaseUrls.REPOSITORY)
            .put("latestTag", "v99.0.0").put("releases", JSONArray().put(release)).toString().toByteArray()
    }

    private fun repository(responses: Map<String, ByteArray>, calls: MutableList<String>, onRead: (() -> Unit)? = null) = AppUpdateRepository(context) { url ->
        calls += url
        val body = responses[url] ?: error("Unexpected request $url")
        object : HttpURLConnection(URL(url)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = body.size.toLong()
            override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
            override fun getInputStream() = if (onRead == null) ByteArrayInputStream(body) else object : ByteArrayInputStream(body) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead(); return super.read(buffer, offset, length)
                }
            }
        }
    }

    @Test fun `a stale mirror package is rejected after switching back to GitHub`() = runBlocking {
        val expected = byteArrayOf(1, 2, 3)
        val changed = byteArrayOf(9, 9, 9)
        val calls = mutableListOf<String>()
        val repository = repository(mapOf(catalog to metadata(expected), canonical to changed), calls)
        repository.setCatalogUrl(catalog)
        val oldAsset = repository.fetchPackAssets().getOrThrow().single()
        repository.setCatalogUrl(null)
        val result = repository.downloadPack(oldAsset) { _, _ -> }
        try {
            println("AUDIT staleAsset success=${result.isSuccess} calls=$calls expectedSha=${sha(expected)} actualSha=${result.getOrNull()?.let { sha(it.readBytes()) }}")
            assertTrue("source switch must require fresh discovery before accepting an old row", result.isFailure)
            assertEquals(listOf(catalog), calls)
        } finally { result.getOrNull()?.delete() }
    }

    @Test fun `selected mirror still rejects the same wrong digest before any source change`() = runBlocking {
        val expected = byteArrayOf(1, 2, 3)
        val calls = mutableListOf<String>()
        val repository = repository(mapOf(catalog to metadata(expected), transfer to byteArrayOf(9, 9, 9)), calls)
        repository.setCatalogUrl(catalog)
        val asset = repository.fetchPackAssets().getOrThrow().single()
        assertTrue(repository.downloadPack(asset) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog, transfer), calls)
    }

    private fun apkInfo() = AppUpdateInfo("v99.0.0", "99.0.0", "reader.apk", canonical.replace("audit.lrpack", "reader.apk"), "", "")

    @Test fun `same catalog reselection cannot revive old row without a new snapshot`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = repository(mapOf(catalog to metadata(byteArrayOf(1))), calls)
        repository.setCatalogUrl(catalog)
        val old = repository.fetchPackAssets().getOrThrow().single()
        repository.setCatalogUrl(catalog)
        assertTrue(repository.downloadPack(old) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog), calls)
    }

    @Test fun `new discovery of the same url uses current digest rather than stale row integrity`() = runBlocking {
        val oldBytes = byteArrayOf(1, 2, 3)
        val newBytes = byteArrayOf(7, 8, 9)
        val calls = mutableListOf<String>()
        val responses = mutableMapOf(catalog to metadata(oldBytes), transfer to oldBytes)
        val repository = repository(responses, calls)
        repository.setCatalogUrl(catalog)
        val old = repository.fetchPackAssets().getOrThrow().single()
        repository.setCatalogUrl(null)
        repository.setCatalogUrl(catalog)
        assertTrue(repository.downloadPack(old) { _, _ -> }.isFailure)
        responses[catalog] = metadata(newBytes)
        repository.fetchPackAssets().getOrThrow()
        assertTrue(repository.downloadPack(old) { _, _ -> }.isFailure)
        responses[transfer] = newBytes
        val file = repository.downloadPack(old) { _, _ -> }.getOrThrow()
        try { assertArrayEquals(newBytes, file.readBytes()) } finally { file.delete() }
        assertEquals(listOf(catalog, catalog, transfer, transfer), calls)
    }

    @Test fun `explicit default selection blocks every legacy download kind before connection`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = repository(emptyMap(), calls)
        repository.setCatalogUrl(null)
        assertTrue(repository.downloadPack(GitHubReleaseParser.PackAsset("audit.lrpack", canonical, 3)) { _, _ -> }.isFailure)
        assertTrue(repository.download(apkInfo()) { _, _ -> }.isFailure)
        val patchUrl = canonical.replace("audit.lrpack", "fix.lrpatch")
        val patch = NativePatchInfo("reader-26", 26, 1, "fix.lrpatch", patchUrl, sha(byteArrayOf(1)), 1, "")
        assertTrue(repository.download(apkInfo().copy(apkName = patch.assetName, downloadUrl = patchUrl, nativePatch = patch)) { _, _ -> }.isFailure)
        assertTrue(calls.isEmpty())
    }

    @Test fun `APK mirror row is rejected after switching back to GitHub`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val json = JSONObject(metadata(bytes).toString(Charsets.UTF_8))
        json.getJSONArray("releases").getJSONObject(0).getJSONArray("assets").getJSONObject(0)
            .put("name", "reader.apk").put("canonicalUrl", apkInfo().downloadUrl).put("url", "assets/reader.apk")
        val calls = mutableListOf<String>()
        val repository = repository(mapOf(catalog to json.toString().toByteArray(), apkInfo().downloadUrl to byteArrayOf(9, 9, 9)), calls)
        repository.setCatalogUrl(catalog)
        val old = (repository.check() as UpdateCheckOutcome.Available).info
        repository.setCatalogUrl(null)
        assertTrue(repository.download(old) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog), calls)
    }

    @Test fun `initial GitHub legacy transfer works but mid transfer source switch cleans the file`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        for (apk in listOf(false, true)) {
            val url = if (apk) apkInfo().downloadUrl else canonical
            val calls = mutableListOf<String>()
            val initial = repository(mapOf(url to bytes), calls)
            val first = if (apk) initial.download(apkInfo()) { _, _ -> } else
                initial.downloadPack(GitHubReleaseParser.PackAsset("audit.lrpack", canonical, 3)) { _, _ -> }
            first.getOrThrow().delete()
            lateinit var changed: AppUpdateRepository
            var switched = false
            changed = repository(mapOf(url to bytes), calls) {
                if (!switched) { switched = true; changed.setCatalogUrl(null) }
            }
            val folder = context.getExternalFilesDir(if (apk) "updates" else "packs-download")!!
            val target = java.io.File(folder, if (apk) "reader.apk" else "audit.lrpack")
            val result = if (apk) changed.download(apkInfo()) { _, _ -> } else
                changed.downloadPack(GitHubReleaseParser.PackAsset("audit.lrpack", canonical, 3)) { _, _ -> }
            assertTrue(result.isFailure)
            assertTrue(switched)
            assertFalse(target.exists())
            assertEquals(listOf(url, url), calls)
        }
    }

    @Test fun `empty or failed first discovery disables bare fallback even at epoch zero`() = runBlocking {
        for (failed in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val repository = AppUpdateRepository(context, { url -> calls += url; error("Must not connect") }, { _, _ ->
                object : ReleaseSource {
                    override val id = "github:${ReleaseUrls.REPOSITORY}"
                    override val assetPolicy = ReleaseUrlPolicy { true }
                    override suspend fun fetchPage(cursor: String?) = ReleasePage(id, emptyList())
                    override suspend fun latestRelease(): ReleaseRecord? = if (failed) error("offline") else null
                }
            })
            assertEquals(0L, repository.sourceGeneration())
            assertEquals(failed, repository.check() is UpdateCheckOutcome.Failure)
            assertEquals(failed, repository.fetchPackAssets().isFailure)
            assertTrue(repository.download(apkInfo()) { _, _ -> }.isFailure)
            assertTrue(repository.downloadPack(GitHubReleaseParser.PackAsset("audit.lrpack", canonical, 3)) { _, _ -> }.isFailure)
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun `null discovery clears existing GitHub snapshots without reviving legacy compatibility`() = runBlocking {
        for (apk in listOf(false, true)) {
            val name = if (apk) "reader.apk" else "audit.lrpack"
            val url = if (apk) apkInfo().downloadUrl else canonical
            val calls = mutableListOf<String>()
            val id = "github:${ReleaseUrls.REPOSITORY}"
            var next: ReleaseRecord? = ReleaseRecord("audit-release", "v99.0.0", "", "2026-11-24",
                assets = listOf(ReleaseAsset(id, name, url, bytes = 3, sha256 = sha(byteArrayOf(1, 2, 3)))))
            val repository = AppUpdateRepository(context, { source -> calls += source; error("Must not connect") }, { _, _ ->
                object : ReleaseSource {
                    override val id = "github:${ReleaseUrls.REPOSITORY}"
                    override val assetPolicy = ReleaseUrlPolicy { true }
                    override suspend fun fetchPage(cursor: String?) = ReleasePage(id, emptyList())
                    override suspend fun latestRelease() = next
                }
            })
            if (apk) {
                val old = (repository.check() as UpdateCheckOutcome.Available).info
                next = null
                assertEquals(UpdateCheckOutcome.UpToDate, repository.check())
                assertTrue(repository.download(old) { _, _ -> }.isFailure)
            } else {
                val old = repository.fetchPackAssets().getOrThrow().single()
                next = null
                assertTrue(repository.fetchPackAssets().getOrThrow().isEmpty())
                assertTrue(repository.downloadPack(old) { _, _ -> }.isFailure)
            }
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun `failed rediscovery clears old mirror snapshot rather than continuing its downloads`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val calls = mutableListOf<String>()
        val responses = mutableMapOf(catalog to metadata(bytes), transfer to bytes)
        val repository = repository(responses, calls)
        repository.setCatalogUrl(catalog)
        val old = repository.fetchPackAssets().getOrThrow().single()
        responses.remove(catalog)
        assertTrue(repository.fetchPackAssets().isFailure)
        assertTrue(repository.downloadPack(old) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog, catalog), calls)
    }

}
