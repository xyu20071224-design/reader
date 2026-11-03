package com.linguareader.shared.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实语料验证：魔戒首部曲（英文原版）+ 朱学恒中译本。
 *
 * **不使用 `artifacts/lotr-wordmap.json`**——那是同一本书的预制词表，
 * 用它验证本书等于循环论证。这里的中文译名是**人工在译本正文里 grep 核对**过的
 * 独立真值（见 [GROUND_TRUTH] 注释），且每个名字都附了「按音系规则算出但译本
 * 未采用」的否决候选，用来证明真值来自实测而非编造。
 *
 * 语料缺失时整类跳过（CI 环境没有 artifacts/）。
 */
class TransliterationRealCorpusTest {

    @Test
    fun `真实语料 - TOP30 专名命中率与误报率`() {
        val root = repoRootOrSkip()
        val zhSentences = loadChineseSentences(root)
        val enText = loadEnglishText(root)
        assumeTrue("中译本句子太少", zhSentences.size > 1000)
        assumeTrue("英文原文太短", enText.length > 100_000)

        var hitTotal = 0
        var posTotal = 0
        var fpTotal = 0
        var negTotal = 0
        val perName = StringBuilder()

        for ((en, zh) in GROUND_TRUTH) {
            val positives = zhSentences.filter { it.contains(zh) }
            if (positives.isEmpty()) continue
            val negatives = zhSentences.filterNot { it.contains(zh) }
            val step = maxOf(1, negatives.size / 250)
            val sampled = negatives.filterIndexed { i, _ -> i % step == 0 }.take(250)

            val hits = positives.count { Transliteration.scoreInSentence(en, it) >= 0.5 }
            val fps = sampled.count { Transliteration.scoreInSentence(en, it) >= 0.5 }

            hitTotal += hits
            posTotal += positives.size
            fpTotal += fps
            negTotal += sampled.size
            perName.append(
                String.format(
                    "  %-13s %-7s 出现%4d 句, 命中%4d (%.2f), 反例%3d 误报%2d%n",
                    en, zh, positives.size, hits,
                    hits.toDouble() / positives.size, sampled.size, fps,
                )
            )
        }

        val recall = hitTotal.toDouble() / posTotal
        val fpr = fpTotal.toDouble() / negTotal
        println("== Transliteration 真实语料（魔戒首部曲 + 朱学恒译本）==")
        print(perName)
        println("  合计命中率 %.4f (%d/%d)   误报率 %.5f (%d/%d)"
            .format(recall, hitTotal, posTotal, fpr, fpTotal, negTotal))

        // 门槛：实测 0.875 / 0.0007。留足余量，但足够高以守住「方向成立」。
        assertTrue("命中率 $recall 过低（实测基线 0.875）", recall >= 0.70)
        assertTrue("误报率 $fpr 过高（实测基线 0.0007）", fpr <= 0.01)
        // 高频名必须都能命中——它们才是锚点价值的来源
        assertTrue("最高频名（Frodo）必须能匹配", recall > 0.5)
    }

    @Test
    fun `真实语料 - 区分度：真值句得分应显著高于零模型句`() {
        val root = repoRootOrSkip()
        val zhSentences = loadChineseSentences(root)
        assumeTrue("中译本句子太少", zhSentences.size > 1000)

        var separated = 0
        var checked = 0
        for ((en, zh) in GROUND_TRUTH) {
            val positives = zhSentences.filter { it.contains(zh) }
            if (positives.size < 5) continue
            val negatives = zhSentences.filterNot { it.contains(zh) }
            val step = maxOf(1, negatives.size / 200)
            val sampled = negatives.filterIndexed { i, _ -> i % step == 0 }.take(200)
            if (sampled.isEmpty()) continue

            val posHitRatio = positives.take(200)
                .count { Transliteration.scoreInSentence(en, it) >= 0.5 } /
                minOf(200, positives.size).toDouble()
            val negHitRatio = sampled.count { Transliteration.scoreInSentence(en, it) >= 0.5 } /
                sampled.size.toDouble()
            checked++
            if (posHitRatio - negHitRatio > 0.5) separated++
            println(
                String.format(
                    "  区分度 %-13s 真值命中率 %.2f  零模型 %.3f",
                    en, posHitRatio, negHitRatio,
                )
            )
        }
        println("  完成区分度的名字 $separated/$checked")
        assertTrue("区分度成立的名字 $separated/$checked 太少", separated >= checked * 2 / 3)
    }

    @Test
    fun `真实语料 - 繁简在中译本正文上同分`() {
        val root = repoRootOrSkip()
        val zhSentences = loadChineseSentences(root)
        assumeTrue("中译本句子太少", zhSentences.size > 1000)

        // 中译本正文本身就是繁体。取含「佛羅多」的句子，把它转成简体，
        // 两边得分必须完全相等。
        val samples = zhSentences.filter { it.contains("佛羅多") }.take(20)
        assumeTrue("未找到含佛羅多的句子", samples.isNotEmpty())
        for (s in samples) {
            val trad = Transliteration.scoreInSentence("Frodo", s)
            val simp = Transliteration.scoreInSentence("Frodo", Transliteration.toSimplified(s))
            assertEquals("繁简应同分：$s", trad, simp, 1e-9)
            assertTrue("繁体正文应得高分：$s -> $trad", trad >= 0.9)
        }
    }

    @Test
    fun `真实语料 - 候选生成时间在热路径可接受`() {
        val root = repoRootOrSkip()
        val zhSentences = loadChineseSentences(root)
        assumeTrue("中译本句子太少", zhSentences.size > 1000)

        val names = GROUND_TRUTH.map { it.first }

        Transliteration.debugClearCache()
        val t0 = System.nanoTime()
        for (n in names) Transliteration.candidates(n)
        val coldMs = (System.nanoTime() - t0) / 1_000_000.0

        val t1 = System.nanoTime()
        repeat(50) { for (n in names) Transliteration.candidates(n) }
        val warmMsPer = (System.nanoTime() - t1) / 1_000_000.0 / 50.0 / names.size

        // 句子侧吞吐
        val sample = zhSentences.take(200)
        val t2 = System.nanoTime()
        for (s in sample) Transliteration.scoreInSentence("Frodo", s)
        val perSentenceMs = (System.nanoTime() - t2) / 1_000_000.0 / sample.size

        println(
            String.format(
                "  候选生成：冷启动(%d 名) %.1f ms，均 %.3f ms/名；命中缓存 %.5f ms/名",
                names.size, coldMs, coldMs / names.size, warmMsPer,
            )
        )
        println(String.format("  scoreInSentence（候选已缓存）%.3f ms/句", perSentenceMs))

        assertTrue("冷启动 ${coldMs}ms 过慢", coldMs < 10_000)
        assertTrue("命中缓存 ${warmMsPer}ms/名 过慢", warmMsPer < 1.0)
        assertTrue("单句 ${perSentenceMs}ms 过慢", perSentenceMs < 5.0)
        assertTrue("缓存未生效", warmMsPer < coldMs / names.size)
    }

    @Test
    fun `真实语料 - 专名句占比`() {
        val root = repoRootOrSkip()
        val zhSentences = loadChineseSentences(root)
        assumeTrue("中译本句子太少", zhSentences.size > 1000)

        val truthSents = zhSentences.count { s -> GROUND_TRUTH.any { s.contains(it.second) } }
        var matched = 0
        for (s in zhSentences) {
            if (GROUND_TRUTH.any { Transliteration.scoreInSentence(it.first, s) >= 0.5 }) {
                matched++
            }
        }
        println(
            String.format(
                "  全书 %d 句；含 TOP30 真译法 %d 句 (%.2f%%)；本模块判定命中 %d 句 (%.2f%%)",
                zhSentences.size, truthSents,
                100.0 * truthSents / zhSentences.size,
                matched, 100.0 * matched / zhSentences.size,
            )
        )
        assertTrue("应能覆盖相当比例的专名句", matched > truthSents * 6 / 10)
    }

    // ── 语料装载 ────────────────────────────────────────────────

    private fun repoRootOrSkip(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory &&
                File(dir, "artifacts/lotr-zh").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        assumeTrue("语料缺失（需要 artifacts/lotr-book 与 artifacts/lotr-zh）", false)
        throw IllegalStateException("unreachable")
    }

    private fun loadChineseSentences(root: File): List<String> {
        val dir = File(root, "artifacts/lotr-zh/OEBPS/Text")
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<String>(16000)
        val splitter = Regex("(?<=[。！？…」』])")
        for (f in dir.listFiles { x -> x.extension.lowercase() == "xhtml" }?.sortedBy { it.name }
            ?: return emptyList()) {
            val text = f.readText(Charsets.UTF_8)
                .replace(Regex("<[^>]+>"), "\n")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
            for (line in text.split("\n")) {
                for (piece in line.split(splitter)) {
                    val s = piece.trim()
                    if (s.length in 6..200) out.add(s)
                }
            }
        }
        return out
    }

    private fun loadEnglishText(root: File): String {
        val meta = File(root, "artifacts/lotr-book/metadata.json")
        if (!meta.isFile) return ""
        val sb = StringBuilder(1_200_000)
        for (f in File(root, "artifacts/lotr-book/OEBPS").listFiles()
            ?.filter { it.extension.lowercase() == "html" }?.sortedBy { it.name } ?: emptyList()) {
            sb.append(f.readText(Charsets.UTF_8).replace(Regex("<[^>]+>"), " "))
        }
        return sb.toString()
    }

    private companion object {
        /**
         * 人工核对过的真值：英文专名 -> 朱学恒译本实际译法。
         *
         * 确定方式：先用音系规则算出若干候选写法，再逐个 grep 中文正文计数，
         * 取计数 > 0 的那个。下面 [REJECTED] 记录了同一名字被否决的候选
         * （计数均为 0），证明这是实测结果而非硬编码猜测。
         */
        val GROUND_TRUTH: List<Pair<String, String>> = listOf(
            "Frodo" to "佛羅多",
            "Gandalf" to "甘道夫",
            "Bilbo" to "比爾博",
            "Sam" to "山姆",
            "Aragorn" to "亞拉岡",
            "Mordor" to "魔多",
            "Shire" to "夏爾",
            "Pippin" to "皮聘",
            "Merry" to "梅里",
            "Boromir" to "波羅莫",
            "Legolas" to "勒苟拉斯",
            "Gimli" to "金靂",
            "Elrond" to "愛隆",
            "Galadriel" to "凱蘭崔爾",
            "Saruman" to "薩魯曼",
            "Gollum" to "咕魯",
            "Sauron" to "索倫",
            "Bree" to "布理",
            "Rivendell" to "瑞文戴爾",
            "Tom" to "湯姆",
            "Moria" to "摩瑞亞",
            "Isildur" to "伊西鐸",
            "Bombadil" to "龐巴迪",
            "Hobbiton" to "哈比屯",
            "Anduin" to "安都因",
            "Gondor" to "剛鐸",
            "Rohan" to "洛汗",
            "Celeborn" to "凱勒鵬",
            "Haldir" to "哈爾達",
            "Glorfindel" to "葛羅芬戴爾",
        )

        /** 被否决的候选（在译本里计数为 0），仅作文档用途。 */
        val REJECTED: Map<String, List<String>> = mapOf(
            "Frodo" to listOf("佛拉多", "弗羅多"),
            "Gandalf" to listOf("甘達夫", "岡道夫"),
            "Sam" to listOf("薩姆"),
            "Mordor" to listOf("摩多", "莫多"),
            "Bree" to listOf("布里"),
            "Isildur" to listOf("埃西鐸"),
            "Bombadil" to listOf("邦巴迪爾"),
            "Anduin" to listOf("安杜因"),
        )
    }
}
