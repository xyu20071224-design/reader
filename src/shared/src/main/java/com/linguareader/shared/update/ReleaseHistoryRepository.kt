package com.linguareader.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Read-only local reads and explicit refresh are separate; the source owns all networking. */
class ReleaseHistoryRepository(
    private val directory: File,
    private val sourceProvider: () -> ReleaseSource,
    private val selectionVersion: () -> Long = { 0L },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val requests = AtomicLong()
    private val publicationLock = Any()
    /** Called after the temporary cache is durable, before the guarded publication boundary. */
    internal var beforeCachePublication: () -> Unit = {}

    fun loadLocal(): ReleaseHistorySnapshot {
        val source = runCatching { sourceProvider() }.getOrElse {
            return ReleaseHistorySnapshot("invalid-source", emptyList(), false, 0L, setOf(HistoryIssue.INVALID_SOURCE))
        }
        return readCache(source.id) ?: ReleaseHistorySnapshot(source.id, emptyList(), false, 0L,
            if (cacheFile(source.id).exists()) setOf(HistoryIssue.CACHE_INVALID) else emptySet())
    }

    fun invalidate() { synchronized(publicationLock) { requests.incrementAndGet() } }

    suspend fun refresh(): ReleaseHistorySnapshot = withContext(Dispatchers.IO) {
        val request = synchronized(publicationLock) { requests.incrementAndGet() }
        val selection = selectionVersion()
        val source = runCatching { sourceProvider() }.getOrElse {
            return@withContext ReleaseHistorySnapshot("invalid-source", emptyList(), false, 0L, setOf(HistoryIssue.INVALID_SOURCE))
        }
        val previous = readCache(source.id)
        val entries = mutableListOf<ReleaseHistoryEntry>()
        val issues = mutableSetOf<HistoryIssue>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var complete = false
        try {
            for (index in 0 until MAX_PAGES) {
                coroutineContext.ensureActive()
                val page = source.fetchPage(cursor)
                checkCurrent(source.id, selection, request)
                require(page.sourceId == source.id) { "History page belongs to another source" }
                entries += page.entries.filter { !it.draft && !it.prerelease }.map(ReleaseHistoryEntry::from)
                if (entries.size > MAX_RELEASES) { issues += HistoryIssue.PAGE_LIMIT; break }
                cursor = page.nextCursor
                if (cursor == null) { complete = true; break }
                if (!seen.add(cursor)) { issues += HistoryIssue.PAGINATION_CYCLE; break }
            }
            if (!complete && issues.isEmpty()) issues += HistoryIssue.PAGE_LIMIT
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SourceChanged) { issues += HistoryIssue.SOURCE_CHANGED }
        catch (_: Exception) { issues += HistoryIssue.FETCH_FAILED }
        if (issues.contains(HistoryIssue.SOURCE_CHANGED)) {
            return@withContext ReleaseHistorySnapshot(source.id, emptyList(), false, now(), issues)
        }
        try { checkCurrent(source.id, selection, request) }
        catch (_: SourceChanged) {
            return@withContext ReleaseHistorySnapshot(source.id, emptyList(), false, now(), setOf(HistoryIssue.SOURCE_CHANGED))
        }
        val merge = if (complete) entries else entries + previous?.entries.orEmpty()
        val (normalized, conflicts) = ReleaseHistoryPolicy.normalize(merge.take(MAX_RELEASES))
        var snapshot = ReleaseHistorySnapshot(source.id, normalized,
            complete && HistoryIssue.CONFLICTING_RELEASE !in conflicts,
            if (entries.isEmpty() && previous != null) previous.fetchedAtMillis else now(), issues + conflicts,
            usesCachedEntries = !complete && previous != null)
        // Incomplete refresh must not replace a previously complete offline archive.
        if (snapshot.complete) {
            coroutineContext.ensureActive()
            val context = coroutineContext
            try {
                writeCache(snapshot) { publish ->
                    beforeCachePublication()
                    synchronized(publicationLock) {
                        context.ensureActive()
                        checkCurrent(source.id, selection, request)
                        publish()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SourceChanged) {
                return@withContext ReleaseHistorySnapshot(source.id, emptyList(), false, now(), setOf(HistoryIssue.SOURCE_CHANGED))
            } catch (_: Exception) { snapshot = snapshot.copy(issues = snapshot.issues + HistoryIssue.CACHE_WRITE_FAILED) }
        }
        snapshot
    }

    private class SourceChanged : IllegalStateException()
    private fun checkCurrent(source: String, selection: Long, request: Long) {
        if (runCatching { sourceProvider().id }.getOrNull() != source || selectionVersion() != selection || requests.get() != request) throw SourceChanged()
    }

    internal fun cacheFile(source: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private fun readCache(source: String): ReleaseHistorySnapshot? = runCatching {
        val file = cacheFile(source)
        val readable = if (file.isFile) file else File(file.path + ".bak")
        require(readable.length() in 1..MAX_CACHE_BYTES)
        val root = JSONObject(readable.readText(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == 1 && root.getString("sourceId") == source)
        val array = root.getJSONArray("entries")
        require(array.length() <= MAX_RELEASES)
        val entries = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            ReleaseHistoryEntry(it.getString("id"), it.getString("tag"), it.getString("notes"),
                it.getString("publishedAt"), it.optString("targetCommitish"))
        } }
        val (normalized, issues) = ReleaseHistoryPolicy.normalize(entries)
        ReleaseHistorySnapshot(source, normalized, root.getBoolean("complete"), root.getLong("fetchedAtMillis"), issues,
            usesCachedEntries = true)
    }.getOrNull()

    private fun writeCache(snapshot: ReleaseHistorySnapshot, publication: (() -> Unit) -> Unit) {
        val root = JSONObject().apply {
            put("schemaVersion", 1); put("sourceId", snapshot.sourceId); put("complete", snapshot.complete)
            put("fetchedAtMillis", snapshot.fetchedAtMillis)
            put("entries", JSONArray().apply { snapshot.entries.forEach { entry -> put(JSONObject().apply {
                put("id", entry.id); put("tag", entry.tag); put("notes", entry.notes)
                put("publishedAt", entry.publishedAt); put("targetCommitish", entry.targetCommitish)
            }) } })
        }
        val text = root.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES)
        AtomicHistoryFile.write(cacheFile(snapshot.sourceId), text, publication)
    }

    companion object {
        private const val MAX_PAGES = 40
        private const val MAX_RELEASES = 1000
        private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
    }
}

/** File.renameTo keeps Android API 23 compatibility; a backup protects platforms that cannot replace. */
internal object AtomicHistoryFile {
    fun write(file: File, text: String, publication: (() -> Unit) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File.createTempFile("history-", ".tmp", file.parentFile)
        val backup = File(file.path + ".bak")
        try {
            temporary.outputStream().use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            publication {
                if (temporary.renameTo(file)) { backup.delete() }
                else {
                    check(!file.exists() || file.renameTo(backup)) { "Cannot preserve history cache" }
                    if (!temporary.renameTo(file)) { backup.renameTo(file); error("Cannot save history cache") }
                    backup.delete()
                }
            }
        } finally { temporary.delete() }
    }
}
