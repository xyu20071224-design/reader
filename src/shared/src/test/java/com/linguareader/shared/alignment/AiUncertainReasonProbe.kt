package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **诊断：A 实验里 8 条 UNCERTAIN 为什么不敢给 ALIGNED**（Lead 要求）。
 *
 * 特别是其中 **6 条候选正确**——如果原因是「内容见证不足但长度+锚点都对」，
 * 说明在 AI 译本上（前提更强）门槛可能偏严；如果是「有竞争候选」，那是正确的谨慎。
 */
class AiUncertainReasonProbe {

    @Test
    fun whyUncertainOnPureAiCorpus() {
        val root = findRepoRoot()
        val file = File(root, "artifacts/ai-translation-memory-device.json")
        if (!file.isFile) {
            println("SKIP: 语料缺失")
            return
        }
        val json = org.json.JSONObject(file.readText())
        val pairs = json.getJSONArray("pairs")
        val enSentences = ArrayList<String>()
        val zhSentences = ArrayList<String>()
        val truthPairs = ArrayList<Pair<String, String>>()
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            val en = pair.optString("es", "").trim()
            val zh = pair.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            enSentences.add(en)
            zhSentences.add(zh)
            truthPairs.add(en to zh)
        }
        val ecdict = EcdictProvider.load(root) ?: run {
            println("SKIP: 词典缺失")
            return
        }
        val document = IndependentAligner().align(
            listOf(AlignmentChapter("ai-pure", enSentences)),
            listOf(AlignmentChapter("ai-pure-zh", zhSentences)),
            ecdict
        )

        println("=== 纯净 AI 语料：所有非 ALIGNED 单元的原因 ===")
        println("  ${document.stats.summaryLine()}")
        println()
        document.units.forEachIndexed { index, unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEachIndexed
            val truth = truthPairs.getOrNull(index) ?: return@forEachIndexed
            val candidateMatches = normalize(unit.targetText) == normalize(truth.second)
            val hasText = unit.targetText.isNotEmpty()
            println("--- #$index ${unit.status} ${unit.skipReason ?: ""} ---")
            println("    en       = \"${truth.first.take(56)}\"")
            println("    truth zs = \"${truth.second.take(40)}\"")
            println("    got      = \"${unit.targetText.take(40)}\"")
            println("    候选${if (candidateMatches) "**正确**" else if (hasText) "错误" else "为空"}")
            println("    z=${fmt(unit.evidence.lengthDeviation)} " +
                "drift=${fmt(unit.evidence.positionDrift)} " +
                "verify=${fmt(unit.evidence.verifyScore)}")
            println("    reasons = ${unit.evidence.reasons}")
            println()
        }

        // 分类统计
        val reasonHist = LinkedHashMap<String, Int>()
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEach
            val key = when (unit.status) {
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                else -> (unit.evidence.reasons.firstOrNull() ?: "?")
                    .substringBefore('=').substringBefore(' ')
            }
            reasonHist.merge(key, 1, Int::plus)
        }
        println("=== 阻断原因直方图 ===")
        reasonHist.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            println("  ${k.padEnd(48)} : $v")
        }
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
