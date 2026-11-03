package com.linguareader.app.alignment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.translation.EcdictMeaningIndex
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.alignment.AlignmentChapter
import com.linguareader.shared.alignment.AlignmentDocument
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.IndependentAligner
import com.linguareader.shared.tts.SentenceSplitter
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMatchLevel
import com.linguareader.shared.translation.TranslationMemory
import com.linguareader.shared.translation.TranslationMemoryIndex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **T5 独立验收：真实语料 × 100 条人工金标准**（验证者，不采信实现者/评估者自述）。
 *
 * ## 这条测试回答什么
 *
 * 1. 把 `artifacts/lotr-book`（英文原版，48 章 app 导入格式）与 `artifacts/lotr-zh`
 *    （朱学恒中译本，解包 EPUB 37 章）**按生产口径**抽成叶级段落，喂给
 *    `IndependentAligner`（`engineId=independent-v1`），拿整本 `AlignmentDocument`。
 * 2. 对 `artifacts/alignment-eval/golden-samples.json` 的 **100 条人工判定**逐条
 *    定位 → 找到新算法给出的单元 → 记录 `status / targetText / confidence /
 *    evidence.summary()`，与人工 `verdict` 做四格表。
 * 3. 同一批样本上跑**旧 V8** `TranslationAligner.align(...)`（只调用、不修改），
 *    拿它与人工判定的一致性对照。
 *
 * ## 口径声明（所有数字的唯一解释依据）
 *
 * - **抽取**：`TtsTextExtractor`（叶级块），与阅读器/TTS 同源；不复制选择器。
 * - **分句**：新引擎内部委托 `SentenceSplitter.split`（不传上限）；旧引擎同样。
 * - **词典**：生产同一个 `EcdictMeaningIndex`（`src/app/.../translation/TranslationMemoryRepository.kt`
 *   里的 `internal class`），指向 `assets/dictionary/ecdict.sqlite` 的 filesDir 副本。
 *   新引擎只是把 `Set<String>` 适配成 `List<String>`，**词典这一项在 A/B 里是同一变量**。
 * - **`coverageT`**：契约里 `truthTranslatableSentences` 由**语料真值**填，本机只有
 *   100 条样本真值、没有整本真值，因此**整本 coverageT 不可权威计算**。本测试报
 *   `coverage`（raw，主判据 §4.1 也照样要打印）+「结构性上限估计」`aligned/(total-structural)`
 *   （即把确实无译文的封面/目录/版权章从分母剔除，这是 coverageT 的**上界**）。
 *   该估计**不是**契约意义的 coverageT，报告里必须如此标注。
 *
 * ## 为什么把结果写文件
 *
 * Gradle 默认吞掉 test stdout（`--info` 才能看到）。所有数字同时写进
 * `${java.io.tmpdir}/t5-verify/` 下的一组 txt/json，报告每个数字都能对上那里的原始输出。
 */
@RunWith(RobolectricTestRunner::class)
class LotrIndependentAlignmentVerificationTest {

    // ──────────────────────────────────────────────────────────────
    //  入口
    // ──────────────────────────────────────────────────────────────

    @Test
    fun realBookAndGoldenSamples() {
        val root = LotrCorpus.findRoot()
        assumeTrue("缺少 artifacts 真实语料（lotr-book/lotr-zh/golden-samples），跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val outDir = File(System.getProperty("java.io.tmpdir"), "t5-verify").apply { mkdirs() }

        // ── 1. 抽取（与阅读器同源）────────────────────────────────
        val extractStarted = System.currentTimeMillis()
        val extractor = TtsTextExtractor()
        val enBook = LotrCorpus.readEnBook(File(artifacts, "lotr-book"))
        val zhBook = LotrCorpus.readZhBook(File(artifacts, "lotr-zh"))
        val enBlocks = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhBlocks = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        val extractMillis = System.currentTimeMillis() - extractStarted

        val enChapters = enBook.chapters.indices.map {
            AlignmentChapter(enBook.chapters[it].title, enBlocks[it])
        }
        val zhChapters = zhBook.chapters.indices.map {
            AlignmentChapter(zhBook.chapters[it].title, zhBlocks[it])
        }

        val header = buildString {
            appendLine("# T5 独立验收原始输出")
            appendLine("抽取耗时=${extractMillis}ms")
            appendLine("英: ${enChapters.size}章/${enChapters.sumOf { it.paragraphs.size }}段")
            appendLine("中: ${zhChapters.size}章/${zhChapters.sumOf { it.paragraphs.size }}段")
            appendLine("maxHeap=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")
            appendLine("java.version=${System.getProperty("java.version")}")
            appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        }
        println(header.trim())
        File(outDir, "00-header.txt").writeText(header)

        // ── 2. 真 ECDICT（生产同一个实现）──────────────────────────
        val app = ApplicationProvider.getApplicationContext<Application>()
        val meaningIndex = EcdictMeaningIndex(app)
        val meaning = LotrCorpus.meaningProvider(meaningIndex)
        // 词典可用性探针：证明这道桥真的接上了，而不是静默空转。
        val probeWords = listOf("hobbit", "ring", "walk", "dark", "shadow", "river")
        val probe = probeWords.joinToString(", ") { w ->
            val n = runCatching { meaning.chineseCandidates(w).size }.getOrDefault(-1)
            "$w=$n"
        }
        println("[dict] ECDICT 探针: $probe")
        File(outDir, "01-ecdict-probe.txt").writeText(
            "words=$probe\n" +
                probeWords.joinToString("\n") { w ->
                    "$w -> " + runCatching { meaning.chineseCandidates(w).take(6) }.getOrNull()
                }
        )

        // ── 3. 新算法跑整本（计时 + 堆峰值采样）───────────────────
        val engine = IndependentAligner()
        val newRun = timedRun { engine.align(enChapters, zhChapters, meaning) }
        val doc = newRun.value

        writeBookMetrics(outDir, "02-new-book-metrics.txt", doc, newRun.millis, newRun.peakHeapMb)

        // ── 4. 无词典退化模式（对照用，明确标注）───────────────────
        val nullRun = timedRun { engine.align(enChapters, zhChapters, null) }
        val nullDoc = nullRun.value
        writeBookMetrics(outDir, "03-new-book-metrics-nodict.txt", nullDoc, nullRun.millis, nullRun.peakHeapMb)

        // ── 5. 旧 V8（只调用，不修改）─────────────────────────────
        val oldRun = timedRun {
            TranslationAligner.align(enBlocks, zhBlocks, meaningIndex)
        }
        val oldPairs = oldRun.value
        val oldMetrics = buildString {
            appendLine("# 旧 V8")
            appendLine("engine=TranslationAligner VERSION=${TranslationAligner.VERSION}")
            appendLine("句对总数=${oldPairs.size}")
            appendLine("句级对数=${oldPairs.count { it.enSentence.isNotBlank() }}")
            appendLine("段级对数=${oldPairs.count { it.enSentence.isBlank() }}")
            appendLine(
                "平均置信度=" + fmt4(
                    oldPairs.map { it.confidence.toDouble() }.average()
                )
            )
            appendLine("整本耗时=${oldRun.millis}ms")
            appendLine("peakHeap=${oldRun.peakHeapMb}MB")
            appendLine("覆盖章节=${oldPairs.map { it.enChapter }.toSet().size}/${enBlocks.size}")
        }
        println(oldMetrics.trim())
        File(outDir, "04-old-v8-metrics.txt").writeText(oldMetrics)

        // ── 6. 100 条金标准逐条对账 ───────────────────────────────
        val fixture = File(artifacts, "alignment-eval/golden-samples.json")
        val samples = GoldenSamples.parse(fixture)
        val unitIndex = GoldenSamples.UnitIndex(doc)
        val oldMemory = buildOldIndex(oldPairs)
        val oldIndex = TranslationMemoryIndex(oldMemory)

        val rows = ArrayList<GoldenSamples.Row>()
        val oldDisplays = LinkedHashMap<String, OldDisplay>()
        for (sample in samples) {
            if (sample.garbage) {
                rows += GoldenSamples.Row(sample, null, null)
                continue
            }
            val located = GoldenSamples.locate(sample, enBlocks)
            val unit = located?.let { unitIndex.find(it, sample.en) }
            rows += GoldenSamples.Row(sample, located, unit)
            oldDisplays[sample.id] = if (located == null) {
                OldDisplay("NOT_LOCATED", "", 0f)
            } else {
                val result = oldIndex.lookup(located.chapter, sample.en, located.paragraph)
                if (result == null) OldDisplay("MISS", "", 0f)
                else OldDisplay(result.matchLevel.name, result.chinese, result.confidence)
            }
        }

        writeGoldenRows(outDir, "05-golden-rows.json", rows, oldDisplays)
        writeFourCell(outDir, "06-four-cell.txt", rows)
        writeOldComparison(outDir, "07-old-vs-new.txt", rows, oldDisplays)
        writeErrorList(outDir, "08-false-aligned-list.txt", rows)
        writeAlignedAudit(outDir, "10-all-aligned-units.txt", doc)
        writeUncertainAudit(outDir, "11-uncertain-contract-violation.txt", doc)

        // ── 7. 硬事实（不设概率门：金标准只有 100 条）──────────────
        // 唯一的硬断言：契约 §2.2「units 覆盖每一条英文句，不许静默丢句」。
        // 独立复算英文句总数（不借引擎的 stats）：叶级段落逐段 SentenceSplitter.split，
        // 与 TextPipeline 的口径相同（不传 maxSentenceLength）。引擎少产一个单元 =
        // 静默丢句 = 契约违约，这里必须红。
        val expectedUnits = enChapters.sumOf { chapter ->
            chapter.paragraphs.sumOf { SentenceSplitter.split(it).size }
        }
        File(outDir, "09-unit-coverage-check.txt").writeText(
            "SentenceSplitter 复算英文句总数=$expectedUnits\n" +
                "新算法 units=${doc.units.size}\n" +
                "差值=${doc.units.size - expectedUnits}\n" +
                "stats.sourceSentenceTotal=${doc.stats.sourceSentenceTotal}\n" +
                "差（units - sourceSentenceTotal，应等于非语言单位数）=" +
                (doc.units.size - doc.stats.sourceSentenceTotal)
        )
        assertEquals(
            "契约 §2.2：units 必须覆盖每一条英文句，不许静默丢句",
            expectedUnits,
            doc.units.size
        )
        assertTrue("新算法一个单元都没产出", doc.units.isNotEmpty())
        assertTrue(
            "新引擎 self-report 的 engineId 不对: ${doc.engineId}",
            doc.engineId == IndependentAligner.ENGINE_ID
        )
        println("[done] 原始输出目录: ${outDir.absolutePath}")
    }

    // ──────────────────────────────────────────────────────────────
    //  报告写盘
    // ──────────────────────────────────────────────────────────────

    private fun writeBookMetrics(outDir: File, name: String, doc: AlignmentDocument, millis: Long, peakMb: Long) {
        val stats = doc.stats
        val nonLangUnits = doc.units.size - stats.sourceSentenceTotal
        val structuralAdjDenominator = stats.sourceSentenceTotal - stats.structuralSkipped
        val structuralEstimate = if (structuralAdjDenominator > 0) {
            stats.sourceSentencesAligned.toDouble() / structuralAdjDenominator
        } else 0.0
        val text = buildString {
            appendLine("# ${doc.engineId} alignerVersion=${doc.alignerVersion}")
            appendLine("stats.summaryLine() = ${stats.summaryLine()}")
            appendLine("sourceSentenceTotal（英文有效句，纯标点/空块不计）=${stats.sourceSentenceTotal}")
            appendLine("units 总数（含非语言单位）=${doc.units.size}")
            appendLine("非语言单位（NOT_A_LANGUAGE_UNIT 量级）=${nonLangUnits}")
            appendLine("sourceSentencesAligned=${stats.sourceSentencesAligned}")
            appendLine("targetSentenceTotal=${stats.targetSentenceTotal}")
            appendLine("coverage（raw）=${fmt4(stats.coverage)}")
            appendLine("translatableCoverage（契约值，真值未知时=coverage）=${fmt4(stats.translatableCoverage)}")
            appendLine("truthTranslatableSentences=${stats.truthTranslatableSentences}（实现不填真值 ⇒ 0）")
            appendLine("structuralSkipped=${stats.structuralSkipped}")
            appendLine(
                "coverageT 上界估计 = aligned/(total-structural) = " +
                    fmt4(structuralEstimate) + "  （分母=${structuralAdjDenominator}；**不是契约 coverageT**）"
            )
            appendLine("ALIGNED 单元=${stats.alignedUnits}")
            appendLine("UNCERTAIN 单元=${stats.uncertainUnits}")
            appendLine("UNALIGNED 单元=${stats.unalignedUnits}")
            appendLine("merged（按 DP 边计）=${stats.mergedUnits}")
            appendLine("uncertainRate=${fmt4(stats.uncertainRate)}")
            appendLine("整本耗时=${millis}ms")
            appendLine("peakHeap=${peakMb}MB")
            appendLine("alignedPrecision（实现不填）=${stats.alignedPrecision}")
            appendLine()
            appendLine("## skipReason 分布（UNALIGNED）")
            doc.unaligned().groupingBy { it.skipReason?.name ?: "(null)" }.eachCount()
                .toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
            appendLine()
            // ── 两个覆盖率口径（Lead 指定：都要报，不许只报高的）──────────
            val uncertainAll = doc.uncertain()
            val uncertainWithText = uncertainAll.filter { it.targetText.isNotBlank() }
            val usableStrict = stats.sourceSentencesAligned.toDouble() / stats.sourceSentenceTotal.coerceAtLeast(1)
            val usableWithUncertain =
                (stats.sourceSentencesAligned + uncertainAll.size).toDouble() /
                    stats.sourceSentenceTotal.coerceAtLeast(1)
            val usableWithTextOnly =
                (stats.sourceSentencesAligned + uncertainWithText.size).toDouble() /
                    stats.sourceSentenceTotal.coerceAtLeast(1)
            appendLine("## 两个「覆盖率」口径（必须都报）")
            appendLine()
            appendLine("| 口径 | 分子 | 覆盖率 |")
            appendLine("| --- | --- | --- |")
            appendLine("| **严格覆盖率**（只算 ALIGNED） | ${stats.sourceSentencesAligned} | **${fmt4(usableStrict)}** |")
            appendLine("| 可用覆盖率（ALIGNED + 全部 UNCERTAIN） | ${stats.sourceSentencesAligned + uncertainAll.size} | ${fmt4(usableWithUncertain)} |")
            appendLine("| **可用覆盖率（ALIGNED + 带非空 targetText 的 UNCERTAIN）** | " +
                "${stats.sourceSentencesAligned + uncertainWithText.size} | **${fmt4(usableWithTextOnly)}** |")
            appendLine()
            appendLine("为什么第三个才是产品真实能交付的：`UNCERTAIN` 但 `targetText` 为空的单元" +
                "（${uncertainAll.size - uncertainWithText.size} 条，占 UNCERTAIN 的 " +
                "${fmt4((uncertainAll.size - uncertainWithText.size).toDouble() / uncertainAll.size.coerceAtLeast(1))}）")
            appendLine("展示给用户只会是一片空白——契约要求 UNCERTAIN「有候选」，它们没有。")
            appendLine()
            appendLine("## notes（降级/诊断自述）")
            doc.notes.forEach { appendLine(it) }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    /**
     * 四格表。口径：
     * - 「人工正」= `ok` 或 `ok2`（契约样本）；「人工负」= `bad`。
     * - `garbage=true` 的 5 条（纯标点切片，第 5 轮已定性「待剔除」）**不进四格表**，单列。
     * - 定位不到 / 找不到单元的样本**单列**，不塞进任何格（塞进去就是在用缺失顶替结论）。
     * - **额外**单列「人工批准的是不展示（MISS）的 ok 样本」——那里 `ALIGNED` 才是错的，
     *   按 verdict 一刀切会把它误算成正确。
     */
    private fun writeFourCell(outDir: File, name: String, rows: List<GoldenSamples.Row>) {
        val scored = rows.filter { !it.sample.garbage && it.located != null && it.unit != null }
        val unlocated = rows.filter { !it.sample.garbage && it.located == null }
        val noUnit = rows.filter { !it.sample.garbage && it.located != null && it.unit == null }
        val garbage = rows.filter { it.sample.garbage }

        var a = 0; var b = 0; var c = 0; var d = 0; var e = 0; var f = 0
        val cells = LinkedHashMap<String, MutableList<GoldenSamples.Row>>()
        for (row in scored) {
            val humanPositive = row.sample.approvedByHuman
            val key = when (row.status) {
                AlignmentStatus.ALIGNED -> if (humanPositive) "A" else "B"
                AlignmentStatus.UNCERTAIN -> if (humanPositive) "C" else "D"
                AlignmentStatus.UNALIGNED -> if (humanPositive) "E" else "F"
                null -> "?"
            }
            cells.getOrPut(key) { ArrayList() } += row
            when (key) {
                "A" -> a++; "B" -> b++; "C" -> c++; "D" -> d++; "E" -> e++; "F" -> f++
            }
        }
        val text = buildString {
            appendLine("# 四格表（新算法三态 × 人工 verdict）")
            appendLine("进表样本 = ${scored.size}（排除 garbage=${garbage.size}、定位失败=${unlocated.size}、无单元=${noUnit.size}）")
            appendLine()
            appendLine("                        人工 ok/ok2      人工 bad")
            appendLine("新算法 ALIGNED     A=${a.toString().padStart(2)}            B=${b.toString().padStart(2)}   ← B 是错标，逐条见 08")
            appendLine("新算法 UNCERTAIN   C=${c.toString().padStart(2)}            D=${d.toString().padStart(2)}")
            appendLine("新算法 UNALIGNED   E=${e.toString().padStart(2)}            F=${f.toString().padStart(2)}")
            appendLine()
            val alignedTotal = a + b
            appendLine("ALIGNED 精确率（以人工 ok/ok2 为正）= $a/$alignedTotal = " +
                (if (alignedTotal > 0) fmt4(a.toDouble() / alignedTotal) else "n/a"))
            val positiveTotal = a + c + e
            appendLine("人工正样本召回（报 ALIGNED 的比例）= A/(A+C+E) = $a/$positiveTotal = " +
                (if (positiveTotal > 0) fmt4(a.toDouble() / positiveTotal) else "n/a"))
            appendLine("弃权率（UNCERTAIN / 进表样本）= ${c + d}/${scored.size} = " +
                fmt4((c + d).toDouble() / scored.size.coerceAtLeast(1)))
            appendLine()
            appendLine("## 附：人工批准「不展示」(MISS) 的 ok 样本 —— 那里 ALIGNED 才是错")
            cells.values.flatten()
                .filter { it.sample.approvedByHuman && it.sample.approvedLevel == "MISS" }
                .forEach {
                    appendLine("${it.sample.id} verdict=${it.sample.verdict} " +
                        "status=${it.status} target=«${preview(it.targetText)}»")
                }
            appendLine()
            appendLine("## 定位失败 / 无单元 / 垃圾样本")
            unlocated.forEach { appendLine("定位失败 ${it.sample.id} en=«${preview(it.sample.en)}»") }
            noUnit.forEach { appendLine("无单元 ${it.sample.id} en=«${preview(it.sample.en)}»") }
            garbage.forEach { appendLine("垃圾样本 ${it.sample.id} en=«${it.sample.en}» verdict=${it.sample.verdict}") }
            appendLine()
            appendLine("## 三态系统的产品价值评估（Lead 指定口径）")
            appendLine()
            appendLine("两个「覆盖率」必须分开报，不许只报高的那个。")
            appendLine()
            val humanPositive = a + c + e
            val humanNegative = b + d + f
            val alignedPrecision = if (a + b > 0) a.toDouble() / (a + b) else Double.NaN
            val alignedRecall = if (humanPositive > 0) a.toDouble() / humanPositive else Double.NaN
            val usable = a + c
            val usablePrecision = if (usable + b + d > 0) usable.toDouble() / (usable + b + d) else Double.NaN
            val usableRecall = if (humanPositive > 0) usable.toDouble() / humanPositive else Double.NaN
            appendLine("| 口径 | precision | recall | 说明 |")
            appendLine("| --- | --- | --- | --- |")
            appendLine("| **严格**（只算 ALIGNED） | $a/($a+$b) = ${fmt4(alignedPrecision)} | " +
                "$a/$humanPositive = ${fmt4(alignedRecall)} | UI 无条件展示，承诺「这是对的」 |")
            appendLine("| **可用**（ALIGNED+UNCERTAIN） | ($a+$c)/($a+$b+$c+$d) = ${fmt4(usablePrecision)} | " +
                "($a+$c)/$humanPositive = ${fmt4(usableRecall)} | 展示但标注「把握不足」 |")
            appendLine()
            appendLine("判定（Lead 事先约定）：可用口径 **recall ≥ 0.70 且 precision ≥ 0.75** ⇒ 功能可用。")
            appendLine()
            val productVerdict = when {
                usableRecall >= 0.70 && usablePrecision >= 0.75 ->
                    "✅ **功能可用**：产品形态 = 默认展示 + 低置信标注「把握不足」，三态设计正好承载。"
                usableRecall < 0.30 ->
                    "❌ **不可用**：即使把 UNCERTAIN 也算上，recall 仍 < 0.30，是信号不足而非门槛过严。"
                else ->
                    "⚠️ **中间态**：recall ≥ 0.30 但未达 0.70 或 precision 未达 0.75，需进一步定位是门槛还是信号。"
            }
            appendLine("⇒ $productVerdict")
            appendLine()
            appendLine("### UNCERTAIN 内部分布（能否真的展示）")
            appendLine()
            val uncertainRows = cells["C"].orEmpty() + cells["D"].orEmpty()
            val uncertainWithText = uncertainRows.filter { it.targetText.isNotBlank() }
            val uncertainBlankRows = uncertainRows.filter { it.targetText.isBlank() }
            appendLine("| 项 | 条数 |")
            appendLine("| --- | --- |")
            appendLine("| UNCERTAIN 合计（样本内） | ${uncertainRows.size} |")
            appendLine("| 其中**带非空 targetText**（真能展示） | ${uncertainWithText.size} |")
            appendLine("| 其中 targetText **为空**（状态说有候选、实际无内容） | ${uncertainBlankRows.size} |")
            appendLine()
            val cWithText = uncertainWithText.count { it.sample.approvedByHuman }
            val dWithText = uncertainWithText.count { !it.sample.approvedByHuman }
            appendLine("**只统计「带非空 targetText」的 UNCERTAIN**（这才是产品真能展示的部分）：")
            appendLine()
            appendLine("| 项 | 条数 |")
            appendLine("| --- | --- |")
            appendLine("| 人工正（C 子集） | $cWithText |")
            appendLine("| 人工负（D 子集） | $dWithText |")
            val showablePrecision = if (cWithText + dWithText > 0) {
                cWithText.toDouble() / (cWithText + dWithText)
            } else Double.NaN
            val showableRecall = if (humanPositive > 0) (a + cWithText).toDouble() / humanPositive else Double.NaN
            appendLine("| **带候选口径 precision** = $cWithText/($cWithText+$dWithText) | ${fmt4(showablePrecision)} |")
            appendLine("| **带候选口径 recall** = ($a+$cWithText)/$humanPositive | ${fmt4(showableRecall)} |")
            appendLine()
            appendLine("注意：`UNCERTAIN 且 targetText 为空` 的条数若不为 0，说明「状态说有候选但拿不出内容」，")
            appendLine("这部分**不能**计入可用覆盖率——它们展示给用户只会是空白。")
            appendLine()
            cells.toSortedMap().forEach { (key, list) ->
                appendLine()
                appendLine("--- 格 $key（${list.size} 条）---")
                list.forEach {
                    appendLine(
                        "${it.sample.id} [${it.sample.stratum}] verdict=${it.sample.verdict} " +
                            "status=${it.status} conf=${fmt2(it.confidence)} " +
                            "approvedLevel=${it.sample.approvedLevel}"
                    )
                    appendLine("   en=«${preview(it.sourceText.ifBlank { it.sample.en }, 90)}»")
                    appendLine("   zh=«${preview(it.targetText, 90)}»")
                    appendLine("   ev=${it.evidenceSummary} verify=${it.verifyMethod} skip=${it.skipReason}")
                }
            }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    private fun writeErrorList(outDir: File, name: String, rows: List<GoldenSamples.Row>) {
        val errors = rows.filter {
            !it.sample.garbage && it.status == AlignmentStatus.ALIGNED && !it.sample.approvedByHuman
        }
        val missOkAligned = rows.filter {
            !it.sample.garbage && it.status == AlignmentStatus.ALIGNED &&
                it.sample.approvedByHuman && it.sample.approvedLevel == "MISS"
        }
        val text = buildString {
            appendLine("# 错标清单 B：报 ALIGNED 但人工判 bad（${errors.size} 条）")
            errors.forEachIndexed { i, row ->
                appendLine()
                appendLine("## B${i + 1}. ${row.sample.id} [${row.sample.stratum}] chapter=${row.sample.chapter}")
                appendLine("人工判定: ${row.sample.verdict}")
                appendLine("英文句  : ${row.sample.en}")
                appendLine("算法译文: ${row.targetText}")
                appendLine("当年批准: [${row.sample.approvedLevel}] ${row.sample.approvedZh}")
                appendLine("confidence: ${fmt4(row.confidence)}")
                appendLine("evidence  : ${row.evidenceSummary}")
                appendLine("verifyMethod: ${row.verifyMethod}")
                appendLine("reasons   : ${row.reasons.joinToString(" | ")}")
            }
            appendLine()
            appendLine("# 附：报 ALIGNED 但人工批准的是「不展示」(${missOkAligned.size} 条)")
            missOkAligned.forEach {
                appendLine("${it.sample.id} verdict=${it.sample.verdict} 算法译文=«${it.targetText}»")
            }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    /**
     * 与旧 V8 的对照。**只比数字**，不改旧实现。
     *
     * 三态与新契约无法直接对齐（旧实现只说「配了 + 一个分数」），所以这里用两个
     * **可定义、可复现**的口径分别算：
     *
     * - 口径 1「句级断言」：新 = `ALIGNED`；旧 = `SENTENCE`。
     *   这是两边都算「我押了这是同一句译文」的强断言。
     * - 口径 2「有展示」：新 = `ALIGNED` 或 `UNCERTAIN`；旧 = `SENTENCE` 或 `PARAGRAPH`。
     */
    private fun writeOldComparison(
        outDir: File,
        name: String,
        rows: List<GoldenSamples.Row>,
        oldDisplays: Map<String, OldDisplay>
    ) {
        val scored = rows.filter { !it.sample.garbage && it.unit != null }
        fun newClaims(level: Int, row: GoldenSamples.Row): Boolean = when (level) {
            0 -> row.status == AlignmentStatus.ALIGNED
            else -> row.status == AlignmentStatus.ALIGNED || row.status == AlignmentStatus.UNCERTAIN
        }
        fun oldClaims(level: Int, id: String): Boolean {
            val display = oldDisplays[id] ?: return false
            return when (level) {
                0 -> display.level == TranslationMatchLevel.SENTENCE.name
                else -> display.level == TranslationMatchLevel.SENTENCE.name ||
                    display.level == TranslationMatchLevel.PARAGRAPH.name
            }
        }
        val text = buildString {
            appendLine("# 新算法 vs 旧 V8（同一批样本，人工判定为参照）")
            appendLine("进表样本=${scored.size}（排除 garbage 与无单元）")
            appendLine()
            for (level in intArrayOf(0, 1)) {
                val label = if (level == 0) "口径1 句级断言（新=ALIGNED / 旧=SENTENCE）"
                else "口径2 有展示（新=ALIGNED|UNCERTAIN / 旧=SENTENCE|PARAGRAPH）"
                appendLine("## $label")
                for (engineName in listOf("新", "旧")) {
                    var onPositive = 0; var positive = 0
                    var onNegative = 0; var negative = 0
                    for (row in scored) {
                        val claims = if (engineName == "新") newClaims(level, row) else oldClaims(level, row.sample.id)
                        if (row.sample.approvedByHuman) {
                            positive++
                            if (claims) onPositive++
                        } else {
                            negative++
                            if (claims) onNegative++
                        }
                    }
                    appendLine(
                        "$engineName: 人工正=${onPositive}/${positive}(${pct(onPositive, positive)}) " +
                            "人工负上仍断言=${onNegative}/${negative}(${pct(onNegative, negative)})"
                    )
                }
                appendLine()
            }
            appendLine("## 展示粒度分布（人工 approved vs 新 vs 旧）")
            fun distNew(): Map<String, Int> = scored.groupingBy {
                when (it.status) {
                    AlignmentStatus.ALIGNED -> "ALIGNED"
                    AlignmentStatus.UNCERTAIN -> "UNCERTAIN"
                    AlignmentStatus.UNALIGNED -> "UNALIGNED"
                    null -> "NONE"
                }
            }.eachCount()
            fun distOld(): Map<String, Int> = scored.groupingBy {
                oldDisplays[it.sample.id]?.level ?: "UNKNOWN"
            }.eachCount()
            fun distHuman(): Map<String, Int> = scored.groupingBy {
                val lvl = it.sample.approvedLevel.ifBlank { "(空)" }
                if (it.sample.approvedByHuman) "人工批准:$lvl" else "人工否决:$lvl"
            }.eachCount()
            appendLine("人工: " + distHuman().toSortedMap().entries.joinToString { (k, v) -> "$k=$v" })
            appendLine("新  : " + distNew().toSortedMap().entries.joinToString { (k, v) -> "$k=$v" })
            appendLine("旧  : " + distOld().toSortedMap().entries.joinToString { (k, v) -> "$k=$v" })
            appendLine()
            appendLine("## 逐样本三列（id / verdict / 人工level / 新status / 旧level）")
            for (row in scored) {
                appendLine(
                    "${row.sample.id}\t${row.sample.verdict}\t${row.sample.approvedLevel}\t" +
                        "${row.status}\t${oldDisplays[row.sample.id]?.level ?: "-"}"
                )
            }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    private fun writeGoldenRows(
        outDir: File,
        name: String,
        rows: List<GoldenSamples.Row>,
        oldDisplays: Map<String, OldDisplay>
    ) {
        val array = JSONArray()
        for (row in rows) {
            val json = row.toJson()
            oldDisplays[row.sample.id]?.let {
                json.put("oldLevel", it.level)
                    .put("oldZh", it.zh)
                    .put("oldConfidence", it.confidence.toDouble())
            }
            array.put(json)
        }
        val root = JSONObject().put("formatVersion", 1).put("samples", array)
        File(outDir, name).writeText(root.toString(2) + "\n")
    }

    /**
     * **全部 28 条 ALIGNED 单元的逐条审计**（不只那 100 条样本里的）。
     *
     * 理由：四格表里 A=0/B=0 是在「样本集」上得出的。若只看样本集，会漏掉「算法在别处
     * 大量错标、只是没落进样本」这种可能。要么证明这 28 条都对，要么逐条给出可疑处。
     */
    private fun writeAlignedAudit(outDir: File, name: String, doc: AlignmentDocument) {
        val aligned = doc.aligned()
        val text = buildString {
            appendLine("# 全书 ALIGNED 单元逐条审计（共 ${aligned.size} 条）")
            appendLine()
            appendLine("口径：每条给出英中原文 + confidence + evidence.summary() + reasons，")
            appendLine("供人工判断「这条对照对不对」。**本文件不做判定，只提供可核对的事实。**")
            appendLine()
            appendLine("### 证据与文本的一致性复核（客观计算，不做主观判定）")
            appendLine()
            appendLine("`evidence.observedRatio` 声称是「中文字数 / 英文词数」。这里用**单元实际的")
            appendLine("sourceText / targetText** 独立复算同一个比值，与 evidence 对比。")
            appendLine("两者差距大 = evidence 描述的与用户看到的不是同一条对照。")
            appendLine()
            appendLine("| # | ch | evidence len | 实测 len | 差 | en 词 | zh 字 |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            var inconsistent = 0
            aligned.forEachIndexed { index, unit ->
                val enWords = Regex("[A-Za-z]+").findAll(unit.sourceText).count()
                val zhChars = unit.targetText.count { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
                val actual = if (enWords > 0) zhChars.toDouble() / enWords else 0.0
                val claimed = unit.evidence.observedRatio
                val diff = Math.abs(actual - claimed)
                if (diff > 0.5) inconsistent++
                appendLine(
                    "| A${index + 1} | ch${unit.sourceChapter} | ${fmt2(claimed)} | ${fmt2(actual)} | " +
                        "${fmt2(diff)} | $enWords | $zhChars |"
                )
            }
            appendLine()
            appendLine("**证据不一致（差 > 0.5）的单元 = $inconsistent / ${aligned.size}**")
            appendLine()
            appendLine()
            aligned.forEachIndexed { index, unit ->
                appendLine("## A${index + 1}. ch${unit.sourceChapter} para#${unit.sourceIndexInParagraph} " +
                    "conf=${fmt4(unit.confidence)}")
                appendLine("EN: ${unit.sourceText}")
                appendLine("ZH: ${unit.targetText}")
                appendLine("evidence: ${unit.evidence.summary()}")
                appendLine("verifyMethod: ${unit.evidence.verifyMethod}")
                appendLine("anchors: ${unit.evidence.matchedAnchors}")
                appendLine("reasons: ${unit.evidence.reasons.joinToString(" | ")}")
                appendLine("span: en=${unit.sourceSpanSize} zh=${unit.targetSpanSize}")
                appendLine()
            }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    /**
     * **UNCERTAIN 但 targetText 空**的契约一致性审计（全书，不只样本集）。
     *
     * 契约：`UNCERTAIN` = 有候选但证据不足 ⇒ 必须有 `targetText`；
     * 没有候选 ⇒ 必须 `UNALIGNED`。这个组合违反契约第一条。
     */
    private fun writeUncertainAudit(outDir: File, name: String, doc: AlignmentDocument) {
        val uncertain = doc.uncertain()
        val blank = uncertain.filter { it.targetText.isBlank() }
        val text = buildString {
            appendLine("# UNCERTAIN 的 targetText 契约一致性（全书）")
            appendLine()
            appendLine("UNCERTAIN 单元=${uncertain.size}，其中 targetText 为空=**${blank.size}**" +
                "（${pct(blank.size, uncertain.size)}）")
            appendLine()
            appendLine("契约（`AlignmentModels.kt` §AlignedUnit.targetText）：")
            appendLine("> 中文句子文本；非 ALIGNED/UNCERTAIN 时为空串。")
            appendLine("> `UNCERTAIN` 的语义是「有候选」——必须给出译文让用户能点开。")
            appendLine()
            if (blank.isEmpty()) {
                appendLine("✅ 无违反。")
            } else {
                appendLine("❌ 违反：这些单元报「有候选」，却拿不出任何译文。")
                appendLine("根因：`UnitProjector.project` 的 `mayShowTarget = status != UNALIGNED`")
                appendLine("只判断了状态，没有把「DP 未给出 edge」也算进去——`draft.edge == null`")
                appendLine("时 targetText 自然是空串，但状态仍被 `decide()` 判成 UNCERTAIN")
                appendLine("（分支 `dp skipped but plausible candidate exists`，confidence=0.15）。")
                appendLine()
                appendLine("## 逐条（前 100）")
                appendLine()
                appendLine("| # | 章 | conf | reasons | 英文句 |")
                appendLine("| --- | --- | --- | --- | --- |")
                blank.take(100).forEachIndexed { index, unit ->
                    val en = unit.sourceText.replace("|", "\\|").take(80)
                    appendLine("| ${index + 1} | ch${unit.sourceChapter} | ${fmt2(unit.confidence)} | " +
                        "${unit.evidence.reasons.joinToString(";")} | $en |")
                }
                appendLine()
                appendLine("## reasons 分布")
                appendLine()
                blank.groupingBy { it.evidence.reasons.joinToString(";").ifBlank { "(无)" } }
                    .eachCount().entries.sortedByDescending { it.value }.forEach { (reason, count) ->
                        appendLine("- `$reason` = $count")
                    }
            }
        }
        println(text)
        File(outDir, name).writeText(text)
    }

    // ──────────────────────────────────────────────────────────────
    //  工具
    // ──────────────────────────────────────────────────────────────

    private class OldDisplay(val level: String, val zh: String, val confidence: Float)

    private class Timed<T>(val value: T, val millis: Long, val peakHeapMb: Long)

    /**
     * 单线程计时 + 堆峰值采样。
     *
     * 堆峰值用 5ms 轮询 `Runtime.totalMemory()-freeMemory()`，**不是** 精确 GC 后占用
     * （那样会低估瞬时峰值）。口径写在这里：它是「进程堆已用内存的上采样峰值」，
     * 既包含对齐器本身，也包含测试进程里已加载的语料对象，报告里必须如此说明。
     */
    private fun <T> timedRun(block: () -> T): Timed<T> {
        val runtime = Runtime.getRuntime()
        var peak = 0L
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
        sampler.isDaemon = true
        val started = System.currentTimeMillis()
        sampler.start()
        val value = try {
            block()
        } finally {
            sampler.interrupt()
            sampler.join(200)
        }
        val millis = System.currentTimeMillis() - started
        return Timed(value, millis, peak / 1024 / 1024)
    }

    private fun buildOldIndex(pairs: List<AlignedSentencePair>): TranslationMemory =
        TranslationMemory(
            sourceBookId = "lotr-en",
            sourceTitle = "lotr-en",
            translationBookId = "lotr-zh",
            translationTitle = "译本",
            alignedAt = 0L,
            pairs = pairs,
            alignerVersion = TranslationAligner.VERSION
        )

    private fun preview(text: String, max: Int = 60): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun fmt2(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    private fun fmt4(v: Double): String =
        if (v.isNaN() || v.isInfinite()) v.toString() else String.format(Locale.ROOT, "%.4f", v)

    private fun pct(num: Int, den: Int): String =
        if (den <= 0) "n/a" else fmt4(num.toDouble() / den)
}
