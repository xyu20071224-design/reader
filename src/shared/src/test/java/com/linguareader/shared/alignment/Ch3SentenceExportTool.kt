package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 一次性工具（非断言测试）：用**生产 SentenceSplitter** 切第 3 章英文段落，
 * 导出句级清单，供 AI 翻译子代理按句翻译、并作为句级真值的基准。
 *
 * 为什么必须用生产分句器而不是自己写：句级真值的下标空间必须与对齐引擎实际
 * 处理的句子完全一致，否则「真值」和「被测对象」不是同一个东西。
 */
class Ch3SentenceExportTool {

    @Test
    fun export() {
        val root = System.getProperty("user.dir").let { File(it) }
        // user.dir = src/shared; 仓库根是上两级
        val repo = generateSequence(root) { it.parentFile }.firstOrNull {
            File(it, "artifacts/ai-ch3/ch3_en_paragraphs.json").isFile
        } ?: return

        val input = File(repo, "artifacts/ai-ch3/ch3_en_paragraphs.json")
        val direct = File(repo, "artifacts/ai-ch3/ch3_sentences.json")
        if (!input.isFile) {
            println("[ch3-export] SKIP: input missing at ${input.absolutePath}")
            return
        }

        val json = JSONObject(input.readText(Charsets.UTF_8))
        val paragraphs = json.getJSONArray("paragraphs")
        val out = JSONArray()
        var totalSentences = 0
        for (p in 0 until paragraphs.length()) {
            val text = paragraphs.getString(p)
            // 对齐线不传上限（与 TranslationAligner 同口径）
            val sentences = SentenceSplitter.split(text)
            totalSentences += sentences.size
            out.put(
                JSONObject()
                    .put("p", p)
                    .put("sentences", JSONArray(sentences as Collection<*>))
            )
        }
        direct.writeText(
            JSONObject()
                .put("sourceParagraphs", paragraphs.length())
                .put("totalSentences", totalSentences)
                .put("paragraphs", out)
                .toString(1),
            Charsets.UTF_8
        )
        println("[ch3-export] paragraphs=${paragraphs.length()} sentences=$totalSentences")
        println("[ch3-export] wrote ${direct.absolutePath}")

        // 打印一个样例便于人工核对
        val first = out.getJSONObject(2)
        val arr = first.getJSONArray("sentences")
        for (i in 0 until arr.length()) {
            println("[ch3-export] p2 s$i = ${arr.getString(i)}")
        }
    }
}
