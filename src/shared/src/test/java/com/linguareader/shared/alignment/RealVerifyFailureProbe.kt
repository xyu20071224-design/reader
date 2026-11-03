package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **真实书 VERIFY_FAILED 归因**（7978 条占 59%，是最大的单一阻断原因）。
 *
 * 要回答：这些句子的 `vLexical` 到底缺什么？
 * - `terms(E) = ∅`（本句英文内容词一个都没进词典桥）→ 桥的覆盖问题
 * - `terms(E) ≠ ∅` 但 `recall < 0.34` → 译文意译/召回问题
 * - `recall` 过线但 `precision` 与 `lift` 都没过 → 双分支问题
 * - `coverableRatio < 0.30` → 可查词比例护栏拦的
 */
class RealVerifyFailureProbe {

    @Test
    fun whyVerifyFailedOnRealCorpus() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        val ecdict = EcdictProvider.load(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty() || ecdict == null) {
            println("SKIP: 语料或词典缺失")
            return
        }

        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val anchors = AnchorLayer(source, target, density)
        anchors.build()
        val chapters = ChapterLayer(source, target, anchors, density)
        chapters.run()
        density.computeBook(chapters.acceptedPairChars, chapters.acceptedPairWords)
        density.computeSeed(anchors.pinResiduals(), target.chapterChars, target.chapterWords)

        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()
        println("=== 验证层 ===")
        println("  lexicalAvailable=${verify.lexicalAvailable}")
        println("  notes=${verify.notes}")
        println("  traditionalCoverage=${String.format("%.4f", verify.traditionalCoverage)}")

        // 用「同序号配对」做下界探测：不追求配对正确，只看 V1 通道在有译文时能不能给出信号
        println()
        println("=== V1 在真实书上的原始信号（同序号配对作为下界探测）===")
        val scores = ArrayList<Double>()
        var termsEmpty = 0
        var tested = 0
        var recallPass = 0
        var twoSidedPass = 0
        var coverableLow = 0
        val n = minOf(source.sentCount, target.sentCount)
        for (i in 0 until n) {
            if (!source.sentLangUnit[i] || !target.sentLangUnit[i]) continue
            tested++
            val score = verify.score(i, 1, i, 1)
            if (!score.available) {
                termsEmpty++
                continue
            }
            scores.add(score.recall)
            if (score.recall >= AlignTuning.VERIFY_RECALL_MIN) recallPass++
            if (score.recall >= AlignTuning.VERIFY_RECALL_MIN &&
                (score.precision >= AlignTuning.VERIFY_PRECISION_MIN ||
                    score.lift >= AlignTuning.VERIFY_LIFT_MIN)
            ) twoSidedPass++
            if (verify.coverableRatio(i) < AlignTuning.MIN_COVERABLE_RATIO) coverableLow++
        }
        scores.sort()
        println("  探测句数=$tested")
        println("  terms(E)=∅（本句无词条可查）：$termsEmpty (${pct(termsEmpty.toDouble() / tested)})")
        println("  V1 available 的句数=${scores.size}")
        if (scores.isNotEmpty()) {
            println(
                "  recall 分布：p10=${fmt(q(scores, 0.1))} p50=${fmt(q(scores, 0.5))} " +
                    "p90=${fmt(q(scores, 0.9))}（门槛 ${AlignTuning.VERIFY_RECALL_MIN}）"
            )
            println("  recall 过门槛：$recallPass/${scores.size} (${pct(recallPass.toDouble() / scores.size)})")
            println(
                "  双分支（recall + precision|lift）过：$twoSidedPass/${scores.size} " +
                    "(${pct(twoSidedPass.toDouble() / scores.size)})"
            )
        }
        println("  coverableRatio < ${AlignTuning.MIN_COVERABLE_RATIO}：$coverableLow/$tested " +
            "(${pct(coverableLow.toDouble() / tested.coerceAtLeast(1))})")

        // 直接看几句真实的 V1 打分，人工核对
        println()
        println("=== 抽样明细（前 5 句，人工核对 V1 是否合理）===")
        var printed = 0
        for (i in 0 until n) {
            if (!source.sentLangUnit[i] || !target.sentLangUnit[i]) continue
            if (printed >= 5) break
            val score = verify.score(i, 1, i, 1)
            println("  i=$i")
            println("    en=\"${source.sentText[i].take(70)}\"")
            println("    zh=\"${target.sentText[i].take(50)}\"")
            println(
                "    terms=${verify.termsAvailable(i)} recall=${fmt(score.recall)} " +
                    "precision=${fmt(score.precision)} lift=${fmt(score.lift)} " +
                    "coverable=${fmt(verify.coverableRatio(i))} available=${score.available}"
            )
            printed++
        }
    }

    // ──────────────────────────────────────────────────────────────

    private fun q(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    private fun fmt(v: Double): String = String.format("%.3f", v)

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根")
    }

    private fun loadEnglishChapters(root: File): List<AlignmentChapter> {
        val metadata = File(root, "artifacts/lotr-book/metadata.json")
        if (!metadata.isFile) return emptyList()
        val json = org.json.JSONObject(metadata.readText())
        val chapters = json.getJSONArray("chapters")
        val out = ArrayList<AlignmentChapter>(chapters.length())
        for (i in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(i)
            val file = File(root, "artifacts/lotr-book/" + chapter.optString("relativePath", ""))
            if (!file.isFile) continue
            out.add(AlignmentChapter(chapter.optString("title", ""), leafBlocks(file)))
        }
        return out
    }

    private fun loadChineseChapters(root: File): List<AlignmentChapter> {
        val dir = File(root, "artifacts/lotr-zh/OEBPS/Text")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.extension.lowercase() == "xhtml" }
            ?.sortedBy { it.name }
            ?.map { AlignmentChapter(it.nameWithoutExtension, leafBlocks(it)) }
            ?: emptyList()
    }

    private fun leafBlocks(file: File): List<String> {
        val document = org.jsoup.Jsoup.parse(file, "UTF-8")
        return document.select(BLOCK_SELECTOR)
            .filter { candidate -> candidate.select(BLOCK_SELECTOR).all { it === candidate } }
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
    }

    private companion object {
        const val BLOCK_SELECTOR =
            "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, " +
                "section, article, header, footer"
    }
}
