package com.linguareader.shared.ui.skin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 往返。要求是**逐字段相等**（data class 的 equals 就是全部字段），
 * 不是「看起来差不多」。
 */
class SkinJsonTest {

    private fun assertRoundTrip(spec: SkinSpec) {
        val json = spec.toJson()
        val back = SkinSpec.fromJson(json)
        assertEquals("id", spec.id, back.id)
        assertEquals("name", spec.name, back.name)
        assertEquals("isDark", spec.isDark, back.isDark)
        assertEquals("colors", spec.colors, back.colors)
        assertEquals("typography", spec.typography, back.typography)
        assertEquals("spacing", spec.spacing, back.spacing)
        assertEquals("radii", spec.radii, back.radii)
        assertEquals("motion", spec.motion, back.motion)
        assertEquals("elevation", spec.elevation, back.elevation)
        assertEquals("origin", spec.origin, back.origin)
        assertEquals("whole spec", spec, back)
    }

    @Test
    fun builtInsRoundTrip() {
        SkinBuiltIns.all.forEach(::assertRoundTrip)
    }

    @Test
    fun imageDerivedSkinRoundTrips() {
        val spec = ImageSkinBuilder.fromImage(
            TestImages.synthetic(160, 120) { x, _ -> if (x < 80) 0xFF1B2A41.toInt() else 0xFFD9C7A8.toInt() },
            160, 120,
            name = "测试图"
        )
        assertRoundTrip(spec)
    }

    @Test
    fun jsonContainsHumanReadableHex() {
        val json = SkinBuiltIns.light.toJson()
        assertTrue("paper hex should be readable in raw JSON", json.contains("#F7F3EA"))
        assertTrue("schema version must be present", json.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun alphaColorsSurviveRoundTrip() {
        // scrim 是带 alpha 的：序列化成 #AARRGGBB 再读回来必须一模一样。
        val spec = SkinBuiltIns.dark
        assertEquals(0x99000000L, spec.colors.scrim)
        assertRoundTrip(spec)
        val json = spec.toJson()
        assertTrue("scrim hex should keep alpha", json.contains("#99000000"))
    }

    // ── 宽松解析：手写 JSON 只写想改的那几项 ─────────────────────────

    @Test
    fun partialJsonFallsBackToDefaults() {
        val spec = SkinSpec.fromJson(
            """
            {
              "id": "tiny",
              "name": "极简",
              "isDark": false,
              "colors": { "paper": "#FFFDF5" }
            }
            """.trimIndent()
        )
        assertEquals("tiny", spec.id)
        assertEquals(0xFFFFFDF5L, spec.colors.paper)
        // 没写的颜色回退到同明暗阵营的基色板。
        assertEquals(SkinBuiltIns.light.colors.ink, spec.colors.ink)
        // 没写的块回退默认。
        assertEquals(SkinTypography.default(), spec.typography)
        assertEquals(SkinSpacing.default(), spec.spacing)
        assertEquals(SkinRadii.default(), spec.radii)
        assertEquals(SkinMotion.default(), spec.motion)
        assertEquals(SkinElevation.default(), spec.elevation)
    }

    @Test
    fun partialJsonUsesDarkFallbackWhenIsDark() {
        val spec = SkinSpec.fromJson(
            """{ "id": "d", "isDark": true, "colors": {} }"""
        )
        assertEquals(SkinBuiltIns.dark.colors.ink, spec.colors.ink)
        assertEquals(SkinBuiltIns.dark.colors.paper, spec.colors.paper)
    }

    @Test
    fun typographyCanBeOverriddenPartially() {
        val spec = SkinSpec.fromJson(
            """
            {
              "id": "big",
              "colors": { "paper": "#FFFFFF" },
              "typography": { "title": { "sizeSp": 24.0, "weight": 700 } }
            }
            """.trimIndent()
        )
        assertEquals(24.0, spec.typography.titleSizeSp, 1e-9)
        assertEquals(700, spec.typography.titleWeight)
        // 同一个角色里没写的字段保留默认。
        assertEquals(SkinTypography.default().titleLineHeight, spec.typography.titleLineHeight, 1e-9)
        // 其他角色完全默认。
        assertEquals(SkinTypography.default().bodySizeSp, spec.typography.bodySizeSp, 1e-9)
    }

    @Test
    fun spacingRadiiMotionElevationDecode() {
        val spec = SkinSpec.fromJson(
            """
            {
              "id": "compact",
              "colors": { "paper": "#FFFFFF" },
              "spacing": { "m": 12.0, "gutter": 16.0 },
              "radii": { "md": 8.0 },
              "motion": { "fastMillis": 90, "easing": "decelerate" },
              "elevation": { "useShadow": true, "low": 2.0 }
            }
            """.trimIndent()
        )
        assertEquals(12.0, spec.spacing.m, 1e-9)
        assertEquals(16.0, spec.spacing.gutter, 1e-9)
        assertEquals(SkinSpacing.default().xs, spec.spacing.xs, 1e-9)
        assertEquals(8.0, spec.radii.md, 1e-9)
        assertEquals(90, spec.motion.fastMillis)
        assertEquals(SkinMotion.EASE_DECELERATE, spec.motion.easing)
        assertTrue(spec.elevation.useShadow)
        assertEquals(2.0, spec.elevation.low, 1e-9)
    }

    // ── 严格解析：颜色写错必须报错，不能静默吞 ───────────────────────

    @Test
    fun invalidColorThrows() {
        val error = runCatching {
            SkinSpec.fromJson("""{ "id": "bad", "colors": { "paper": "not-a-color" } }""")
        }.exceptionOrNull()
        assertNotNull("expected SkinParseException", error)
        assertTrue("expected SkinParseException, got " + error, error is SkinParseException)
        assertTrue("message should name the field", error!!.message!!.contains("paper"))
    }

    @Test
    fun invalidJsonThrows() {
        val error = runCatching { SkinSpec.fromJson("{ this is not json") }.exceptionOrNull()
        assertTrue("expected SkinParseException, got " + error, error is SkinParseException)
    }

    @Test
    fun originDefaultsToManualAndDecodesExplicitly() {
        val manual = SkinSpec.fromJson("""{ "id": "m", "colors": { "paper": "#FFFFFF" } }""")
        assertEquals(SkinOrigin.MANUAL, manual.origin)

        val image = SkinSpec.fromJson(
            """{ "id": "i", "colors": { "paper": "#FFFFFF" }, "origin": "image" }"""
        )
        assertEquals(SkinOrigin.IMAGE, image.origin)
    }

    @Test
    fun unknownOriginWireFallsBackToManual() {
        val spec = SkinSpec.fromJson(
            """{ "id": "x", "colors": { "paper": "#FFFFFF" }, "origin": "wat" }"""
        )
        assertEquals(SkinOrigin.MANUAL, spec.origin)
    }

    @Test
    fun decodeFromJsonObjectDirectly() {
        val obj = JSONObject().apply {
            put("id", "obj")
            put("isDark", false)
            put("colors", JSONObject().put("paper", "#010203"))
        }
        val spec = SkinSpec.fromJson(obj)
        assertEquals("obj", spec.id)
        assertEquals(0xFF010203L, spec.colors.paper)
    }

    @Test
    fun missingIdAndNameGetStableDefaults() {
        val spec = SkinSpec.fromJson("""{ "colors": { "paper": "#FFFFFF" } }""")
        assertTrue(spec.id.isNotBlank())
        assertTrue(spec.name.isNotBlank())
    }

    @Test
    fun unknownKeysAreIgnored() {
        // 前向兼容：将来加字段时，老版本代码读新 JSON 不能炸。
        val spec = SkinSpec.fromJson(
            """{ "id": "fwd", "colors": { "paper": "#FFFFFF" }, "futureField": 42 }"""
        )
        assertEquals("fwd", spec.id)
        assertNull(null)
    }

    @Test
    fun encodedJsonIsValidJsonAndStable() {
        val a = SkinBuiltIns.dark.toJson()
        val b = SkinBuiltIns.dark.toJson()
        assertEquals("encoding must be deterministic", a, b)
        assertNotNull(JSONObject(a))
        assertFalse(a.isBlank())
    }
}
