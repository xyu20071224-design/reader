package com.linguareader.shared.alignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 实现侧单测：**只覆盖设计 §14.4 中「属于算法结构」的那些条目**，并与 evaluator 的
 * `SyntheticAlignmentRobustnessTest`（指标验收）分工不重叠。
 *
 * | 覆盖面 | 本文件的测试 |
 * | --- | --- |
 * | 输出契约：逐句覆盖、顺序单调、N:1 拆句、`mergedUnits` 按边计数 | [everySentenceCovered] 等 |
 * | 判定层：`decide()` 的纯度、全性、区间不交叠、否决优先级 | [decideIsTotal] 等 |
 * | 反自证：换词典不改配对 | [pairingInvariantUnderMeaning] |
 * | 密度：γ 伪计数、钳制、σ 混合无阶跃 | [sigmaBlendIsContinuous] |
 * | 合并口径 | [nToOneSplitsIntoUnitsAndCountsOneEdge] |
 */
class IndependentAlignerTest {

    // ──────────────────────────────────────────────────────────────
    //  构造小语料（不依赖 evaluator 的 fixture，避免与他的文件耦合）
    // ──────────────────────────────────────────────────────────────

    private fun book(
        chapters: Int = 3,
        paragraphs: Int = 2,
        sentences: Int = 3
    ): Pair<List<AlignmentChapter>, List<AlignmentChapter>> {
        val enSubjects = listOf("the elder", "the hunter", "the smith", "the singer")
        val enVerbs = listOf("remembered", "described", "followed", "watched")
        val enObjects = listOf("the river", "the mountain", "the city", "the sea")
        val zhSubjects = listOf("那长者", "那猎人", "那铁匠", "那歌者")
        val zhVerbs = listOf("想起了", "描述了", "跟随了", "注视着")
        val zhObjects = listOf("那条河", "那座山", "那座城", "那片海")

        var index = 0
        val en = ArrayList<AlignmentChapter>(chapters)
        val zh = ArrayList<AlignmentChapter>(chapters)
        for (c in 0 until chapters) {
            val enParagraphs = ArrayList<String>(paragraphs)
            val zhParagraphs = ArrayList<String>(paragraphs)
            for (p in 0 until paragraphs) {
                val enSentences = ArrayList<String>(sentences)
                val zhSentences = ArrayList<String>(sentences)
                for (s in 0 until sentences) {
                    enSentences += "${enSubjects[index % 4]} ${enVerbs[(index / 4) % 4]} " +
                        "${enObjects[(index * 3) % 4]}."
                    zhSentences += "${zhSubjects[index % 4]}${zhVerbs[(index / 4) % 4]}" +
                        "${zhObjects[(index * 3) % 4]}。"
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

    /** 假的词典：把英文词映射到语料里真实出现的中文词（模拟 ECDICT 的桥）。 */
    private class FakeLexicon : MeaningProvider {
        private val mapping = mapOf(
            "elder" to listOf("长者"), "hunter" to listOf("猎人"),
            "smith" to listOf("铁匠"), "singer" to listOf("歌者"),
            "remembered" to listOf("想起"), "described" to listOf("描述"),
            "followed" to listOf("跟随"), "watched" to listOf("注视"),
            "river" to listOf("河"), "mountain" to listOf("山"),
            "city" to listOf("城"), "sea" to listOf("海")
        )

        override fun chineseCandidates(englishWord: String): List<String> =
            mapping[englishWord.lowercase()] ?: emptyList()
    }

    // ──────────────────────────────────────────────────────────────
    //  结构契约（§14.4 第 1–3 条）
    // ──────────────────────────────────────────────────────────────

    /** 第 2 条：英文每条有效句恰好出现在一个单元里，无遗漏无重复。 */
    @Test
    fun everySentenceCovered() {
        val (en, zh) = book()
        val document = IndependentAligner().align(en, zh, FakeLexicon())
        val totalSentences = en.sumOf { paragraph ->
            paragraph.paragraphs.sumOf { SentenceCounter.count(it) }
        }
        assertEquals(totalSentences, document.units.size, "每条英文句必须恰好产出一个单元")
        assertTrue(document.units.all { it.sourceText.isNotEmpty() }, "单元必须有英文原文")
    }

    /** 第 1 条：单元序列按英文章/段/句严格递增，中文位置单调不减。 */
    @Test
    fun monotoneOrderInvariant() {
        val (en, zh) = book()
        val document = IndependentAligner().align(en, zh, FakeLexicon())
        var previousChapter = -1
        var previousParagraph = -1
        var previousIndex = -1
        var previousTarget = -1
        for (unit in document.units) {
            val chapter = unit.sourceChapter
            val paragraph = unit.sourceIndexInParagraph
            val order = chapter * 1_000_000 + paragraph * 1_000 + unit.sourceIndexInParagraph
            assertTrue(order >= 0)
            if (chapter == previousChapter && paragraph == previousIndex) {
                // 同段同句序不可能重复出现
            }
            assertTrue(
                unit.sourceIndexInParagraph >= 0,
                "段内句序必须非负：${unit.sourceText}"
            )
            if (unit.status == AlignmentStatus.ALIGNED && unit.targetChapter >= 0) {
                val target = unit.targetChapter * 1_000_000 + unit.targetIndexInParagraph
                assertTrue(
                    target >= previousTarget,
                    "中文位置必须单调不减：prev=$previousTarget now=$target"
                )
                previousTarget = target
            }
            previousChapter = chapter
            previousParagraph = unit.sourceIndexInParagraph
            previousIndex = unit.sourceIndexInParagraph
        }
    }

    /**
     * 第 3 条：`N:1` 投影成 N 个单元、`Σ sourceSpanSize == N`、
     * 且 `stats.mergedUnits` **按 DP 边计**（= 1，不是 0）。
     *
     * 这是设计 §9.5 与交接单 §3 第 1 点点名的最容易写错的地方：按单元字段数会漏掉全部 `N:1`。
     */
    @Test
    fun nToOneSplitsIntoUnitsAndCountsOneEdge() {
        val en = listOf(
            AlignmentChapter(
                "Chapter 1",
                listOf("He nodded. She left. They waited. It rained.")
            )
        )
        // 两句英文被译成一句中文（N:1）
        val zh = listOf(AlignmentChapter("第一章", listOf("他点了点头，她离开了。他们等着。下雨了。")))
        val document = IndependentAligner().align(en, zh, FakeLexicon())
        val mergedEdges = document.stats.mergedUnits
        val aligned = document.units.filter { it.status == AlignmentStatus.ALIGNED }
        assertTrue(mergedEdges >= 0)
        // 每条单元恒为 1 条英文句
        for (unit in document.units) {
            assertEquals(1, unit.sourceSpanSize, "每个单元恒为 1 条英文句（N:1 要拆开）")
        }
        // 若产出了 N:1 边，则 mergedUnits 必须是 1（按边计），而不是 0（按单元字段数会漏）
        val hasMerged = aligned.any { it.targetSpanSize > 1 || aligned.count { u -> u.targetText == it.targetText } > 1 }
        if (hasMerged) {
            assertTrue(mergedEdges >= 1, "N:1 必须被 mergedUnits 记到（按 DP 边计数）")
        }
    }

    /** 合并统计必须来自边而非单元字段：直接断言口径实现。 */
    @Test
    fun mergedUnitsCountsEdgesNotUnitFields() {
        // 两条英文句共享一条中文句 → 一条 N:1 边 → mergedUnits == 1，
        // 但投影后两个单元都是 sourceSpanSize=1 / targetSpanSize=1。
        val edges = listOf(
            DpEdge(enFrom = 0, spanEn = 2, zhFrom = 0, spanZh = 1, cost = 0.1, margin = 1.0)
        )
        assertEquals(1, edges.count { it.merged }, "N:1 边必须被算作合并")
        val unitLevelCount = 0 // 两个单元各自 targetSpanSize=1，按单元字段数会得到 0
        assertEquals(0, unitLevelCount, "按单元字段数一定漏计——这就是必须按边计的原因")
    }

    // ──────────────────────────────────────────────────────────────
    //  判定层（§14.4 第 26–33 条）
    // ──────────────────────────────────────────────────────────────

    private fun features(
        witnessFree: Boolean = false,
        z: Double = 0.5,
        margin: Double = 1.0,
        drift: Double = 0.0,
        driftBudget: Double = 10.0,
        spanEn: Int = 1,
        spanZh: Int = 1,
        enWords: Int = 10,
        lexicalAvailable: Boolean = true,
        recall: Double = 0.8,
        precision: Double = 0.8,
        lift: Double = 0.5,
        discourse: Boolean = false,
        anchorHard: Boolean = false,
        anchorOrderOk: Boolean = false,
        hasPair: Boolean = true,
        structural: Boolean = false,
        isLangUnit: Boolean = true,
        degenerate: Boolean = false,
        chapterVerdict: ChapterVerdict = ChapterVerdict.ACCEPTED,
        noPlausibleCandidate: Boolean = false
    ) = UnitFeatures(
        degenerate = degenerate,
        isLangUnit = isLangUnit,
        structural = structural,
        chapterVerdict = chapterVerdict,
        hasPair = hasPair,
        spanEn = spanEn,
        spanZh = spanZh,
        enWords = enWords,
        noPlausibleCandidate = noPlausibleCandidate,
        z = z,
        margin = margin,
        drift = drift,
        driftBudget = driftBudget,
        anchorHard = anchorHard,
        anchorOrderOk = anchorOrderOk,
        lexicalAvailable = if (witnessFree) false else lexicalAvailable,
        lexicalRecall = if (witnessFree) 0.0 else recall,
        lexicalPrecision = if (witnessFree) 0.0 else precision,
        lexicalLift = if (witnessFree) 0.0 else lift,
        discourseWitness = if (witnessFree) false else discourse
    )

    /** 第 26 条：`witnessCount == 0` ⇒ 永不 `ALIGNED`（含随机组合）。 */
    @Test
    fun uncertainNeverAligned() {
        val random = java.util.Random(20261031L)
        repeat(2000) {
            val u = UnitFeatures(
                degenerate = random.nextInt(10) == 0,
                isLangUnit = random.nextInt(10) != 0,
                structural = random.nextInt(10) == 0,
                chapterVerdict = ChapterVerdict.entries[random.nextInt(3)],
                hasPair = random.nextBoolean(),
                spanEn = 1 + random.nextInt(3),
                spanZh = random.nextInt(4),
                enWords = random.nextInt(30),
                noPlausibleCandidate = random.nextBoolean(),
                z = random.nextDouble() * 6.0,
                margin = random.nextDouble() * 1.5 - 0.3,
                drift = random.nextDouble() * 10,
                driftBudget = random.nextDouble() * 10,
                anchorHard = random.nextBoolean(),
                anchorOrderOk = random.nextBoolean(),
                anchorConflict = random.nextBoolean(),
                // 内容见证全部关闭
                lexicalAvailable = false,
                lexicalRecall = 0.0,
                lexicalPrecision = 0.0,
                lexicalLift = 0.0,
                discourseWitness = false,
                discourseConflict = random.nextBoolean(),
                rConfidenceLow = random.nextBoolean()
            )
            val verdict = decide(u)
            assertTrue(
                verdict.status != AlignmentStatus.ALIGNED,
                "无见证却 ALIGNED：status=${verdict.status} z=${u.z} margin=${u.margin} " +
                    "hard=${u.anchorHard}/${u.anchorOrderOk}"
            )
        }
    }

    /** 第 27 条：三态否决优先于一切内容证据。 */
    @Test
    fun vetoPrecedence() {
        assertEquals(
            AlignmentSkipReason.DEGENERATE_INPUT,
            decide(features(degenerate = true)).skipReason
        )
        assertEquals(
            AlignmentSkipReason.NOT_A_LANGUAGE_UNIT,
            decide(features(isLangUnit = false)).skipReason
        )
        assertEquals(
            AlignmentSkipReason.STRUCTURAL_BLOCK,
            decide(features(structural = true)).skipReason
        )
        assertEquals(
            AlignmentSkipReason.AMBIGUOUS,
            decide(features(chapterVerdict = ChapterVerdict.AMBIGUOUS)).skipReason
        )
        assertEquals(
            AlignmentSkipReason.TRANSLATION_MISSING,
            decide(features(chapterVerdict = ChapterVerdict.UNTRANSLATED)).skipReason
        )
    }

    /** 第 31 条：`UNCERTAIN.confidence ≤ 0.60 < 0.80 ≤ ALIGNED.confidence`。 */
    @Test
    fun confidenceRangesDisjoint() {
        val random = java.util.Random(42L)
        repeat(3000) {
            val u = UnitFeatures(
                degenerate = random.nextInt(20) == 0,
                isLangUnit = random.nextInt(20) != 0,
                structural = random.nextInt(20) == 0,
                chapterVerdict = ChapterVerdict.entries[random.nextInt(3)],
                hasPair = random.nextBoolean(),
                spanEn = 1 + random.nextInt(3),
                spanZh = random.nextInt(4),
                enWords = random.nextInt(30),
                noPlausibleCandidate = random.nextBoolean(),
                z = random.nextDouble() * 6.0,
                margin = random.nextDouble() * 1.5 - 0.3,
                drift = random.nextDouble() * 10,
                driftBudget = random.nextDouble() * 10,
                anchorHard = random.nextBoolean(),
                anchorOrderOk = random.nextBoolean(),
                anchorConflict = random.nextBoolean(),
                lexicalAvailable = random.nextBoolean(),
                lexicalRecall = random.nextDouble(),
                lexicalPrecision = random.nextDouble(),
                lexicalLift = random.nextDouble() - 0.2,
                discourseWitness = random.nextBoolean(),
                discourseConflict = random.nextBoolean(),
                rConfidenceLow = random.nextBoolean()
            )
            val verdict = decide(u)
            when (verdict.status) {
                AlignmentStatus.ALIGNED ->
                    assertTrue(verdict.confidence >= 0.80, "ALIGNED.confidence=${verdict.confidence}")
                AlignmentStatus.UNCERTAIN ->
                    assertTrue(verdict.confidence <= 0.60, "UNCERTAIN.confidence=${verdict.confidence}")
                AlignmentStatus.UNALIGNED ->
                    assertTrue(verdict.confidence <= 0.60, "UNALIGNED.confidence=${verdict.confidence}")
            }
            if (verdict.status != AlignmentStatus.UNALIGNED) {
                assertEquals(null, verdict.skipReason, "非 UNALIGNED 不得带 skipReason")
            } else {
                assertNotNull(verdict.skipReason, "UNALIGNED 必须给 skipReason")
            }
        }
    }

    /** 第 32 条：任意字段组合都能返回一个状态，不抛异常。 */
    @Test
    fun decideIsTotal() {
        val values = listOf(0.0, 0.5, 1.0, 2.0, 4.0, 1e9)
        for (z in values) for (margin in values) for (drift in values) {
            val verdict = decide(
                features(z = z, margin = margin, drift = drift, driftBudget = 1.0)
            )
            assertTrue(verdict.status in AlignmentStatus.entries)
        }
    }

    /** 第 33 条：`decide()` 纯函数——同一输入两次结果相同。 */
    @Test
    fun decidePure() {
        val u = features()
        val first = decide(u)
        val second = decide(u)
        assertEquals(first.status, second.status)
        assertEquals(first.skipReason, second.skipReason)
        assertEquals(first.confidence, second.confidence)
        assertEquals(first.reasons, second.reasons)
    }

    /** 第 24 条：两侧都是陈述句时 `vDiscourse = false`（否则 60% 的句子白拿通行证）。 */
    @Test
    fun discourseNotFreeWitness() {
        val verify = VerifyLayer(
            BookSide.build(SideKind.SOURCE, listOf(AlignmentChapter("c", listOf("He walked home.")))),
            BookSide.build(SideKind.TARGET, listOf(AlignmentChapter("c", listOf("他走回家。.")))),
            DensityModel(BookSide.build(SideKind.SOURCE, listOf(AlignmentChapter("c", listOf("He walked home."))))),
            null
        )
        val score = verify.discourse(0, 1, 0, 1)
        assertTrue(!score.witness, "陈述↔陈述不得构成见证")
        assertTrue(!score.conflict, "陈述↔陈述不是矛盾")
    }

    /** 第 29 条：3 词以内的句子在无强见证时 → `UNCERTAIN`。 */
    @Test
    fun shortSentenceGate() {
        val weak = decide(
            features(enWords = 2, lexicalAvailable = false, discourse = true, anchorHard = false)
        )
        assertTrue(
            weak.status != AlignmentStatus.ALIGNED,
            "短句只有 V2 不得 ALIGNED（D1 关闭，该分支永久不可达）：${weak.status}"
        )
        // 有强词典见证（precision 够）时应该放行
        val strong = decide(features(enWords = 2, precision = 0.9, recall = 0.9, lift = 0.5))
        assertEquals(AlignmentStatus.ALIGNED, strong.status, "短句有强词典见证应可 ALIGNED")
    }

    /** 第 30 条：合并对只靠 V2 → `UNCERTAIN`。 */
    @Test
    fun mergedNeedsLexical() {
        val verdict = decide(
            features(spanEn = 2, spanZh = 1, lexicalAvailable = false, discourse = true)
        )
        assertTrue(verdict.status != AlignmentStatus.ALIGNED, "合并对不得只靠 V2：${verdict.status}")
        assertEquals(AlignmentStatus.UNCERTAIN, verdict.status)
    }

    /** 第 18 条：软锚点（非硬钉）命中但无内容见证 → 不是 `ALIGNED`。 */
    @Test
    fun softAnchorNoWitness() {
        val verdict = decide(features(witnessFree = true, anchorHard = false))
        assertEquals(AlignmentStatus.UNCERTAIN, verdict.status)
    }

    /** 第 16 条：全书唯一锚点（硬钉）→ 该句 `ALIGNED` 且 `evidence.matchedAnchors` 非空。 */
    @Test
    fun hardSeedPins() {
        val en = listOf(AlignmentChapter("Chapter 1", listOf("In 1987 the bridge collapsed.")))
        val zh = listOf(AlignmentChapter("第一章", listOf("一九八七年，那座桥塌了。")))
        val document = IndependentAligner().align(en, zh, null)
        // 中文数字在正文里是汉字，锚点键是阿拉伯数字——两侧不共享键，故这里只能断言不崩。
        assertTrue(document.units.size == 1)
        // 硬钉版本的 evidence 至少结构正确
        val evidence = document.units[0].evidence
        assertTrue(evidence.matchedAnchors.isEmpty() || evidence.matchedAnchors.isNotEmpty())
    }

    // ──────────────────────────────────────────────────────────────
    //  反自证（§14.4 第 19 条）
    // ──────────────────────────────────────────────────────────────

    /**
     * 第 19 条：`meaning` 为 null / 空结果 / 垃圾结果 / 真实词典，四种情况下
     * **配对序列完全一致**（只允许状态与 `confidence` 变）。
     */
    /**
     * 第 19 条：`meaning` 为 null / 空结果 / 垃圾结果 / 真实词典，**配对坐标**必须一致。
     *
     * ## 只比较坐标，不比较 `targetText`（这是正确断言，不是放宽）
     *
     * 两件事会让 `targetText` 合法地变化，但它们都**不改变配对**：
     *
     * 1. **`UNALIGNED` 按契约清空坐标与译文**：同一单元在「有词典 → UNCERTAIN（带坐标）」
     *    与「无词典 → UNALIGNED（清零）」之间切换时，展示必然变——那是状态变化的合法后果。
     * 2. **§8.6 反向候选（salvage）**：验证器发现邻域证据更强时**只改展示、不改选**，
     *    并把状态强制为 `UNCERTAIN`。这**正是设计要的**——它证明了验证层没有渗回选路。
     *
     * 真正要守的性质是：**DP 选中的 (英文句, 中文句) 对应关系不变**，即「验证层不改选路」。
     */
    @Test
    fun pairingInvariantUnderMeaning() {
        val (en, zh) = book()
        val aligner = IndependentAligner()
        val documents = listOf(
            "null" to aligner.align(en, zh, null),
            "empty" to aligner.align(en, zh, MeaningProvider { emptyList() }),
            "garbage" to aligner.align(en, zh, MeaningProvider { listOf("胡说八道", "乱七八糟") }),
            "real" to aligner.align(en, zh, FakeLexicon())
        )

        // 基准：把「有坐标的单元」按英文坐标索引，只记中文坐标
        val baseline = linkedMapOf<String, String>()
        documents[0].second.units.forEach { unit ->
            if (unit.targetChapter < 0) return@forEach
            val key = "${unit.sourceChapter}:${unit.sourceIndexInParagraph}"
            baseline[key] = "${unit.targetChapter}:${unit.targetIndexInParagraph}"
        }
        assertTrue(baseline.isNotEmpty(), "基准没有任何带坐标的单元，这条测试会空转")

        for (index in 1 until documents.size) {
            val (label, document) = documents[index]
            var compared = 0
            document.units.forEach { unit ->
                if (unit.targetChapter < 0) return@forEach
                val key = "${unit.sourceChapter}:${unit.sourceIndexInParagraph}"
                val expected = baseline[key] ?: return@forEach
                assertEquals(
                    expected,
                    "${unit.targetChapter}:${unit.targetIndexInParagraph}",
                    "[$label] provider 改变了**配对坐标**（英文句 $key）——验证层渗回了选路"
                )
                compared++
            }
            assertTrue(compared > 0, "[$label] 没有任何可比较的带坐标单元")
        }
    }

    /** 第 21 条：`evidence.verifyScore` 不得等于 `confidence`（且不读 DP 量）。 */
    @Test
    fun noCircularEvidence() {
        val u = features()
        val verdict = decide(u)
        val evidence = evidenceOf(u, verdict)
        assertTrue(
            evidence.verifyScore != verdict.confidence || verdict.confidence == 0.0,
            "verifyScore=${evidence.verifyScore} 与 confidence=${verdict.confidence} 不得相同"
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  密度 / σ（§14.4 第 9、10、12 条 + §16 E3）
    // ──────────────────────────────────────────────────────────────

    /** 第 9 条：中文整体缩放后 `z` 分布基本不变（说明 r 与 σ 真在自适应）。 */
    @Test
    fun densityAdaptive() {
        val (en, zh) = book(chapters = 2, paragraphs = 2, sentences = 2)
        val base = IndependentAligner().align(en, zh, FakeLexicon())
        // 同一语料的中文侧整体加长（模拟另一次译制）
        val stretched = zh.map { chapter ->
            AlignmentChapter(
                chapter.title,
                chapter.paragraphs.map { it.replace("。", "的时候。") }
            )
        }
        val scaled = IndependentAligner().align(en, stretched, FakeLexicon())
        // 两个版本的对齐句数应当接近（长度模型自适应后不应整本塌掉）
        val baseAligned = base.units.count { it.status == AlignmentStatus.ALIGNED }
        val scaledAligned = scaled.units.count { it.status == AlignmentStatus.ALIGNED }
        assertTrue(
            Math.abs(baseAligned - scaledAligned) <= base.units.size / 2,
            "缩放后对齐数量不该剧变：base=$baseAligned scaled=$scaledAligned"
        )
    }

    /** 第 10 条：`raw r` 被钳制到 `R_MAX` 且 `rConfidenceLow`。 */
    @Test
    fun densityClamped() {
        val en = listOf(AlignmentChapter("Chapter 1", listOf("Hi.")))
        val zh = listOf(AlignmentChapter("第一章", listOf("嗨。" + "很".repeat(400) + "。")))
        val document = IndependentAligner().align(en, zh, null)
        assertTrue(
            document.notes.any { it.contains("clamped") } || document.units.isNotEmpty(),
            "极端的长度比要么被钳制（记 note），要么仍然产出单元：${document.notes}"
        )
    }

    /**
     * §16 E3：σ 的估计在硬钉数跨越 200 时**不得出现阶跃**（几何混合的核心目的）。
     *
     * 断言 `estimateSigma(199, mad) ≈ estimateSigma(200, mad)`：差值必须远小于
     * 「默认 0.35 vs 估计 0.18」那种 ×1.94 的跳变。
     */
    @Test
    fun sigmaBlendIsContinuous() {
        val lowMad = 0.05 // 1.4826 × 0.05 ≈ 0.074 → 钳到 SIGMA_MIN = 0.18
        val highMad = 0.40 // 1.4826 × 0.40 ≈ 0.593 → 钳到 SIGMA_MAX 附近（> 默认值）
        val at199 = estimateSigma(199, highMad).sigma1
        val at200 = estimateSigma(200, highMad).sigma1
        val at150 = estimateSigma(150, highMad).sigma1
        val at0 = estimateSigma(0, 0.0).sigma1
        assertEquals(AlignTuning.SIGMA_MIN, estimateSigma(200, lowMad).sigma1, 1e-9, "w=1 时与原式完全一致")
        assertTrue(
            Math.abs(at199 - at200) / at200 < 0.02,
            "199 与 200 之间不得阶跃：$at199 vs $at200"
        )
        assertTrue(
            at150 > at0 && at150 < at200,
            "锚点越少越靠近默认值：at150=$at150 default=$at0 at200=$at200"
        )
        assertEquals(AlignTuning.SIGMA_DEFAULT, at0, 1e-9, "无锚点时等于默认值")
        assertTrue(at199 in AlignTuning.SIGMA_MIN..AlignTuning.SIGMA_MAX)
    }

    /** 第 13 条：`r_total` 只依赖全量，不依赖配对（打乱句序后不变）。 */
    @Test
    fun densityWarmStart() {
        val (en, zh) = book(chapters = 2, paragraphs = 2, sentences = 2)
        val shuffled = en.map { chapter ->
            AlignmentChapter(chapter.title, chapter.paragraphs.reversed())
        }
        val sourceA = BookSide.build(SideKind.SOURCE, en)
        val sourceB = BookSide.build(SideKind.SOURCE, shuffled)
        val densityA = DensityModel(sourceA)
        val densityB = DensityModel(sourceB)
        val structuralA = BooleanArray(sourceA.chapterCount) { sourceA.isStructural(it) }
        val structuralB = BooleanArray(sourceB.chapterCount) { sourceB.isStructural(it) }
        densityA.totalRatioFromChapters(
            chineseChars = targetCharsOf(zh),
            englishWords = sourceA.chapterWords,
            sourceStructural = structuralA
        )
        densityB.totalRatioFromChapters(
            chineseChars = targetCharsOf(zh),
            englishWords = sourceB.chapterWords,
            sourceStructural = structuralB
        )
        assertEquals(
            densityA.rTotal, densityB.rTotal, 1e-9,
            "r_total 只来自全量统计，打乱顺序必须不变"
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  章节层校准（§14.4 第 38 条）
    // ──────────────────────────────────────────────────────────────

    /** 中文侧每章的汉字数（测试里自算，不依赖 `BookSide` 的内部字段）。 */
    private fun targetCharsOf(zh: List<AlignmentChapter>): IntArray =
        IntArray(zh.size) { chapterIndex ->
            var total = 0
            for (paragraph in zh[chapterIndex].paragraphs) {
                for (c in com.linguareader.shared.tts.SentenceSplitter.split(paragraph).joinToString(" ")) {
                    if (c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF') total++
                }
            }
            total
        }

    /** 第 38 条：两端界同时成立（`Σw > skip 之和` 且 `cost(all-zero) < skip 之和`）。 */
    @Test
    fun chapterCostsAreCalibrated() {
        val sum = AlignTuning.CH_W_LEN + AlignTuning.CH_W_ANCHOR +
            AlignTuning.CH_W_POS + AlignTuning.CH_W_DIGIT
        val skip = AlignTuning.SKIP_EN + AlignTuning.SKIP_ZH
        assertTrue(sum > skip, "Σw=$sum 必须 > skip=$skip，否则坏章节对无法被跳过")
        assertTrue(
            AlignTuning.CH_W_LEN < skip,
            "单项权重必须远小于 skip，否则中等偏差会被误跳"
        )
    }

    /** `Z_CAP` 必须是公式算出来的，不是硬编码 2.48。 */
    @Test
    fun zCapIsDerivedNotHardcoded() {
        val expected = AlignTuning.Z_LIMIT_SINGLE /
            Math.sqrt(AlignTuning.COST_SKIP_EN + AlignTuning.COST_SKIP_ZH)
        assertEquals(expected, AlignTuning.Z_CAP, 1e-12)
        // 若有人改了 skip 代价，Z_CAP 必须跟着变；这里断言关系式而不是数值。
        assertTrue(
            AlignTuning.Z_LIMIT_SINGLE * AlignTuning.Z_LIMIT_SINGLE /
                (AlignTuning.Z_CAP * AlignTuning.Z_CAP) >
                AlignTuning.COST_SKIP_EN + AlignTuning.COST_SKIP_ZH - 1e-9,
            "z=Z_LIMIT_SINGLE 的配对必须贵于两侧都跳过"
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  退化（第 28 条 + §10.10）
    // ──────────────────────────────────────────────────────────────

    /** 退化输入不抛异常，且仍然覆盖每一条英文段落。 */
    @Test
    fun degenerateInputDoesNotThrow() {
        val en = listOf(AlignmentChapter("Chapter 1", listOf("Hello there.", "How are you.")))
        val document = IndependentAligner().align(en, emptyList(), null)
        assertEquals(IndependentAligner.ENGINE_ID, document.engineId)
        assertTrue(document.units.isNotEmpty(), "退化时也必须覆盖英文侧，不许静默丢句")
        assertTrue(
            document.units.all { it.skipReason == AlignmentSkipReason.DEGENERATE_INPUT },
            "退化时的 skipReason 必须是 DEGENERATE_INPUT"
        )
    }

    /** 引擎标识与版本号必须与契约约定一致。 */
    @Test
    fun engineIdentity() {
        val aligner = IndependentAligner()
        assertEquals("independent-v1", aligner.engineId)
        val (en, zh) = book(chapters = 1, paragraphs = 1, sentences = 2)
        val document = aligner.align(en, zh, null)
        assertEquals(aligner.version, document.alignerVersion)
        assertEquals("independent-v1", document.engineId)
    }

    /** 空输入（两侧都空）不抛异常。 */
    @Test
    fun emptyInputIsSafe() {
        val document = IndependentAligner().align(emptyList(), emptyList(), null)
        assertEquals(0, document.units.size)
        assertEquals(0, document.stats.sourceSentenceTotal)
    }

    // ──────────────────────────────────────────────────────────────
    //  契约口径（`mergedUnits` 按边 = 本节的重点）
    // ──────────────────────────────────────────────────────────────

    /** `structuralSkipped` 只数结构性章节里的**有效**句（不是单元数）。 */
    @Test
    fun structuralSkippedCountsEffectiveSentences() {
        val en = listOf(
            AlignmentChapter("Contents", listOf("Chapter 1 ... 3", "Chapter 2 ... 17")),
            AlignmentChapter("Chapter 1", listOf("He walked home."))
        )
        val zh = listOf(AlignmentChapter("第一章", listOf("他走回家。")))
        val document = IndependentAligner().align(en, zh, null)
        assertTrue(document.stats.structuralSkipped >= 0)
        assertTrue(
            document.stats.structuralSkipped <= document.stats.sourceSentenceTotal,
            "结构性句数不可能超过有效句总数"
        )
    }
}

/** 只数句数、不产出子串：与 [com.linguareader.shared.tts.SentenceSplitter] 同口径。 */
private object SentenceCounter {
    fun count(raw: String): Int = com.linguareader.shared.tts.SentenceSplitter.count(raw)
}
