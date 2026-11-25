package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * **决定性实验**：用**真值配对**（golden-samples）检验 V1 的 `recall` 上限。
 *
 * 之前的探针用「同序号/比例位置」配对，那是**错配**——recall 当然为 0。
 * 这个探针只用金标准里 `approved.level != MISS` 且能定位到真值中文句的样本，
 * 在**正确配对**上算 recall。
 *
 * ## 这是回答「ECDICT 到底行不行」的唯一干净方式
 *
 * 若在正确配对上 recall 仍然很低，那说明 ECDICT 的释义确实对不上文学译本
 * （因为这是「送分题」：配对已经给对了，只剩词义匹配）。
 */
class OraclePairRecallProbe {

    @Test
    fun recallOnGoldenTruePairs() {
        val inputs = AlignmentCorpusInputs.requireCase("F10")
        val enChapters = inputs.english
        val zhChapters = inputs.chinese
        val ecdict = inputs.requireDictionary()
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        CorpusExecution.requireSides(source, target)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()
        println("bridge: ${verify.notes}")

        // 读金标准：取 approved.zh（真值译文），在 target 里定位它
        val json = inputs.requireGolden()
        val samples = json.getJSONArray("samples")

        println()
        println("=== 在真值配对上的 V1 recall（送分题）===")
        val recalls = ArrayList<Double>(128)
        var located = 0
        var termsEmpty = 0
        var noApproved = 0
        val examples = ArrayList<String>(10)

        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val approved = sample.optJSONObject("approved") ?: run { noApproved++; null } ?: continue
            val level = approved.optString("level", "")
            if (level == "MISS") continue
            val approvedZh = approved.optString("zh", "")
            val english = sample.optString("en", "")
            if (approvedZh.isEmpty() || english.isEmpty()) continue

            // 在 target 里定位这段真值译文（用归一化后的前 20 字匹配）
            val zhIndex = locate(target, approvedZh)
            if (zhIndex < 0) continue
            // 在 source 里定位英文句
            val enIndex = locateEnglish(source, english)
            if (enIndex < 0) continue
            located++

            val score = verify.score(enIndex, 1, zhIndex, 1)
            if (!score.available) {
                termsEmpty++
                continue
            }
            recalls.add(score.recall)
            if (examples.size < 8) {
                examples.add(
                    "[$level] recall=${String.format("%.3f", score.recall)} " +
                        "prec=${String.format("%.3f", score.precision)} " +
                        "lift=${String.format("%.3f", score.lift)} " +
                        "en=\"${english.take(34)}\""
                )
            }
        }
        recalls.sort()
        println("  金标准样本总数=${samples.length()}  无 approved 字段=$noApproved")
        println("  成功定位两侧的真值对=$located  terms(E)=∅ 的=$termsEmpty")
        check(located > 0 && recalls.isNotEmpty()) { "No evaluable golden pair: located=$located available=${recalls.size}" }
        println("  V1 available 的真值对=${recalls.size}")
        println(
            "  **recall 分布：p10=${f(q(recalls, 0.1))} p50=${f(q(recalls, 0.5))} " +
                "p90=${f(q(recalls, 0.9))} max=${f(recalls.last())}**"
        )
        println(
            "  recall ≥ ${AlignTuning.VERIFY_RECALL_MIN} 的比例：" +
                "${recalls.count { it >= AlignTuning.VERIFY_RECALL_MIN }}/${recalls.size}"
        )
        println("  recall > 0 的比例：${recalls.count { it > 0.0 }}/${recalls.size}")
        println()
        println("  样例：")
        examples.forEach { println("    $it") }
        println()
        println("  → 这是**正确配对**上的 recall，排除了配对错误的影响。")
        println("    若这个数仍很低，说明 ECDICT 释义与文学译本措辞确实对不上（词典能力上限）。")
    }

    private fun locate(target: BookSide, zhText: String): Int {
        val needle = normalizeForMatch(zhText).take(30)
        if (needle.length < 8) return -1
        for (t in 0 until target.sentCount) {
            if (normalizeForMatch(target.sentText[t]).contains(needle)) return t
        }
        // 退一步：用前 12 字
        val shorter = needle.take(12)
        if (shorter.length < 8) return -1
        for (t in 0 until target.sentCount) {
            if (normalizeForMatch(target.sentText[t]).contains(shorter)) return t
        }
        return -1
    }

    private fun locateEnglish(source: BookSide, english: String): Int {
        val needle = normalizeForMatch(english)
        if (needle.length < 10) return -1
        for (s in 0 until source.sentCount) {
            val hay = normalizeForMatch(source.sentText[s])
            if (hay == needle || hay.contains(needle) || needle.contains(hay)) return s
        }
        return -1
    }

    private fun normalizeForMatch(text: String): String {
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

    private fun q(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private fun f(v: Double): String = String.format("%.3f", v)

}
