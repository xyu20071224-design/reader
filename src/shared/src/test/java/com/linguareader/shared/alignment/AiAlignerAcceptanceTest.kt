package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **`ai-pair-v1` 验收台**：在 35 句 AI 译本真值上跑新引擎，并与 `independent-v1` 对照。
 *
 * ## 验收标准（新定义，显式声明适用范围）
 *
 * ```
 * 分母 = 段内英文句（章节/段落对应由构造给出，不计入算法成绩）
 * 同时报全书句分母作对照
 *
 * 目标（仅对「AI 译本 + 构造性段落对应」有效，不可外推到人工译本）：
 *   - falseAligned = 0（硬红线）
 *   - 段内覆盖率 >= 0.90
 *   - precision >= 0.98（有真值的语料上）
 * ```
 *
 * ## 两种输入形态都跑
 *
 * 1. **原始段落形态**（真实生产形态）：直接用 `enParagraphs`/`zhParagraphs` →
 *    两次 `SentenceSplitter` 分句。**含分句噪声。**
 * 2. **真值句对形态**（隔离分句）：用 `pairs` 的 `es`/`zs` 每句成段，绕过 `SentenceSplitter`。
 *    **纯对齐能力。**
 *
 * 两个数字并列，差距就是「分句器对对齐准确率的损害」。
 */
class AiAlignerAcceptanceTest {

    @Test
    fun measureBothEnginesOnBothInputForms() {
        val root = findRepoRoot()
        val file = File(root, "artifacts/ai-translation-memory-device.json")
        if (!file.isFile) {
            println("SKIP: AI 译本语料缺失")
            return
        }
        val ecdict = EcdictProvider.load(root)
        if (ecdict == null) {
            println("SKIP: ecdict-sample.tsv 缺失（真 ECDICT 不可用）")
            return
        }
        val json = org.json.JSONObject(file.readText())
        val enParagraphs = json.getJSONArray("enParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val zhParagraphs = json.getJSONArray("zhParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val pairs = json.getJSONArray("pairs")

        // 真值：pairs 的 es → zs
        val truthEn = ArrayList<String>(pairs.length())
        val truthZh = ArrayList<String>(pairs.length())
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim()
            val zh = p.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            truthEn.add(en)
            truthZh.add(zh)
        }
        // 真值：英文句 → 正确的中文句（用归一化文本匹配，支持合并句）
        val truthTargetOf = HashMap<String, String>()
        for (i in truthEn.indices) truthTargetOf[normalize(truthEn[i])] = truthZh[i]

        val ai = AiTranslationAligner()
        val legacy = IndependentAligner()

        println("=== 语料 ===")
        println("  原始形态：en 段=${enParagraphs.size} zh 段=${zhParagraphs.size}")
        println("  真值句对=${truthEn.size}")

        // ── 形态 1：原始段落（含分句噪声）──────────────────────────
        println()
        println("=== 形态 1：原始段落（走 SentenceSplitter，含分句噪声）===")
        val enChapters = listOf(AlignmentChapter("AI sample", enParagraphs))
        val zhChapters = listOf(AlignmentChapter("AI sample zh", zhParagraphs))
        report("ai-pair-v1", ai.align(enChapters, zhChapters, ecdict), truthTargetOf)
        report("independent-v1", legacy.align(enChapters, zhChapters, ecdict), truthTargetOf)

        // ── 形态 2：真值句对（绕过 SentenceSplitter，纯对齐能力）────
        println()
        println("=== 形态 2：真值句对形态（绕过 SentenceSplitter，纯对齐能力）===")
        val pureEn = listOf(AlignmentChapter("pure", truthEn))
        val pureZh = listOf(AlignmentChapter("pure zh", truthZh))
        report("ai-pair-v1", ai.align(pureEn, pureZh, ecdict), truthTargetOf)
        report("independent-v1", legacy.align(pureEn, pureZh, ecdict), truthTargetOf)
    }

    // ──────────────────────────────────────────────────────────────

    private fun report(
        label: String,
        document: AlignmentDocument,
        truthTargetOf: Map<String, String>
    ) {
        var aligned = 0
        var alignedCorrect = 0
        var uncertain = 0
        var uncertainCorrect = 0
        var unaligned = 0
        val unalignedReasons = LinkedHashMap<String, Int>()
        val wrongSamples = ArrayList<String>(8)

        for (unit in document.units) {
            val key = normalize(unit.sourceText)
            val truthZh = truthTargetOf[key]
            when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    aligned++
                    if (truthZh != null && normalize(unit.targetText) == normalize(truthZh)) {
                        alignedCorrect++
                    } else if (wrongSamples.size < 8) {
                        wrongSamples +=
                            "      ALIGNED-WRONG en=\"${unit.sourceText.take(34)}\" " +
                                "truth=\"${truthZh?.take(28) ?: "<not in truth>"}\" " +
                                "got=\"${unit.targetText.take(28)}\""
                    }
                }
                AlignmentStatus.UNCERTAIN -> {
                    uncertain++
                    if (truthZh != null && normalize(unit.targetText) == normalize(truthZh)) {
                        uncertainCorrect++
                    }
                }
                AlignmentStatus.UNALIGNED -> {
                    unaligned++
                    val why = (unit.evidence.reasons.firstOrNull() ?: "?")
                        .substringBefore(':')
                        .take(40)
                    unalignedReasons.merge(why, 1, Int::plus)
                }
            }
        }
        val total = document.units.size
        val precision = if (aligned == 0) 1.0 else alignedCorrect.toDouble() / aligned
        println("--- $label ---")
        println(
            "  ALIGNED=$aligned (正确 $alignedCorrect) UNCERTAIN=$uncertain " +
                "(其中候选正确 $uncertainCorrect) UNALIGNED=$unaligned"
        )
        println("  coverage  = ${pct(aligned.toDouble() / total.coerceAtLeast(1))}（分母=$total 个英文句）")
        println("  precision = ${pct(precision)}")
        println("  falseAligned = ${aligned - alignedCorrect}")
        println("  可利用率（ALIGNED + UNCERTAIN 里正确的）= " +
            pct((alignedCorrect + uncertainCorrect).toDouble() / total.coerceAtLeast(1)))
        if (unalignedReasons.isNotEmpty()) {
            println("  UNALIGNED 原因：$unalignedReasons")
        }
        if (wrongSamples.isNotEmpty()) {
            println("  错标明细：")
            wrongSamples.forEach { println(it) }
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

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
    }
}
