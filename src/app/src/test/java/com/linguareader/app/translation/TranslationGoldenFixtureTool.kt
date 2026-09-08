package com.linguareader.app.translation

import org.json.JSONArray
import org.json.JSONObject
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
 * **工具测试（人工跑，不是回归断言）**：把 2026-09-02~03 六轮人工判定的散落数据
 * 整理成单一金标准 fixture，供 [TranslationGoldenReplayTest] 做「上轮 ok 的必须
 * 保持 ok」的特征化回归。
 *
 * ## 输入（全部在 `artifacts/`，gitignored，只存在于存档机）
 *  - `alignment-eval.html` — 第 1 轮判定卡片，**样本集权威来源**（100 条：
 *    id / 分层 / 章节 / 英文句 / 第 1 轮设备展示）；
 *  - `eval-verdicts.txt` — 第 1 轮判定（100 条）；
 *  - `probe18.py` 内嵌的 `RAW2` — 第 2 轮判定（60 条；原文件在旧机器的
 *    Downloads 下已丢失，唯一备份是探针脚本里的字面量，故从这里考古提取）；
 *  - `alignment-v3-corrected.csv` — 第 3 轮（P0 修正后）的合并判定态；
 *  - `eval4-verdicts.txt` — 第 4 轮重判（54 条变化）；
 *  - `alignment-eval6.csv` — 第 6 轮重放输出，其 `verdict4` 列 = 第 4 轮后的
 *    合并判定态（eval5_tool 的合并口径，第 5/6 轮用户实际基于的基线）；
 *  - `eval5-verdicts.txt` — 第 5 轮重判（18 条变化）。
 *
 * 第 6 轮没有逐样本判定串：用户通过 12 张「V4→V5 修正口径对比卡」整体确认
 * V5 通过（VALIDATION.md 2026-09-03 条目），只记为 fixture 备注。
 *
 * ## 输出
 *  - `artifacts/alignment-eval/golden-samples.json` — 全量 fixture（**含书文，
 *    永不入库**，与它依赖的 EPUB 素材同命运）；
 *  - `src/tools/alignment-eval/verdict-history.json` — **判定台账（不含任何
 *    书文）入库**：判定是已花掉的人工成本，丢失不可再生；书文本身因版权与
 *    大文件红线留在本地。
 *
 * ## 合并口径（与历史工具链一致，别的地方不要另起炉灶）
 *  - 权威链 `verdict` = eval6.csv `verdict4` → 第 5 轮覆盖；
 *  - 独立重建链 r1→r2→r3（仅用于对账，不作为最终值）；
 *  - 差异只打印不阻断——两链对不上的样本说明历史工具口径有出入，留给人工裁决。
 *
 * 运行：`./toolchain/build.sh :app:testDebugUnitTest --tests "com.linguareader.app.translation.TranslationGoldenFixtureTool"`
 * （工作目录 `src/`；素材缺失的机器上自动跳过）
 */
@RunWith(RobolectricTestRunner::class)
class TranslationGoldenFixtureTool {

    @Test
    fun regenerateGoldenFixtureFromLegacyEvalRounds() {
        val root = findLegacyEvalRoot()
        assumeTrue("缺少 artifacts/ 下的六轮评估原始数据,跳过(仅存档机可重建)", root != null)
        val artifacts = File(root!!, "artifacts")

        // ---- 1. 样本集(第 1 轮卡片,权威) ----
        val seeds = parseCards(File(artifacts, "alignment-eval.html").readText())
        assertTrue("样本集应为 100 条,实得 ${seeds.size}", seeds.size == 100)

        // ---- 2. 六轮判定 ----
        val r1 = parseVerdicts(File(artifacts, "eval-verdicts.txt").readText())
        val r2 = parseVerdicts(extractRaw2(File(artifacts, "probe18.py").readText()))
        val r3Rows = EvalCsv.parse(File(artifacts, "alignment-v3-corrected.csv").readText())
        val r3 = r3Rows.associate { it.getValue("id") to it.getValue("verdict") }
        val r4 = parseVerdicts(File(artifacts, "eval4-verdicts.txt").readText())
        val round6 = EvalCsv.parse(File(artifacts, "alignment-eval6.csv").readText())
            .associateBy { it.getValue("id") }
        val r5 = parseVerdicts(File(artifacts, "eval5-verdicts.txt").readText())

        // ---- 3. 独立重建链(r1→r2→r3),对账用 ----
        val rebuilt = linkedMapOf<String, String>()
        for (seed in seeds) rebuilt[seed.id] = r1[seed.id] ?: ""
        for ((id, verdict) in r2) if (id in rebuilt) rebuilt[id] = verdict
        for ((id, verdict) in r3) if (id in rebuilt) rebuilt[id] = verdict

        // ---- 4. 权威链:verdict4 → r5 覆盖 + 对账 ----
        val finalVerdicts = linkedMapOf<String, String>()
        var reconciliationDiffs = 0
        var enMismatches = 0
        for (seed in seeds) {
            val verdict4 = round6[seed.id]?.get("verdict4").orEmpty()
            val consolidated = verdict4.ifBlank { rebuilt[seed.id] ?: "" }
            finalVerdicts[seed.id] = r5[seed.id] ?: consolidated

            // 对账 1:两链在「第 4 轮未重判」的样本上应一致,不一致即历史口径出入。
            if (verdict4.isNotBlank() && seed.id !in r4 && verdict4 != rebuilt[seed.id]) {
                reconciliationDiffs++
                println(
                    "[对账差异] ${seed.id}: verdict4=$verdict4 vs 重建链=${rebuilt[seed.id]}" +
                        " (r1=${r1[seed.id] ?: '-'}, r2=${r2[seed.id] ?: '-'}, r3=${r3[seed.id] ?: '-'})"
                )
            }
            // 对账 2:eval6 csv 的 en 与第 1 轮卡片应同源(排除空白差异)。
            val csvEn = round6[seed.id]?.get("en").orEmpty()
            if (csvEn.isNotBlank() && collapse(csvEn) != collapse(seed.en)) enMismatches++
        }

        // ---- 5. 组装 fixture ----
        val goldenDir = File(artifacts, "alignment-eval").apply { mkdirs() }
        val goldenFile = File(goldenDir, "golden-samples.json")
        val existingApproved = if (goldenFile.isFile) {
            val samples = JSONObject(goldenFile.readText()).optJSONArray("samples") ?: JSONArray()
            (0 until samples.length()).associate {
                samples.getJSONObject(it).getString("id") to samples.getJSONObject(it).optJSONObject("approved")
            }
        } else emptyMap()

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val samplesArray = JSONArray()
        val ledgerSamples = JSONArray()
        var missingVerdict = 0
        var garbageCount = 0
        for (seed in seeds.sortedBy { it.numericId }) {
            val garbage = seed.en.none { it.isLetterOrDigit() }
            val verdict = finalVerdicts[seed.id].orEmpty()
            if (verdict.isBlank()) missingVerdict++
            if (garbage) garbageCount++
            val sample = JSONObject()
                .put("id", seed.id)
                .put("stratum", seed.stratum)
                .put("chapter", seed.chapter)
                .put("en", seed.en)
                .put("verdict", verdict)
                .put("garbage", garbage)
                .put("deviceV1", seed.zhDevice)
            val history = JSONObject()
            r1[seed.id]?.let { history.put("r1", it) }
            r2[seed.id]?.let { history.put("r2", it) }
            r3[seed.id]?.let { history.put("r3", it) }
            r4[seed.id]?.let { history.put("r4", it) }
            r5[seed.id]?.let { history.put("r5", it) }
            if (history.length() > 0) sample.put("history", history)
            if (garbage) {
                sample.put(
                    "note",
                    "eval-set garbage: 纯标点切片,应用内无可点词不会发起查询(第 5 轮已定性,待剔除)"
                )
            }
            existingApproved[seed.id]?.let { sample.put("approved", it) }
            samplesArray.put(sample)

            // 台账(入库):不写任何书文。
            ledgerSamples.put(
                JSONObject()
                    .put("id", seed.id)
                    .put("stratum", seed.stratum)
                    .put("chapter", seed.chapter)
                    .put("verdict", verdict)
                    .put("garbage", garbage)
                    .put("history", history)
            )
        }

        val fixture = JSONObject()
            .put("formatVersion", 1)
            .put("generatedAt", now)
            .put("generator", "src/app/src/test/.../TranslationGoldenFixtureTool.kt(人工运行)")
            .put(
                "verdictLineage",
                "verdict = eval6.csv verdict4(第4轮后合并态)→ 第5轮覆盖;第6轮为整体确认通过(12张修正口径对比卡),无逐样本判定"
            )
            .put("samples", samplesArray)
        goldenFile.writeText(fixture.toString(2) + "\n")

        val ledgerFile = File(root, "src/tools/alignment-eval/verdict-history.json")
        ledgerFile.parentFile?.mkdirs()
        ledgerFile.writeText(
            JSONObject()
                .put("formatVersion", 1)
                // 不写 generatedAt：台账要入库，内容必须对同一批输入逐字稳定，
                // 否则每次跑全量单测都会产生一条无意义的时间戳 diff。
                .put(
                    "description",
                    "六轮人工判定台账(2026-09-02~03,魔戒首部曲 100 样本集)。不含书文;" +
                        "全量 fixture(含 en 原文与批准展示)在 artifacts/alignment-eval/golden-samples.json,因版权不入库。"
                )
                .put("verdictVocabulary", "ok=对 / ok2=勉强对(粒度跳或残缺) / bad=错 / skip=跳过")
                .put("samples", ledgerSamples)
                .toString(2) + "\n"
        )

        // ---- 6. 报告 ----
        val distribution = finalVerdicts.values.groupingBy { it.ifBlank { "(缺)" } }.eachCount()
        println(
            "[golden] 样本=${seeds.size} 判定分布=$distribution" +
                " 垃圾样本=$garbageCount 缺判定=$missingVerdict" +
                " 对账差异(两链)=$reconciliationDiffs en不同源=$enMismatches"
        )
        println("[golden] fixture → ${goldenFile.path}")
        println("[golden] 台账(入库) → ${ledgerFile.path}")
        assertTrue("存在无判定的样本,先补齐再生成", missingVerdict == 0)
    }

    // ---- 解析:第 1 轮卡片(与 eval5_tool.load_samples 同源的正则) ----

    private class CardSeed(
        val id: String,
        val numericId: Int,
        val stratum: String,
        val chapter: Int,
        val en: String,
        val zhDevice: String
    )

    private fun parseCards(html: String): List<CardSeed> {
        val cardPattern = Regex(
            """<div class="s">\s*<div class="meta">(.*?)</div>\s*<div class="en">EN：(.*?)</div>\s*<div class="zh">ZH：(.*?)</div>(?:\s*<div class="ctx">(.*?)</div>)?\s*<div><label>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val idPattern = Regex("""name="(s\d+)"""")
        val stratumPattern = Regex("""· ([^·]+?) ·""")
        val chapterPattern = Regex("""章(\d+)""")
        return cardPattern.findAll(html).mapNotNull { match ->
            val (meta, en, zh) = match.destructured
            val after = html.substring(match.range.last + 1, minOf(match.range.last + 121, html.length))
            val id = idPattern.find(after)?.groupValues?.get(1) ?: return@mapNotNull null
            CardSeed(
                id = id,
                numericId = id.removePrefix("s").toInt(),
                stratum = stratumPattern.find(meta)?.groupValues?.get(1) ?: "?",
                chapter = chapterPattern.find(meta)?.groupValues?.get(1)?.let { it.toInt() - 1 } ?: -1,
                en = unescape(en.trim()),
                zhDevice = unescape(zh.trim())
            )
        }.toList()
    }

    /** 第 1 轮卡片只零星转义过(&amp; 等),统一还原,无害。 */
    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun parseVerdicts(text: String): Map<String, String> =
        Regex("(s\\d+)=(ok2?|bad|skip)").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** 第 2 轮判定串考古:唯一备份是探针脚本里的字面量。 */
    private fun extractRaw2(probeSource: String): String =
        Regex("""RAW2\s*=\s*'([^']+)'""").find(probeSource)?.groupValues?.get(1)
            ?: error("probe18.py 里找不到 RAW2 判定串")

    /** 极简 RFC4180:引号包裹字段 + "" 转义。输入带 BOM 时剥掉。 */
    private fun parseCsv(text: String): List<Map<String, String>> = EvalCsv.parse(text)

    private fun collapse(text: String): String = Regex("\\s+").replace(text, " ").trim().lowercase(Locale.ROOT)

    private fun findLegacyEvalRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val artifacts = File(dir, "artifacts")
            if (File(artifacts, "alignment-eval.html").isFile &&
                File(artifacts, "lotr-book/metadata.json").isFile &&
                File(artifacts, "lotr-zh/META-INF/container.xml").isFile &&
                File(dir, "src/tools/alignment-eval").isDirectory
            ) return dir
            dir = dir.parentFile
        }
        return null
    }
}
