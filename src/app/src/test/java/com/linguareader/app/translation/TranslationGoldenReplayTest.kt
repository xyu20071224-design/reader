package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.translation.AlignedSentencePair
import com.linguareader.shared.translation.TranslationAligner
import com.linguareader.shared.translation.TranslationMemory
import com.linguareader.shared.translation.TranslationMemoryIndex
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 译本对齐的**金标准回归**（特征化测试）。
 *
 * 六轮人工判定（2026-09-02~03，对齐器 V2→V5）的结论沉淀在
 * `artifacts/alignment-eval/golden-samples.json`（本地产物，含书文不入库；
 * 判定台账见 `src/tools/alignment-eval/verdict-history.json`）。本测试用
 * **生产代码路径**重放每条样本：
 *
 * ```
 * lotr-book / lotr-zh → TtsTextExtractor(叶级段落)
 *   → TranslationAligner.align(..., EcdictMeaningIndex)（真离线词典）
 *   → TranslationMemoryIndex.lookup(章, 英文句, 段落)
 *   → 记录「用户点词会看到什么」(level + 中文)
 * ```
 *
 * **契约**：`verdict = ok / ok2` 的样本，当前展示必须与 `approved` 完全一致——
 * 上轮判定「对」的，对齐器改动后不许变差；`bad / skip` 只报告变化，不失败。
 * 展示发生变化的样本就是下一轮人工判定的输入（回归报告直接列出）。
 *
 * 素材或 fixture 缺失时自动跳过（CI 上没有这批 gitignore 的本地产物）。
 *
 * 批准新基线（人工判定完新一轮后）：
 * ```
 * touch artifacts/alignment-eval/bless.flag     # Windows: New-Item ...
 * ./toolchain/build.sh :app:testDebugUnitTest \
 *   --tests "com.linguareader.app.translation.TranslationGoldenReplayTest"
 * ```
 * 测试会把当前展示写成新的 `approved` 并删除 flag 文件；随后再跑一次不带 flag 的
 * 校验，确认基线自洽。用文件标志而不是 `-D` 系统属性：Gradle 的 `-D` 只进构建
 * JVM，不会自动转发给 fork 出来的测试 JVM。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationGoldenReplayTest {

    private class Display(val level: String, val zh: String, val confidence: Float)

    private class Sample(
        val id: String,
        val stratum: String,
        val chapter: Int,
        val en: String,
        val verdict: String,
        val garbage: Boolean,
        val approved: Display?,
        /** 原始 JSON，bless 时原地更新 approved 再整体写回，避免丢字段。 */
        val json: JSONObject
    )

    private class Located(val chapter: Int, val paragraph: String)

    @Test
    fun goldenSamplesStayApproved() {
        val root = findGoldenRoot()
        assumeTrue("缺少 artifacts/alignment-eval 金标准数据，跳过（仅存档机可跑）", root != null)
        val artifacts = File(root!!, "artifacts")
        val fixtureFile = File(artifacts, "alignment-eval/golden-samples.json")
        assumeTrue("尚未生成金标准 fixture，先跑 TranslationGoldenFixtureTool", fixtureFile.isFile)
        val blessFlag = File(artifacts, "alignment-eval/bless.flag")

        val fixture = JSONObject(fixtureFile.readText())
        val samples = parseSamples(fixture)

        // ---- 1. 生产线重放（与应用同一套抽取/对齐/查询代码） ----
        val extractor = TtsTextExtractor()
        val enBook = readEnBook(File(artifacts, "lotr-book"))
        val zhBook = readZhBook(File(artifacts, "lotr-zh"))
        val enChapters = enBook.chapters.indices.map { extractor.chapter(enBook, it).blocks }
        val zhChapters = zhBook.chapters.indices.map { extractor.chapter(zhBook, it).blocks }
        println(
            "[golden] 抽取: 英${enChapters.size}章/${enChapters.sumOf { it.size }}段" +
                " 中${zhChapters.size}章/${zhChapters.sumOf { it.size }}段"
        )

        val started = System.currentTimeMillis()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pairs = TranslationAligner.align(enChapters, zhChapters, EcdictMeaningIndex(app))
        val elapsed = System.currentTimeMillis() - started
        val index = TranslationMemoryIndex(
            TranslationMemory(
                sourceBookId = enBook.id,
                sourceTitle = enBook.title,
                translationBookId = zhBook.id,
                translationTitle = zhBook.title,
                alignedAt = 0L,
                pairs = pairs,
                alignerVersion = TranslationAligner.VERSION
            )
        )
        println(
            "[golden] 对齐: 句对=${pairs.size} 句级=${pairs.count { it.enSentence.isNotBlank() }}" +
                " 耗时=${elapsed}ms alignerVersion=${TranslationAligner.VERSION}"
        )

        // ---- 2. 逐样本定位 + 查询 ----
        val displays = linkedMapOf<String, Display>()
        val locatedBySample = linkedMapOf<String, Located>()
        val notLocated = mutableListOf<String>()
        for (sample in samples) {
            if (sample.garbage) continue
            val located = locate(sample, enChapters)
            if (located == null) {
                notLocated += sample.id
                continue
            }
            locatedBySample[sample.id] = located
            val result = index.lookup(located.chapter, sample.en, located.paragraph)
            displays[sample.id] = result
                ?.let { Display(it.matchLevel.name, it.chinese, it.confidence) }
                ?: Display(LEVEL_MISS, "", 0f)
        }

        // ---- 3. bless 或校验 ----
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        if (blessFlag.isFile) {
            var blessed = 0
            for (sample in samples) {
                val display = displays[sample.id] ?: continue
                sample.json.put(
                    "approved",
                    JSONObject()
                        .put("level", display.level)
                        .put("zh", display.zh)
                        .put("confidence", display.confidence.toDouble())
                        .put("blessedAt", now)
                        .put("alignerVersion", TranslationAligner.VERSION)
                )
                blessed++
            }
            fixtureFile.writeText(fixture.toString(2) + "\n")
            blessFlag.delete()
            println("[golden] bless: 已批准 $blessed 条 → ${fixtureFile.path}（bless.flag 已删除）")
            println("[golden] 再跑一次不带 bless.flag 的校验，确认基线自洽")
            return
        }

        val failures = mutableListOf<String>()
        val changedApproved = mutableListOf<String>()
        val changedKnownBad = mutableListOf<String>()
        var stableOk = 0
        var stableKnownBad = 0
        var missingApproved = 0
        var contractCount = 0
        for (sample in samples) {
            if (sample.garbage) continue
            val isContract = sample.verdict == "ok" || sample.verdict == "ok2"
            if (isContract) contractCount++
            val current = displays[sample.id]
            if (current == null) {
                if (isContract) failures += "${sample.id}: 无法在书里定位样本原文（抽取管线或样本损坏）"
                continue
            }
            val approved = sample.approved
            if (approved == null) {
                if (isContract) {
                    missingApproved++
                    failures += "${sample.id}: 缺 approved，先跑一次 bless 建立基线"
                }
                continue
            }
            val unchanged = approved.level == current.level &&
                lightNormalize(approved.zh) == lightNormalize(current.zh)
            if (isContract) {
                if (unchanged) {
                    stableOk++
                } else {
                    changedApproved += describe(sample, approved, current)
                    failures +=
                        "${sample.id}: 展示变化 [${sample.verdict}] " +
                            "approved=${approved.level}/«${preview(approved.zh)}» " +
                            "现在=${current.level}/«${preview(current.zh)}»"
                }
            } else {
                if (unchanged) stableKnownBad++ else changedKnownBad += describe(sample, approved, current)
            }
        }

        // ---- 4. 保真度对账：与 eval6.csv 记录的 V5 展示比对（只报告，不阻断） ----
        reportV6Fidelity(artifacts, samples, displays, locatedBySample, pairs)

        // ---- 4b. 回归报告（本地产物）：判定卡生成器的输入 ----
        writeReplayReport(artifacts, samples, displays)

        // ---- 5. 报告 ----
        val garbageCount = samples.count { it.garbage }
        println(
            "[golden] 契约样本(ok/ok2)=$contractCount 保持=$stableOk 变化=${changedApproved.size}" +
                " 缺批准=$missingApproved | bad/skip 保持=$stableKnownBad 变化=${changedKnownBad.size}" +
                " | 未定位=${notLocated.size} 垃圾样本=$garbageCount"
        )
        changedApproved.forEach { println("[变化·需人工判定] $it") }
        changedKnownBad.forEach { println("[已知坏状态变化] $it") }
        notLocated.forEach { println("[未定位] $it") }

        assertTrue(
            "金标准回归失败 ${failures.size} 条：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    // ---- 回归报告（本地产物，供判定卡生成器使用） ----

    /**
     * 把本次重放的「用户所见」写成 JSON：每个样本的批准展示 vs 当前展示 + 是否变化。
     *
     * 这是 `TranslationJudgmentCardTool` 的输入——下一轮人工判定只需要看这里标了
     * `changed = true` 的样本。文件在 `artifacts/`（gitignored，含书文）。
     */
    private fun writeReplayReport(
        artifacts: File,
        samples: List<Sample>,
        displays: Map<String, Display>
    ) {
        val array = JSONArray()
        for (sample in samples) {
            val current = displays[sample.id]
            val entry = JSONObject()
                .put("id", sample.id)
                .put("stratum", sample.stratum)
                .put("chapter", sample.chapter)
                .put("en", sample.en)
                .put("verdict", sample.verdict)
                .put("garbage", sample.garbage)
            sample.approved?.let {
                entry.put("approved", JSONObject().put("level", it.level).put("zh", it.zh))
            }
            if (current != null) {
                entry.put(
                    "current",
                    JSONObject()
                        .put("level", current.level)
                        .put("zh", current.zh)
                        .put("confidence", current.confidence.toDouble())
                )
                entry.put(
                    "changed",
                    sample.approved?.let {
                        it.level != current.level || lightNormalize(it.zh) != lightNormalize(current.zh)
                    } ?: true
                )
            } else {
                entry.put("changed", true)
            }
            array.put(entry)
        }
        val report = JSONObject()
            .put("formatVersion", 1)
            .put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))
            .put("alignerVersion", TranslationAligner.VERSION)
            .put("samples", array)
        val file = File(artifacts, "alignment-eval/replay-report.json")
        file.parentFile?.mkdirs()
        file.writeText(report.toString(2) + "\n")
        println("[golden] 回归报告 → ${file.path}")
    }

    // ---- 定位：样本英文句 → 它所在的章与段落 ----

    /**
     * 优先样本记录的章（用户当时所在的阅读章），否则全书扫描。
     *
     * 用「段落实际所在章」做查询桶，而不是无条件信样本的 `chapter` 字段——
     * 历史评估工具用错过桶（s50/s66 的 MISS 就是这么来的伪影）。
     */
    private fun locate(sample: Sample, enChapters: List<List<String>>): Located? {
        val target = lightNormalize(sample.en)
        if (target.isBlank()) return null
        val order = buildList {
            if (sample.chapter in enChapters.indices) add(sample.chapter)
            for (i in enChapters.indices) if (i != sample.chapter) add(i)
        }
        for (chapterIndex in order) {
            val hit = enChapters[chapterIndex].firstOrNull { lightNormalize(it).contains(target) }
            if (hit != null) return Located(chapterIndex, hit)
        }
        return null
    }

    // ---- 书籍构造（与 app 导入格式一致） ----

    private fun readEnBook(dir: File): Book {
        val meta = JSONObject(File(dir, "metadata.json").readText())
        val chaptersJson = meta.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { i ->
            val chapter = chaptersJson.getJSONObject(i)
            Chapter(chapter.getString("title"), chapter.getString("relativePath"))
        }
        return Book(
            id = meta.optString("id", "golden-en"),
            title = meta.optString("title", "golden-en"),
            author = meta.optString("author", ""),
            extractedDir = dir.absolutePath,
            coverRelativePath = if (meta.has("coverRelativePath")) meta.getString("coverRelativePath") else null,
            chapters = chapters,
            addedAt = 0L
        )
    }

    /** 中文译本按解包 EPUB 的 spine 读章节，不碰文件内容（导入时的 sanitize 已做过）。 */
    private fun readZhBook(dir: File): Book {
        val container = File(dir, "META-INF/container.xml").readText()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: error("container.xml 缺少 rootfile full-path")
        val opf = Jsoup.parse(File(dir, opfPath).readText())
        val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
        val baseDir = opfPath.substringBeforeLast('/', "")
        val chapters = spine.map { href ->
            val relativePath = if (baseDir.isEmpty()) href else "$baseDir/$href"
            Chapter(href.substringAfterLast('/'), relativePath)
        }
        return Book(
            id = "golden-zh",
            title = opf.select("metadata title").firstOrNull()?.text() ?: "译本",
            author = "",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = 0L
        )
    }

    // ---- fixture 读写 ----

    private fun parseSamples(fixture: JSONObject): List<Sample> {
        val array = fixture.getJSONArray("samples")
        return (0 until array.length()).map { i ->
            val json = array.getJSONObject(i)
            val approvedJson = json.optJSONObject("approved")
            Sample(
                id = json.getString("id"),
                stratum = json.optString("stratum"),
                chapter = json.optInt("chapter", -1),
                en = json.optString("en"),
                verdict = json.optString("verdict"),
                garbage = json.optBoolean("garbage", false),
                approved = approvedJson?.let {
                    Display(
                        level = it.optString("level"),
                        zh = it.optString("zh"),
                        confidence = it.optDouble("confidence", 0.0).toFloat()
                    )
                },
                json = json
            )
        }
    }

    /**
     * 与 `alignment-eval6.csv` 记录的 2026-09-03 V5 展示对账（**历史一次性基线校验**，
     * 只报告不阻断）：证明重放管线（抽取/对齐/查询）忠实于当年的评估结论。
     *
     * 已知的 13 处差异（2026-09-08 逐条归因，写进 `src/tools/alignment-eval/README.md`）：
     * 3 处是旧评估工具的伪影（章节桶错位 / `&amp;` 未反转义 / 空 `es` 条目优先命中），
     * 10 处是旧重放与当前生产路径在个别段落上的句级/段级落盘差异（整本 11,017 句对
     * 与历史重放 11,016 基本一致，差异是局部的）。
     */
    private fun reportV6Fidelity(
        artifacts: File,
        samples: List<Sample>,
        displays: Map<String, Display>,
        located: Map<String, Located>,
        pairs: List<AlignedSentencePair>
    ) {
        val v6File = File(artifacts, "alignment-eval6.csv")
        if (!v6File.isFile) return
        val v6 = EvalCsv.parse(v6File.readText()).associateBy { it.getValue("id") }
        var same = 0
        var diff = 0
        for (sample in samples) {
            if (sample.garbage) continue
            val current = displays[sample.id] ?: continue
            val row = v6[sample.id] ?: continue
            val v6Level = row["level"].orEmpty()
            val v6Zh = row["zh_shown"].orEmpty()
            if (current.level == v6Level && lightNormalize(current.zh) == lightNormalize(v6Zh)) {
                same++
            } else {
                diff++
                val location = located[sample.id]
                val sentencePairs = location?.let { loc ->
                    pairs.filter { it.enParagraph == loc.paragraph && it.enSentence.isNotBlank() }.size
                }
                println(
                    "[历史对账差异] ${sample.id}: 现在=${current.level}/«${preview(current.zh)}»" +
                        " vs eval6=${v6Level}/«${preview(v6Zh)}»" +
                        (sentencePairs?.let { "（该段句级句对=$it）" } ?: "")
                )
            }
        }
        println("[golden] 历史对账(eval6.csv 09-03 V5 展示): 一致=$same 差异=$diff")
    }

    // ---- 小工具 ----

    /**
     * 轻归一化：只折叠空白 + 小写。**不要**用查询侧的
     * [com.linguareader.shared.translation.TranslationMemorySearch.normalize]——
     * 它会把标点转成空格，定位时的「包含」判断会因此误命中。
     */
    private fun lightNormalize(text: String): String =
        Regex("[\\s\\u00A0\\u2007\\u202F]+").replace(text, " ").trim().lowercase(Locale.ROOT)

    private fun describe(sample: Sample, approved: Display, current: Display): String =
        "${sample.id}(${sample.stratum}): ${approved.level}/«${preview(approved.zh)}»" +
            " → ${current.level}/«${preview(current.zh)}» conf=${"%.2f".format(current.confidence)}"

    private fun preview(text: String, max: Int = 40): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun findGoldenRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val artifacts = File(dir, "artifacts")
            if (File(artifacts, "alignment-eval/golden-samples.json").isFile &&
                File(artifacts, "lotr-book/metadata.json").isFile &&
                File(artifacts, "lotr-zh/META-INF/container.xml").isFile
            ) return dir
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val LEVEL_MISS = "MISS"
    }
}
