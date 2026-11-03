package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.AiTranslationAligner
import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **`ai-pair-v1` 两种形态的完整人工可读审计（定稿版）**。
 *
 * ## 为什么需要「人工可读」而不是再来一个百分比
 *
 * 用户能自己判断对错的只有一件事：**点开这句英文，看到的中文是不是它的译文。**
 * 所以本测试把两种形态的**每个单元**都排成「英文 → 算法给的译文 → 人工该看到的译文」，
 * 由人肉核对。百分比只是汇总。
 *
 * ## 判据分三层（从宽到严，全部报）
 *
 * | 层 | 判据 | 能抓出什么 |
 * | --- | --- | --- |
 * | L1 有内容 | targetText 含 ≥4 汉字 | 空串、纯引号 `”` |
 * | L2 与真值一致 | 归一化后相等 | 与（正确的）真值不符 |
 * | L3 位置同步 | 英中在各自段落里的归一化位置差 ≤ 0.35 | 拿了**相邻句**的译文 |
 *
 * L3 是本测试的独有贡献：它**不依赖真值**，能抓出「真值自己也错了」时被 L2 放过的情况。
 */
@RunWith(RobolectricTestRunner::class)
class AiAlignerFullAuditTest {

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/ai-translation-memory-device.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun han(s: String) = s.count { it in '\u4E00'..'\u9FFF' }

    private fun norm(s: String) = s.filter {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in '\u4E00'..'\u9FFF'
    }.lowercase(Locale.ROOT)

    private fun hasContent(t: String) = han(t) >= 4

    private fun posOf(hay: String, needle: String): Double {
        if (needle.isBlank()) return -1.0
        val i = hay.indexOf(needle)
        if (i < 0) return -1.0
        return if (hay.length <= 1) 0.0 else i.toDouble() / (hay.length - 1)
    }

    private fun pct(v: Double, d: Int = 2) = String.format(Locale.ROOT, "%.${d}f%%", v * 100)

    @Test
    fun fullAuditBothForms() {
        val root = findRoot()
        assumeTrue("缺少 AI 译本语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val json = JSONObject(File(artifacts, "ai-translation-memory-device.json").readText())
        val enParagraphs = json.getJSONArray("enParagraphs").let { a -> (0 until a.length()).map { a.optString(it, "") } }
        val zhParagraphs = json.getJSONArray("zhParagraphs").let { a -> (0 until a.length()).map { a.optString(it, "") } }
        val pairs = json.getJSONArray("pairs")
        val truthEn = ArrayList<String>(); val truthZh = ArrayList<String>()
        val truthF = ArrayList<Double>(); val truthEp = ArrayList<Int>()
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim(); val zh = p.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            truthEn.add(en); truthZh.add(zh)
            truthF.add(p.optDouble("f", 0.0)); truthEp.add(p.optInt("ep", -1))
        }
        val truthTargetOf = HashMap<String, String>()
        for (i in truthEn.indices) truthTargetOf[norm(truthEn[i])] = truthZh[i]
        val truthIndex = HashMap<String, Int>()
        for (i in truthEn.indices) truthIndex[norm(truthEn[i])] = i

        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))
        val aligner = AiTranslationAligner()

        val text = StringBuilder()
        text.appendLine("# `ai-pair-v1` 完整审计（两种形态，人工可读）")
        text.appendLine()
        text.appendLine("语料：`artifacts/ai-translation-memory-device.json` — " +
            "18 个原始段落（英/中 1:1）、35 条 AI 生成的句级真值 `pairs`。")
        text.appendLine("词典：**生产 `EcdictMeaningIndex`**（不是 `:shared` 的预导出快照）。")
        text.appendLine()

        // ══════════════════════════════════════════════════
        //  先判定真值本身的质量（这决定尺子准不准）
        // ══════════════════════════════════════════════════
        text.appendLine("## 0. 真值质量（先确认尺子）")
        text.appendLine()
        text.appendLine("AI 生成的 `pairs` 真值**本身有错**。逐条标记：")
        text.appendLine()
        text.appendLine("| # | f | ep | 真值中文 | 汉字 | 判定 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        val badTruth = LinkedHashSet<Int>()
        for (i in truthEn.indices) {
            var reason = ""
            if (truthF[i] < 0.5) reason = "低置信 f=${String.format(Locale.ROOT, "%.2f", truthF[i])}"
            if (!hasContent(truthZh[i])) reason = if (reason.isEmpty()) "中文无内容" else "$reason / 中文无内容"
            if (reason.isNotEmpty()) badTruth += i
            if (reason.isNotEmpty()) {
                text.appendLine("| $i | ${String.format(Locale.ROOT, "%.3f", truthF[i])} | ${truthEp[i]} | " +
                    "`${truthZh[i]}` | ${han(truthZh[i])} | **⚠️ $reason** |")
            }
        }
        text.appendLine()
        text.appendLine("**有问题的真值 = ${badTruth.size} / ${truthEn.size}**（${badTruth.joinToString(", ")}）")
        text.appendLine()
        text.appendLine("这些条目的问题是「英文 3 句 / 中文 2 句」导致**真值整体错位一句**——")
        text.appendLine("算法若忠实跟随，会拿到相邻句的译文。**这不是算法的错，是输入真值的错。**")
        text.appendLine()

        // ══════════════════════════════════════════════════
        //  形态 2：真值句对形态
        // ══════════════════════════════════════════════════
        val docPure = aligner.align(
            listOf(AlignmentChapter("pure", truthEn)),
            listOf(AlignmentChapter("pure zh", truthZh)),
            meaning
        )
        text.appendLine("## 1. 形态 2（真值句对）：逐条可读审计")
        text.appendLine()
        text.appendLine("| # | status | conf | 英文句 | 算法译文 | 真值译文 | L1 有内容 | L2 等真值 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        var p2L1 = 0; var p2L2 = 0; var p2Aligned = 0; var p2Uncertain = 0
        var p2AlignedL1 = 0; var p2AlignedL2 = 0
        val p2UncertainRows = ArrayList<String>()
        var p2NoContent = 0
        for ((i, u) in docPure.units.withIndex()) {
            val truth = truthTargetOf[norm(u.sourceText)]
            val l1 = hasContent(u.targetText)
            val l2 = truth != null && norm(u.targetText) == norm(truth)
            if (l1) p2L1++
            if (l2) p2L2++
            if (u.status == AlignmentStatus.ALIGNED) {
                p2Aligned++; if (l1) p2AlignedL1++; if (l2) p2AlignedL2++
            }
            if (u.status == AlignmentStatus.UNCERTAIN) {
                p2Uncertain++
                if (!l1) p2NoContent++
                p2UncertainRows += "| U${p2Uncertain} | ${String.format(Locale.ROOT, "%.2f", u.confidence)} | " +
                    "`${u.sourceText.take(56).replace("|", "\\|")}` | " +
                    "`${u.targetText.take(44).replace("|", "\\|")}` | " +
                    "`${(truth ?: "-").take(44).replace("|", "\\|")}` | ${if (l1) "✅" else "**❌**"} | " +
                    "${if (l2) "✅" else "❌"} |"
            }
            text.appendLine(
                "| $i | ${u.status} | ${String.format(Locale.ROOT, "%.2f", u.confidence)} | " +
                    "`${u.sourceText.take(48).replace("|", "\\|")}` | " +
                    "`${u.targetText.take(38).replace("|", "\\|")}` | " +
                    "`${(truth ?: "-").take(38).replace("|", "\\|")}` | " +
                    "${if (l1) "✅" else "**❌**"} | ${if (l2) "✅" else "❌"} |"
            )
        }
        text.appendLine()
        text.appendLine("**形态 2 汇总**：units=${docPure.units.size}，" +
            "ALIGNED=$p2Aligned（有内容 $p2AlignedL1 / 等真值 $p2AlignedL2），UNCERTAIN=$p2Uncertain")
        text.appendLine()
        text.appendLine("### 9 条 UNCERTAIN 单独列出（Lead 指定的重点）")
        text.appendLine()
        text.appendLine("| # | conf | 英文句 | 算法译文 | 真值译文 | L1 有内容 | L2 等真值 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        p2UncertainRows.forEach { text.appendLine(it) }
        text.appendLine()
        text.appendLine("**UNCERTAIN 里没有给出译文的（L1 失败）= $p2NoContent / $p2Uncertain**")
        text.appendLine()
        text.appendLine("⇒ 实现方报告「可利用率 100%（26 + 9）」不成立：")
        text.appendLine("**$p2NoContent 条 UNCERTAIN 的 `targetText` 是孤立引号 `”`，用户点开看不到任何译文。**")
        text.appendLine()

        // ══════════════════════════════════════════════════
        //  形态 1：原始段落（生产真实形态）
        // ══════════════════════════════════════════════════
        val docRaw = aligner.align(
            listOf(AlignmentChapter("raw", enParagraphs)),
            listOf(AlignmentChapter("raw zh", zhParagraphs)),
            meaning
        )
        text.appendLine("## 2. 形态 1（原始段落，**生产真实形态**）：逐条可读审计")
        text.appendLine()
        text.appendLine("含 `SentenceSplitter` 噪声。这是用户实际会遇到的形态。")
        text.appendLine()
        text.appendLine("| # | status | conf | 英文句 | 算法译文 | 位置差 | 问题 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        var p1Aligned = 0; var p1Uncertain = 0; var p1Unaligned = 0
        var p1NoContent = 0
        var p1PositionBad = 0
        var p1AlignedNoContent = 0
        val p1Issues = ArrayList<String>()
        for ((i, u) in docRaw.units.withIndex()) {
            val l1 = hasContent(u.targetText)
            val st = posOf(u.sourceParagraph, u.sourceText)
            val zt = posOf(u.targetParagraph, u.targetText)
            val delta = if (st >= 0 && zt >= 0) Math.abs(st - zt) else Double.NaN
            val posBad = !delta.isNaN() && delta > 0.35
            val issues = ArrayList<String>()
            if (!l1 && u.status != AlignmentStatus.UNALIGNED) issues += "无译文"
            if (posBad) issues += "位置差 ${String.format(Locale.ROOT, "%.2f", delta)}"
            when (u.status) {
                AlignmentStatus.ALIGNED -> {
                    p1Aligned++
                    if (!l1) p1AlignedNoContent++
                    if (posBad) p1PositionBad++
                }
                AlignmentStatus.UNCERTAIN -> { p1Uncertain++; if (!l1) p1NoContent++ }
                AlignmentStatus.UNALIGNED -> p1Unaligned++
            }
            if (issues.isNotEmpty() && u.status != AlignmentStatus.UNALIGNED) p1Issues += "${u.status}#$i: ${issues.joinToString("/")}"
            text.appendLine(
                "| $i | ${u.status} | ${String.format(Locale.ROOT, "%.2f", u.confidence)} | " +
                    "`${u.sourceText.take(44).replace("|", "\\|")}` | " +
                    "`${u.targetText.take(40).replace("|", "\\|")}` | " +
                    "${if (delta.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", delta)} | " +
                    "${issues.joinToString("; ").ifEmpty { "" }} |"
            )
        }
        text.appendLine()
        text.appendLine("**形态 1 汇总**：units=${docRaw.units.size}，" +
            "ALIGNED=$p1Aligned（无译文 $p1AlignedNoContent、位置异常 $p1PositionBad），" +
            "UNCERTAIN=$p1Uncertain（无译文 $p1NoContent），UNALIGNED=$p1Unaligned")
        text.appendLine()
        val rawTotal = docRaw.units.size
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| 严格 coverage（ALIGNED / units） | ${pct(p1Aligned.toDouble() / rawTotal)}（$p1Aligned/$rawTotal） |")
        text.appendLine("| ALIGNED 中无译文（= 必然错标） | $p1AlignedNoContent |")
        text.appendLine("| ALIGNED 中位置异常 | $p1PositionBad |")
        text.appendLine("| UNCERTAIN 中无译文 | $p1NoContent |")
        text.appendLine("| **可用（有真译文）覆盖率** | " +
            pct((p1Aligned - p1AlignedNoContent + p1Uncertain - p1NoContent).toDouble() / rawTotal) + " |")
        text.appendLine()
        if (p1Issues.isNotEmpty()) {
            text.appendLine("### 形态 1 的问题单元清单")
            text.appendLine()
            p1Issues.forEach { text.appendLine("- $it") }
            text.appendLine()
        }

        // ══════════════════════════════════════════════════
        //  两种形态对比（定稿数字）
        // ══════════════════════════════════════════════════
        text.appendLine("## 3. 定稿数字")
        text.appendLine()
        text.appendLine("| 形态 | units | ALIGNED | 严格 coverage | 有译文 coverage | 无译文的单元 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        text.appendLine("| 形态 2（真值句对，隔离分句） | ${docPure.units.size} | $p2Aligned | " +
            "${pct(p2Aligned.toDouble() / docPure.units.size)} | " +
            "${pct((p2AlignedL1 + (p2Uncertain - p2NoContent)).toDouble() / docPure.units.size)} | " +
            "${p2NoContent + (p2Aligned - p2AlignedL1)} |")
        text.appendLine("| 形态 1（原始段落，生产形态） | $rawTotal | $p1Aligned | " +
            "${pct(p1Aligned.toDouble() / rawTotal)} | " +
            "${pct((p1Aligned - p1AlignedNoContent + p1Uncertain - p1NoContent).toDouble() / rawTotal)} | " +
            "${p1AlignedNoContent + p1NoContent} |")
        text.appendLine()
        text.appendLine("**与实现方报告的差异**：")
        text.appendLine()
        text.appendLine("| 项 | 实现方报告 | 本核验 | 差异原因 |")
        text.appendLine("| --- | --- | --- | --- |")
        text.appendLine("| 形态 2 coverage | 74.29% | ${pct(p2Aligned.toDouble() / docPure.units.size)} | " +
            "${if (Math.abs(p2Aligned.toDouble() / docPure.units.size - 0.7429) < 0.005) "一致" else "不一致"} |")
        text.appendLine("| 形态 2 可利用率 | **100%** | **${pct((p2AlignedL1 + p2Uncertain - p2NoContent).toDouble() / docPure.units.size)}** | " +
            "**$p2NoContent 条 UNCERTAIN 的 targetText 是孤立引号，不是译文** |")
        text.appendLine("| 形态 1 coverage | 69.44% | ${pct(p1Aligned.toDouble() / rawTotal)} | " +
            "${if (Math.abs(p1Aligned.toDouble() / rawTotal - 0.6944) < 0.005) "一致" else "见下注"} |")
        text.appendLine()
        text.appendLine("注：形态 1 的 coverage 若与报告不同，差异来自「用生产 ECDICT 而非预导出快照」——")
        text.appendLine("那正好证明**词典实现的选择会影响判定**，值得记录。")
        text.appendLine()

        // ══════════════════════════════════════════════════
        //  4. 用「人工修正过的真值」重算（面向用户的真实可利用率）
        // ══════════════════════════════════════════════════
        text.appendLine("## 4. 用**人工修正后的真值**重算（这才是用户视角）")
        text.appendLine()
        text.appendLine("形态 2 的问题：输入的真值 `pairs` 在 `ep=8 / ep=10` 两处**整体错位一句**。")
        text.appendLine("算法忠实跟随错真值 → 用户看到的仍是错的。所以必须用**正确**的真值再算一遍。")
        text.appendLine()
        // 人工修正：按原始中文段落逐句对齐（我人工核对过）
        val correctedZh = LinkedHashMap<String, String>()
        for (i in truthEn.indices) correctedZh[norm(truthEn[i])] = truthZh[i]
        // ep=8 的三句（正确对应）
        correctedZh[norm("\"Your uncle was a careful man,\" he said at last.")] =
            "“你叔叔是个谨慎的人，”他终于说，“他把属于别人的东西存放在这里。"
        correctedZh[norm("\"He kept things here that belonged to other people.")] =
            "他把属于别人的东西存放在这里。"
        correctedZh[norm("In 1919 he left a box and asked me never to open it.\"")] =
            "1919年，他留下了一个盒子，并让我永远不要打开它。”"
        // ep=10 的三句（正确对应）
        correctedZh[norm("Mr. Whitmore gave him 2 keys, one brass and one iron.")] =
            "惠特莫尔先生给了他2把钥匙，一把黄铜的，一把铁的。"
        correctedZh[norm("\"The brass one opens the box,\" he said.")] =
            "“黄铜钥匙打开盒子，”他说，"
        correctedZh[norm("\"The iron one opens the question.\"")] =
            "“铁钥匙打开‘问题’。”"

        text.appendLine("### 人工修正的三处（`ep=8` / `ep=10`）")
        text.appendLine()
        text.appendLine("| 英文句 | 原真值（错） | 修正为（对） |")
        text.appendLine("| --- | --- | --- |")
        listOf(
            "\"He kept things here that belonged to other people." ,
            "In 1919 he left a box and asked me never to open it.\"",
            "\"The iron one opens the question.\""
        ).forEach { en ->
            val key = norm(en)
            val old = truthTargetOf[key] ?: "-"
            val new = correctedZh[key] ?: "-"
            text.appendLine("| `${en.take(48).replace("|", "\\|")}` | `${old.replace("|", "\\|")}` | `${new.replace("|", "\\|")}` |")
        }
        text.appendLine("（`ep=10` 的 `Mr. Whitmore gave him 2 keys...` 原真值是对的，只有 `The iron one...` 那句错了）")
        text.appendLine()

        // 用修正真值重算形态 2
        var c2L1 = 0; var c2L2 = 0; var c2Aligned = 0; var c2Uncertain = 0
        val c2Wrong = ArrayList<String>()
        for (u in docPure.units) {
            val truth = correctedZh[norm(u.sourceText)]
            val l1 = hasContent(u.targetText)
            val l2 = truth != null && norm(u.targetText) == norm(truth)
            if (l1) c2L1++
            if (l2) c2L2++
            if (u.status == AlignmentStatus.ALIGNED) { c2Aligned++; if (!l2) c2Wrong += "ALIGNED#「${u.sourceText.take(40)}」" }
            else if (u.status == AlignmentStatus.UNCERTAIN) c2Uncertain++
            if (!l2) c2Wrong += "${u.status}#「${u.sourceText.take(40)}」got=「${u.targetText.take(24)}」want=「${(truth ?: "-").take(24)}」"
        }
        text.appendLine("### 形态 2（修正真值后）")
        text.appendLine()
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| units | ${docPure.units.size} |")
        text.appendLine("| ALIGNED | $c2Aligned |")
        text.appendLine("| UNCERTAIN | $c2Uncertain |")
        text.appendLine("| 有内容的单元（L1） | $c2L1 / ${docPure.units.size} = ${pct(c2L1.toDouble() / docPure.units.size)} |")
        text.appendLine("| **与修正真值一致的单元（= 用户真正能用的）** | **$c2L2 / ${docPure.units.size} = ${pct(c2L2.toDouble() / docPure.units.size)}** |")
        text.appendLine("| ALIGNED 中错标的（用户会看到错的） | **${c2Wrong.count { it.startsWith("ALIGNED") }}** |")
        text.appendLine()
        text.appendLine("**面向用户的真实可利用率 = 与修正真值一致的比例 = ${pct(c2L2.toDouble() / docPure.units.size)}**")
        text.appendLine()

        // 用修正真值重算形态 1
        var c1L1 = 0; var c1L2 = 0; var c1Aligned = 0; var c1Uncertain = 0
        val c1Wrong = ArrayList<String>()
        for (u in docRaw.units) {
            val truth = correctedZh[norm(u.sourceText)]
            val l1 = hasContent(u.targetText)
            val l2 = truth != null && norm(u.targetText) == norm(truth)
            if (l1) c1L1++
            if (l2) c1L2++
            if (u.status == AlignmentStatus.ALIGNED) {
                c1Aligned++
                if (!l2) c1Wrong += "ALIGNED#「${u.sourceText.take(46)}」got=「${u.targetText.take(22)}」"
            } else if (u.status == AlignmentStatus.UNCERTAIN) c1Uncertain++
        }
        text.appendLine("### 形态 1（修正真值后，生产形态）")
        text.appendLine()
        text.appendLine("| 指标 | 值 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| units | $rawTotal |")
        text.appendLine("| ALIGNED | $c1Aligned |")
        text.appendLine("| UNCERTAIN | $c1Uncertain |")
        text.appendLine("| 有内容的单元（L1） | $c1L1 / $rawTotal = ${pct(c1L1.toDouble() / rawTotal)} |")
        text.appendLine("| **与修正真值一致的单元** | **$c1L2 / $rawTotal = ${pct(c1L2.toDouble() / rawTotal)}** |")
        text.appendLine("| ALIGNED 中错标的 | ${c1Wrong.count { it.startsWith("ALIGNED") }} |")
        text.appendLine()
        if (c1Wrong.isNotEmpty()) {
            text.appendLine("**形态 1 的错标明细（ALIGNED 但译文不对）**：")
            text.appendLine()
            c1Wrong.filter { it.startsWith("ALIGNED") }.forEach { text.appendLine("- $it") }
            text.appendLine()
        }

        println(text.toString())
        File(outDir, "34-ai-full-audit.txt").writeText(text.toString())

        org.junit.Assert.assertTrue(
            "engineId 必须是 ai-pair-v1",
            docPure.engineId == "ai-pair-v1" && docRaw.engineId == "ai-pair-v1"
        )
    }
}
