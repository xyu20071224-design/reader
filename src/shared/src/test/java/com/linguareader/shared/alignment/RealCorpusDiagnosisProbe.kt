package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **真实语料诊断**（Lead 要求）：直接跑魔戒英中语料，量化两个根因。
 *
 * 1. `anchors=0`：全书硬钉为何被剪光（打印 TOP 20 被剪锚点 + 原因）
 * 2. 繁简缺口：中译本有多少字不在 `TraditionalSimplified` 表里
 *
 * 数据源（本机既有）：
 * - 英文：`artifacts/lotr-book/OEBPS` 下的 HTML 文件 + `metadata.json` 的章节目录
 * - 中文：`artifacts/lotr-zh/OEBPS/Text` 下的 XHTML 文件
 *
 * 这个文件不依赖 Android，用 `jsoup`（`:shared` 的 compileOnly 依赖，测试期有
 * `testImplementation`）抽取叶级块，与 `TtsTextExtractor` 同口径。
 */
class RealCorpusDiagnosisProbe {

    @Test
    fun diagnoseRealCorpus() {
        val root = findRepoRoot()
        val enChapters = loadEnglishChapters(root)
        val zhChapters = loadChineseChapters(root)
        println("=== 语料装载 ===")
        println("  en: ${enChapters.size} 章 / ${enChapters.sumOf { it.paragraphs.size }} 段")
        println("  zh: ${zhChapters.size} 章 / ${zhChapters.sumOf { it.paragraphs.size }} 段")
        if (enChapters.isEmpty() || zhChapters.isEmpty()) {
            println("  !! 语料为空，跳过诊断")
            return
        }

        val source = BookSide.build(SideKind.SOURCE, enChapters)
        val target = BookSide.build(SideKind.TARGET, zhChapters)
        println("  en 句=${source.sentCount}  zh 句=${target.sentCount}")

        // ── 根因 2：繁简归一缺口 ─────────────────────────────────
        println()
        println("=== 根因 2：繁简归一缺口 ===")
        diagnoseTraditionalGap(target)

        // ── 根因 1：锚点为何被剪光 ───────────────────────────────
        println()
        println("=== 根因 1：锚点诊断 ===")
        diagnoseAnchors(source, target)
    }

    // ──────────────────────────────────────────────────────────────
    //  根因 2：繁简缺口
    // ──────────────────────────────────────────────────────────────

    private fun diagnoseTraditionalGap(target: BookSide) {
        // 统计：中文侧所有汉字 → 出现次数；归一后变化的字 → 说明表里有
        val charCount = HashMap<Char, Int>()
        var totalCjk = 0L
        for (s in 0 until target.sentCount) {
            val original = target.sentText[s]
            for (c in original) {
                if (c in '\u4E00'..'\u9FFF') {
                    charCount.merge(c, 1, Int::plus)
                    totalCjk++
                }
            }
        }
        var replaced = 0L
        val unfixedChars = ArrayList<Pair<Char, Int>>()
        for ((c, count) in charCount) {
            val simplified = com.linguareader.shared.translation.TraditionalSimplified
                .toSimplified(c.toString())
            if (simplified != c.toString()) {
                replaced += count
            } else if (looksTraditional(c)) {
                // 未被替换的疑似繁体字
                unfixedChars.add(c to count)
            }
        }
        println("  汉字总数=$totalCjk，归一表覆盖（发生替换）=$replaced " +
            "(${pct(replaced.toDouble() / totalCjk.coerceAtLeast(1))})")
        println("  疑似繁体但未被归一表处理的字：${unfixedChars.size} 个不同字，" +
            "共 ${unfixedChars.sumOf { it.second }} 次")
        println("  TOP 20 缺失字（字 × 次数）：")
        unfixedChars.sortedByDescending { it.second }.take(20).forEach { (c, count) ->
            println("    '$c' (U+${Integer.toHexString(c.code).uppercase()}) × $count")
        }
    }

    /**
     * 启发式判断「疑似繁体」：用**归一表的反向映射**（简体→繁体）来判定。
     *
     * 精确做法：若某字是表里某个繁体字的**唯一对应简体**的繁体形式，它就该被替换；
     * 若没被替换，说明表里缺这条。这里用「该字不在简体集合里，且出现在一个高频
     * 繁体特征字表里」的近似。
     */
    private fun looksTraditional(c: Char): Boolean = c in TRADITIONAL_MARKERS

    // ──────────────────────────────────────────────────────────────
    //  根因 1：锚点
    // ──────────────────────────────────────────────────────────────

    private fun diagnoseAnchors(source: BookSide, target: BookSide) {
        val density = DensityModel(source)
        val structural = BooleanArray(source.chapterCount) { source.isStructural(it) }
        density.totalRatioFromChapters(target.chapterChars, source.chapterWords, structural)

        // 复现 AnchorLayer 的三道过滤，并记录每个键被哪一道剪掉
        val freqEn = HashMap<Int, Int>(8192)
        val freqZh = HashMap<Int, Int>(8192)
        countFrequency(source, freqEn)
        countFrequency(target, freqZh)
        println("  原始键：en=${freqEn.size} zh=${freqZh.size}")
        val shared = freqEn.keys.intersect(freqZh.keys)
        println("  两侧共有的键：${shared.size}")

        // 第一道：频率 ≤ ANCHOR_MAX_FREQ
        val afterFreq = shared.filter {
            (freqEn[it] ?: 0) <= AlignTuning.ANCHOR_MAX_FREQ &&
                (freqZh[it] ?: 0) <= AlignTuning.ANCHOR_MAX_FREQ
        }
        println("  第一道（频率 ≤ ${AlignTuning.ANCHOR_MAX_FREQ}）后：${afterFreq.size}")
        val freqPruned = (shared - afterFreq.toSet())
        println("  被频率剪掉的键：${freqPruned.size}")

        // 把被剪掉的高频键还原成可读文本（从两侧句子文本里查）
        println()
        println("  --- 被「频率」剪掉的 TOP 20（按 en 频次降序）---")
        println("  这就是 Lead 怀疑的根因：高频专名（Frodo/Gandalf）全在这里")
        freqPruned.sortedByDescending { freqEn[it] ?: 0 }.take(20).forEach { key ->
            val text = readableKey(source, key)
            println(
                "    \"${text}\"  en频次=${freqEn[key]}  zh频次=${freqZh[key] ?: 0}"
            )
        }

        val anchors = AnchorLayer(source, target, density)
        anchors.build()
        println()
        println("  最终硬钉数：${anchors.pins.size}")
        println("  notes: ${anchors.notes.take(8)}")
        if (anchors.notes.size > 8) println("  ... 共 ${anchors.notes.size} 条 note")
        println("  σ 输入：硬钉对残差数=${anchors.pinResiduals().size}")
    }

    private fun countFrequency(side: BookSide, into: HashMap<Int, Int>) {
        for (s in 0 until side.sentCount) {
            for (k in side.sentDigits[s]) into.merge(k, 1, Int::plus)
            for (k in side.sentLatins[s]) into.merge(k, 1, Int::plus)
        }
    }

    /** 把哈希键还原成可读文本（找第一个出现的句子里的原串）。 */
    private fun readableKey(side: BookSide, key: Int): String {
        for (s in 0 until side.sentCount) {
            val text = side.sentText[s]
            var i = 0
            while (i < text.length) {
                if (TextScan.isLetter(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isLetter(text[j]) ||
                            TextScan.isDigit(text[j]) || text[j] == '\'' || text[j] == '-')) j++
                    if (TextScan.hashLowerAscii(text, i, j) == key) return text.substring(i, j)
                    i = j
                } else if (TextScan.isDigit(text[i]) || TextScan.isFullwidthDigit(text[i])) {
                    var j = i + 1
                    while (j < text.length && (TextScan.isDigit(text[j]) ||
                            TextScan.isFullwidthDigit(text[j]) || text[j] == '.' || text[j] == ',')) j++
                    if (TextScan.hash(text, i, j) == key) return text.substring(i, j)
                    i = j
                } else i++
            }
        }
        return "<key=$key>"
    }

    // ──────────────────────────────────────────────────────────────
    //  语料装载（与 TtsTextExtractor 同口径：叶级块 + 空白归一）
    // ──────────────────────────────────────────────────────────────

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根（含 artifacts/lotr-book）：${System.getProperty("user.dir")}")
    }

    private fun loadEnglishChapters(root: File): List<AlignmentChapter> {
        val metadata = File(root, "artifacts/lotr-book/metadata.json")
        if (!metadata.isFile) return emptyList()
        val json = org.json.JSONObject(metadata.readText())
        val chapters = json.getJSONArray("chapters")
        val out = ArrayList<AlignmentChapter>(chapters.length())
        for (i in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(i)
            val title = chapter.optString("title", "")
            val relative = chapter.optString("relativePath", "")
            val file = File(root, "artifacts/lotr-book/$relative")
            if (!file.isFile) continue
            out.add(AlignmentChapter(title, leafBlocks(file)))
        }
        return out
    }

    private fun loadChineseChapters(root: File): List<AlignmentChapter> {
        val dir = File(root, "artifacts/lotr-zh/OEBPS/Text")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.extension.lowercase() == "xhtml" }
            ?.sortedBy { it.name }
            ?.map { AlignmentChapter(it.nameWithoutExtension, leafBlocks(it)) }
            ?: emptyList()
    }

    /** 叶级块抽取：与 `TtsTextExtractor.BLOCK_SELECTOR` 同口径 + `\s+`→空格 + trim。 */
    private fun leafBlocks(file: File): List<String> {
        val document = org.jsoup.Jsoup.parse(file, "UTF-8")
        val selector = com.linguareader.shared.alignment.RealCorpusDiagnosisProbe.BLOCK_SELECTOR
        return document.select(selector)
            .filter { candidate -> candidate.select(selector).all { it === candidate } }
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
    }

    private fun pct(v: Double): String = "${Math.round(v * 10000.0) / 100.0}%"

    private companion object {
        /** 与 `TtsTextExtractor.BLOCK_SELECTOR` 保持一字不差（口径同源）。 */
        const val BLOCK_SELECTOR =
            "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, " +
                "section, article, header, footer"

        /**
         * 繁体特征字（只在繁体里出现、简体不用的常见字）。
         *
         * 用途：判断「中译本里有多少汉字没被归一表覆盖」。这是一份**检测用**的字表，
         * 不是归一表本身——归一仍需扩充 `TraditionalSimplified`（那是 `translation`
         * 包的既有资产，改动要 bump 版本）。
         */
        val TRADITIONAL_MARKERS: Set<Char> = (
            "們個來說時會後點裡對於過還開關門問題進發現實學經濟給結寫報動東樂戰寶樹車軍" +
                "觀覺閃當國圖師臉龍語簡運遠邊選錢鐘長閉離隊約萬與鳥馬麥黃魚飛飯體幫處變幾" +
                "懷換減將據總愛備親賽讀該網責賣買媽壞館敵漢紅綠藍慶節驚舊燭鏡銀鎖聞習窮鄉" +
                "猶優條澤產標筆紙組計記設證響評講話談請謝識誰詞試誠護慣數書畫異災亂彎義膚" +
                "莊華蕩藝蘇蟲蠟衛術衝裝補規討詳認誕課調議豐貝質賴趕轉轟輕較郵醫闊階隨險雜" +
                "雞雲靈靜韓順願顯額驅驗鳴鷹齋齊齒龜蓮臘蘭圓塵層憶攔擁擇獲鍵鏽麼電餘縱縮龐" +
                "瀉獵懇撥讚練擊辭農騎麵亞歐臺爐斷斬懸敗財賞賠跨腳腦腰膝膀歲兒頭氣"
            ).toSet()
    }
}
