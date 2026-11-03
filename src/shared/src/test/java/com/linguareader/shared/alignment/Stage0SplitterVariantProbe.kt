package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * **阶段 0b**：量出「中文分句器口径」对句级对齐的**归因**。
 *
 * ## 背景
 *
 * [Stage0ProductionBaselineProbe] 实测生产链路（[com.linguareader.shared.translation.TranslationAligner]）：
 * ch1 静默错 13.08%、ch3 静默错 16.34%。抽样样例指向两条**分句口径**缺陷，而非对齐算法：
 *
 *  1. `ZH_CLOSING_LEADS` 缺弯引号 `”`/`’` → `！”甘道夫说。` 被切成两句（AI 译文用弯引号）。
 *  2. 中文 `；` 算句界、英文 `;` 不算 → `; but ...` 一句在中文侧被切成两句。
 *
 * `TranslationAligner.kt:220` 附近记录过「`；` 算句界（口径 A）实测更好」，但那是用
 * **人译本**（KJV/和合本完全平行语料）测的。本项目对齐目标已改为 **AI 译本**，
 * 而 AI 译文的构造前提是「不合并、不拆分句子」——即中文 `；` 通常落在**英文同一句内部**，
 * 此时把 `；` 当句界会系统性制造 off-by-one。测量基准变了，结论必须重测。
 *
 * ## 本探针做什么
 *
 * 纯只读、零 API 费用：把四套分句口径分别作用在**真值中文段**上，计算
 *   ① 句数与英文句数相等的段落比例（对齐能否走理想 1:1 的前提）
 *   ② 在句数相等的段落里，**按下标直接配对**的正确率（理想 1:1 的正确率上界）
 *   ③ 全章口径下「按段内下标配对」的静默错率（对不齐的段落按段落口径单独计）
 *
 * 若某变体把 ②③ 显著改善 → 说明收益来自分句口径，**不需要** P3 那 12~14 个文件的改造。
 */
class Stage0SplitterVariantProbe {

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    private class Variant(
        val name: String,
        val terminator: Regex,
        val closingLeads: Set<Char>,
        val desc: String,
        /** R2 处理方式：true=整段并入前句（现状），false=只把开头闭合引号游程搬到前句。 */
        val mergeWholeSegment: Boolean = true,
        /**
         * 非空时启用「选择性合并」：闭合引号后的剩余文本只有在其长度 ≤ 本值
         * （判定为说话人引导语）时才并入前句，否则另起新句。null = 不启用。
         */
        val speechTagMaxChars: Int? = null
    )

    /**
     * 说话人引导语形态（宽松）：`他说。` `甘道夫说。` `弗罗多问道，` `他说着，` 等。
     * 判据：短 + 含说话动词 + 不含句中逗号（有逗号说明是新的叙事内容）。
     */
    private val SPEECH_VERB = Regex("说|道|问|答|喊|叫|嚷|叹|嘟囔|咕哝|低语|耳语|回答|补充")

    private fun looksLikeSpeechTag(rest: String, maxChars: Int): Boolean {
        if (rest.length > maxChars) return false
        if (rest.any { it in "，,；;" }) return false
        return SPEECH_VERB.containsMatchIn(rest)
    }

    /** 与 [com.linguareader.shared.translation.TranslationAligner] 的 splitChinese 同构的复刻。 */
    private fun splitChinese(text: String, v: Variant): List<String> {
        val raw = text.split(v.terminator).map { it.trim() }.filter { it.isNotBlank() }
        val merged = mutableListOf<String>()
        for (seg in raw) {
            val isPunctuationOnly = seg.none { it.isLetterOrDigit() }
            val leadsWithClosing = seg.firstOrNull() in v.closingLeads
            val shouldAttach = when {
                merged.isEmpty() -> false
                isPunctuationOnly -> true
                !leadsWithClosing -> false
                !v.mergeWholeSegment -> {
                    // V4 语义：只搬游程
                    val k = seg.indexOfFirst { it !in v.closingLeads }.let { if (it < 0) seg.length else it }
                    val rest = seg.substring(k).trim()
                    val prev = merged.last()
                    merged[merged.lastIndex] = prev + seg.substring(0, k)
                    if (rest.isNotEmpty() && rest.any { it.isLetterOrDigit() }) merged += rest
                    continue
                }
                v.speechTagMaxChars == null -> true
                else -> {
                    val k = seg.indexOfFirst { it !in v.closingLeads }.let { if (it < 0) seg.length else it }
                    val rest = seg.substring(k).trim()
                    rest.isEmpty() || looksLikeSpeechTag(rest, v.speechTagMaxChars)
                }
            }
            if (shouldAttach) merged[merged.lastIndex] = merged.last() + seg else merged += seg
        }
        return merged.filter { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }
    }

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "artifacts/ai-ch1/ch1_truth.json").isFile) dir = dir.parentFile
        return dir
    }

    @Test
    fun measure() {
        val repo = repoRoot() ?: run { println("[0b] SKIP: repo root not found"); return }

        // 现状：口径 A（中文 ；算句界），闭合引号只认直引号
        val currentCloses = setOf('」', '』', '"', '\'', '）', '】')
        val curvedCloses = currentCloses + setOf('”', '’', '』')

        val variants = listOf(
            Variant(
                "V0 现状", Regex("(?<=[。！？；!?;])|(?<=\\n)"), currentCloses,
                "中文 ；算句界 + 只认直引号"
            ),
            Variant(
                "V1 V0+弯引号", Regex("(?<=[。！？；!?;])|(?<=\\n)"), curvedCloses,
                "补 ” ’ 到闭合引号集合"
            ),
            Variant(
                "V2 去分号", Regex("(?<=[。！？!?])|(?<=\\n)"), currentCloses,
                "中文 ；不算句界（与英文 ; 对齐）"
            ),
            Variant(
                "V3 V1+V2", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "两者都改"
            ),
            Variant(
                "V4 V3+引号游程", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "R2 只搬闭合引号游程，剩余另起新句", mergeWholeSegment = false
            ),
            Variant(
                "V5 V3+guize4", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "R2 选择性合并：剩余≤4字且像说话人才并入", speechTagMaxChars = 4
            ),
            Variant(
                "V6 V3+guize6", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "R2 选择性合并：剩余≤6字且像说话人才并入", speechTagMaxChars = 6
            ),
            Variant(
                "V7 V3+guize8", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "R2 选择性合并：剩余≤8字且像说话人才并入", speechTagMaxChars = 8
            ),
            Variant(
                "V8 V3+guize10", Regex("(?<=[。！？!?])|(?<=\\n)"), curvedCloses,
                "R2 选择性合并：剩余≤10字且像说话人才并入", speechTagMaxChars = 10
            )
        )

        for ((name, rel) in listOf(
            "ch1" to "artifacts/ai-ch1/ch1_truth.json",
            "ch3" to "artifacts/ai-ch3/ch3_merged_partial.json"
        )) {
            val file = File(repo, rel)
            if (!file.isFile) { println("=== $name MISSING ==="); continue }
            val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")

            println()
            println("=== $name ===")
            println("  段落数=${arr.length()}")

            for (v in variants) {
                var parity = 0
                var posCorrect = 0
                var posWrong = 0
                var totalTruth = 0
                var totalEn = 0
                // 段落级兜底口径：句数不等的段落，其全部英文句都算「无法句级定位」
                var fallbackSentences = 0

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val en = o.getJSONArray("en")
                    val zh = o.getJSONArray("zh")
                    val n = minOf(en.length(), zh.length())
                    totalEn += en.length()
                    totalTruth += n
                    val zhText = (0 until zh.length()).joinToString("") { zh.getString(it) }
                    val segs = splitChinese(zhText, v)
                    if (segs.size == en.length()) {
                        parity++
                        for (k in 0 until n) {
                            if (norm(segs[k]) == norm(zh.getString(k))) posCorrect++ else posWrong++
                        }
                    } else {
                        fallbackSentences += en.length()
                    }
                }

                val pct = { a: Int, b: Int -> if (b == 0) 0.0 else a * 100.0 / b }
                println("  --- ${v.name} （${v.desc}）---")
                println("    句数相等段落 = $parity / ${arr.length()} (${"%.2f".format(pct(parity, arr.length()))}%)")
                if (posCorrect + posWrong > 0) {
                    println("    下标配对正确 = $posCorrect / ${posCorrect + posWrong} " +
                        "(${"%.2f".format(pct(posCorrect, posCorrect + posWrong))}%)  " +
                        "错 = $posWrong (${"%.2f".format(pct(posWrong, posCorrect + posWrong))}%)")
                }
                println("    句数不等段落涉及英文句 = $fallbackSentences / $totalEn " +
                    "(${"%.2f".format(pct(fallbackSentences, totalEn))}%) → 需靠 DP 或降级")
            }

            // 真值层面（不重切）的理论上界
            var truthCorrect = 0
            var truthTotal = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val en = o.getJSONArray("en")
                val zh = o.getJSONArray("zh")
                val n = minOf(en.length(), zh.length())
                truthTotal += n
                truthCorrect += n  // 真值本身 1:1 完全正确，恒等于 n
            }
            println("  --- 真值上界（不重切，直接按构造句界配对）---")
            println("    正确 = $truthCorrect / $truthTotal (100.00%)  静默错 = 0.00%")
        }

        println()
        println("=== 判读 ===")
        println("  V0 是生产现状。若 V2/V3 的「下标配对正确率」显著高于 V0，")
        println("  说明静默错的主因是中文 ；被当句界（与英文 ; 不对称），改分句口径即可，")
        println("  不必做 P3 的模型侧句界改造。")
    }
}
