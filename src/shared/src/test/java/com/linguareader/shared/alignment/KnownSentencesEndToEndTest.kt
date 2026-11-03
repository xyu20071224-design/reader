package com.linguareader.shared.alignment

import com.linguareader.shared.translation.MeaningIndex
import com.linguareader.shared.translation.TranslationAligner
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **P3 端到端验证**：构造性句界真的被对齐器采用了吗？
 *
 * 两条断言，对应两个必须成立的契约：
 *  1. 给了句界 → 对齐结果**必须**优于不给（否则接线毫无意义，且说明查表落空）
 *  2. 不给句界 → 行为与 V10 完全一致（P3 是纯增强，不能改变既有路径）
 *
 * 第 2 条尤其重要：句界表的**键**用 `TtsTextExtractor` 同款归一化（空白折叠 + trim）。
 * 键写错时不会报错，只会静默失效——所以这里直接用真值语料构造键，复现生产链路。
 */
class KnownSentencesEndToEndTest {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, EcdictProvider.EXPORT_RELATIVE_PATH).isFile) dir = dir.parentFile
        return dir
    }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun knownSentencesImproveAlignment() {
        val repo = repoRoot() ?: run { println("[p3e2e] SKIP"); return }
        val file = File(repo, "artifacts/ai-ch3/ch3_merged_partial.json")
        if (!file.isFile) { println("[p3e2e] SKIP: corpus missing"); return }

        val ecdict = EcdictProvider.load(repo)
        val meaningIndex: MeaningIndex? = ecdict?.let { p ->
            object : MeaningIndex {
                override fun phrasesOf(word: String): Set<String> =
                    p.chineseCandidates(word).flatMap { EcdictProvider.parseGlosses(it) }.toSet()
            }
        }

        val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        val known = HashMap<String, List<String>>()
        val truth = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            val enText = StringBuilder()
            val zhText = StringBuilder()
            val zhLines = ArrayList<String>()
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                val z = zh.getString(k)
                zhText.append(z)
                zhLines += z
                truth[norm(en.getString(k))] = norm(z)
            }
            enParas.add(enText.toString())
            zhParas.add(zhText.toString())
            // 键必须与对齐器看到的 zhParagraph 同形（空白折叠 + trim），这正是生产链路的
            // AiBookTranslator.normalizeParagraphKey 所做的事。
            known[AiBookTranslatorKey.normalize(zhText.toString())] = zhLines
        }

        fun score(pairs: List<com.linguareader.shared.translation.AlignedSentencePair>): Pair<Int, Int> {
            val got = HashMap<String, String>()
            for (p in pairs) {
                val k = norm(p.enSentence)
                if (k.isNotEmpty() && !got.containsKey(k)) got[k] = norm(p.zhSentence)
            }
            var correct = 0
            var wrong = 0
            for ((en, zh) in truth) {
                val g = got[en] ?: continue
                if (g == zh) correct++ else wrong++
            }
            return correct to wrong
        }

        val without = score(TranslationAligner.align(listOf(enParas), listOf(zhParas), meaningIndex))
        val with = score(
            TranslationAligner.align(
                listOf(enParas), listOf(zhParas), meaningIndex,
                TranslationAligner.ZhSplitPolicy.withKnownSentences(known)
            )
        )

        println("[p3e2e] 不给句界: 正确=${without.first} 静默错=${without.second}")
        println("[p3e2e] 给句界  : 正确=${with.first} 静默错=${with.second}")

        // 契约 1：句界必须真的被用上（否则键口径不一致，P3 静默失效）
        assertTrue(
            "构造性句界未生效（结果与不带句界完全一致）——检查键的归一化口径",
            with != without
        )
        // 契约 2：必须变好，不能变差
        assertTrue("静默错应减少：${without.second} → ${with.second}", with.second < without.second)
        assertTrue("正确数应增加：${without.first} → ${with.first}", with.first > without.first)
    }

    @Test
    fun emptyKnownSentencesBehavesLikeProduction() {
        val repo = repoRoot() ?: run { println("[p3e2e] SKIP"); return }
        val file = File(repo, "artifacts/ai-ch1/ch1_truth.json")
        if (!file.isFile) { println("[p3e2e] SKIP: corpus missing"); return }
        val arr = JSONObject(file.readText(Charsets.UTF_8)).getJSONArray("paragraphs")
        val enParas = ArrayList<String>()
        val zhParas = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val en = o.getJSONArray("en")
            val zh = o.getJSONArray("zh")
            val enText = StringBuilder()
            val zhText = StringBuilder()
            for (k in 0 until en.length()) {
                if (enText.isNotEmpty()) enText.append(' ')
                enText.append(en.getString(k))
                zhText.append(zh.getString(k))
            }
            enParas.add(enText.toString())
            zhParas.add(zhText.toString())
        }
        val a = TranslationAligner.align(listOf(enParas), listOf(zhParas), null)
        val b = TranslationAligner.align(
            listOf(enParas), listOf(zhParas), null,
            TranslationAligner.ZhSplitPolicy.WITHOUT_SEMICOLON.copy(knownSentences = emptyMap())
        )
        assertTrue(
            "空句界表必须与生产路径逐条等价（P3 是纯增强）",
            a.size == b.size && a.zip(b).all { (x, y) ->
                x.enSentence == y.enSentence && x.zhSentence == y.zhSentence
            }
        )
    }
}

/** 与 `AiBookTranslator.normalizeParagraphKey` 同口径；:shared 测试直接调用生产函数。 */
private object AiBookTranslatorKey {
    fun normalize(text: String): String =
        com.linguareader.shared.ai.AiBookTranslator.normalizeParagraphKey(text)
}
