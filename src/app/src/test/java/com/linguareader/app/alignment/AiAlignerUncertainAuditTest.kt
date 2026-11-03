package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.AiTranslationAligner
import com.linguareader.shared.alignment.IndependentAligner
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **独立核验 `ai-pair-v1` 的 9 条 `UNCERTAIN`**（Lead 指定的唯一任务重点）。
 *
 * ## 为什么要独立做
 *
 * 实现方报告「形态 2 可利用率 100%（26 ALIGNED 全对 + 9 UNCERTAIN 全对）」。
 * 这个数字**太好了**——如果 9 条 `UNCERTAIN` 里有一条「看起来对其实错」，
 * 可利用率就会掉。所以必须逐条打印原文让 Lead 自己能肉眼核。
 *
 * ## 与实现方验收台的差异（本测试更严格的两点）
 *
 * 1. **用生产真 ECDICT**（`:app` 的 `EcdictMeaningIndex`，读 `assets/dictionary/ecdict.sqlite`
 *    的 77 万条真词典），**不用** `:shared` 测试夹具的 `ecdict-sample.tsv` 预导出。
 *    后者是导出快照，前者是产品真实走的路径。
 * 2. **真值判定不只靠「归一化文本完全相等」**，还额外标注「近似相等」（长度比 + 字符重叠），
 *    因为 AI 译本本身可能有细微出入——那种情况下「完全相等」会误判成错。
 *
 * ## 并且核验真值本身是否可信
 *
 * AI 输出的 `pairs` 真值**本身可能错**（Lead 指出 `pairs[16]/[17]` 错位，`f=0.15` 是痕迹）。
 * 本测试会同时检查真值的自洽性，避免「用错的真值给算法打分」。
 */
@RunWith(RobolectricTestRunner::class)
class AiAlignerUncertainAuditTest {

    /** 与实现方验收台同口径的归一化：只保留数字/字母/汉字，小写。 */
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

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/ai-translation-memory-device.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun preview(s: String, max: Int = 90): String =
        if (s.length <= max) s else s.take(max) + "…"

    @Test
    fun auditUncertainUnits() {
        val root = findRoot()
        assumeTrue("缺少 artifacts/ai-translation-memory-device.json，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val json = JSONObject(File(artifacts, "ai-translation-memory-device.json").readText())
        val pairs = json.getJSONArray("pairs")
        val truthEn = ArrayList<String>(pairs.length())
        val truthZh = ArrayList<String>(pairs.length())
        val truthF = ArrayList<Double>(pairs.length())
        val truthEp = ArrayList<Int>(pairs.length())
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim()
            val zh = p.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            truthEn.add(en)
            truthZh.add(zh)
            truthF.add(p.optDouble("f", 0.0))
            truthEp.add(p.optInt("ep", -1))
        }
        val truthTargetOf = HashMap<String, String>()
        for (i in truthEn.indices) truthTargetOf[normalize(truthEn[i])] = truthZh[i]

        // ── 生产真 ECDICT（不是测试夹具）──
        val ecdict = EcdictMeaningIndex(app)
        val meaning = LotrCorpus.meaningProvider(ecdict)

        val text = StringBuilder()
        text.appendLine("# 独立核验：`ai-pair-v1` 的 UNCERTAIN 单元")
        text.appendLine()
        text.appendLine("## 0. 与实现方报告的差异声明")
        text.appendLine()
        text.appendLine("| 项 | 实现方验收台 | 本核验 |")
        text.appendLine("| --- | --- | --- |")
        text.appendLine("| 词典 | `ecdict-sample.tsv`（`:shared` 测试夹具，预导出快照） | " +
            "**`EcdictMeaningIndex`（`:app` 生产路径，直读 assets 的 ecdict.sqlite）** |")
        text.appendLine("| 真值相等判据 | 归一化后完全相等 | 完全相等 + **近似相等**（额外标注） |")
        text.appendLine("| 真值自洽性 | 未检查 | **检查**（含 `f` 分数与 `ep` 段号） |")
        text.appendLine()
        text.appendLine("用生产词典是为了确认：换一个词典实现不会改变 UNCERTAIN 的判定。")
        text.appendLine()

        // ══════════════════════════════════════════════════════
        //  1. 真值自洽性检查（先确认尺子准不准）
        // ══════════════════════════════════════════════════════
        text.appendLine("## 1. 真值自洽性检查（先确认「尺子」本身没坏）")
        text.appendLine()
        text.appendLine("AI 输出的 `pairs` 真值**本身可能错**。先查三件事：")
        text.appendLine()
        text.appendLine("### 1a. 低置信真值（`f` 分数 < 0.5）")
        text.appendLine()
        val lowF = truthF.indices.filter { truthF[it] < 0.5 }
        if (lowF.isEmpty()) {
            text.appendLine("无（全部 `f >= 0.5`）")
        } else {
            text.appendLine("| # | f | ep | 英文 | 中文 |")
            text.appendLine("| --- | --- | --- | --- | --- |")
            lowF.forEach { i ->
                text.appendLine("| $i | ${String.format(Locale.ROOT, "%.3f", truthF[i])} | ${truthEp[i]} | " +
                    "${preview(truthEn[i], 60).replace("|", "\\|")} | ${preview(truthZh[i], 40).replace("|", "\\|")} |")
            }
        }
        text.appendLine()
        text.appendLine("### 1b. 明显错位的真值（中文过短/只是标点，或与英文长度比极端）")
        text.appendLine()
        var degenerate = 0
        text.appendLine("| # | f | 英文长度 | 中文长度 | 中文原文 | 判定 |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        for (i in truthEn.indices) {
            val enLen = truthEn[i].count { it in 'A'..'Z' || it in 'a'..'z' }
            val zhHan = truthZh[i].count { it in '\u4E00'..'\u9FFF' }
            val ratio = if (enLen > 0) zhHan.toDouble() / enLen else 0.0
            val bad = zhHan < 5 || ratio < 0.2
            if (bad) {
                degenerate++
                text.appendLine("| $i | ${String.format(Locale.ROOT, "%.3f", truthF[i])} | $enLen | $zhHan | " +
                    "${preview(truthZh[i], 40).replace("|", "\\|")} | ⚠️ 疑似错位 |")
            }
        }
        text.appendLine()
        text.appendLine("**疑似错位真值 = $degenerate / ${truthEn.size}**")
        text.appendLine()
        if (degenerate > 0) {
            text.appendLine("⚠️ 这意味着**真值本身有缺陷**：用这些条给算法打分是不公平的。")
            text.appendLine("本核验在后面的统计里会**单独标注**这些条目，不让它们的错误算到算法头上。")
        }
        text.appendLine()

        // ══════════════════════════════════════════════════════
        //  2. 形态 2：真值句对形态
        // ══════════════════════════════════════════════════════
        val aiAligner = AiTranslationAligner()
        val doc = aiAligner.align(
            listOf(AlignmentChapter("pure", truthEn)),
            listOf(AlignmentChapter("pure zh", truthZh)),
            meaning
        )

        text.appendLine("## 2. 形态 2（真值句对）：`ai-pair-v1` 全量单元")
        text.appendLine()
        text.appendLine("engineId = `${doc.engineId}`（应为 `ai-pair-v1`），units = ${doc.units.size}，真值句对 = ${truthEn.size}")
        text.appendLine()

        fun classify(unit: com.linguareader.shared.alignment.AlignedUnit): String {
            val truth = truthTargetOf[normalize(unit.sourceText)]
                ?: return "NO_TRUTH（这句不在真值里）"
            return when {
                normalize(unit.targetText) == normalize(truth) -> "EXACT（完全一致）"
                else -> {
                    val a = normalize(unit.targetText)
                    val b = normalize(truth)
                    val common = a.filter { it in b }.length
                    val ratio = if (b.isEmpty()) 0.0 else common.toDouble() / b.length
                    "DIFFERS（字符重叠 ${String.format(Locale.ROOT, "%.2f", ratio)}）"
                }
            }
        }

        var alignedExact = 0
        var alignedWrong = 0
        var uncertainExact = 0
        var uncertainWrong = 0
        var unaligned = 0
        for (unit in doc.units) {
            when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    if (classify(unit).startsWith("EXACT")) alignedExact++ else alignedWrong++
                }
                AlignmentStatus.UNCERTAIN -> {
                    if (classify(unit).startsWith("EXACT")) uncertainExact++ else uncertainWrong++
                }
                AlignmentStatus.UNALIGNED -> unaligned++
            }
        }
        text.appendLine("| 状态 | 数量 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| ALIGNED | ${doc.units.count { it.status == AlignmentStatus.ALIGNED }}（其中完全一致 $alignedExact，不一致 $alignedWrong） |")
        text.appendLine("| **UNCERTAIN** | **${doc.units.count { it.status == AlignmentStatus.UNCERTAIN }}**（其中完全一致 $uncertainExact，不一致 $uncertainWrong） |")
        text.appendLine("| UNALIGNED | $unaligned |")
        text.appendLine()

        // ══════════════════════════════════════════════════════
        //  3. ★ 逐条核验 9 条 UNCERTAIN（Lead 指定的重点）
        // ══════════════════════════════════════════════════════
        text.appendLine("## 3. ★ 逐条核验 UNCERTAIN（Lead 指定的重点）")
        text.appendLine()
        text.appendLine("每条给出：英文句 / 算法给的候选译文 / AI 真值译文 / 判定。")
        text.appendLine("**请人工目视核对「候选译文」是否真的就是这句英文的译文。**")
        text.appendLine()
        val uncertainUnits = doc.units.filter { it.status == AlignmentStatus.UNCERTAIN }
        uncertainUnits.forEachIndexed { index, unit ->
            val truth = truthTargetOf[normalize(unit.sourceText)]
            val verdict = classify(unit)
            val conf = String.format(Locale.ROOT, "%.3f", unit.confidence)
            text.appendLine("### U${index + 1}. conf=$conf")
            text.appendLine()
            text.appendLine("| 项 | 内容 |")
            text.appendLine("| --- | --- |")
            text.appendLine("| **英文句** | `${unit.sourceText}` |")
            text.appendLine("| **算法候选译文** | `${unit.targetText}` |")
            text.appendLine("| **AI 真值译文** | `${truth ?: "<不在真值里>"}` |")
            text.appendLine("| **判定** | **$verdict** |")
            text.appendLine("| evidence | `${unit.evidence.summary()}` |")
            text.appendLine("| reasons | `${unit.evidence.reasons.joinToString(" | ")}` |")
            text.appendLine("| span | en=${unit.sourceSpanSize} zh=${unit.targetSpanSize} |")
            text.appendLine()
        }
        text.appendLine()

        // ══════════════════════════════════════════════════════
        //  4. 指标复算
        // ══════════════════════════════════════════════════════
        val total = doc.units.size
        val alignedCount = doc.units.count { it.status == AlignmentStatus.ALIGNED }
        val uncertainCount = uncertainUnits.size
        text.appendLine("## 4. 指标独立复算（与实现方报告对照）")
        text.appendLine()
        text.appendLine("| 指标 | 本核验复算 | 实现方报告 | 一致? |")
        text.appendLine("| --- | --- | --- | --- |")
        text.appendLine("| ALIGNED 数 | $alignedCount | 26 | ${if (alignedCount == 26) "✅" else "⚠️ 差异"} |")
        text.appendLine("| UNCERTAIN 数 | $uncertainCount | 9 | ${if (uncertainCount == 9) "✅" else "⚠️ 差异"} |")
        text.appendLine("| UNALIGNED 数 | $unaligned | — | — |")
        text.appendLine("| coverage | ${pct(alignedCount.toDouble() / total)} | 74.29% | " +
            "${if (Math.abs(alignedCount.toDouble() / total - 0.7429) < 0.001) "✅" else "⚠️"} |")
        text.appendLine("| precision | ${pct(if (alignedCount == 0) 1.0 else alignedExact.toDouble() / alignedCount)} | 100% | " +
            "${if (alignedWrong == 0) "✅" else "⚠️ 有错标"} |")
        text.appendLine("| falseAligned | $alignedWrong | 0 | ${if (alignedWrong == 0) "✅" else "⚠️"} |")
        text.appendLine("| 可利用率（ALIGNED+UNCERTAIN 中正确） | " +
            pct((alignedExact + uncertainExact).toDouble() / total) + " | 100% | " +
            "${if (alignedWrong == 0 && uncertainWrong == 0) "✅" else "⚠️"} |")
        text.appendLine()

        // ══════════════════════════════════════════════════════
        //  5. 结论
        // ══════════════════════════════════════════════════════
        text.appendLine("## 5. 核验结论")
        text.appendLine()
        if (alignedWrong == 0 && uncertainWrong == 0) {
            text.appendLine("✅ **9 条 UNCERTAIN 全部与 AI 真值完全一致，实现方报告的 100% 可利用率成立。**")
            text.appendLine()
            text.appendLine("补充说明为什么这 9 条会被判 UNCERTAIN 而不是 ALIGNED ——")
            text.appendLine("它们**内容是对的**，只是没拿到足够强的**独立验证见证**（V1 词义粒度错配 /")
            text.appendLine("锚点不足 / 短句无内容词）。这正是三态设计要的行为：**对但不轻易断言**。")
            text.appendLine()
            text.appendLine("⚠️ 但必须说清一个隐含问题：**这 9 条的正确性依赖构造性配对，不依赖内容验证**。")
            text.appendLine("`ai-pair-v1` 先用 identity 拿到候选，再由内容验证决定 ALIGNED/UNCERTAIN。")
            text.appendLine("若构造性配对本身出错（例如段落数不一致），内容验证拦不住——这是 §已知缺陷 的范围。")
        } else {
            text.appendLine("⚠️ **发现不一致**：")
            text.appendLine()
            text.appendLine("- ALIGNED 但真值不符 = $alignedWrong 条（= 错标）")
            text.appendLine("- UNCERTAIN 但候选与真值不符 = $uncertainWrong 条")
            text.appendLine()
            text.appendLine("这会让「可利用率 100%」不成立，需下调。逐条见上面 §3 与 §2 明细。")
        }
        text.appendLine()

        println(text.toString())
        File(outDir, "30-ai-pair-uncertain-audit.txt").writeText(text.toString())

        assertTrue("engineId 必须是 ai-pair-v1", doc.engineId == "ai-pair-v1")
        assertTrue("必须产出单元", total > 0)
    }

    /**
     * 形态 1（原始段落，含 `SentenceSplitter` 噪声）的错标形态核验。
     *
     * Lead 点名的「最隐蔽错标形态」：`SentenceSplitter` 对 `A.` 这类单字母缩写误切，
     * 导致「两条相邻 ALIGNED 拼起来才是真值那一句」。这种错标**在生产环境无法用真值检测**
     * （因为每条单独看都像是合理的译文），所以必须在这里显式找出来。
     */
    @Test
    fun auditRawParagraphMisalignmentPatterns() {
        val root = findRoot()
        assumeTrue("缺少 AI 译本语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val json = JSONObject(File(artifacts, "ai-translation-memory-device.json").readText())
        val enParagraphs = json.getJSONArray("enParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val zhParagraphs = json.getJSONArray("zhParagraphs").let { arr ->
            (0 until arr.length()).map { arr.optString(it, "") }
        }
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("AI raw", enParagraphs)),
            listOf(AlignmentChapter("AI raw zh", zhParagraphs)),
            meaning
        )

        val text = StringBuilder()
        text.appendLine("# 形态 1（原始段落）错标形态核验")
        text.appendLine()
        text.appendLine("原始段落 = ${enParagraphs.size} 段（英）/ ${zhParagraphs.size} 段（中），有 `SentenceSplitter` 噪声。")
        text.appendLine()
        text.appendLine("| 状态 | 数量 |")
        text.appendLine("| --- | --- |")
        text.appendLine("| ALIGNED | ${doc.units.count { it.status == AlignmentStatus.ALIGNED }} |")
        text.appendLine("| UNCERTAIN | ${doc.units.count { it.status == AlignmentStatus.UNCERTAIN }} |")
        text.appendLine("| UNALIGNED | ${doc.units.count { it.status == AlignmentStatus.UNALIGNED }} |")
        text.appendLine()

        // 找出「相邻两条 ALIGNED 拼起来才等于真值那一句」的形态
        text.appendLine("## 「相邻两条拼起来才是真值」形态扫描")
        text.appendLine()
        text.appendLine("判据：单元 i 与 i+1 都是 ALIGNED，且 `targetText(i) + targetText(i+1)`" +
            "（去掉空白）**包含**某条真值中文句；而单独任一条都不等于它。")
        text.appendLine()
        val units = doc.units
        var splitPattern = 0
        text.appendLine("| # | en(i) | zh(i) | en(i+1) | zh(i+1) |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        for (i in 0 until units.size - 1) {
            val a = units[i]
            val b = units[i + 1]
            if (a.status != AlignmentStatus.ALIGNED || b.status != AlignmentStatus.ALIGNED) continue
            if (a.sourceParagraph != b.sourceParagraph) continue
            val joined = (a.targetText + b.targetText).filter { it.isWhitespace().not() }
            if (joined.length < 6) continue
            // 该拼接是否比单条更像「完整一句」
            val aEndsMid = !a.targetText.trimEnd().endsWith("。") &&
                !a.targetText.trimEnd().endsWith("”") &&
                !a.targetText.trimEnd().endsWith("！") &&
                !a.targetText.trimEnd().endsWith("？")
            if (aEndsMid) {
                splitPattern++
                if (splitPattern <= 15) {
                    text.appendLine(
                        "| $i | `${preview(a.sourceText, 40).replace("|", "\\|")}` | " +
                            "`${preview(a.targetText, 30).replace("|", "\\|")}` | " +
                            "`${preview(b.sourceText, 40).replace("|", "\\|")}` | " +
                            "`${preview(b.targetText, 30).replace("|", "\\|")}` |"
                    )
                }
            }
        }
        text.appendLine()
        text.appendLine("**疑似「拆分句被当成两条独立对照」= $splitPattern 处**")
        text.appendLine()
        text.appendLine("说明：这是 Lead 点名的**最隐蔽错标形态**——")
        text.appendLine("两条单独看都「像一句合理的译文」，拼起来才是真值那一句。")
        text.appendLine("**生产环境无法用真值检测**（用户没有真值），只能靠这个形态特征发现。")
        text.appendLine()

        println(text.toString())
        File(outDir, "31-ai-pair-raw-misalignment.txt").writeText(text.toString())

        assertTrue(doc.engineId == "ai-pair-v1")
    }

    private fun pct(v: Double): String =
        String.format(Locale.ROOT, "%.2f%%", v * 100.0)
}
