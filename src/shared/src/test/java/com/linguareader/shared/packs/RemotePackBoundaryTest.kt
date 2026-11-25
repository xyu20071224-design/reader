package com.linguareader.shared.packs

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class RemotePackBoundaryTest {
    private val body = "verified bytes".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pack() = RemotePack("test.lrpack", "https://packs.example/test.lrpack", body.size.toLong(), sha(body))
    private fun entry() = JSONObject().put("packId", "test").put("version", "1.0.0").put("type", "voice")
        .put("minAppVersion", 1).put("name", "test.lrpack").put("bytes", body.size).put("sha256", sha(body))
        .put("urls", JSONArray().put(pack().downloadUrl))
    private fun catalog(vararg entries: JSONObject) = JSONObject().put("formatVersion", 1)
        .put("packs", JSONArray(entries.toList())).toString()
    private fun connection(bytes: ByteArray, status: Int = 200, headers: Map<String, String> = emptyMap(), api23: Boolean = false) =
        object : HttpURLConnection(URL(pack().downloadUrl)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = status
            override fun getInputStream() = ByteArrayInputStream(bytes)
            override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
            override fun getContentLengthLong(): Long = if (api23) throw NoSuchMethodError("API23") else super.getContentLengthLong()
        }

    @Test fun rejectsUnsafeIdsAndVersionsEvenWhenIncompatible() {
        for (bad in listOf(entry().put("packId", "../outside"), entry().put("version", "../1"),
            entry().put("packId", "UPPER"), entry().put("version", "a".repeat(33)))) {
            bad.put("minAppVersion", 99)
            assertTrue("Unsafe future entry accepted: $bad", runCatching { RemotePackCatalog.parse(catalog(bad), 26) }.isFailure)
        }
    }

    @Test fun rejectsCoercedNumbersAndDuplicateNamesOrUrls() {
        for (bad in listOf(entry().put("bytes", body.size.toString()), entry().put("bytes", 3.5),
            entry().put("minAppVersion", "1"), entry().put("minAppVersion", 1.5),
            entry().put("urls", JSONArray().put(pack().downloadUrl).put(pack().downloadUrl)))) {
            assertTrue("Coerced/duplicate entry accepted: $bad", runCatching { RemotePackCatalog.parse(catalog(bad), 26) }.isFailure)
        }
        assertTrue(runCatching { RemotePackCatalog.parse(catalog(entry(), entry().put("packId", "second")), 26) }.isFailure)
    }

    @Test fun catalogRejectsTruncationAndCompressionInsteadOfAcceptingValidJsonPrefix() = runBlocking {
        val json = catalog(entry()).toByteArray()
        assertTrue(RemotePackClient { connection(json, headers = mapOf("Content-Length" to "${json.size + 20}")) }.fetchCatalog(26).isFailure)
        assertTrue(RemotePackClient { connection(json, headers = mapOf("Content-Encoding" to "gzip")) }.fetchCatalog(26).isFailure)
    }

    @Test fun responseRangeEndMustMatchTheBytesActuallyClaimed() = runBlocking {
        val dir = Files.createTempDirectory("remote-range-").toFile()
        try {
            java.io.File(dir, "${pack().sha256}.part").writeBytes(body.copyOfRange(0, 5))
            val client = RemotePackClient { connection(body.copyOfRange(5, body.size), 206,
                mapOf("Content-Range" to "bytes 5-5/${body.size}")) }
            assertTrue("Incorrect range end accepted", client.download(pack(), dir).isFailure)
            assertFalse(dir.listFiles().orEmpty().any { it.extension == "lrpack" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun downloadUsesApi1LengthLookupAndRetainsExactIntegrityChecks() = runBlocking {
        val dir = Files.createTempDirectory("remote-api23-").toFile()
        try {
            val downloaded = RemotePackClient { connection(body, headers = mapOf("Content-Length" to body.size.toString()), api23 = true) }
                .download(pack(), dir).getOrThrow()
            assertArrayEquals(body, downloaded.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun unknownSizeIsRejectedBeforeOpeningAnyConnection() = runBlocking {
        val dir = Files.createTempDirectory("remote-unknown-").toFile()
        try {
            var calls = 0
            val result = RemotePackClient { calls++; connection(body) }.download(pack().copy(bytes = 0, sha256 = null), dir)
            assertTrue(result.isFailure)
            assertEquals(0, calls)
        } finally { dir.deleteRecursively() }
    }
}
