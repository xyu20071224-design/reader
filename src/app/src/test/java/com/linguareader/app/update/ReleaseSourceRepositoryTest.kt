package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.BuildConfig
import com.linguareader.shared.update.ReleaseUrls
import com.linguareader.shared.update.ReleaseSource
import com.linguareader.shared.update.ReleasePage
import com.linguareader.shared.update.ReleaseRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ReleaseSourceRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val catalog = "https://mirror.example/reader/catalog.json"
    private val tag = "v99.0.0"
    private fun canonical(tag: String, name: String) =
        "https://github.com/${ReleaseUrls.REPOSITORY}/releases/download/$tag/$name"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Before fun resetSettings() {
        context.getSharedPreferences("update_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun metadata(tag: String, name: String, payload: ByteArray, digest: String = sha(payload)): ByteArray =
        JSONObject().apply {
            put("schemaVersion", 1); put("repository", ReleaseUrls.REPOSITORY); put("latestTag", tag)
            put("releases", JSONArray().put(JSONObject().apply {
                put("id", "test-release"); put("tag", tag); put("notes", "test notes")
                put("publishedAt", "2026-11-24T00:00:00Z")
                put("assets", JSONArray().put(JSONObject().apply {
                    put("name", name); put("canonicalUrl", canonical(tag, name))
                    put("url", "assets/$name"); put("bytes", payload.size); put("sha256", digest)
                }))
            }))
        }.toString().toByteArray()

    private fun repository(responses: Map<String, ByteArray>, calls: MutableList<String>): AppUpdateRepository =
        AppUpdateRepository(context) { url ->
            calls += url
            val body = responses[url] ?: error("Unexpected request: $url")
            object : HttpURLConnection(URL(url)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = body.size.toLong()
                override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
                override fun getInputStream() = ByteArrayInputStream(body)
            }
        }

    @Test fun `source setting is explicit normalized and does not reset auto check`() {
        val repo = repository(emptyMap(), mutableListOf())
        assertNull(repo.loadSettings().catalogUrl)
        assertFalse(repo.loadSettings().autoCheckEnabled)
        repo.setAutoCheck(true)
        repo.setCatalogUrl("  $catalog  ")
        assertEquals(catalog, repo.loadSettings().catalogUrl)
        assertTrue(repo.loadSettings().autoCheckEnabled)
        repo.setAutoCheck(false)
        assertEquals(catalog, repo.loadSettings().catalogUrl)
        assertFalse(repo.loadSettings().autoCheckEnabled)
        assertTrue(runCatching { repo.setCatalogUrl("http://mirror.example/reader/catalog.json") }.isFailure)
        assertEquals(catalog, repo.loadSettings().catalogUrl)
        repo.setCatalogUrl("   ")
        assertNull(repo.loadSettings().catalogUrl)
        assertEquals("github:${ReleaseUrls.REPOSITORY}", repo.currentSource().id)
    }

    @Test fun `mirror pack discovery keeps canonical url and downloads mirror bytes`() = runBlocking {
        val payload = "lrpack fixture bytes".toByteArray()
        val calls = mutableListOf<String>()
        val transferUrl = "https://mirror.example/reader/assets/test.lrpack"
        val repo = repository(mapOf(catalog to metadata(tag, "test.lrpack", payload), transferUrl to payload), calls)
        repo.setCatalogUrl(catalog)
        val asset = repo.fetchPackAssets().getOrThrow().single()
        assertEquals(canonical(tag, "test.lrpack"), asset.downloadUrl)
        val file = repo.downloadPack(asset) { _, _ -> }.getOrThrow()
        try {
            assertArrayEquals(payload, file.readBytes())
            assertEquals(listOf(catalog, transferUrl), calls)
            assertEquals("packs-download", file.parentFile!!.name)
        } finally { file.delete() }
    }

    @Test fun `mirror APK check and download use same discovered identity`() = runBlocking {
        val payload = "apk fixture bytes".toByteArray()
        val calls = mutableListOf<String>()
        val transferUrl = "https://mirror.example/reader/assets/reader.apk"
        val repo = repository(mapOf(catalog to metadata(tag, "reader.apk", payload), transferUrl to payload), calls)
        repo.setCatalogUrl(catalog)
        val outcome = repo.check() as UpdateCheckOutcome.Available
        assertEquals(canonical(tag, "reader.apk"), outcome.info.downloadUrl)
        val file = repo.download(outcome.info) { _, _ -> }.getOrThrow()
        try {
            assertArrayEquals(payload, file.readBytes())
            assertEquals(listOf(catalog, transferUrl), calls)
        } finally { file.delete() }
    }

    @Test fun `source switch discards discovered assets and cannot download stale mirror item`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val calls = mutableListOf<String>()
        val repo = repository(mapOf(catalog to metadata(tag, "test.lrpack", payload)), calls)
        repo.setCatalogUrl(catalog)
        val item = repo.fetchPackAssets().getOrThrow().single()
        repo.setCatalogUrl("https://second.example/reader/catalog.json")
        assertTrue(repo.downloadPack(item) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog), calls)
    }

    @Test fun `discovered same name from another release is not silently accepted`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val calls = mutableListOf<String>()
        val repo = repository(mapOf(catalog to metadata(tag, "test.lrpack", payload)), calls)
        repo.setCatalogUrl(catalog)
        val item = repo.fetchPackAssets().getOrThrow().single()
        val other = item.copy(downloadUrl = canonical("v98.0.0", "test.lrpack"))
        assertTrue(repo.downloadPack(other) { _, _ -> }.isFailure)
        assertEquals(listOf(catalog), calls)
    }

    @Test fun `mirrored hotfix discovery catalog verifies its own hash before parsing`() = runBlocking {
        val payload = """{"schemaVersion":1,"patches":[]}""".toByteArray()
        val baseTag = "v${BuildConfig.VERSION_NAME}"
        val transferUrl = "https://mirror.example/reader/assets/native-patches.json"
        val calls = mutableListOf<String>()
        val repo = repository(mapOf(catalog to metadata(baseTag, "native-patches.json", payload, "0".repeat(64)),
            transferUrl to payload), calls)
        repo.setCatalogUrl(catalog)
        val outcome = repo.check()
        assertTrue(outcome is UpdateCheckOutcome.Failure)
        assertTrue((outcome as UpdateCheckOutcome.Failure).message.contains("SHA-256"))
        assertEquals(listOf(catalog, transferUrl), calls)
        assertTrue(File(context.filesDir, "hotfix-downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun `verified mirrored hotfix catalog reaches existing selector without changing trust policy`() = runBlocking {
        val payload = """{"schemaVersion":1,"patches":[]}""".toByteArray()
        val baseTag = "v${BuildConfig.VERSION_NAME}"
        val transferUrl = "https://mirror.example/reader/assets/native-patches.json"
        val calls = mutableListOf<String>()
        val repo = repository(mapOf(catalog to metadata(baseTag, "native-patches.json", payload),
            transferUrl to payload), calls)
        repo.setCatalogUrl(catalog)
        assertEquals(UpdateCheckOutcome.UpToDate, repo.check())
        assertEquals(listOf(catalog, transferUrl), calls)
        assertTrue(File(context.filesDir, "hotfix-downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun `reselecting same source cannot resurrect an old in flight result`() = runBlocking {
        lateinit var repo: AppUpdateRepository
        repo = AppUpdateRepository(context, { error("Must not connect") }, { _, _ ->
            object : ReleaseSource {
                override val id = "static:$catalog"
                override val assetPolicy = ReleaseUrls.mirror(catalog)
                override suspend fun fetchPage(cursor: String?) = ReleasePage(id, emptyList())
                override suspend fun latestRelease(): ReleaseRecord {
                    repo.setCatalogUrl(catalog)
                    return ReleaseRecord("r1", tag, "", "2026-11-24")
                }
            }
        })
        repo.setCatalogUrl(catalog)
        val result = repo.fetchPackAssets()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("source changed"))
    }
}
