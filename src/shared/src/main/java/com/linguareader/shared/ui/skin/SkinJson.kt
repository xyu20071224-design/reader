package com.linguareader.shared.ui.skin

import org.json.JSONException
import org.json.JSONObject

/**
 * [SkinSpec] 与 JSON 的往返。手写实现而不是引入 kotlinx-serialization：
 * `:shared` 的硬规则是「不新增运行时依赖」，而这份 schema 只有 9 个块、字段稳定，
 * 手写的成本远低于给整条桌面线钉一个序列化插件。
 *
 * 解析策略：**分区宽松、字段严格**
 * - 缺整个 `typography`/`spacing`/`radii`/`motion`/`elevation` 块 → 取该块默认值；
 * - 缺某个颜色 → 取基色板（按 `isDark` 选 PaperLight / PaperDark）的同名值；
 * - 颜色字面量认不出 → 抛 [SkinParseException]（静默回退会让用户以为写对了）。
 *
 * 这样手写 JSON 可以只写「想改的那几项」，而写错的值不会被悄悄吞掉。
 */
internal object SkinJson {

    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DARK = "isDark"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_COLORS = "colors"
    private const val KEY_TYPOGRAPHY = "typography"
    private const val KEY_SPACING = "spacing"
    private const val KEY_RADII = "radii"
    private const val KEY_MOTION = "motion"
    private const val KEY_ELEVATION = "elevation"

    fun encode(spec: SkinSpec): String {
        val root = JSONObject()
        root.put(KEY_SCHEMA, SkinSpec.SCHEMA_VERSION)
        root.put(KEY_ID, spec.id)
        root.put(KEY_NAME, spec.name)
        root.put(KEY_DARK, spec.isDark)
        root.put(KEY_ORIGIN, spec.origin.wire)
        root.put(KEY_COLORS, encodeColors(spec.colors))
        root.put(KEY_TYPOGRAPHY, encodeTypography(spec.typography))
        root.put(KEY_SPACING, encodeSpacing(spec.spacing))
        root.put(KEY_RADII, encodeRadii(spec.radii))
        root.put(KEY_MOTION, encodeMotion(spec.motion))
        root.put(KEY_ELEVATION, encodeElevation(spec.elevation))
        return root.toString(2)
    }

    fun decode(json: String): SkinSpec {
        val obj = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw SkinParseException("不是合法的 JSON 对象：" + e.message, e)
        }
        return decode(obj)
    }

    fun decode(obj: JSONObject): SkinSpec {
        val isDark = obj.optBoolean(KEY_DARK, false)
        val fallback = if (isDark) SkinBuiltIns.dark.colorFallback else SkinBuiltIns.light.colorFallback
        val colorsObj = obj.optJSONObject(KEY_COLORS) ?: JSONObject()
        return SkinSpec(
            id = obj.optString(KEY_ID).ifBlank { "custom" },
            name = obj.optString(KEY_NAME).ifBlank { "自定义皮肤" },
            isDark = isDark,
            colors = decodeColors(colorsObj, fallback),
            typography = obj.optJSONObject(KEY_TYPOGRAPHY)?.let(::decodeTypography)
                ?: SkinTypography.default(),
            spacing = obj.optJSONObject(KEY_SPACING)?.let(::decodeSpacing) ?: SkinSpacing.default(),
            radii = obj.optJSONObject(KEY_RADII)?.let(::decodeRadii) ?: SkinRadii.default(),
            motion = obj.optJSONObject(KEY_MOTION)?.let(::decodeMotion) ?: SkinMotion.default(),
            elevation = obj.optJSONObject(KEY_ELEVATION)?.let(::decodeElevation)
                ?: SkinElevation.default(),
            origin = SkinOrigin.fromWire(obj.optString(KEY_ORIGIN))
        )
    }

    // ── colors ────────────────────────────────────────────────────────

    private fun encodeColors(c: SkinColors): JSONObject = JSONObject().apply {
        put("paper", SkinColorMath.toHex(c.paper))
        put("paperDeep", SkinColorMath.toHex(c.paperDeep))
        put("cardSurface", SkinColorMath.toHex(c.cardSurface))
        put("ink", SkinColorMath.toHex(c.ink))
        put("inkSoft", SkinColorMath.toHex(c.inkSoft))
        put("inkFaint", SkinColorMath.toHex(c.inkFaint))
        put("accent", SkinColorMath.toHex(c.accent))
        put("accentDeep", SkinColorMath.toHex(c.accentDeep))
        put("accentSoft", SkinColorMath.toHex(c.accentSoft))
        put("onAccent", SkinColorMath.toHex(c.onAccent))
        put("gold", SkinColorMath.toHex(c.gold))
        put("goldOnPaper", SkinColorMath.toHex(c.goldOnPaper))
        put("success", SkinColorMath.toHex(c.success))
        put("successOn", SkinColorMath.toHex(c.successOn))
        put("danger", SkinColorMath.toHex(c.danger))
        put("dangerOn", SkinColorMath.toHex(c.dangerOn))
        put("warning", SkinColorMath.toHex(c.warning))
        put("outline", SkinColorMath.toHex(c.outline))
        put("scrim", SkinColorMath.toHex(c.scrim))
        put("bookCoverFallback", SkinColorMath.toHex(c.bookCoverFallback))
    }

    private fun decodeColors(obj: JSONObject, fallback: SkinColors): SkinColors {
        fun color(key: String, default: Long): Long {
            if (!obj.has(key)) return default
            val raw = obj.optString(key)
            return SkinColorMath.parseHex(raw)
                ?: throw SkinParseException("colors." + key + " 不是合法颜色：" + raw)
        }
        return SkinColors(
            paper = color("paper", fallback.paper),
            paperDeep = color("paperDeep", fallback.paperDeep),
            cardSurface = color("cardSurface", fallback.cardSurface),
            ink = color("ink", fallback.ink),
            inkSoft = color("inkSoft", fallback.inkSoft),
            inkFaint = color("inkFaint", fallback.inkFaint),
            accent = color("accent", fallback.accent),
            accentDeep = color("accentDeep", fallback.accentDeep),
            accentSoft = color("accentSoft", fallback.accentSoft),
            onAccent = color("onAccent", fallback.onAccent),
            gold = color("gold", fallback.gold),
            goldOnPaper = color("goldOnPaper", fallback.goldOnPaper),
            success = color("success", fallback.success),
            successOn = color("successOn", fallback.successOn),
            danger = color("danger", fallback.danger),
            dangerOn = color("dangerOn", fallback.dangerOn),
            warning = color("warning", fallback.warning),
            outline = color("outline", fallback.outline),
            scrim = color("scrim", fallback.scrim),
            bookCoverFallback = color("bookCoverFallback", fallback.bookCoverFallback)
        )
    }

    // ── typography ────────────────────────────────────────────────────

    private fun encodeTypography(t: SkinTypography): JSONObject = JSONObject().apply {
        put("display", encodeTypeRole(t.displaySizeSp, t.displayLineHeight, t.displayWeight, t.displayLetterSpacingSp, t.displayFamily))
        put("title", encodeTypeRole(t.titleSizeSp, t.titleLineHeight, t.titleWeight, t.titleLetterSpacingSp, t.titleFamily))
        put("body", encodeTypeRole(t.bodySizeSp, t.bodyLineHeight, t.bodyWeight, t.bodyLetterSpacingSp, t.bodyFamily))
        put("caption", encodeTypeRole(t.captionSizeSp, t.captionLineHeight, t.captionWeight, t.captionLetterSpacingSp, t.captionFamily))
        put("label", encodeTypeRole(t.labelSizeSp, t.labelLineHeight, t.labelWeight, t.labelLetterSpacingSp, t.labelFamily))
    }

    private fun encodeTypeRole(
        sizeSp: Double,
        lineHeight: Double,
        weight: Int,
        letterSpacingSp: Double,
        family: String
    ): JSONObject = JSONObject().apply {
        put("sizeSp", sizeSp)
        put("lineHeight", lineHeight)
        put("weight", weight)
        put("letterSpacingSp", letterSpacingSp)
        put("family", family)
    }

    private fun decodeTypography(obj: JSONObject): SkinTypography {
        val d = SkinTypography.default()
        val display = obj.optJSONObject("display")
        val title = obj.optJSONObject("title")
        val body = obj.optJSONObject("body")
        val caption = obj.optJSONObject("caption")
        val label = obj.optJSONObject("label")
        return SkinTypography(
            displaySizeSp = optDouble(display, "sizeSp", d.displaySizeSp),
            displayLineHeight = optDouble(display, "lineHeight", d.displayLineHeight),
            displayWeight = optInt(display, "weight", d.displayWeight),
            displayLetterSpacingSp = optDouble(display, "letterSpacingSp", d.displayLetterSpacingSp),
            displayFamily = optString(display, "family", d.displayFamily),
            titleSizeSp = optDouble(title, "sizeSp", d.titleSizeSp),
            titleLineHeight = optDouble(title, "lineHeight", d.titleLineHeight),
            titleWeight = optInt(title, "weight", d.titleWeight),
            titleLetterSpacingSp = optDouble(title, "letterSpacingSp", d.titleLetterSpacingSp),
            titleFamily = optString(title, "family", d.titleFamily),
            bodySizeSp = optDouble(body, "sizeSp", d.bodySizeSp),
            bodyLineHeight = optDouble(body, "lineHeight", d.bodyLineHeight),
            bodyWeight = optInt(body, "weight", d.bodyWeight),
            bodyLetterSpacingSp = optDouble(body, "letterSpacingSp", d.bodyLetterSpacingSp),
            bodyFamily = optString(body, "family", d.bodyFamily),
            captionSizeSp = optDouble(caption, "sizeSp", d.captionSizeSp),
            captionLineHeight = optDouble(caption, "lineHeight", d.captionLineHeight),
            captionWeight = optInt(caption, "weight", d.captionWeight),
            captionLetterSpacingSp = optDouble(caption, "letterSpacingSp", d.captionLetterSpacingSp),
            captionFamily = optString(caption, "family", d.captionFamily),
            labelSizeSp = optDouble(label, "sizeSp", d.labelSizeSp),
            labelLineHeight = optDouble(label, "lineHeight", d.labelLineHeight),
            labelWeight = optInt(label, "weight", d.labelWeight),
            labelLetterSpacingSp = optDouble(label, "letterSpacingSp", d.labelLetterSpacingSp),
            labelFamily = optString(label, "family", d.labelFamily)
        )
    }

    // ── spacing / radii / motion / elevation ─────────────────────────

    private fun encodeSpacing(s: SkinSpacing): JSONObject = JSONObject().apply {
        put("xs", s.xs); put("s", s.s); put("m", s.m)
        put("l", s.l); put("xl", s.xl); put("xxl", s.xxl); put("gutter", s.gutter)
    }

    private fun decodeSpacing(obj: JSONObject): SkinSpacing {
        val d = SkinSpacing.default()
        return SkinSpacing(
            xs = obj.optDouble("xs", d.xs),
            s = obj.optDouble("s", d.s),
            m = obj.optDouble("m", d.m),
            l = obj.optDouble("l", d.l),
            xl = obj.optDouble("xl", d.xl),
            xxl = obj.optDouble("xxl", d.xxl),
            gutter = obj.optDouble("gutter", d.gutter)
        )
    }

    private fun encodeRadii(r: SkinRadii): JSONObject = JSONObject().apply {
        put("sm", r.sm); put("md", r.md); put("lg", r.lg); put("pill", r.pill)
    }

    private fun decodeRadii(obj: JSONObject): SkinRadii {
        val d = SkinRadii.default()
        return SkinRadii(
            sm = obj.optDouble("sm", d.sm),
            md = obj.optDouble("md", d.md),
            lg = obj.optDouble("lg", d.lg),
            pill = obj.optDouble("pill", d.pill)
        )
    }

    private fun encodeMotion(m: SkinMotion): JSONObject = JSONObject().apply {
        put("fastMillis", m.fastMillis)
        put("normalMillis", m.normalMillis)
        put("slowMillis", m.slowMillis)
        put("easing", m.easing)
    }

    private fun decodeMotion(obj: JSONObject): SkinMotion {
        val d = SkinMotion.default()
        return SkinMotion(
            fastMillis = obj.optInt("fastMillis", d.fastMillis),
            normalMillis = obj.optInt("normalMillis", d.normalMillis),
            slowMillis = obj.optInt("slowMillis", d.slowMillis),
            easing = obj.optString("easing", d.easing)
        )
    }

    private fun encodeElevation(e: SkinElevation): JSONObject = JSONObject().apply {
        put("none", e.none)
        put("low", e.low)
        put("high", e.high)
        put("useShadow", e.useShadow)
    }

    private fun decodeElevation(obj: JSONObject): SkinElevation {
        val d = SkinElevation.default()
        return SkinElevation(
            none = obj.optDouble("none", d.none),
            low = obj.optDouble("low", d.low),
            high = obj.optDouble("high", d.high),
            useShadow = obj.optBoolean("useShadow", d.useShadow)
        )
    }

    // ── 取值助手：JSONObject.optDouble 对 null 会抛，先判空更直白 ──────

    private fun optDouble(obj: JSONObject?, key: String, default: Double): Double {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return default
        return obj.optDouble(key, default)
    }

    private fun optInt(obj: JSONObject?, key: String, default: Int): Int {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return default
        return obj.optInt(key, default)
    }

    private fun optString(obj: JSONObject?, key: String, default: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return default
        return obj.optString(key, default)
    }
}

/** 皮肤 JSON 解析失败。带上人话原因，设置页直接提示用户改哪一行。 */
class SkinParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
