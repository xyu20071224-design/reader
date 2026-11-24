package com.linguareader.shared.update

import org.json.JSONObject

/** A publisher-generated snapshot bound to both real release boundaries and an exact APK build. */
data class PublishedReleaseNotes(
    val releaseId: String,
    val releaseTag: String,
    val versionName: String,
    val versionCode: Int,
    val sourceCommit: String,
    val previousReleaseTag: String,
    val previousReleaseCommit: String,
    val publishedAt: String,
    val notes: String
) {
    fun matches(build: BuildReleaseIdentity): Boolean = !build.sourceDirty &&
        build.versionName == versionName && build.versionCode == versionCode &&
        build.sourceCommit == sourceCommit

    fun entry() = ReleaseHistoryEntry(releaseId, releaseTag, notes, publishedAt, sourceCommit)

    companion object {
        fun parse(body: String): PublishedReleaseNotes {
            val root = JSONObject(body)
            require(root.get("schemaVersion").toString() == "1")
            require(root.getString("repository") == ReleaseUrls.REPOSITORY)
            val version = root.getString("versionName")
            val tag = root.getString("releaseTag")
            require(tag.removePrefix("v").removePrefix("V") == version)
            val current = requireNotNull(ReleaseVersion.parse(version))
            val previousTag = root.getString("previousReleaseTag")
            require(requireNotNull(ReleaseVersion.parse(previousTag)) < current)
            val commit = root.getString("sourceCommit")
            val previousCommit = root.getString("previousReleaseCommit")
            require(commit.matches(Regex("[0-9a-f]{40}")) && previousCommit.matches(Regex("[0-9a-f]{40}")))
            val code = root.getInt("versionCode").also { require(it > 0) }
            val published = root.getString("publishedAt").also { require(it.isNotBlank()) }
            return PublishedReleaseNotes(root.getString("releaseId"), tag, version, code, commit,
                previousTag, previousCommit, published, root.getString("notes"))
        }
    }
}
