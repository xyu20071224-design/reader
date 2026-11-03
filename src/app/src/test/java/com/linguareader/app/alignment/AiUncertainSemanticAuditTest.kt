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
 * **对 9 条 `UNCERTAIN` 的严格语义核验**（不采信「归一化文本相等」这一条判据）。
 *
 * ## 为什么「与真值完全一致」不足以证明对
 *
 * 前面的核验发现一个陷阱：`pairs` 真值在 `ep=8` 与 `ep=10` 两个段落上**本身是错位的**。
 * 于是算法输出 `”`（一个孤立的右引号）时，与（同样错了的）真值「完全一致」，
 * 被判成「正确」——**但用户点开这句英文，看到的会是一个引号，不是译文。**
 *
 * ## 本测试改用「绝对正确性」判据
 *
 * 对每个单元，不看真值，直接问：
 * 1. `targetText` 是否**构成一句中文**（有汉字、至少 4 个汉字）？
 * 2. 它的**语义**是否与英文句对应？（打印出来供人工判定，并做机器预判：
 *    汉字数 ≥ 4 且不是纯标点）
 *
 * 这样「`”`」这类会被立刻识别成「未给出任何译文」。
 */
@RunWith(RobolectricTestRunner::class)
class AiUncertainSemanticAuditTest {

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/ai-translation-memory-device.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun han(s: String): Int = s.count { it in '\u4E00'..'\u9FFF' }

    /** 「是不是一句能读的中文译文」的机器预判（宽松，只排除明显非译文）。 */
    private fun isRealTranslation(text: String): Boolean {
        val h = han(text)
        if (h < 4) return false
        // 纯标点/纯引号形态
        val nonPunct = text.count { it.isLetterOrDigit() || it in '\u4E00'..'\u9FFF' }
        return nonPunct >= 4
    }

    @Test
    fun strictSemanticAuditOfUncertain() {
        val root = findRoot()
        assumeTrue("缺少 AI 译本语料，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val json = JSONObject(File(artifacts, "ai-translation-memory-device.json").readText())
        val enParagraphs = json.getJSONArray("enParagraphs").let { a ->
            (0 until a.length()).map { a.optString(it, "") }
        }
        val zhParagraphs = json.getJSONArray("zhParagraphs").let { a ->
            (0 until a.length()).map { a.optString(it, "") }
        }
        val pairs = json.getJSONArray("pairs")
        val truthEn = ArrayList<String>()
        val truthZh = ArrayList<String>()
        val truthEp = ArrayList<Int>()
        for (i in 0 until pairs.length()) {
            val p = pairs.getJSONObject(i)
            val en = p.optString("es", "").trim()
            val zh = p.optString("zs", "").trim()
            if (en.isEmpty() || zh.isEmpty()) continue
            truthEn.add(en); truthZh.add(zh); truthEp.add(p.optInt("ep", -1))
        }
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("pure", truthEn)),
            listOf(AlignmentChapter("pure zh", truthZh)),
            meaning
        )

        val text = StringBuilder()
        text.appendLine("# 9 条 UNCERTAIN 的严格语义核验")
        text.appendLine()
        text.appendLine("判据不看「与真值是否相等」，只看：**targetText 是不是一句能读的中文译文。**")
        text.appendLine()

        // ══ 1. 段落拆解：暴露真值错位的机制 ══
        text.appendLine("## 1. 先看两个「真值错位」段落的实际拆句")
        text.appendLine()
        for (ep in intArrayOf(8, 10)) {
            text.appendLine("### 段落 ep=$ep / zp=$ep")
            text.appendLine()
            text.appendLine("**英文原文**：`${enParagraphs[ep]}`")
            text.appendLine()
            text.appendLine("**中文原文**：`${zhParagraphs[ep]}`")
            text.appendLine()
            val enS = SentenceSplitter.split(enParagraphs[ep])
            val zhS = SentenceSplitter.split(zhParagraphs[ep])
            text.appendLine("`SentenceSplitter` 拆出 **英文 ${enS.size} 句 / 中文 ${zhS.size} 句**：")
            text.appendLine()
            text.appendLine("| # | 英文句 |")
            text.appendLine("| --- | --- |")
            enS.forEachIndexed { i, s -> text.appendLine("| EN${i + 1} | `${s}` |") }
            text.appendLine()
            text.appendLine("| # | 中文句 |")
            text.appendLine("| --- | --- |")
            zhS.forEachIndexed { i, s -> text.appendLine("| ZH${i + 1} | `${s}` |") }
            text.appendLine()
            text.appendLine("**AI 真值 `pairs` 在这个段落里怎么记的**：")
            text.appendLine()
            text.appendLine("| # | 真值英文 | 真值中文 | 汉字数 | 是否像译文 |")
            text.appendLine("| --- | --- | --- | --- | --- |")
            for (i in truthEn.indices) {
                if (truthEp[i] != ep) continue
                val h = han(truthZh[i])
                val ok = isRealTranslation(truthZh[i])
                text.appendLine(
                    "| $i | `${truthEn[i].take(56).replace("|", "\\|")}` | " +
                        "`${truthZh[i].replace("|", "\\|")}` | $h | ${if (ok) "✅" else "**❌ 不是译文**"} |"
                )
            }
            text.appendLine()
        }

        // ══ 2. 逐条严格判定 ══
        text.appendLine("## 2. 逐条严格判定（9 条 UNCERTAIN）")
        text.appendLine()
        val uncertain = doc.units.filter { it.status == AlignmentStatus.UNCERTAIN }
        var realCount = 0
        var notRealCount = 0
        text.appendLine("| # | conf | 英文句 | 算法 targetText | 汉字数 | 是一句译文? |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        for ((i, u) in uncertain.withIndex()) {
            val ok = isRealTranslation(u.targetText)
            if (ok) realCount++ else notRealCount++
            text.appendLine(
                "| U${i + 1} | ${String.format(Locale.ROOT, "%.2f", u.confidence)} | " +
                    "`${u.sourceText.take(50).replace("|", "\\|")}` | " +
                    "`${u.targetText.replace("|", "\\|")}` | ${han(u.targetText)} | " +
                    "${if (ok) "✅ 是" else "**❌ 否**"} |"
            )
        }
        text.appendLine()
        text.appendLine("**给出真译文的 = $realCount / ${uncertain.size}；没给译文（空/纯标点）= $notRealCount / ${uncertain.size}**")
        text.appendLine()

        // ══ 3. 对每条「不是译文」的做完整追溯 ══
        text.appendLine("## 3. 「不是译文」的条目的完整追溯")
        text.appendLine()
        var traced = 0
        for ((i, u) in uncertain.withIndex()) {
            if (isRealTranslation(u.targetText)) continue
            traced++
            text.appendLine("### U${i + 1}. `${u.sourceText}`")
            text.appendLine()
            text.appendLine("- 算法 targetText：`${u.targetText}`（汉字 ${han(u.targetText)} 个）")
            text.appendLine("- evidence：`${u.evidence.summary()}`")
            text.appendLine("- reasons：`${u.evidence.reasons.joinToString(" | ")}`")
            text.appendLine("- **这条内容对用户毫无用处**：它没有给出这句英文的任何译文。")
            text.appendLine()
        }
        if (traced == 0) text.appendLine("（无）")
        text.appendLine()

        // ══ 4. 修正后的可利用率 ══
        text.appendLine("## 4. 修正后的「可利用率」")
        text.appendLine()
        val aligned = doc.units.filter { it.status == AlignmentStatus.ALIGNED }
        val alignedReal = aligned.count { isRealTranslation(it.targetText) }
        val total = doc.units.size
        text.appendLine("| 口径 | 数量 | 占全部 $total 句 |")
        text.appendLine("| --- | --- | --- |")
        text.appendLine("| ALIGNED（全部） | ${aligned.size} | ${pct(aligned.size.toDouble() / total)} |")
        text.appendLine("| 其中给出真译文的 | $alignedReal | ${pct(alignedReal.toDouble() / total)} |")
        text.appendLine("| UNCERTAIN（全部） | ${uncertain.size} | ${pct(uncertain.size.toDouble() / total)} |")
        text.appendLine("| 其中给出真译文的 | $realCount | ${pct(realCount.toDouble() / total)} |")
        text.appendLine("| **可利用率（ALIGNED + UNCERTAIN 里有真译文的）** | **${alignedReal + realCount}** | " +
            "**${pct((alignedReal + realCount).toDouble() / total)}** |")
        text.appendLine()
        text.appendLine("实现方报告的可利用率 = 100%（26 + 9 = 35/35）。")
        text.appendLine("本核验：**${pct((alignedReal + realCount).toDouble() / total)}**（${alignedReal + realCount}/$total）。")
        text.appendLine()
        if (notRealCount > 0) {
            text.appendLine("⇒ **实现方报告的 100% 被高估了 $notRealCount 条**：")
            text.appendLine("其中 $notRealCount 条 UNCERTAIN 的 `targetText` 不是译文（是孤立引号）。")
            text.appendLine()
            text.appendLine("**成因不是算法「猜错了」，而是它的构造性 1:1 配对继承了一个错位的真值结构**：")
            text.appendLine("英文侧 `SentenceSplitter` 拆出 3 句，中文侧只拆出 2 句 → 最后一条英文句")
            text.appendLine("在中文侧没有对应句，却仍被强行分配了一个「剩余片段」（`”`）。")
            text.appendLine()
            text.appendLine("**这正是 Lead 点名的「最隐蔽错标形态」的一个实例，而且它躲过了「与真值相等」这个判据。**")
        }
        text.appendLine()

        println(text.toString())
        File(outDir, "32-ai-uncertain-strict-semantic.txt").writeText(text.toString())

        assertTrueLocal(doc.engineId == "ai-pair-v1")
    }

    private fun pct(v: Double): String = String.format(Locale.ROOT, "%.2f%%", v * 100.0)

    private fun assertTrueLocal(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
