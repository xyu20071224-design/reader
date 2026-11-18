package com.linguareader.shared.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePatchCatalogTest {
    private val url = "https://github.com/xyu20071224-design/reader/releases/download/v2.3.0/fix.lrpatch"
    private fun entry(rev: Int = 1, base: String = "reader-25", code: Int = 25) = JSONObject()
        .put("baseId", base).put("baseVersionCode", code).put("revision", rev)
        .put("assetName", "fix.lrpatch").put("sha256", "a".repeat(64)).put("bytes", 123)
    private fun release(url: String = this.url, bytes: Int = 123) = JSONObject().put("assets", JSONArray()
        .put(JSONObject().put("name", "fix.lrpatch").put("browser_download_url", url).put("size", bytes))).toString()
    private fun select(vararg patches: JSONObject, highest: Int = 0, source: String = release()) =
        NativePatchCatalog.select(JSONObject().put("schemaVersion", 1).put("patches", JSONArray(patches.toList())).toString(),
            source, "reader-25", 25, highest)

    @Test fun selectsNewestExactBaseAndIgnoresOtherBase() {
        assertEquals(2, select(entry(), entry(2), entry(3, "other"), entry(4, code = 26))!!.revision)
    }
    @Test fun rollbackHighWaterPreventsReofferOrDowngrade() {
        assertNull(select(entry(), entry(2), highest = 2))
        assertEquals(3, select(entry(3), highest = 2)!!.revision)
    }
    @Test fun rejectsUntrustedAssetSource() {
        for (url in listOf("http://github.com/xyu20071224-design/reader/releases/download/v/x", "https://evil.test/x",
            "https://github.com/other/repo/releases/download/v/x", "https://github.com/xyu20071224-design/reader/releases/download/../x")) {
            assertThrows(IllegalArgumentException::class.java) { select(entry(), source = release(url)) }
        }
    }
    @Test fun rejectsMalformedHashFilenameAndSize() {
        for (p in listOf(entry().put("sha256", "bad"), entry().put("assetName", "../fix.lrpatch"),
            entry().put("bytes", 124), entry().put("revision", -1))) {
            assertThrows(IllegalArgumentException::class.java) { select(p) }
        }
    }
    @Test fun noCompatiblePatchDoesNotClaimUpdate() { assertNull(select(entry(base = "old"))) }
}
