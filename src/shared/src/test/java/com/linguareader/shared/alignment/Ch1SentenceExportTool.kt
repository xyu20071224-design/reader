package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 一次性工具：用**生产 SentenceSplitter** 把第 1 章切成句，导出供翻译。
 *
 * 与第 3 章完全同一口径（`Ch3SentenceExportTool`），保证两章可比。
 */
class Ch1SentenceExportTool {

    @Test
    fun export() {
        var repo: File? = File(System.getProperty("user.dir"))
        while (repo != null && !File(repo, "artifacts/ai-ch1/ch1_en_paragraphs.json").isFile) {
            repo = repo.parentFile
        }
        if (repo == null) { println("[ch1-export] SKIP"); return }

        val src = File(repo, "artifacts/ai-ch1/ch1_en_paragraphs.json")
        val json = JSONObject(src.readText(Charsets.UTF_8))
        val paragraphs = json.getJSONArray("paragraphs")

        val out = org.json.JSONArray()
        var total = 0
        for (p in 0 until paragraphs.length()) {
            val text = paragraphs.getString(p)
            val sents = SentenceSplitter.split(text)
            total += sents.size
            out.put(
                JSONObject()
                    .put("p", p)
                    .put("sentences", org.json.JSONArray(sents as Collection<*>))
            )
        }
        val dest = File(repo, "artifacts/ai-ch1/ch1_sentences.json")
        dest.writeText(
            JSONObject()
                .put("sourceParagraphs", paragraphs.length())
                .put("totalSentences", total)
                .put("paragraphs", out)
                .toString(1),
            Charsets.UTF_8
        )
        println("[ch1-export] paragraphs=${paragraphs.length()} sentences=$total")
        println("[ch1-export] wrote ${dest.absolutePath}")

        // 分句长度分布（与第 3 章对比用）
        val sizes = (0 until paragraphs.length()).map { p ->
            val t = paragraphs.getString(p)
            SentenceSplitter.split(t).size
        }
        sizes.groupingBy { it }.eachCount().toSortedMap().forEach { (k, v) ->
            println("[ch1-export]   ${k} 句的段: $v")
        }
    }
}
