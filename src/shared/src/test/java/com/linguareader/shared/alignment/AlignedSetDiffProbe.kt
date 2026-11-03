package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **两个引擎在同一批 35 条上的 ALIGNED 集合差集诊断**（Lead 要求）。
 *
 * 要回答两个问题：
 * 1. `ai-pair-v1` 判 UNCERTAIN 而 `independent-v1` 判 ALIGNED 的句子有几条？内容见证值多少？
 * 2. 若这些句子 `vLexical` 确实很低，说明 `independent-v1` 靠**别的通道**（锚点？）判的，
 *    而 `ai-pair-v1` 漏用了同样的通道 → **那是 bug，不是保守**。
 */
class AlignedSetDiffProbe {

    @Test
    fun diffAlignedSets() {
        val root = findRepoRoot()
        val file = File(root, "artifacts/ai-translation-memory-device.json")
        if (!file.isFile) {
            println("SKIP: 语料缺失")
            return
        }
        val ecdict = EcdictProvider.load(root) ?: run {
            println("SKIP: 词典缺失")
            return
        }
        val json = org.json.JSONObject(file.readText())
        val pairs = json.getJSONArray("pairs")
        val truthEn = ArrayList<String>()
        val truthZh = ArrayList<String>()
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim()
            val zh = p.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            truthEn.add(en)
            truthZh.add(zh)
        }
        val enChapters = listOf(AlignmentChapter("pure", truthEn))
        val zhChapters = listOf(AlignmentChapter("pure zh", truthZh))

        val docAi = AiTranslationAligner().align(enChapters, zhChapters, ecdict)
        val docLegacy = IndependentAligner().align(enChapters, zhChapters, ecdict)

        // 按英文句归一化文本建索引
        val aiByKey = HashMap<String, AlignedUnit>()
        docAi.units.forEach { aiByKey[normalize(it.sourceText)] = it }
        val legacyByKey = HashMap<String, AlignedUnit>()
        docLegacy.units.forEach { legacyByKey[normalize(it.sourceText)] = it }

        // ── 差集 1：ai=UNCERTAIN 但 legacy=ALIGNED ─────────────────
        var aiUncertainLegacyAligned = 0
        var aiAlignedLegacyUncertain = 0
        var bothAligned = 0
        var bothNotAligned = 0
        println("=== ALIGNED 集合差集 ===")
        for (key in aiByKey.keys) {
            val ai = aiByKey[key] ?: continue
            val legacy = legacyByKey[key] ?: continue
            when {
                ai.status != AlignmentStatus.ALIGNED &&
                    legacy.status == AlignmentStatus.ALIGNED -> {
                    aiUncertainLegacyAligned++
                    println()
                    println("--- [A] ai=${ai.status} legacy=ALIGNED ---")
                    println("    en=\"${ai.sourceText.take(56)}\"")
                    println("    ai     reasons = ${ai.evidence.reasons}")
                    println("    ai     verify  = ${fmt(ai.evidence.verifyScore)} " +
                        "z=${fmt(ai.evidence.lengthDeviation)}")
                    println("    legacy verify  = ${fmt(legacy.evidence.verifyScore)} " +
                        "z=${fmt(legacy.evidence.lengthDeviation)} " +
                        "anchors=${legacy.evidence.matchedAnchors}")
                    println("    legacy reasons = ${legacy.evidence.reasons}")
                }
                ai.status == AlignmentStatus.ALIGNED &&
                    legacy.status != AlignmentStatus.ALIGNED -> {
                    aiAlignedLegacyUncertain++
                    println()
                    println("--- [B] ai=ALIGNED legacy=${legacy.status} ---")
                    println("    en=\"${ai.sourceText.take(56)}\"")
                    println("    ai     reasons = ${ai.evidence.reasons}")
                    println("    legacy reasons = ${legacy.evidence.reasons}")
                }
                ai.status == AlignmentStatus.ALIGNED &&
                    legacy.status == AlignmentStatus.ALIGNED -> bothAligned++
                else -> bothNotAligned++
            }
        }
        println()
        println("  两引擎都 ALIGNED        = $bothAligned")
        println("  **ai 非 ALIGNED / legacy ALIGNED = $aiUncertainLegacyAligned**  ← 关键")
        println("  ai ALIGNED / legacy 非 ALIGNED   = $aiAlignedLegacyUncertain")
        println("  两引擎都非 ALIGNED      = $bothNotAligned")

        // ── 结论提示 ──────────────────────────────────────────────
        println()
        println("=== 判读 ===")
        println("  若「ai 非 ALIGNED / legacy ALIGNED」的句子 legacy.verifyScore 明显 > 0")
        println("  → 说明 legacy 靠内容见证判的，ai 的门槛更严 → 门槛设置问题")
        println("  若 legacy.matchedAnchors 非空而 ai 的 matchedAnchors 空")
        println("  → 说明 legacy 靠**锚点通道**判的，ai 漏用了 → **bug**")
    }

    private fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'A'..'Z' -> builder.append(c + 32)
                c in 'a'..'z' || c in '0'..'9' -> builder.append(c)
                c in '\u4E00'..'\u9FFF' -> builder.append(c)
            }
        }
        return builder.toString()
    }

    private fun fmt(v: Double): String =
        if (v == Double.MAX_VALUE) "inf" else String.format("%.3f", v)

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
    }
}
