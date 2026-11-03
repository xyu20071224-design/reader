package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **translit 数字的独立验证**（Lead 要求：不采信开发者的自报数字）。
 *
 * 逐项复算：
 * 1. 含专名锚点的中文句占比（他说 29.03%）
 * 2. 精确档 `score >= 0.9` 的命中率
 * 3. **误报率**：用「专名译法唯一性」做判据——若一个英文名在全书对应多个中文串，它不能做锚点
 * 4. **能救多少句**：V1 失败但 V3 精确命中的句子数
 */
class TransliterationVerifyProbe {

    @Test
    fun verifyTransliterationClaims() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        if (enChapters.isEmpty() || zhChapters.isEmpty()) {
            println("SKIP: 语料缺失")
            return
        }
        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        println("=== 语料 ===")
        println("  en 句=${source.sentCount}  zh 句=${target.sentCount}")

        // ── 1. 抽取全书专名 + 统计每句的命中 ──────────────────────────
        println()
        println("=== 专名抽取 ===")
        val namesBySentence = Array(source.sentCount) { emptyList<String>() }
        val allNames = LinkedHashMap<String, Int>() // name → 出现句数
        for (s in 0 until source.sentCount) {
            val names = Transliteration.extractNames(source.sentText[s])
            namesBySentence[s] = names
            for (name in names) allNames.merge(name, 1, Int::plus)
        }
        println("  抽取到的不同专名数 = ${allNames.size}")
        println("  出现句数 TOP 15：")
        allNames.entries.sortedByDescending { it.value }.take(15).forEach { (name, count) ->
            println("    \"$name\" × $count 句  候选=${Transliteration.candidates(name).take(3)}")
        }

        // ── 2. 专名译法唯一性（Lead 的第三条件）─────────────────────
        println()
        println("=== 专名译法唯一性统计 ===")
        // 对每个专名，找出全书中文里**精确命中**（score >= 0.9）的不同中文串
        // 注意：score 只给分数，不给命中串。这里用「候选表里哪个串出现在哪些句」反推。
        val nameToZhForms = HashMap<String, MutableSet<String>>(allNames.size * 2)
        var zhSentencesWithName = 0
        val zhHasName = BooleanArray(target.sentCount)
        // 性能：13611 × 9336 的全量笛卡尔积不可行。按「英文句的阅读位置」映射到
        // 中文侧的 ±40 句窗口内搜索——远超实际需要（真正的偏差远小于这个数），
        // 足以判断「这个专名在中文里有没有精确命中」。
        for (s in 0 until source.sentCount) {
            val names = namesBySentence[s]
            if (names.isEmpty()) continue
            val center = (s.toLong() * target.sentCount / source.sentCount).toInt()
                .coerceIn(0, target.sentCount - 1)
            val from = (center - 40).coerceAtLeast(0)
            val to = (center + 40).coerceAtMost(target.sentCount - 1)
            for (name in names) {
                val cands = Transliteration.candidates(name)
                if (cands.isEmpty()) continue
                for (t in from..to) {
                    val score = Transliteration.scoreInSentence(name, target.sentText[t])
                    if (score < 0.9) continue
                    zhHasName[t] = true
                    val simplified = Transliteration.toSimplified(target.sentText[t])
                    for (c in cands) if (simplified.contains(c)) {
                        nameToZhForms.getOrPut(name) { LinkedHashSet() }.add(c)
                        break
                    }
                }
            }
        }
        for (t in 0 until target.sentCount) if (zhHasName[t]) zhSentencesWithName++
        println(
            "  含专名锚点的中文句 = $zhSentencesWithName/${target.sentCount} = " +
                "${pct(zhSentencesWithName.toDouble() / target.sentCount)}"
        )
        println("  （开发者自报 29.03%，3708/12774）")
        val unique = nameToZhForms.count { it.value.size == 1 }
        val multi = nameToZhForms.count { it.value.size > 1 }
        println("  译法唯一的专名 = $unique，译法多于一串的 = $multi")
        if (multi > 0) {
            println("  多译法样例（这些不能做锚点）：")
            nameToZhForms.filter { it.value.size > 1 }.entries.take(5).forEach { (n, forms) ->
                println("    \"$n\" → $forms")
            }
        }

        // ── 3. 能救多少句：V1 失败但 V3 精确命中的句子 ────────────────
        println()
        println("=== 核心指标：V1 失败但 V3 精确命中 ===")
        val ecdict = EcdictProvider.load(root)
        if (ecdict == null) {
            println("  SKIP: 词典缺失")
            return
        }
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)
        val verify = VerifyLayer(source, target, density, ecdict)
        verify.build()
        println("  bridge: ${verify.notes}")

        // 用「比例位置配对」做下界探测（不是真值，只看 V3 能覆盖多大范围）
        var v3Hits = 0
        var v1Failed = 0
        var v1FailedV3Hit = 0
        var v1PassedAndV3Hit = 0
        val examples = ArrayList<String>(8)
        for (s in 0 until minOf(source.sentCount, target.sentCount)) {
            if (!source.sentLangUnit[s]) continue
            val t = (s.toLong() * target.sentCount / source.sentCount).toInt()
                .coerceIn(0, target.sentCount - 1)
            val score = verify.score(s, 1, t, 1)
            val v1Ok = score.available && score.recall >= AlignTuning.VERIFY_RECALL_MIN
            // V3：该英文句的专名在配对的中文句里精确命中
            var v3Score = 0.0
            var v3Name = ""
            for (name in namesBySentence[s]) {
                val sc = Transliteration.scoreInSentence(name, target.sentText[t])
                if (sc > v3Score) { v3Score = sc; v3Name = name }
            }
            val v3Ok = v3Score >= 0.9
            if (v3Ok) v3Hits++
            if (!v1Ok) v1Failed++
            if (!v1Ok && v3Ok) {
                v1FailedV3Hit++
                if (examples.size < 8) {
                    examples += "s=$s name=\"$v3Name\" score=${String.format("%.2f", v3Score)} " +
                        "en=\"${source.sentText[s].take(40)}\""
                }
            }
            if (v1Ok && v3Ok) v1PassedAndV3Hit++
        }
        val langUnits = (0 until source.sentCount).count { source.sentLangUnit[it] }
        println("  英文有效句 = $langUnits")
        println("  V3 精确命中（score >= 0.9）的句数 = $v3Hits (${pct(v3Hits.toDouble() / langUnits)})")
        println("  V1 失败（recall 不过门）的句数 = $v1Failed (${pct(v1Failed.toDouble() / langUnits)})")
        println(
            "  **V1 失败 ∧ V3 命中 = $v1FailedV3Hit** " +
                "(${pct(v1FailedV3Hit.toDouble() / v1Failed.coerceAtLeast(1))} of V1 failures)"
        )
        println("  V1 成功 ∧ V3 命中 = $v1PassedAndV3Hit")
        println()
        println("  样例（V1 失败但 V3 命中）：")
        examples.forEach { println("    $it") }

        println()
        println("  → 若 v1FailedV3Hit 显著 > 0，V3 能救回这些句子。")
        println("    注意：这里用的是「比例位置配对」，不是真值配对，所以这只是**上界探测**。")
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
