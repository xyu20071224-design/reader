package com.linguareader.app.hotfix

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import com.linguareader.app.BuildConfig
import com.linguareader.shared.importer.ImportSupport
import com.linguareader.shared.update.NativePatchInfo
import com.tencent.tinker.lib.service.PatchResult
import com.tencent.tinker.lib.tinker.Tinker
import com.tencent.tinker.loader.shareutil.ShareConstants
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil
import com.tencent.tinker.loader.shareutil.ShareSecurityCheck
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Native code is merged by Tinker and loaded before application classes on the next process start. */
class NativeHotfixRuntime(context: Context) {
    private val app = context.applicationContext
    private val status = statusFile(app)

    data class Snapshot(
        val highestRevision: Int,
        val loadedRevision: Int,
        val restartRequired: Boolean,
        val rolledBack: Boolean = false,
        val failed: Boolean = false
    )

    fun snapshot(): Snapshot {
        val s = read(status)
        val tinker = if (Tinker.isTinkerInstalled()) Tinker.with(app) else null
        val loaded = if (tinker?.isTinkerLoaded == true) tinker.tinkerLoadResultIfPresent else null
        // The engine's signed metadata is authoritative, even if the UI/result process was interrupted.
        val revision = loaded?.getPackageConfigByName("PATCH_REVISION")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val highest = maxOf(s.optInt("highest"), revision)
        if (highest > s.optInt("highest")) write(status, s.put("highest", highest))
        // A merged patch can load even if Android killed the result callback before it persisted ready.
        if (revision > 0 && s.optString("phase") == "applying" && loaded?.currentVersion == s.optString("md5")) {
            write(status, s.put("phase", "ready"))
        }
        if (s.optString("phase") == "ready" && s.optInt("pid") != Process.myPid() &&
            loaded?.currentVersion != s.optString("md5") && tinker != null) {
            tinker.cleanPatch()
            write(status, s.put("phase", "failed"))
            return Snapshot(highest, revision, revision > 0, rolledBack = revision > 0, failed = true)
        }
        val rolledBack = s.optString("phase") == "rolled_back"
        val restart = (s.optString("phase") == "ready" && loaded?.currentVersion != s.optString("md5")) ||
            (rolledBack && revision > 0)
        return Snapshot(highest, revision, restart, rolledBack,
            s.optString("phase") in setOf("failed", "corrupt"))
    }

    /** Hash is transport validation; Tinker verifies every signed package entry against the installed APK certificate. */
    suspend fun apply(file: File, info: NativePatchInfo) = withContext(Dispatchers.IO) {
        require(info.baseId == BuildConfig.HOTFIX_BASE_ID && info.baseVersionCode == BuildConfig.VERSION_CODE)
        require(info.revision > read(status).optInt("highest")) { "Patch revision was already applied or rolled back" }
        require(file.length() == info.bytes && ImportSupport.sha256(file) == info.sha256) { "Patch hash mismatch" }
        com.linguareader.shared.update.NativePatchArchive.validate(file)
        val security = ShareSecurityCheck(app)
        val code = ShareTinkerInternals.checkTinkerPackage(app, ShareConstants.TINKER_DEX_MASK, file, security)
        require(code == ShareConstants.ERROR_PACKAGE_CHECK_OK) { "Patch signature/base validation failed: $code" }
        val properties = requireNotNull(security.packagePropertiesIfPresent)
        require(properties["BASE_APK_SHA256"] == ImportSupport.sha256(File(app.applicationInfo.sourceDir))) {
            "Patch was not built against this exact installed base APK"
        }
        require(properties["PATCH_REVISION"] == info.revision.toString() &&
            properties["BASE_VERSION_CODE"] == BuildConfig.VERSION_CODE.toString()) { "Signed patch metadata mismatch" }
        check(Tinker.isTinkerInstalled()) { "Hotfix engine unavailable" }
        check(!com.tencent.tinker.lib.util.TinkerServiceInternals.isTinkerPatchServiceRunning(app)) {
            "Another patch is still processing"
        }
        val md5 = requireNotNull(SharePatchFileUtil.getMD5(file))
        val s = read(status)
        s.put("phase", "applying").put("revision", info.revision)
            .put("md5", md5).put("path", file.absolutePath).put("pid", Process.myPid())
        write(status, s)
        val accepted = Tinker.with(app).patchListener.onPatchReceived(file.absolutePath)
        if (accepted != ShareConstants.ERROR_PATCH_OK) {
            write(status, s.put("phase", "failed"))
            throw IOException("Patch processing rejected: $accepted")
        }
        // The result service persists completion atomically; no cross-process SharedPreferences cache.
        repeat(400) {
            delay(300)
            val current = read(status)
            if (current.optString("md5") == md5) {
                when (current.optString("phase")) {
                    "ready" -> return@withContext
                    "failed" -> throw IOException("Patch merge failed; base application remains available")
                }
            }
        }
        throw IOException("Patch processing still in progress; check again after reopening the app")
    }

    fun rollback() {
        val s = read(status)
        check(s.optString("phase") != "applying")
        if (Tinker.isTinkerInstalled()) Tinker.with(app).cleanPatch()
        write(status, s.put("phase", "rolled_back").put("revision", 0).put("md5", ""))
    }

    companion object {
        private fun statusFile(context: Context): AtomicFile {
            val root = File(context.filesDir, "hotfix/${BuildConfig.HOTFIX_BASE_ID}").apply { mkdirs() }
            return AtomicFile(File(root, "status.json"))
        }
        private fun read(file: AtomicFile): JSONObject = try {
            file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: java.io.FileNotFoundException) { JSONObject() }
        catch (_: Exception) {
            // Corrupt local anti-rollback state must not crash startup or silently permit old code again.
            JSONObject().put("highest", Int.MAX_VALUE).put("phase", "corrupt")
        }

        private fun write(file: AtomicFile, value: JSONObject) {
            val stream = file.startWrite()
            try {
                stream.write(value.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Throwable) { file.failWrite(stream); throw e }
        }

        @JvmStatic fun finish(context: Context, result: PatchResult) {
            val file = statusFile(context)
            val s = read(file)
            if (s.optString("phase") != "applying" || s.optString("path") != result.rawPatchFilePath) return
            val success = result.isSuccess && s.optString("md5") == result.patchVersion
            if (success) s.put("highest", maxOf(s.optInt("highest"), s.optInt("revision")))
            write(file, s.put("phase", if (success) "ready" else "failed"))
        }
    }
}
