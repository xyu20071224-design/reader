package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.update.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class NativePatchDownloadTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val url = "https://github.com/xyu20071224-design/reader/releases/download/v2.3.0/fix.lrpatch"
    private val directory get() = File(context.filesDir, "hotfix-downloads")
    private fun files() = directory.listFiles()?.toSet().orEmpty()
    private fun info(body: ByteArray, bytes: Long = body.size.toLong(), hash: String? = null): AppUpdateInfo {
        val sha = hash ?: MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val patch = NativePatchInfo("reader-25",25,1,"fix.lrpatch",url,sha,bytes,"")
        return AppUpdateInfo("v2.3.0","2.3.0",patch.assetName,url,"","",patch)
    }
    private fun repo(body: ByteArray): AppUpdateRepository = AppUpdateRepository(context) { source ->
        object : HttpURLConnection(URL(source)) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(body)
        }
    }
    @Test fun writesVerifiedPatchIntoPrivateAppDirectory() = runBlocking<Unit> {
        val body = "signed-content-fixture".toByteArray()
        val file = repo(body).download(info(body)) { _, _ -> }.getOrThrow()
        assertEquals(directory, file.parentFile)
        assertArrayEquals(body, file.readBytes())
        file.delete()
    }
    @Test fun digestMismatchRemovesPartialContent() = runBlocking<Unit> {
        val body = "changed".toByteArray()
        val before = files()
        assertTrue(repo(body).download(info(body,hash="0".repeat(64))) { _, _ -> }.isFailure)
        assertEquals(before, files())
    }
    @Test fun declaredSizeBoundsBothTooLargeAndTruncatedResponses() = runBlocking<Unit> {
        val body = ByteArray(8)
        val before = files()
        for (size in listOf(4L, 12L)) {
            assertTrue(repo(body).download(info(body,bytes=size)) { _, _ -> }.isFailure)
            assertEquals(before, files())
        }
    }
    @Test fun cancellationPropagatesAndRemovesPartialFile() = runBlocking<Unit> {
        val body = ByteArray(100_000)
        val before = files()
        try { repo(body).download(info(body)) { _, _ -> throw CancellationException() }; fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(before, files())
    }
    @Test fun anotherDownloadCannotOverwriteAnExistingMergeInput() = runBlocking<Unit> {
        val first = "already verified".toByteArray()
        val old = repo(first).download(info(first)) { _, _ -> }.getOrThrow()
        val second = "new candidate".toByteArray()
        val fresh = repo(second).download(info(second)) { _, _ -> }.getOrThrow()
        assertNotEquals(old, fresh)
        assertArrayEquals(first, old.readBytes())
        assertArrayEquals(second, fresh.readBytes())
        old.delete(); fresh.delete()
    }
    @Test fun filenameTraversalFailsBeforeOpeningConnection() = runBlocking<Unit> {
        val p = info(ByteArray(1))
        val result = AppUpdateRepository(context) { error("Must not connect") }
            .download(p.copy(nativePatch=p.nativePatch!!.copy(assetName="../escape.lrpatch"))) { _, _ -> }
        assertTrue(result.isFailure)
        assertFalse(File(context.filesDir,"escape.lrpatch").exists())
    }
}
