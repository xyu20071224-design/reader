package com.linguareader.app.translation

import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

/**
 * **工具测试（人工跑）**：语义代理的**大规模**验证 + **评分变体选型**——用公版圣经的
 * 「逐节对齐」当机械真值，把 91 条人工判定的 AUC 换成几千条自动标注的 AUC。
 *
 * 为什么需要它：`TranslationSemanticProxyTool` 的 AUC 建立在 60 条 ok/ok2 + 12 条 bad
 * 上，置信区间很宽。圣经 KJV × 和合本逐节对齐是免费真值：句对两端落在同一节=正、
 * 错节=负，两千多条样本、零人工成本，还能按文体（福音书/创世记/箴言）分开看泛化性。
 *
 * 同时它是 **P3（候选词特异性加权）的选型台**：同一批机械标签上比 `base` / `idf` /
 * `primary` / `idf_min2` 等变体的 AUC，选完再把赢家写回 `SemanticProxyIndex.Config` 默认值。
 *
 * 注意口径：这里量的是**对齐级**正确（同节），不是展示级质量（合并句对、整段兜底
 * 仍可能是好展示）。它与金标准集互补，不替代人工判定。
 *
 * 素材：`artifacts/generalization/{KJV,ChiUn}.json` + `cedict.txt.gz`（都 gitignored，
 * `fetch-corpus.sh` 下载并校验），缺失时跳过。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationProxyBibleValidationTest {

    private val books = listOf("John", "Genesis", "Proverbs")

    /** P3 候选变体：名称 → 索引配置。第一项必须与 `SemanticProxyIndex.Config` 默认值一致。 */
    private val variants = listOf(
        "base(200,uniform)" to SemanticProxyIndex.Config(maxCandidates = 200, idf = false),
        "idf(200)" to SemanticProxyIndex.Config(maxCandidates = 200, idf = true),
        "idf_wide(5000)" to SemanticProxyIndex.Config(maxCandidates = 5000, idf = true),
        "primary" to SemanticProxyIndex.Config(primaryGlossOnly = true),
        "primary_idf" to SemanticProxyIndex.Config(primaryGlossOnly = true, idf = true),
        "idf_min2" to SemanticProxyIndex.Config(idf = true, minCandidateChars = 2)
    )

    @Test
    fun validateAgainstMechanicalVerseLabels() {
        val root = findRoot()
        assumeTrue("缺少 artifacts 下的评估素材，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val kjv = File(artifacts, "generalization/KJV.json")
        val cuv = File(artifacts, "generalization/ChiUn.json")
        val cedict = File(artifacts, "generalization/cedict.txt.gz")
        assumeTrue("缺少公版圣经语料（见 fetch-corpus.sh），跳过", kjv.isFile && cuv.isFile)
        assumeTrue("缺少 CC-CEDICT（见 fetch-corpus.sh），跳过", cedict.isFile)

        // 1) 先对齐 + 机械标注（与评分索引无关，只做一次）
        val all = mutableListOf<Record>()
        for (name in books) {
            all += label(name, loadBook(kjv, name), loadBook(cuv, name))
        }
        println("[proxy-scale] 机械标注：可定位句对=%d".format(all.size))

        // 2) P3 变体选型：同一批标签上比 AUC
        println("[proxy-scale] P3 变体对照（共同子集 = 所有变体都能评分的句对，口径才可比）：")
        val variantScored = variants.map { (name, config) ->
            val index = SemanticProxyIndex.build(cedict, config)
            name to all
                .map { it to SemanticProxyIndex.evaluate(index, it.en, it.zh) }
                .filter { it.second.scoreable }
        }
        val common = variantScored
            .map { entry -> entry.second.map { System.identityHashCode(it.first) }.toSet() }
            .reduce { a, b -> a intersect b }
        var best: Pair<String, Double>? = null
        for ((name, scored) in variantScored) {
            val sub = scored.filter { System.identityHashCode(it.first) in common }
            val subAuc = auc(sub.map { it.first to it.second.hitRate })
            val allAuc = auc(scored.map { it.first to it.second.hitRate })
            println(
                "  %-18s 共同子集 n=%-5d AUC=%.3f | 全可评分 n=%-5d AUC=%.3f%s".format(
                    name, sub.size, subAuc, scored.size, allAuc,
                    if (variants.first { it.second == SemanticProxyIndex.Config() }.first == name) "（当前默认）" else ""
                )
            )
            if (best == null || subAuc > best!!.second) best = name to subAuc
        }
        println("  → 共同子集最佳变体：${best!!.first}（AUC %.3f）".format(best!!.second))

        // 3) 用默认配置详报（分书 + 合计 + 阈值表）
        val index = SemanticProxyIndex.build(cedict)
        for (name in books) {
            report(name, all.filter { it.book == name }, index)
        }
        reportPooled(all, index)
    }

    // ---- 标注与评分 ----

    /** 一条句对的机械标注：同节=正，错节=负（记录 |Δ| 便于分层看）。 */
    private class Record(
        val book: String,
        val en: String,
        val zh: String,
        val correct: Boolean,
        val delta: Int
    )

    private fun label(book: String, en: BibleBook, zh: BibleBook): List<Record> {
        val enVerses = en.chapters.flatten()
        val zhVerses = zh.chapters.flatten()
        val enNorm = enVerses.map { normalize(it) }
        val zhNorm = zhVerses.map { normalize(it) }

        val pairs = TranslationAligner.align(en.chapters, zh.chapters)
        val records = mutableListOf<Record>()
        var enCursor = 0
        var zhCursor = 0
        for (pair in pairs) {
            if (pair.enSentence.isBlank()) continue
            val enIndex = locate(enNorm, pair.enSentence, enCursor) ?: continue
            val zhIndex = locate(zhNorm, pair.zhSentence, zhCursor) ?: continue
            enCursor = enIndex
            zhCursor = zhIndex
            val delta = zhIndex - enIndex
            records += Record(book, pair.enSentence, pair.zhSentence, delta == 0, delta)
        }
        return records
    }

    private fun report(name: String, records: List<Record>, index: SemanticProxyIndex.Index) {
        val scored = records.map { it to SemanticProxyIndex.evaluate(index, it.en, it.zh) }
        val scoreable = scored.filter { it.second.scoreable }
        val thin = scored.filter { !it.second.scoreable }
        val pos = scoreable.filter { it.first.correct }
        val neg = scoreable.filter { !it.first.correct }
        if (pos.isEmpty() || neg.isEmpty()) {
            println("[proxy-scale] %-8s 可评分=%d 但正/负样本不足，跳过".format(name, scoreable.size))
            return
        }
        println(
            "[proxy-scale] %-8s 句对=%d 可评分=%d（%.0f%%）同节=%d 错节=%d | AUC(原始)=%.3f AUC(Wilson)=%.3f".format(
                name, records.size, scoreable.size, scoreable.size * 100.0 / records.size,
                pos.size, neg.size, auc(scoreable.map { it.first to it.second.hitRate }),
                auc(scoreable.map { it.first to it.second.wilsonLower })
            )
        )
        println(
            "[proxy-scale] %-8s 同节 均值=%.3f 中位=%.3f | 错节 均值=%.3f 中位=%.3f | 证据不足=%d 条（同节 %d / 错节 %d）".format(
                name, pos.map { it.second.hitRate }.average(),
                pos.map { it.second.hitRate }.sorted()[pos.size / 2],
                neg.map { it.second.hitRate }.average(),
                neg.map { it.second.hitRate }.sorted()[neg.size / 2],
                thin.size, thin.count { it.first.correct }, thin.count { !it.first.correct }
            )
        )
        val byDelta = neg.groupBy { kotlin.math.abs(it.first.delta) }.toSortedMap()
        println(
            "[proxy-scale] %-8s 错节分层 |Δ|: %s".format(
                name,
                byDelta.entries.joinToString("  ") { (d, list) ->
                    "|Δ|%d n=%d 命中均值=%.3f".format(d, list.size, list.map { it.second.hitRate }.average())
                }
            )
        )
    }

    private fun reportPooled(records: List<Record>, index: SemanticProxyIndex.Index) {
        val scored = records.map { it to SemanticProxyIndex.evaluate(index, it.en, it.zh) }
        val scoreable = scored.filter { it.second.scoreable }
        val pos = scoreable.filter { it.first.correct }
        val neg = scoreable.filter { !it.first.correct }
        if (pos.isEmpty() || neg.isEmpty()) return
        println(
            "[proxy-scale] 合计     可评分=%d 同节=%d 错节=%d | AUC(原始)=%.3f AUC(Wilson)=%.3f".format(
                scoreable.size, pos.size, neg.size,
                auc(scoreable.map { it.first to it.second.hitRate }),
                auc(scoreable.map { it.first to it.second.wilsonLower })
            )
        )
        val thresholds = listOf(0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5)
        println("[proxy-scale] 合计     报警阈值表（命中率 < t）:")
        for (t in thresholds) {
            val flagged = scoreable.filter { it.second.hitRate < t }
            val trueBad = flagged.count { !it.first.correct }
            val precision = if (flagged.isEmpty()) 0.0 else trueBad.toDouble() / flagged.size
            val recall = trueBad.toDouble() / neg.size
            println(
                "[proxy-scale]   t=%.2f 报警=%-5d 精确率=%.3f 召回=%.3f 漏报=%d".format(
                    t, flagged.size, precision, recall, neg.size - trueBad
                )
            )
        }
        val best = thresholds
            .map { t -> t to scoreable.filter { it.second.hitRate < t } }
            .map { (t, flagged) ->
                val trueBad = flagged.count { !it.first.correct }
                Triple(
                    t,
                    if (flagged.isEmpty()) 0.0 else trueBad.toDouble() / flagged.size,
                    trueBad.toDouble() / neg.size
                )
            }
            .filter { it.second >= 0.70 }
            .maxByOrNull { it.third }
        println(
            if (best == null) "[proxy-scale] 合计     没有任何阈值能到精确率 0.70——代理只能当粗筛"
            else "[proxy-scale] 合计     推荐操作点 t=%.2f（精确率=%.3f 召回=%.3f）".format(
                best.first, best.second, best.third
            )
        )
    }

    // ---- 指标 ----

    private fun auc(records: List<Pair<Record, Double>>): Double {
        val pos = records.filter { it.first.correct }.map { it.second }
        val neg = records.filter { !it.first.correct }.map { it.second }
        if (pos.isEmpty() || neg.isEmpty()) return Double.NaN
        var wins = 0.0
        for (p in pos) for (n in neg) {
            wins += when {
                p > n -> 1.0
                p < n -> 0.0
                else -> 0.5
            }
        }
        return wins / (pos.size.toDouble() * neg.size)
    }

    private fun locate(normalizedVerses: List<String>, sentence: String, cursor: Int): Int? {
        val target = normalize(sentence)
        if (target.isBlank()) return null
        for (i in cursor until normalizedVerses.size) {
            if (normalizedVerses[i].contains(target)) return i
        }
        for (i in 0 until cursor) {
            if (normalizedVerses[i].contains(target)) return i
        }
        return null
    }

    private fun normalize(text: String): String =
        Regex("\\s+").replace(text, " ").trim().lowercase(Locale.ROOT)

    // ---- 数据 ----

    private class BibleBook(val chapters: List<List<String>>)

    private fun loadBook(file: File, wanted: String): BibleBook {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("books")
        for (i in 0 until array.length()) {
            val book = array.getJSONObject(i)
            if (book.optString("name") != wanted) continue
            val chaptersJson = book.getJSONArray("chapters")
            val chapters = (0 until chaptersJson.length()).map { c ->
                val verses = chaptersJson.getJSONObject(c).getJSONArray("verses")
                (0 until verses.length()).map { v -> verses.getJSONObject(v).getString("text").trim() }
            }
            return BibleBook(chapters)
        }
        error("$file 里没找到 $wanted")
    }

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/generalization/KJV.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
