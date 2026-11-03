package com.linguareader.shared.alignment

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 真实 AI 译本第 3 章验收：单线程、只打印，不做阈值断言。
 *
 * 语料：`artifacts/ai-ch3/ch3_merged_partial.json`
 *   `paragraphs[i] = { p, en:[句子], zh:[句子] }` —— zh 是由真实 LLM 逐句翻译出来的，
 *   与 en **逐句一一对应**，因此它就是句级真值。
 *
 * 验证设计（关键）：**只把段落级文本喂给引擎**，不给它句级真值。
 * 引擎输出 AlignedUnit 后，再拿真值算 precision/coverage。
 * 这样测的是「生产里真实会发生的事」，而不是把答案喂进去。
 */
class Ch3RealAiTranslationAcceptanceTest {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "artifacts/ai-ch3/ch3_merged_partial.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun measureOnRealAiTranslation() {
        val repo = repoRoot()
        if (repo == null) {
            println("[ch3-ai] SKIP: corpus not found")
            return
        }
        val corpusFile = File(repo, "artifacts/ai-ch3/ch3_merged_partial.json")
        val json = JSONObject(corpusFile.readText(Charsets.UTF_8))
        val paras = json.getJSONArray("paragraphs")

        // ── 构造输入：每段 = 英文整段 / 中文整段（句子用空格拼接，模拟段落级译文）
        val enParagraphs = ArrayList<String>(paras.length())
        val zhParagraphs = ArrayList<String>(paras.length())
        // 真值：每段第 k 个英文句 ↔ 第 k 个中文句
        val truth = ArrayList<List<Pair<String, String>>>(paras.length())
        for (i in 0 until paras.length()) {
            val para = paras.getJSONObject(i)
            val en = para.getJSONArray("en")
            val zh = para.getJSONArray("zh")
            check(en.length() == zh.length()) { "truth mismatch at para $i" }
            val enText = StringBuilder()
            val zhText = StringBuilder()
            val pairs = ArrayList<Pair<String, String>>(en.length())
            for (k in 0 until en.length()) {
                val e = en.getString(k)
                val z = zh.getString(k)
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(e)
                // 中文句直接拼接（不加空格，符合中文排版）
                zhText.append(z)
                pairs.add(e to z)
            }
            enParagraphs.add(enText.toString())
            zhParagraphs.add(zhText.toString())
            truth.add(pairs)
        }

        val source = listOf(AlignmentChapter("Chapter 3", enParagraphs))
        val target = listOf(AlignmentChapter("第3章", zhParagraphs))

        // 真 ECDICT（生产同源），缺失则回落到空词典并标注
        val ecdictFile = File(repo, EcdictProvider.EXPORT_RELATIVE_PATH)
        println("[diag] repoRoot=${repo.absolutePath}")
        println("[diag] ecdictFile=${ecdictFile.absolutePath} isFile=${ecdictFile.isFile}")
        val ecdict = EcdictProvider.load(repo)
        println("[diag] ecdict loaded=" + (ecdict != null) +
            " entries=" + (ecdict?.entryCount ?: 0) + " lemmas=" + (ecdict?.lemmaCount ?: 0))
        val provider: MeaningProvider = ecdict ?: object : MeaningProvider {
            override fun chineseCandidates(englishWord: String): List<String> = emptyList()
        }

        val engines: List<Pair<String, SentenceAligner>> = listOf(
            "ai-pair-v1" to AiTranslationAligner(),
            "independent-v1" to IndependentAligner()
        )

        println("=== ch3 real AI translation ===")
        println("paragraphs=${paras.length()} enSentences=${truth.sumOf { it.size }}")
        println("zhChars=" + zhParagraphs.sumOf { it.length })
        println("meaningProvider = " +
            if (ecdict != null) "REAL ECDICT (${ecdict.entryCount} entries, ${ecdict.lemmaCount} lemmas)"
            else "EMPTY (无词典，测纯结构+锚点)")

        for ((name, engine) in engines) {
            val started = System.currentTimeMillis()
            val doc = engine.align(source, target, provider)
            val elapsed = System.currentTimeMillis() - started

            // 真值映射：en句 → zh句
            // 比对前归一化：去掉所有空白（引擎在拼接中文句时会插空格，属排版差异，
            // 不代表配对错误——中文本不需要空格分隔句子）。
            fun norm(s: String) = s.filterNot { it.isWhitespace() }
            val truthMap = HashMap<String, String>()
            for (pairs in truth) for ((e, z) in pairs) truthMap[norm(e)] = norm(z)

            var aligned = 0
            var alignedCorrect = 0
            var uncertain = 0
            var uncertainCorrect = 0
            var unaligned = 0
            var falseAligned = 0
            val wrong = ArrayList<String>()

            for (u in doc.units) {
                val expected = truthMap[norm(u.sourceText)]
                val got = norm(u.targetText)
                when (u.status) {
                    AlignmentStatus.ALIGNED -> {
                        aligned++
                        if (got.isNotEmpty() && got == expected) {
                            alignedCorrect++
                        } else {
                            falseAligned++
                            wrong.add("${u.sourceText.take(50)} | got=${u.targetText.take(50)} | want=${expected?.take(50)}")
                        }
                    }
                    AlignmentStatus.UNCERTAIN -> {
                        uncertain++
                        if (got.isNotEmpty() && got == expected) uncertainCorrect++
                    }
                    AlignmentStatus.UNALIGNED -> unaligned++
                }
            }
            val total = doc.units.size
            println("--- $name ---")
            println("  units=${doc.units.size} elapsed=${elapsed}ms")
            println("  ALIGNED=$aligned (correct=$alignedCorrect, wrong=$falseAligned)")
            println("  UNCERTAIN=$uncertain (correct=$uncertainCorrect)")
            println("  UNALIGNED=$unaligned")
            println("  coverage  = %.2f%%".format(aligned * 100.0 / total))
            println("  precision = %.2f%%".format(if (aligned == 0) 0.0 else alignedCorrect * 100.0 / aligned))
            println("  usable    = %.2f%%".format((alignedCorrect + uncertainCorrect) * 100.0 / total))
            if (wrong.isNotEmpty()) {
                println("  错标前 5 条:")
                wrong.take(5).forEach { println("    " + it) }
            }
        }
    }
}
