package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.AiTranslationAligner
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **位置核对：`targetText` 在中文段落里的相对位置是否正确？**
 *
 * ## 为什么还需要这一层
 *
 * 前一节的「targetText 是不是一句能读的中文」只能抓出「空/纯标点」这一类。
 * 抓不出更隐蔽的一种：**译文本身是好的中文，但它是相邻那一句的译文**。
 *
 * 本次实测就出现了这种形态（`ep=8`）：真值 `pairs` 因为「英文 3 句 / 中文 2 句」而整体
 * 错位一句，于是算法把 EN3 的译文安到了 EN2 头上——**「与真值完全相等」的判据完全看不出来**，
 * 因为真值自己也错了。
 *
 * ## 本节的判据（不依赖真值 `pairs`）
 *
 * 1. 在**原始中文段落**里定位 `targetText` 的字符区间，算出归一化位置 `zt ∈ [0,1]`
 * 2. 在**原始英文段落**里定位 `sourceText` 的字符区间，算出归一化位置 `st ∈ [0,1]`
 * 3. 若 `|st − zt|` 很大（错位超过一个句子的量级），说明这条对照的位置是错的
 *
 * 同时把段落里 `targetText` 的**前后文**打出来，供人工直接判断。
 */
@RunWith(RobolectricTestRunner::class)
class AiPositionalAuditTest {

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/ai-translation-memory-device.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun han(s: String): Int = s.count { it in '\u4E00'..'\u9FFF' }

    private fun norm(s: String): String = s.filter {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in '\u4E00'..'\u9FFF'
    }.lowercase(Locale.ROOT)

    @Test
    fun positionalCorrectnessOfUncertain() {
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
        val meaning = LotrCorpus.meaningProvider(EcdictMeaningIndex(app))

        val doc = AiTranslationAligner().align(
            listOf(AlignmentChapter("raw", enParagraphs)),
            listOf(AlignmentChapter("raw zh", zhParagraphs)),
            meaning
        )

        val text = StringBuilder()
        text.appendLine("# 位置核对：`targetText` 在段落里的相对位置对不对")
        text.appendLine()
        text.appendLine("判据**不依赖真值 `pairs`**：直接在原始英/中段落里定位文本，比较归一化位置。")
        text.appendLine()

        fun positionOf(haystack: String, needle: String): Double {
            if (needle.isBlank()) return -1.0
            val idx = haystack.indexOf(needle)
            if (idx < 0) return -1.0
            if (haystack.length <= 1) return 0.0
            return idx.toDouble() / (haystack.length - 1)
        }

        text.appendLine("## UNCERTAIN 单元的位置核对")
        text.appendLine()
        text.appendLine("`st` = 英文句在**其叶级段落**里的归一化位置；`zt` = `targetText` 在**其叶级段落**里的位置。")
        text.appendLine("两段落本身由构造 1:1 对应，所以位置应大致同步。")
        text.appendLine()
        text.appendLine("| # | st(英) | zt(中) | \\|st−zt\\| | 英文句 | targetText |")
        text.appendLine("| --- | --- | --- | --- | --- | --- |")
        val uncertain = doc.units.filter { it.status == AlignmentStatus.UNCERTAIN }
        var suspicious = 0
        for ((i, u) in uncertain.withIndex()) {
            val enP = positionOf(u.sourceParagraph, u.sourceText)
            val ztP = positionOf(u.targetParagraph, u.targetText)
            val delta = if (enP >= 0 && ztP >= 0) Math.abs(enP - ztP) else Double.NaN
            val flag = if (delta.isNaN()) "?" else if (delta > 0.35) { suspicious++; "⚠️" } else "✅"
            text.appendLine(
                "| U${i + 1} | ${if (enP >= 0) String.format(Locale.ROOT, "%.2f", enP) else "?"} | " +
                    "${if (ztP >= 0) String.format(Locale.ROOT, "%.2f", ztP) else "?"} | " +
                    "${if (delta.isNaN()) "?" else String.format(Locale.ROOT, "%.2f", delta)} $flag | " +
                    "`${u.sourceText.take(44).replace("|", "\\|")}` | " +
                    "`${u.targetText.take(30).replace("|", "\\|")}` |"
            )
        }
        text.appendLine()
        text.appendLine("**位置明显错位（\\|st−zt\\| > 0.35）的 = $suspicious / ${uncertain.size}**")
        text.appendLine()
        text.appendLine("说明：一个段落的正常句序下，英中位置应大致同步（差值趋近 0）。")
        text.appendLine("差值大 = 这条中文来自段落的另一处 = **语义错位**。")
        text.appendLine()

        // ══ 形态 1（原始段落）里 ep=8 的真实产出 ══
        text.appendLine("## 形态 1（原始段落）下 `ep=8` 段落产出的全部单元")
        text.appendLine()
        text.appendLine("**英文原文**：`${enParagraphs[8]}`")
        text.appendLine()
        text.appendLine("**中文原文**：`${zhParagraphs[8]}`")
        text.appendLine()
        text.appendLine("| # | status | 英文句 | targetText | 人工判断 |")
        text.appendLine("| --- | --- | --- | --- | --- |")
        for ((i, u) in doc.units.withIndex()) {
            if (u.sourceParagraph != enParagraphs[8]) continue
            text.appendLine(
                "| $i | ${u.status} | `${u.sourceText.take(50).replace("|", "\\|")}` | " +
                    "`${u.targetText.take(40).replace("|", "\\|")}` | |"
            )
        }
        text.appendLine()
        text.appendLine("**请人工核对**：")
        text.appendLine()
        text.appendLine("- `\"He kept things here that belonged to other people.` 的正确译文是「他把属于别人的东西存放在这里。」")
        text.appendLine("- 若它拿到的是包含「你叔叔是个谨慎的人…」的整句，那是 **2:1 合并**（可接受，但展示了上一句内容）")
        text.appendLine("- `In 1919 he left a box...` 的正确译文是「1919年，他留下了一个盒子，并让我永远不要打开它。」")
        text.appendLine()

        println(text.toString())
        File(outDir, "33-ai-positional-audit.txt").writeText(text.toString())

        org.junit.Assert.assertTrue(doc.engineId == "ai-pair-v1")
    }
}
