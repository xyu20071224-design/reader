package com.linguareader.shared.update

import org.junit.Assert.*
import org.junit.Test

class PublishedReleaseNotesTest {
    private val commit = "a".repeat(40)
    private val body = """{"schemaVersion":1,"repository":"${ReleaseUrls.REPOSITORY}","releaseId":"r2",
        "releaseTag":"v2.0.0","versionName":"2.0.0","versionCode":2,"sourceCommit":"$commit",
        "previousReleaseTag":"v1.0.0","previousReleaseCommit":"${"b".repeat(40)}",
        "publishedAt":"2026-11-24T00:00:00Z","notes":"Published notes only"}"""

    @Test fun `only exact clean published build identity can claim current version notes`() {
        val notes = PublishedReleaseNotes.parse(body)
        val build = BuildReleaseIdentity("2.0.0", 2, commit)
        assertTrue(notes.matches(build))
        assertFalse(notes.matches(build.copy(sourceDirty = true)))
        assertFalse(notes.matches(build.copy(sourceCommit = null)))
        assertFalse(notes.matches(build.copy(sourceCommit = "c".repeat(40))))
        assertFalse(notes.matches(build.copy(versionCode = 3)))
        assertFalse(notes.matches(build.copy(versionName = "2.0.0-verify")))
    }

    @Test fun `wrong repository future schema and reversed boundaries are rejected`() {
        for (invalid in listOf(body.replace(ReleaseUrls.REPOSITORY, "other/repo"),
            body.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            body.replace("\"previousReleaseTag\":\"v1.0.0\"", "\"previousReleaseTag\":\"v3.0.0\""))) {
            assertTrue(runCatching { PublishedReleaseNotes.parse(invalid) }.isFailure)
        }
    }
}
