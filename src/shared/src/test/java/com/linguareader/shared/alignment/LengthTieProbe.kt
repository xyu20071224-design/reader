package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * 临时诊断：把「长度重复」作为**唯一变量**隔离出来。
 *
 * 三份语料，句数/段数/章节数完全相同，**只有句长的重复程度不同**：
 * - `tied`：句长只从 3 个值里取（大量并列 → 结构上不可分）
 * - `varied`：句长从 20 个值里取（少量并列）
 * - `unique`：句长严格唯一（无任何并列）
 *
 * 目的：证明「coverage 低」是**长度并列**导致的（设计 §11 F1 的等长陷阱），
 * 还是我的 margin 实现有问题。
 */
class LengthTieProbe {

    @Test
    fun coverageVsLengthTieDensity() {
        for (case in listOf("tied", "varied", "unique")) {
            val corpus = build(case)
            val document = IndependentAligner().align(
                corpus.source, corpus.translation, corpus.provider()
            )
            val truth = corpus.toAlignmentTruth()
            val metrics = AlignmentMetricsCalculator.compute(
                variant = case,
                engine = document.engineId,
                truth = truth,
                projection = AlignmentProjection.project(document, truth)
            )
            val reasons = HashMap<String, Int>()
            document.units.forEach { unit ->
                if (unit.status == AlignmentStatus.ALIGNED) return@forEach
                val why = when (unit.status) {
                    AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                    else -> "UNCERTAIN:" +
                        (unit.evidence.reasons.firstOrNull() ?: "?").substringBefore('=').substringBefore(' ')
                }
                reasons.merge(why, 1, Int::plus)
            }
            println(
                "=== $case === coverage=${pct(metrics.coverage)} " +
                    "falseAligned=${metrics.falseAlignedCount} " +
                    "uncertain=${metrics.uncertainSentences} unaligned=${metrics.unalignedSentences}"
            )
            println("    原因：${reasons.entries.sortedByDescending { it.value }.joinToString { "${it.key}×${it.value}" }}")
            println("    en 句长 distinct=${corpus.enLengths.toSet().size}/${corpus.enLengths.size}")
        }
        // 关键：打印 ρ 的离散度。若所有句子的 ρ 都贴着 rBook，长度模型**在原理上**
        // 就无法区分任何两句——这才是 margin 恒小的真因（不是并列数，不是阈值）。
        val corpus = build("unique")
        val source = BookSide.build(SideKind.SOURCE, corpus.source)
        val target = BookSide.build(SideKind.TARGET, corpus.translation)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val ratios = (0 until minOf(source.sentCount, target.sentCount))
            .filter { source.sentLangUnit[it] && target.sentLangUnit[it] }
            .map { density.observedRatio(source.sentWords[it], target.sentChars[it]) }
            .sorted()
        println("=== ρ 的离散度（ρ = (ch+γ)/(w+γ)）===")
        println("  n=${ratios.size} min=${r(ratios.first())} p50=${r(ratios[ratios.size / 2])} " +
            "max=${r(ratios.last())} distinct=${ratios.map { Math.round(it * 1000) }.toSet().size}")
        val logDeviation = ratios.map { Math.abs(Math.log(it) - Math.log(density.rBook)) }.sorted()
        println("  |ln ρ − ln rBook|: p50=${r(logDeviation[logDeviation.size / 2])} " +
            "p90=${r(logDeviation[(logDeviation.size * 0.9).toInt().coerceAtMost(logDeviation.lastIndex)])}")
        println("  → 若 p90(|ln 偏离|) 远小于 σ·Z_LIMIT(≈0.35×2=0.70)，则长度模型对**每一句**都无话说")
    }

    // ──────────────────────────────────────────────────────────────

    private class Corpus(
        val source: List<AlignmentChapter>,
        val translation: List<AlignmentChapter>,
        val keys: List<String>,
        val units: List<TargetUnit>,
        val markers: Map<String, Int>,
        val gloss: Map<String, String>,
        val enLengths: List<Int>
    ) {
        fun provider(): MeaningProvider {
            val copy = gloss
            return MeaningProvider { word -> copy[word.lowercase()]?.let { listOf(it) } ?: emptyList() }
        }

        fun toAlignmentTruth(): AlignmentTruth = AlignmentTruth(
            sourceKeysInOrder = keys,
            targetUnits = units,
            markerToUnit = markers,
            sourceMarkerUniverse = keys.toSet(),
            targetMarkerUniverse = markers.keys.toSet()
        )
    }

    /** 8 个主语 × 8 个动词 × 8 个宾语 × 补语，按唯一编码区分。 */
    private fun build(case: String): Corpus {
        val subjects = listOf(
            "elder", "hunter", "smith", "singer", "farmer", "sailor", "healer", "scribe"
        )
        val zhSubjects = listOf(
            "那长者", "那猎人", "那铁匠", "那歌者", "那农夫", "那水手", "那医者", "那书吏"
        )
        val verbs = listOf(
            "remembered", "described", "followed", "watched",
            "answered", "waited", "glimpsed", "counted"
        )
        val zhVerbs = listOf(
            "想起了", "描述了", "跟随了", "注视着", "回答了", "等待着", "望见了", "数着"
        )
        val objects = listOf(
            "river", "mountain", "city", "sea", "harvest", "harbour", "forest", "tower"
        )
        val zhObjects = listOf(
            "那条河", "那座山", "那座城", "那片海", "那次收获", "那处港口", "那座森林", "那座塔"
        )
        val padding = listOf(
            "again", "quietly", "at dawn", "without haste", "in the old way",
            "before the storm", "with great care", "as the bells rang",
            "for three long days", "and then said nothing at all",
            "while the wind pushed against the shutters",
            "and the whole valley seemed to hold its breath"
        )
        val zhPadding = listOf(
            "又一次", "静静地", "在黎明", "不慌不忙", "依着旧例",
            "在暴风雨之前", "极为仔细", "钟声响起时",
            "连着漫长的三天", "然后什么也没再说",
            "当时风正推着百叶窗",
            "整个山谷仿佛都屏住了呼吸"
        )

        val enCodes = listOf("zqk", "vur", "nif", "gle", "drax", "mub", "phet", "jox", "civ", "wam", "ryu", "kob")
        val zhCodes = listOf("叺", "丂", "丄", "乂", "仉", "厹", "夬", "奂", "屮", "巛", "彳", "恏")

        val enLengths = ArrayList<Int>()
        val keys = ArrayList<String>()
        val units = ArrayList<TargetUnit>()
        val markers = HashMap<String, Int>()
        val gloss = LinkedHashMap<String, String>()
        val enParagraphsAll = ArrayList<ArrayList<String>>()
        val zhParagraphsAll = ArrayList<ArrayList<String>>()

        var index = 0
        for (chapter in 0 until 4) {
            val enParagraphs = ArrayList<String>()
            val zhParagraphs = ArrayList<String>()
            for (paragraph in 0 until 4) {
                val enSentences = ArrayList<String>()
                val zhSentences = ArrayList<String>()
                for (sentence in 0 until 4) {
                    // ── 唯一变量：补语取几个（决定句长）──────────────
                    val extra = when (case) {
                        "tied" -> 1 + (index % 3)                 // 3 种长度
                        "varied" -> 1 + (index % 20).coerceAtMost(padding.size)
                        "unique" -> 1 + (index % padding.size) + (index / padding.size) % 4
                        else -> 1
                    }
                    val subject = index % subjects.size
                    val verb = (index / 8) % verbs.size
                    val obj = (index * 3) % objects.size
                    val parts = ArrayList<String>(8)
                    parts.add("The")
                    parts.add(subjects[subject])
                    parts.add(verbs[verb])
                    parts.add("the")
                    parts.add(objects[obj])
                    for (k in 0 until extra) parts.add(padding[k % padding.size])
                    val enKey = "${enCodes[index % 12]} ${enCodes[(index / 12) % 12]} ${enCodes[(index / 144) % 12]}"
                    enSentences.add(parts.joinToString(" ") + " \u00AB$enKey\u00BB.")
                    enLengths.add(parts.size + 3) // +3 是编码 token

                    val zhParts = StringBuilder()
                    zhParts.append(zhSubjects[subject]).append(zhVerbs[verb])
                        .append(zhObjects[obj]).append('，')
                    for (k in 0 until extra) zhParts.append(zhPadding[k % zhPadding.size])
                    val zhKey = buildString {
                        append(zhCodes[index % 12])
                        append(zhCodes[(index / 12) % 12])
                        append(zhCodes[(index / 144) % 12])
                    }
                    val zhText = zhParts.toString() + "「$zhKey」。"
                    zhSentences.add(zhText)

                    keys.add(enKey)
                    units.add(TargetUnit(units.size, listOf(zhKey), listOf(enKey), zhText))
                    markers[zhKey] = units.size - 1
                    gloss.putIfAbsent(subjects[subject], zhSubjects[subject].drop(1))
                    gloss.putIfAbsent(verbs[verb], zhVerbs[verb].dropLast(1))
                    gloss.putIfAbsent(objects[obj], zhObjects[obj].drop(1).dropLast(1))
                    index++
                }
                enParagraphs.add(enSentences.joinToString(" "))
                zhParagraphs.add(zhSentences.joinToString(""))
            }
            enParagraphsAll.add(enParagraphs)
            zhParagraphsAll.add(zhParagraphs)
        }
        val source = enParagraphsAll.mapIndexed { i, paragraphs ->
            AlignmentChapter("Chapter ${i + 1}", paragraphs)
        }
        val translation = zhParagraphsAll.mapIndexed { i, paragraphs ->
            AlignmentChapter("第${i + 1}章", paragraphs)
        }
        return Corpus(source, translation, keys, units, markers, gloss, enLengths)
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun r(v: Double): String = (Math.round(v * 1000.0) / 1000.0).toString()
}
