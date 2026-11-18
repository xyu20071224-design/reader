package com.linguareader.app.hotfix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.BuildConfig
import com.linguareader.shared.update.NativePatchInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeHotfixDeviceTest {
    private fun hash(file: File) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun prepareSignedNativePatchWithoutInstallingAnApk() = runBlocking {
        val patch = File(context.getExternalFilesDir(null), "fixture.lrpatch")
        assertTrue("Push the signed fixture to the verify app before running", patch.isFile)
        val info = NativePatchInfo(BuildConfig.HOTFIX_BASE_ID, BuildConfig.VERSION_CODE, 1,
            "fixture.lrpatch", "https://github.com/xyu20071224-design/reader/releases/download/test/fixture.lrpatch",
            hash(patch), patch.length(), "Device proof")
        NativeHotfixRuntime(context).apply(patch, info)
        assertTrue(NativeHotfixRuntime(context).snapshot().restartRequired)
    }
    private suspend fun rejectedFixture(name: String, message: String, revision: Int = 2) {
        val patch = File(context.getExternalFilesDir(null), name)
        assertTrue(patch.isFile)
        val info = NativePatchInfo(BuildConfig.HOTFIX_BASE_ID, BuildConfig.VERSION_CODE, revision,
            name, "https://github.com/xyu20071224-design/reader/releases/download/test/$name",
            hash(patch), patch.length(), "")
        try { NativeHotfixRuntime(context).apply(patch, info); fail("Invalid patch accepted: $name") }
        catch (e: IllegalArgumentException) { assertTrue(e.message, e.message.orEmpty().contains(message)) }
    }
    @Test fun validArchiveWithWrongCertificateIsRejected() = runBlocking {
        rejectedFixture("wrong-cert.lrpatch", "signature/base validation failed")
    }
    @Test fun validSignatureWithWrongBaseIsRejected() = runBlocking {
        rejectedFixture("wrong-base.lrpatch", "signature/base validation failed")
    }
    @Test fun unsignedDiscoveryCannotForgeSignedRevision() = runBlocking {
        rejectedFixture("wrong-revision.lrpatch", "Signed patch metadata mismatch")
    }
    @Test fun rolledBackRevisionCannotBeReapplied() = runBlocking {
        rejectedFixture("wrong-revision.lrpatch", "already applied or rolled back", revision = 1)
    }
    @Test fun interruptedResultCallbackRecoversFromLoadedSignedMetadata() {
        val type = context.classLoader.loadClass("com.linguareader.app.hotfix.NativeHotfixRuntime")
        val runtime = type.getConstructor(android.content.Context::class.java).newInstance(context)
        val status = File(context.filesDir, "hotfix/${BuildConfig.HOTFIX_BASE_ID}/status.json")
        val pending = org.json.JSONObject(status.readText()).put("phase", "applying").put("highest", 0)
        val atomic = android.util.AtomicFile(status)
        val stream = atomic.startWrite()
        stream.write(pending.toString().toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
        val snapshot = type.getMethod("snapshot").invoke(runtime)
        assertEquals(1, snapshot.javaClass.getMethod("getLoadedRevision").invoke(snapshot))
        assertEquals(1, snapshot.javaClass.getMethod("getHighestRevision").invoke(snapshot))
        assertEquals(false, snapshot.javaClass.getMethod("getRestartRequired").invoke(snapshot))
        assertEquals("ready", org.json.JSONObject(status.readText()).getString("phase"))
    }
    @Test fun rollbackRetainsRevisionHighWaterAndRestoresBaseOnReopen() {
        // Instrumentation's parent loader can predate injection; exercise the actual application's loader.
        val type = context.classLoader.loadClass("com.linguareader.app.hotfix.NativeHotfixRuntime")
        val runtime = type.getConstructor(android.content.Context::class.java).newInstance(context)
        type.getMethod("rollback").invoke(runtime)
        val snapshot = type.getMethod("snapshot").invoke(runtime)
        assertEquals(1, snapshot.javaClass.getMethod("getHighestRevision").invoke(snapshot))
        // Removing files cannot unload already running Kotlin; reopening is required by the UI contract.
        assertEquals(1, snapshot.javaClass.getMethod("getLoadedRevision").invoke(snapshot))
        assertEquals(true, snapshot.javaClass.getMethod("getRestartRequired").invoke(snapshot))
    }
    @Test fun tamperedPatchCannotBecomeActive() = runBlocking {
        val patch = File(context.cacheDir, "tampered.lrpatch").apply { writeText("not a signed patch") }
        val info = NativePatchInfo(BuildConfig.HOTFIX_BASE_ID, BuildConfig.VERSION_CODE, 2,
            patch.name, "https://github.com/xyu20071224-design/reader/releases/download/test/tampered.lrpatch",
            hash(patch), patch.length(), "")
        try { NativeHotfixRuntime(context).apply(patch, info); fail("Unsigned content accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
