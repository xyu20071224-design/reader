package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **词典覆盖率消融实验**（Lead 要求）：同一语料、同一算法，只换 `MeaningProvider`。
 *
 * ## 为什么不用 `SyntheticBilingualFixture` 做这件事
 *
 * 那个语料**每句长度同构**（都是 8 词 + 3 编码 token / 16 汉字 + 3 编码字），于是任意
 * 两句互换的 `pairCost` 几乎相同 → `margin ≈ 0` → G3 一律失败 → 覆盖率天然为 0。
 * 那**不是 bug，是设计要的行为**（设计 §11 F1「等长陷阱」：结构上不可分时必须弃权，
 * 而不是赌一个）。用它做消融，测出来的是语料属性，不是词典覆盖率。
 *
 * 所以主实验用 [VariedTruthCorpus]：长度有方差、真值已知、中英严格 1:1。
 *
 * ## 三组
 *
 * | 组 | provider | 模拟什么 |
 * | --- | --- | --- |
 * | 1 | `sparse`（只覆盖约 20 个词） | ECDICT 对本书词汇覆盖很差的最坏情形 |
 * | 2 | `oracle`（完美：每个实词都返回真值译文片段） | 词典 100% 正确 + 100% 覆盖的理想上界 |
 * | 3 | `null` | 无词典退化模式 |
 *
 * 判据（Lead 给的）：若 (2) 覆盖率显著跳高且 falseAligned == 0 → 瓶颈是词典覆盖；
 * 若 (2) 也只有 0.5–0.6 → 瓶颈在算法别处；(2) 一旦 falseAligned > 0 → `vLexical` 是漏洞。
 */
class LexiconAblationTest {

    private class Row(
        val name: String,
        val coverage: Double,
        val falseAligned: Int,
        val uncertain: Int,
        val unaligned: Int,
        val reasons: List<Pair<String, Int>>
    )

    @Test
    fun ablation_sparseVsOracleVsNone_onVariedLengthCorpus() {
        val corpus = VariedTruthCorpus.build()
        val engine = IndependentAligner()

        val rows = listOf(
            measure("1.sparse", corpus, engine, corpus.sparseProvider()),
            measure("2.oracle", corpus, engine, corpus.oracleProvider()),
            measure("3.none", corpus, engine, null)
        )

        println("=== 词典覆盖率消融（长度有方差、真值已知的语料, engine=${engine.engineId}）===")
        println(
            "语料：en=${corpus.sourceSentences} 句 / ${corpus.paragraphCount} 段 / " +
                "${corpus.chapterCount} 章（句长 3–25 词，长度有方差）"
        )
        println("| provider | coverage | falseAligned | uncertain | unaligned |")
        println("| --- | --- | --- | --- | --- |")
        rows.forEach { row ->
            println(
                "| ${row.name} | ${pct(row.coverage)} | ${row.falseAligned} | " +
                    "${row.uncertain} | ${row.unaligned} |"
            )
        }
        println()
        rows.forEach { row ->
            println("[${row.name}] 未对齐原因分布：")
            row.reasons.forEach { (why, count) -> println("    $why -> $count") }
        }

        // ── 硬红线：任何 provider 下都不许错标（断言，不是打印）──────
        rows.forEach { row ->
            assertEquals(0, row.falseAligned, "[${row.name}] 出现错标")
        }
    }

    /**
     * 对照：合成 fixture 上三组都是低覆盖。
     *
     * 这条的作用是**证明等长陷阱下弃权是对的**（设计 §11 F1），并说明「fixture 上的
     * 覆盖率不能用来评价词典质量”。
     */
    @Test
    fun ablation_homogeneousFixture_onlyProvesAbstention() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val engine = IndependentAligner()
        val rows = listOf(
            measureFixture("1.sparse21", book, engine, simpleSparse()),
            measureFixture("2.oracle", book, engine, OracleLexicon(book)),
            measureFixture("3.none", book, engine, null)
        )
        println("=== 对照：合成 fixture（每句长度同构 → 设计 §11 F1 的等长陷阱）===")
        println("| provider | coverage | falseAligned | uncertain | unaligned |")
        println("| --- | --- | --- | --- | --- |")
        rows.forEach { row ->
            println(
                "| ${row.name} | ${pct(row.coverage)} | ${row.falseAligned} | " +
                    "${row.uncertain} | ${row.unaligned} |"
            )
        }
        rows.forEach { row ->
            println("[${row.name}] 未对齐原因分布：")
            row.reasons.forEach { (why, count) -> println("    $why -> $count") }
        }
        rows.forEach { row -> assertEquals(0, row.falseAligned, "[${row.name}] 错标") }
    }

    // ──────────────────────────────────────────────────────────────

    private fun measure(
        name: String,
        corpus: VariedTruthCorpus,
        engine: SentenceAligner,
        provider: MeaningProvider?
    ): Row {
        val document = engine.align(corpus.source, corpus.translation, provider)
        val truth = corpus.alignmentTruth()
        val projection = AlignmentProjection.project(document, truth)
        val metrics = AlignmentMetricsCalculator.compute(
            variant = name,
            engine = engine.engineId,
            truth = truth,
            projection = projection
        )
        return Row(
            name = name,
            coverage = metrics.coverage,
            falseAligned = metrics.falseAlignedCount,
            uncertain = metrics.uncertainSentences,
            unaligned = metrics.unalignedSentences,
            reasons = histogramOf(document)
        )
    }

    private fun measureFixture(
        name: String,
        book: SyntheticBilingualFixture.FixtureBook,
        engine: SentenceAligner,
        provider: MeaningProvider?
    ): Row {
        val document = engine.align(book.source, book.translation, provider)
        val projection = AlignmentProjection.project(document, book.truth)
        val metrics = AlignmentMetricsCalculator.compute(
            variant = book.variant.label,
            engine = engine.engineId,
            truth = book.truth,
            projection = projection
        )
        return Row(
            name = name,
            coverage = metrics.coverage,
            falseAligned = metrics.falseAlignedCount,
            uncertain = metrics.uncertainSentences,
            unaligned = metrics.unalignedSentences,
            reasons = histogramOf(document)
        )
    }

    private fun histogramOf(document: AlignmentDocument): List<Pair<String, Int>> {
        val histogram = HashMap<String, Int>()
        document.units.forEach { unit ->
            if (unit.status == AlignmentStatus.ALIGNED) return@forEach
            val key = when (unit.status) {
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                else -> {
                    val why = unit.evidence.reasons.firstOrNull() ?: "unknown"
                    "UNCERTAIN:" + why.substringBefore('=').substringBefore(' ')
                }
            }
            histogram.merge(key, 1, Int::plus)
        }
        return histogram.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private fun simpleSparse(): MeaningProvider {
        val mapping = mapOf(
            "elder" to "长者", "hunter" to "猎人", "smith" to "铁匠", "singer" to "歌者",
            "remembered" to "想起", "described" to "描述", "followed" to "跟随",
            "watched" to "注视", "river" to "河", "mountain" to "山",
            "city" to "城", "sea" to "海"
        )
        return MeaningProvider { word -> mapping[word.lowercase()]?.let { listOf(it) } ?: emptyList() }
    }
}

/**
 * **Oracle 词典**：模拟「词典 100% 正确 + 100% 覆盖」的**上界**。
 *
 * ## 关键：它必须像 ECDICT 那样给「词条的稳定词法义」，而不是「本次出现的整句内容」
 *
 * 第一版实现犯过这个错：把某个英文词在**每一处**出现时所在中文句的 2–4 字片段全部
 * 合并成候选。于是 `elder` 的候选里同时有「那条河」「那座山」「那座城」……几十个字面，
 * 而**任意一个**正确的中文句只含其中一小部分 → `recall ≈ 0.013`，连完美配对都过不了门。
 *
 * 那不是算法的问题，是「词典给得太宽」的问题——而真实 ECDICT 不会这样：它对一个词给
 * 少量稳定的词法义（`elder → 长者 / 年长的`），不会把它出现过的所有句子内容都塞进来。
 *
 * 所以这里取「该词在所有出现中**稳定共现**的片段」（出现率 ≥ [STABLE_RATIO]），
 * 这才是 ECDICT 的语义。这个修正**没有**放宽算法的任何门槛。
 *
 * 它保留的唯一比 ECDICT 强的地方：多义词被真值唯一化（这一点已在报告里声明为理想化）。
 */
internal class OracleLexicon(private val book: SyntheticBilingualFixture.FixtureBook) : MeaningProvider {

    private val mapping: Map<String, List<String>> = buildMapping()

    override fun chineseCandidates(englishWord: String): List<String> =
        mapping[englishWord.lowercase()] ?: emptyList()

    private fun buildMapping(): Map<String, List<String>> {
        val truth = book.truth
        // 词 → （片段 → 出现在多少句里）+ 该词总共出现多少句
        val perWord = HashMap<String, HashMap<String, Int>>()
        val occurrences = HashMap<String, Int>()

        val englishSentences = book.source.flatMap { chapter ->
            chapter.paragraphs.flatMap { paragraph -> SentenceSplitter.split(paragraph) }
        }
        englishSentences.forEach { english ->
            val key = AlignmentProjection
                .sourceKeysIn(english, truth.sourceMarkerUniverse)
                .firstOrNull() ?: return@forEach
            val unitId = truth.trueTargetUnitsOf(key).firstOrNull() ?: return@forEach
            val fragments = chineseFragments(truth.targetUnits[unitId].text)
            if (fragments.isEmpty()) return@forEach
            englishWords(english).forEach { word ->
                occurrences.merge(word, 1, Int::plus)
                val counter = perWord.getOrPut(word) { HashMap(16) }
                fragments.forEach { counter.merge(it, 1, Int::plus) }
            }
        }

        // 只保留「稳定共现」的片段：这才是词的词法义，而不是它在某句里的偶然上下文。
        val out = HashMap<String, List<String>>(perWord.size * 2)
        for ((word, counter) in perWord) {
            val total = occurrences[word] ?: 0
            if (total <= 0) continue
            val threshold = kotlin.math.max(2, kotlin.math.ceil(total * STABLE_RATIO).toInt())
            val stable = counter.entries
                .filter { it.value >= threshold }
                .sortedByDescending { it.value }
                .take(MAX_GLOSSES)
                .map { it.key }
            if (stable.isNotEmpty()) out[word] = stable
        }
        return out
    }

    private fun englishWords(sentence: String): List<String> {
        val result = ArrayList<String>(8)
        var i = 0
        while (i < sentence.length) {
            val c = sentence[i]
            if (TextScan.isLetter(c)) {
                var j = i + 1
                while (j < sentence.length && (TextScan.isLetter(sentence[j]) ||
                        sentence[j].isDigit() || sentence[j] == '\'' || sentence[j] == '-')
                ) j++
                val word = sentence.substring(i, j).lowercase()
                when {
                    word.length < 2 -> Unit
                    // 编码 token（`zqk vur nif`）不是英文词，词典里不该有它们。
                    word in CODE_SYLLABLES -> Unit
                    word in FUNCTION_WORDS -> Unit
                    else -> result.add(word)
                }
                i = j
            } else i++
        }
        return result
    }

    private fun chineseFragments(text: String): List<String> {
        val cjk = text.filter { TextScan.isCjk(it) }
        if (cjk.length < 2) return emptyList()
        val result = LinkedHashSet<String>()
        for (len in 2..4) {
            var from = 0
            while (from + len <= cjk.length) {
                result.add(cjk.substring(from, from + len))
                from++
            }
        }
        return result.toList()
    }

    private companion object {
        /** 片段在该词的所有出现里至少占这个比例，才算「稳定词法义」。 */
        const val STABLE_RATIO = 0.5

        /** 与 ECDICT 的词条释义规模对齐（一个词 3–6 个义项）。 */
        const val MAX_GLOSSES = 6

        /** 合成语料的英文编码音节：它们是伪词，词典里查不到（模拟真实专名）。 */
        val CODE_SYLLABLES = setOf(
            "zqk", "vur", "nif", "gle", "drax", "mub", "phet", "jox", "civ", "wam", "ryu", "kob"
        )

        val FUNCTION_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "was", "were", "are", "his",
            "her", "its", "they", "them", "their", "there", "here", "have", "has", "had",
            "will", "would", "could", "should", "not", "but", "you", "our", "who", "what",
            "did", "does", "do", "is", "be", "been", "being", "am", "of", "to", "in", "on",
            "at", "as", "by", "or", "if", "so", "no", "yes", "said", "says", "say", "into",
            "over", "under", "about", "after", "before", "again", "more", "most", "very",
            "just", "now", "only", "also", "too", "such", "own", "same", "other", "each",
            "every", "both", "few", "many", "much", "was", "it", "he", "she", "we", "my"
        )
    }
}
