package com.linguareader.shared.packs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemotePackCatalogTest {
    private val digest = "ab".repeat(32)
    private fun entry() = JSONObject().put("packId", "fixture").put("version", "1.0.0").put("type", "voice")
        .put("minAppVersion", 1).put("name", "fixture.lrpack").put("bytes", 12).put("sha256", digest)
        .put("urls", JSONArray().put("https://packs.example/fixture.lrpack"))
    private fun catalog(vararg rows: JSONObject) = JSONObject().put("formatVersion", 1).put("packs", JSONArray(rows.toList())).toString()

    @Test fun allFourTypesKeepExplicitIdentityAndCompatibilityFields() {
        for (type in PackType.entries) {
            val pack = RemotePackCatalog.parse(catalog(entry().put("type", type.wire)), 26).single()
            assertEquals(type, pack.type); assertEquals("fixture", pack.packId); assertEquals("1.0.0", pack.version); assertEquals(1, pack.minAppVersion)
        }
        assertTrue(RemotePackCatalog.parse(catalog(entry().put("minAppVersion", 27)), 26).isEmpty())
        assertEquals(digest, RemotePackCatalog.parse(catalog(entry().put("sha256", digest.uppercase())), 26).single().sha256)
    }

    @Test fun sourceSizeLimitsFollowTheExistingPackTypeContract() {
        for (type in PackType.entries) {
            val maximum = PackLimits.of(type).maxSourceBytes
            assertEquals(maximum, RemotePackCatalog.parse(catalog(entry().put("type", type.wire).put("bytes", maximum)), 26).single().bytes)
            assertTrue(runCatching { RemotePackCatalog.parse(catalog(entry().put("type", type.wire).put("bytes", maximum + 1)), 26) }.isFailure)
        }
    }

    @Test fun invalidScalarTypesMissingIntegrityAndNumericOverflowFailClosed() {
        for (bad in listOf(entry().put("sha256", JSONObject.NULL), entry().put("sha256", ""), entry().put("sha256", "z".repeat(64)),
            entry().put("packId", 3), entry().put("type", "plugin"), entry().put("version", 1),
            entry().put("bytes", Long.MAX_VALUE), entry().put("bytes", -1), entry().put("minAppVersion", 2147483648L),
            entry().put("urls", JSONArray().put(12)), entry().put("urls", JSONArray()),
            entry().put("urls", JSONArray((0..5).map { "https://p$it.example/x" })))) {
            assertTrue("Accepted $bad", runCatching { RemotePackCatalog.parse(catalog(bad), 26) }.isFailure)
        }
        for (version in listOf<Any>(0, 2, "1", 1.5)) {
            assertTrue(runCatching { RemotePackCatalog.parse(JSONObject(catalog(entry())).put("formatVersion", version).toString(), 26) }.isFailure)
        }
    }

    @Test fun unsafeHttpsUrlsAreRejectedBeforeAnyConnection() {
        for (url in listOf("http://packs.example/x", "/relative", "https://user:pass@packs.example/x",
            "https://packs.example/x#fragment", "https://packs.example:0/x", "https://packs.example:65536/x", "https:///missing")) {
            assertTrue("Accepted $url", runCatching { RemotePackCatalog.validateUrl(url) }.isFailure)
        }
    }

    @Test fun duplicatesAndOversizedCatalogsAreRejectedBeforeFiltering() {
        assertTrue(runCatching { RemotePackCatalog.parse(catalog(entry(), entry().put("name", "other.lrpack")), 26) }.isFailure)
        assertTrue(runCatching { RemotePackCatalog.parse(catalog(entry(), entry().put("packId", "other").put("name", "FIXTURE.lrpack").put("minAppVersion", 99)), 26) }.isFailure)
        assertTrue(runCatching { RemotePackCatalog.parse(" ".repeat(RemotePackCatalog.MAX_CATALOG_BYTES + 1), 26) }.isFailure)
        assertTrue(runCatching { RemotePackCatalog.parse(catalog(*(0..1000).map { entry().put("packId", "p$it").put("name", "p$it.lrpack") }.toTypedArray()), 26) }.isFailure)
    }
}
