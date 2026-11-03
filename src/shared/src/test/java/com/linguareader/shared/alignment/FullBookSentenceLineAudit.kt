package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 用**生产 `SentenceSplitter` 本身**校验全书的句级行数一致性。
 *
 * 为什么不用 Python 复刻：`SentenceSplitter` 有缩写分档、引号归属、省略号、
 * 首字母等十余条交互规则，Python 镜像实测只能对上 8/12，会产生大量假阳性。
 * 这里直接调 Kotlin 原实现，判据与生产完全一致。
 *
 * 输出：整体一致率 + 不一致段落清单（供 translator 定点返工）。
 */
class FullBookSentenceLineAudit {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "artifacts/ai-full/plan_active.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun audit() {
        val repo = repoRoot() ?: run { println("[audit] SKIP: plan not found"); return }
        val batchDir = File(repo, "artifacts/ai-full/batches")
        val plan = JSONObject(File(repo, "artifacts/ai-full/plan_active.json").readText(Charsets.UTF_8))
        val batches = plan.getJSONArray("batches")

        var done = 0
        var missing = 0
        var structErrors = 0
        var segTotal = 0
        var segOk = 0
        val badBatches = LinkedHashMap<String, Int>()
        val samples = ArrayList<String>()

        for (i in 0 until batches.length()) {
            val b = batches.getJSONObject(i)
            val bid = b.getString("batchId")
            val inFile = File(batchDir, "${bid}_in.json")
            val outFile = File(batchDir, "${bid}_out.json")
            if (!outFile.isFile) { missing++; continue }
            done++
            val src = runCatching { JSONObject(inFile.readText(Charsets.UTF_8)) }.getOrNull()
            val dst = runCatching { JSONObject(outFile.readText(Charsets.UTF_8)) }.getOrNull()
            if (src == null || dst == null) { structErrors++; continue }
            val srcItems = src.getJSONArray("items")
            val dstItems = dst.optJSONArray("items")
            if (dstItems == null || dstItems.length() != srcItems.length()) {
                structErrors++
                continue
            }
            var batchBad = 0
            for (k in 0 until srcItems.length()) {
                val s = srcItems.getJSONObject(k)
                val d = dstItems.getJSONObject(k)
                if (s.optInt("i") != d.optInt("i")) { structErrors++; break }
                val en = s.optString("en")
                val zh = d.optString("zh").trim()
                if (zh.isEmpty()) { structErrors++; continue }
                val enN = SentenceSplitter.count(en)
                val zhN = zh.split('\n').count { it.isNotBlank() }
                segTotal++
                if (zhN == enN) segOk++
                else {
                    batchBad++
                    if (samples.size < 10) {
                        samples += "$bid i=${s.optInt("i")} en=$enN zh=$zhN\n" +
                            "        EN «${en.take(70)}»\n" +
                            "        ZH «${zh.take(70).replace("\n", "⏎")}»"
                    }
                }
            }
            if (batchBad > 0) badBatches[bid] = batchBad
        }

        println("[audit] ===== 全书句级行数审计 =====")
        println("[audit] 计划批次=${batches.length()} 已完成=$done 未完成=$missing 结构错误=$structErrors")
        if (segTotal > 0) {
            println("[audit] 段落 $segTotal | 句数一致 $segOk (%.2f%%) | 不一致 %d"
                .format(segOk * 100.0 / segTotal, segTotal - segOk))
        }
        if (badBatches.isNotEmpty()) {
            println("[audit] 有不一致的批次 ${badBatches.size} 个：")
            badBatches.entries.take(40).forEach { (k, v) -> println("[audit]    $k : $v 段") }
        }
        if (samples.isNotEmpty()) {
            println("[audit] 样例：")
            samples.forEach { println("[audit]    $it") }
        }
    }
}
