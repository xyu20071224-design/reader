package com.linguareader.app.update

import android.content.Context
import com.linguareader.shared.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class AppReleaseHistoryView(
    val installation: InstallationHistory,
    val currentNotes: PublishedReleaseNotes?,
    val history: ReleaseHistorySnapshot,
    val upgrade: ReleaseHistoryRange,
    val publishedEntries: List<ReleaseHistoryEntry>,
    val unread: Boolean,
    val nativePatchRevision: Int
)

/** Local observation is separate from the user's read marker and from all explicit network refreshes. */
class AppReleaseHistoryRepository(
    context: Context,
    updateRepository: AppUpdateRepository,
    private val build: BuildReleaseIdentity
) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("release_history", Context.MODE_PRIVATE)
    private val history = ReleaseHistoryRepository(File(this.context.filesDir, "release-history"),
        updateRepository::currentSource, updateRepository::sourceGeneration)

    fun loadLocal(nativePatchRevision: Int = 0): AppReleaseHistoryView =
        view(history.loadLocal(), nativePatchRevision, observeInstallation())

    suspend fun refresh(nativePatchRevision: Int = 0): AppReleaseHistoryView = withContext(Dispatchers.IO) {
        view(history.refresh(), nativePatchRevision, currentInstallation())
    }

    fun markRead(nativePatchRevision: Int = 0): AppReleaseHistoryView {
        preferences.edit().putString("read_build", readKey(nativePatchRevision)).apply()
        return view(history.loadLocal(), nativePatchRevision, currentInstallation())
    }

    fun invalidate() = history.invalidate()

    private fun view(snapshot: ReleaseHistorySnapshot, revision: Int, installation: InstallationHistory): AppReleaseHistoryView {
        val bundled = loadPublishedArchive()
        val current = bundled.singleOrNull { it.matches(build) }
        val cachedTags = snapshot.entries.mapNotNull { ReleaseVersion.parse(it.tag)?.toString() }.toSet()
        val archive = bundled.filter { ReleaseVersion.parse(it.releaseTag)?.toString() !in cachedTags }.map { it.entry() }
        val (entries, _) = ReleaseHistoryPolicy.normalize(snapshot.entries + archive)
        val rangeSnapshot = snapshot.copy(entries = entries)
        return AppReleaseHistoryView(installation, current, snapshot,
            ReleaseHistoryPolicy.upgrade(rangeSnapshot, installation), entries,
            preferences.getString("read_build", null) != readKey(revision), revision.coerceAtLeast(0))
    }

    private fun readKey(revision: Int) = "${build.key}|patch=${revision.coerceAtLeast(0)}"

    private fun storedInstallation(): InstallationHistory? = runCatching {
            val root = JSONObject(requireNotNull(preferences.getString("installation", null)))
            val identity = BuildReleaseIdentity(root.getString("versionName"), root.getInt("versionCode"),
                root.optString("sourceCommit").takeIf { it.isNotEmpty() }, root.optBoolean("sourceDirty"))
            InstallationHistory(identity, root.optString("previousVersion").takeIf { it.isNotEmpty() },
                InstallationChange.valueOf(root.getString("change")))
        }.getOrNull()

    private fun currentInstallation(): InstallationHistory =
        storedInstallation()?.takeIf { it.current == build } ?: InstallationHistory(build)

    private fun observeInstallation(): InstallationHistory {
        val previous = storedInstallation()
        val observed = InstallationHistoryPolicy.observe(previous, build)
        if (observed != previous) {
            val root = JSONObject().apply {
                put("versionName", build.versionName); put("versionCode", build.versionCode)
                put("sourceCommit", build.sourceCommit.orEmpty()); put("sourceDirty", build.sourceDirty)
                put("previousVersion", observed.previousVersion.orEmpty()); put("change", observed.change.name)
            }
            preferences.edit().putString("installation", root.toString()).apply()
        }
        return observed
    }

    private fun loadPublishedArchive(): List<PublishedReleaseNotes> = runCatching {
        context.assets.list("release-notes").orEmpty().filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                context.assets.open("release-notes/$name").use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BUNDLE_BYTES)
                        output.write(buffer, 0, count)
                    }
                    PublishedReleaseNotes.parse(output.toString("UTF-8"))
                }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object { private const val MAX_BUNDLE_BYTES = 1024 * 1024 }
}
