package com.linguareader.shared.update

/** Numeric release versions only. Unknown tags remain visible but cannot silently enter a range. */
class ReleaseVersion private constructor(private val parts: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val a = parts.getOrElse(i) { "0" }; val b = other.parts.getOrElse(i) { "0" }
            if (a.length != b.length) return a.length.compareTo(b.length)
            val result = a.compareTo(b)
            if (result != 0) return result
        }
        return 0
    }
    override fun toString(): String = parts.joinToString(".")
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val raw = value.trim().removePrefix("v").removePrefix("V")
            if (raw.isEmpty() || raw.length > 128) return null
            val parts = raw.split('.')
            if (parts.size > 16 || parts.any { it.isEmpty() || it.length > 16 || it.any { c -> c !in '0'..'9' } }) return null
            val normalized = parts.map { it.trimStart('0').ifEmpty { "0" } }.toMutableList()
            while (normalized.size > 1 && normalized.last() == "0") normalized.removeAt(normalized.lastIndex)
            return ReleaseVersion(normalized)
        }
    }
}

data class ReleaseHistoryEntry(
    val id: String,
    val tag: String,
    val notes: String,
    val publishedAt: String,
    val targetCommitish: String = ""
) {
    companion object {
        fun from(release: ReleaseRecord) = ReleaseHistoryEntry(release.id, release.tag, release.notes,
            release.publishedAt, release.targetCommitish)
    }
}

enum class HistoryIssue { FETCH_FAILED, INVALID_SOURCE, PAGE_LIMIT, PAGINATION_CYCLE, CONFLICTING_RELEASE, INVALID_VERSION,
    CACHE_INVALID, CACHE_WRITE_FAILED, SOURCE_CHANGED }

data class ReleaseHistorySnapshot(
    val sourceId: String,
    val entries: List<ReleaseHistoryEntry>,
    val complete: Boolean,
    val fetchedAtMillis: Long,
    val issues: Set<HistoryIssue> = emptySet(),
    val usesCachedEntries: Boolean = false
)

data class BuildReleaseIdentity(
    val versionName: String,
    val versionCode: Int,
    val sourceCommit: String? = null,
    val sourceDirty: Boolean = false
) {
    val key: String get() = "$versionName|$versionCode|${sourceCommit.orEmpty()}|$sourceDirty"
}

enum class InstallationChange { FIRST_OBSERVED, SAME_BUILD, UPGRADE, DOWNGRADE, REBUILD, UNKNOWN }

data class InstallationHistory(
    val current: BuildReleaseIdentity,
    val previousVersion: String? = null,
    val change: InstallationChange = InstallationChange.FIRST_OBSERVED
)

object InstallationHistoryPolicy {
    fun observe(previous: InstallationHistory?, build: BuildReleaseIdentity): InstallationHistory {
        if (previous == null) return InstallationHistory(build)
        if (previous.current == build) return previous
        val old = ReleaseVersion.parse(previous.current.versionName)
        val current = ReleaseVersion.parse(build.versionName)
        val change = when {
            old == null || current == null -> InstallationChange.UNKNOWN
            current > old -> InstallationChange.UPGRADE
            current < old -> InstallationChange.DOWNGRADE
            else -> InstallationChange.REBUILD
        }
        return InstallationHistory(build, previous.current.versionName, change)
    }
}

data class ReleaseHistoryRange(
    val entries: List<ReleaseHistoryEntry>,
    val complete: Boolean,
    val issues: Set<HistoryIssue>
)

object ReleaseHistoryPolicy {
    /** De-duplicate page boundaries, retaining evidence of edits/conflicts rather than hiding them. */
    fun normalize(entries: List<ReleaseHistoryEntry>): Pair<List<ReleaseHistoryEntry>, Set<HistoryIssue>> {
        val ids = linkedMapOf<String, ReleaseHistoryEntry>()
        val tags = mutableMapOf<String, String>()
        val issues = mutableSetOf<HistoryIssue>()
        for (entry in entries) {
            val existing = ids[entry.id]
            if (existing != null) {
                if (existing != entry) issues += HistoryIssue.CONFLICTING_RELEASE
                continue
            }
            val tag = ReleaseVersion.parse(entry.tag)?.toString() ?: entry.tag.also { issues += HistoryIssue.INVALID_VERSION }
            val existingId = tags[tag]
            if (existingId != null && existingId != entry.id) {
                issues += HistoryIssue.CONFLICTING_RELEASE
                continue
            }
            ids[entry.id] = entry
            tags[tag] = entry.id
        }
        val sorted = ids.values.sortedWith { a, b ->
            val av = ReleaseVersion.parse(a.tag); val bv = ReleaseVersion.parse(b.tag)
            when {
                av != null && bv != null -> bv.compareTo(av)
                av != null -> -1
                bv != null -> 1
                else -> b.publishedAt.compareTo(a.publishedAt)
            }
        }
        return sorted to issues
    }

    fun upgrade(snapshot: ReleaseHistorySnapshot, installation: InstallationHistory): ReleaseHistoryRange {
        val previous = installation.previousVersion?.let(ReleaseVersion::parse)
        val current = ReleaseVersion.parse(installation.current.versionName)
        if (installation.change != InstallationChange.UPGRADE || previous == null || current == null) {
            return ReleaseHistoryRange(emptyList(), snapshot.complete, snapshot.issues)
        }
        val (entries, conflicts) = normalize(snapshot.entries)
        val issues = snapshot.issues + conflicts
        return ReleaseHistoryRange(entries.filter {
            ReleaseVersion.parse(it.tag)?.let { version -> version > previous && version <= current } == true
        }, snapshot.complete && issues.none { it in setOf(HistoryIssue.INVALID_VERSION, HistoryIssue.CONFLICTING_RELEASE) }, issues)
    }
}
