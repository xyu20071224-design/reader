package com.linguareader.shared.alignment

import kotlin.test.Test

/**
 * 临时诊断（不是交付物）：量 margin / z 的**真实分布**，用于给 Lead 的 D6 阈值标定提供数据。
 *
 * 这是 Lead 明确要求的那一步：「先按 §9.2 初值原样实现并实测，拿真实分布再说话」。
 */
class MarginDistributionProbe {

    /**
     * 回答 Lead 的 A 问：章节 margin 恒为 0 是 bug，还是语料退化？
     *
     * 做法：直接打印章节代价矩阵的**对角线**与**次对角线**值，以及 `pairCost` 的四项分解。
     * 若对角线（正确配对）明显更便宜而 margin 报 0 → margin 计算 bug；
     * 若两者本就几乎相同 → 语料同构（结构信号确实分不开），margin=0 是诚实结果。
     */
    @Test
    fun chapterCostMatrixIsDiagnosed() {
        // 语料 A：每章等长（合成 fixture 的典型形态）
        val equalLength = equalLengthChapters(6)
        // 语料 B：每章长度差异明显（真实书形态）
        val varied = variedLengthChapters(6)
        for ((label, pair) in listOf("equalLength" to equalLength, "variedLength" to varied)) {
            val (en, zh) = pair
            println("=== chapter cost matrix: $label ===")
            val source = BookSide.build(SideKind.SOURCE, en)
            val target = BookSide.build(SideKind.TARGET, zh)
            val density = DensityModel(source)
            val structuralEn = BooleanArray(source.chapterCount) { source.isStructural(it) }
            density.totalRatioFromChapters(
                chineseChars = target.chapterChars,
                englishWords = source.chapterWords,
                sourceStructural = structuralEn
            )
            val anchors = AnchorLayer(source, target, density)
            anchors.build()
            val chapters = ChapterLayer(source, target, anchors, density)
            chapters.run()
            println("  notes: ${chapters.notes}")

            // 用反射不可取，改用「跑两次不同语料看 r_total 与章节词数」的自证：
            // 直接打印每章的 enWords / zhChars，读者可自算 ρ 与 cL。
            println("  en chapter words: ${(0 until source.chapterCount).map { source.chapterWords[it] }}")
            println("  zh chapter chars: ${(0 until target.chapterCount).map { target.chapterChars[it] }}")
            println("  rTotal=${density.rTotal}")
            // 对角线 vs 次对角线：用公开的 pairCost 逐格打印
            val n = source.chapterCount
            val m = target.chapterCount
            val diagonal = (0 until minOf(n, m)).map { chapters.pairCost(it, 1, it, 1) }
            val offByOne = (0 until minOf(n - 1, m)).map { chapters.pairCost(it, 1, it + 1, 1) }
            println("  diag cost: ${diagonal.map { round4(it) }}")
            println("  off+1 cost: ${offByOne.map { round4(it) }}")
            if (diagonal.isNotEmpty() && offByOne.isNotEmpty()) {
                val maxDiag = diagonal.max()
                val minOff = offByOne.min()
                println(
                    "  maxDiag=$maxDiag minOff=$minOff → 对角线是否明显更便宜=" +
                        (minOff - maxDiag > AlignTuning.MARGIN_CHAPTER)
                )
            }
        }
    }

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    /** 等长章节：每章 4 段 × 3 句，句子长度高度同构。 */
    private fun equalLengthChapters(count: Int): Pair<List<AlignmentChapter>, List<AlignmentChapter>> {
        val en = ArrayList<AlignmentChapter>()
        val zh = ArrayList<AlignmentChapter>()
        var index = 0
        for (c in 0 until count) {
            val enParagraphs = ArrayList<String>()
            val zhParagraphs = ArrayList<String>()
            for (p in 0 until 4) {
                val enSentences = ArrayList<String>()
                val zhSentences = ArrayList<String>()
                for (s in 0 until 3) {
                    enSentences += "The elder remembered the river at dusk."
                    zhSentences += "那长者想起了那条河，时天色已暗。"
                    index++
                }
                enParagraphs += enSentences.joinToString(" ")
                zhParagraphs += zhSentences.joinToString("")
            }
            en += AlignmentChapter("Chapter ${c + 1}", enParagraphs)
            zh += AlignmentChapter("第${c + 1}章", zhParagraphs)
        }
        return en to zh
    }

    /** 差异明显的章节：每章段数从 2 到 12 不等（真实书里章节长度差好几倍）。 */
    private fun variedLengthChapters(count: Int): Pair<List<AlignmentChapter>, List<AlignmentChapter>> {
        val en = ArrayList<AlignmentChapter>()
        val zh = ArrayList<AlignmentChapter>()
        var index = 0
        for (c in 0 until count) {
            val paragraphCount = 2 + c * 2
            val enParagraphs = ArrayList<String>()
            val zhParagraphs = ArrayList<String>()
            for (p in 0 until paragraphCount) {
                val enSentences = ArrayList<String>()
                val zhSentences = ArrayList<String>()
                for (s in 0 until 3) {
                    enSentences += "The elder remembered the river at dusk."
                    zhSentences += "那长者想起了那条河，时天色已暗。"
                    index++
                }
                enParagraphs += enSentences.joinToString(" ")
                zhParagraphs += zhSentences.joinToString("")
            }
            en += AlignmentChapter("Chapter ${c + 1}", enParagraphs)
            zh += AlignmentChapter("第${c + 1}章", zhParagraphs)
        }
        return en to zh
    }

    @Test
    fun marginDistribution() {
        val (en, zh) = realisticBook()
        val aligner = IndependentAligner()
        // 需要看内部 margin：用反射不可取，改为对齐后从 evidence.positionDrift 看 drift，
        // 这里直接复算「配对的边」的 margin 分布 —— 用与前缀签名的对比近似不可靠，
        // 因此改用另一条路：在同语料上跑，统计 ALIGNED/UNCERTAIN 的原因分布。
        val document = aligner.align(en, zh, lexicon())
        val reasons = HashMap<String, Int>()
        document.units.forEach { unit ->
            val key = when (unit.status) {
                AlignmentStatus.ALIGNED -> "ALIGNED"
                AlignmentStatus.UNALIGNED -> "UNALIGNED:${unit.skipReason}"
                AlignmentStatus.UNCERTAIN -> {
                    val why = unit.evidence.reasons.firstOrNull() ?: "unknown"
                    "UNCERTAIN:" + why.substringBefore('=').substringBefore(' ')
                }
            }
            reasons.merge(key, 1, Int::plus)
        }
        println("=== 判定原因分布（realistic corpus, n=${document.units.size}）===")
        reasons.entries.sortedByDescending { it.value }.forEach { (why, count) ->
            println("  $why -> $count (${(count * 100.0 / document.units.size).toInt()}%)")
        }
        println("=== STATS ===")
        println("  " + document.stats.summaryLine())
        println("=== NOTES ===")
        document.notes.forEach { println("  $it") }

        // z 与 drift 分布（只统计有配对的单元）
        val zs = document.units.filter { it.evidence.lengthDeviation != Double.MAX_VALUE }
            .map { it.evidence.lengthDeviation }
            .sorted()
        println("=== z 分布（有配对单元 n=${zs.size}）===")
        if (zs.isNotEmpty()) {
            println("  p10=${percentile(zs, 0.10)} p50=${percentile(zs, 0.50)} p90=${percentile(zs, 0.90)}")
        }
    }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]

    /**
     * 更接近真实散文的语料：段落长短不一、句长有方差、有对话与数字专名。
     *
     * 与合成 fixture 的区别是**不该被忽略的**：fixture 每句 10–13 词且几乎等长，
     * 那种同构性会让 length-only 的 margin 天然接近 0（Lead 提示过这个陷阱）。
     */
    private fun realisticBook(): Pair<List<AlignmentChapter>, List<AlignmentChapter>> {
        val enSentences = listOf(
            "He stopped dead in the middle of the road and stared at the sky for a long while.",
            "\"Run,\" she said.",
            "The hall was silent.",
            "Outside, the wind pushed against the shutters and the old house creaked in answer, " +
                "as if it had been waiting all night for someone to come back and knock.",
            "He nodded.",
            "There were nine of them now, nine riders on the ridge, and the sun was already behind the hills.",
            "\"We should not have come this far,\" the stranger whispered, \"not with the river rising.\"",
            "Rain.",
            "The road bent east, then north, and vanished into a stand of pines that had not been there the year before.",
            "She counted the bells: one, two, three, and then silence that lasted far too long.",
            "\"Is anyone there?\"",
            "Nothing moved.",
            "By the time they reached the harbour the boats had already gone out and the gulls were screaming " +
                "over a catch that no one would ever bring home.",
            "He was tired.",
            "The elder remembered the river at dusk and the way the water had carried the light away."
        )
        val zhSentences = listOf(
            "他猛地停在大路中间，久久地仰望着天空，一动不动。",
            "「快跑。」她说。",
            "大厅里静悄悄的。",
            "外面，风推着百叶窗，老房子吱呀作响，仿佛它整夜都在等着有人回来敲门似的，",
            "他点了点头。",
            "山脊上现在有九个人，九名骑手，而太阳已经落到山后了。",
            "「我们不该走这么远，」陌生人低声说，「河水还在涨。」",
            "雨。",
            "大路向东转，再向北，最后消失在一片去年还不在那里的松林里。",
            "她数着钟声：一、二、三，然后是一段长得出奇的寂静。",
            "「有人吗？」",
            "什么也没有动。",
            "等他们赶到港口时，船早已出海，海鸥在一网再也不会有人带回家的渔获上方尖声叫着。",
            "他累了。",
            "那长者想起了黄昏时的河，想起了水是怎样把光带走的。"
        )
        var index = 0
        val source = ArrayList<AlignmentChapter>()
        val translation = ArrayList<AlignmentChapter>()
        for (chapter in 0 until 6) {
            val enParagraphs = ArrayList<String>()
            val zhParagraphs = ArrayList<String>()
            for (paragraph in 0 until 4) {
                val en = ArrayList<String>()
                val zh = ArrayList<String>()
                for (s in 0 until 3) {
                    en += enSentences[index % enSentences.size]
                    zh += zhSentences[index % zhSentences.size]
                    index++
                }
                enParagraphs += en.joinToString(" ")
                zhParagraphs += zh.joinToString("")
            }
            source += AlignmentChapter("Chapter ${chapter + 1}", enParagraphs)
            translation += AlignmentChapter("第${chapter + 1}章", zhParagraphs)
        }
        return source to translation
    }

    private fun lexicon(): MeaningProvider = MeaningProvider { word ->
        when (word.lowercase()) {
            "elder" -> listOf("长者")
            "remembered" -> listOf("想起")
            "river" -> listOf("河")
            "water" -> listOf("水")
            "light" -> listOf("光")
            "harbour" -> listOf("港口")
            "boats" -> listOf("船")
            "gulls" -> listOf("海鸥")
            "road" -> listOf("大路")
            "pines" -> listOf("松林")
            "bells" -> listOf("钟声")
            "silence" -> listOf("寂静")
            "hall" -> listOf("大厅")
            "wind" -> listOf("风")
            "house" -> listOf("房子")
            "riders" -> listOf("骑手")
            "ridge" -> listOf("山脊")
            "sun" -> listOf("太阳")
            "rain" -> listOf("雨")
            "stranger" -> listOf("陌生人")
            "nodded" -> listOf("点了点头")
            "tired" -> listOf("累")
            else -> emptyList()
        }
    }
}
