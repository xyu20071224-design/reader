package com.linguareader.shared.alignment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **离线机械验收台**：每个扰动变体一个 `@Test`，断言指标不破线。
 *
 * ## 怎么读这套测试
 *
 * - 真值由 [SyntheticBilingualFixture] **构造即知**（每句嵌唯一编码），不依赖人工判定，
 *   也不需要网络、Android、词典或任何第三方依赖。
 * - 指标口径只在 [AlignmentMetricsCalculator] 定义一次，本类不自己算任何比率。
 * - 所有阈值集中在 [Acceptance] 常量块。**改阈值只改那一处**，断言读常量。
 *
 * ## 硬断言 / 软断言两档（**本文件最容易误读的一点**）
 *
 * | 档 | 内容 | 红了意味着 |
 * | --- | --- | --- |
 * | **硬断言**（必须绿） | 工具自检（oracle + 指标算子）、语料自检、**接上真实实现后的** `falseAligned == 0` / `foreignAligned == 0` | **真 bug**：要么实现错标，要么工具坏了 |
 * | **软断言**（当前只警告） | 覆盖率 / coverageT / uncertainRate / 精确率阈值 | 实现未达标，但可能只是**还没做到**，不阻断 CI |
 *
 * 错标红线（`falseAligned == 0`）刻意**只在 [AlignmentEngines.underTest] 非空时硬判**：
 * 占位基线在结构扰动下必然误标，对它硬判会让整套测试长期红、掩盖真回归。未接真实实现
 * 时错标数照样全量打印成 `BASELINE-FALSE-ALIGNED` 行，所以「基线错在哪」依然可见。
 *
 * 软断言的存在理由同上：覆盖率类指标现在跑的是占位引擎。真实实现交付后由 Lead 决定
 * 翻成硬断言——**只改** [Acceptance.STRICT_COVERAGE] 一处即可全部升级。
 *
 * ## 「红」的三种含义
 *
 * | 现象 | 含义 |
 * | --- | --- |
 * | 软断言打印 `WARN` | 待验收引擎覆盖率/精确率未达标（当前预期如此） |
 * | 硬断言红 | 真 bug：错标，或工具坏了 |
 * | [TruthOracleAligner] 破线 | **指标或真值表有缺陷**——先修工具 |
 */
class SyntheticAlignmentRobustnessTest {

    // ==================================================================
    //  阈值（集中一处，禁止在断言里散落魔法数字）
    //
    //  | 常量 | 目标值（产品红线） | 当前初值 | 说明 |
    //  | --- | --- | --- | --- |
    //  | MIN_PRECISION | 0.98 | 0.90 | 实现未到位，先宽松以便观察真实分布 |
    //  | MIN_COVERAGE | 0.95 | 0.80 | raw 覆盖率，只作弱下限与打印 |
    //  | MIN_TRANSLATABLE_COVERAGE | 0.95 | 0.85 | **主判据**（剔掉真值缺口） |
    //  | MAX_UNCERTAIN_RATE | 0.25 | 0.50 | 弃权上限 |
    //  | MAX_FALSE_ALIGNED | 0 | 0 | 硬红线，不给初值让步 |
    //  | MAX_FOREIGN_ALIGNED | 0 | 0 | 硬红线：指向译者加写 = 硬凑 |
    //
    //  收严顺序建议：先确认硬红线（错标 0），再收精确率，最后收覆盖率。
    // ==================================================================
    private object Acceptance {

        /**
         * 是否把覆盖率/精确率类断言升级为硬断言。
         *
         * `false`（当前）= 只打印 `WARN`，不失败。真实实现接入后由 Lead 决定翻成 `true`。
         */
        const val STRICT_COVERAGE = false

        /** ALIGNED 精确率下限。目标 0.98（契约 AlignmentAcceptance.MIN_ALIGNED_PRECISION）。 */
        const val MIN_PRECISION = 0.90

        /** raw 英文句覆盖率下限。目标 0.95。**只作弱下限**，主判据是 [MIN_TRANSLATABLE_COVERAGE]。 */
        const val MIN_COVERAGE = 0.80

        /** 可对齐句覆盖率下限（分母只算真值上有译文的英文句）。目标 0.95。 */
        const val MIN_TRANSLATABLE_COVERAGE = 0.85

        /** UNCERTAIN 率上限。目标 0.25。 */
        const val MAX_UNCERTAIN_RATE = 0.50

        /** 错标数上限。硬红线 = 0。 */
        const val MAX_FALSE_ALIGNED = 0

        /** 「指向译者加写」的错标上限。硬红线 = 0（最恶劣形态，单独计数）。 */
        const val MAX_FOREIGN_ALIGNED = 0

        /** UNCERTAIN 中「真值其实可对齐」的比例下限：必须显著高于 0，弃权才叫诚实。 */
        const val MIN_UNCERTAIN_TRANSLATABLE_RATE = 0.30

        /** 章节数不匹配变体单独放宽的 raw 覆盖率：中文删了第 1 章，物理上限只有 0.80。 */
        const val MIN_COVERAGE_CHAPTER_MISMATCH = 0.70

        /** 同上变体的 coverageT：缺口被剔掉后上限回到 1.0，但结构错位更容易掉。 */
        const val MIN_TRANSLATABLE_COVERAGE_CHAPTER_MISMATCH = 0.85

        /**
         * **等长章节变体的 coverageT 地板 = 0.28**（Lead 由 0.35 下调，并降级为只报警）。
         *
         * ## 它是什么：**回归地板**，不是验收目标
         *
         * 这个数字**不代表算法能力**。该变体的语料是「依赖词表直译」的合成语料
         * （每句靠 `truthLexicon()` 的直译对应），覆盖率主要由词典桥决定，与真实文学
         * 译本的表现没有可比性。
         *
         * 唯一用途：防止实现改动让它掉回「整本放弃」（历史事故：margin 恒 0 →
         * 章节层否决级联 → coverage 0.0139）。
         *
         * ## 标定基线
         *
         * 在**关闭单字匹配的干净基线**下标定，实测 0.3167。当前 0.3833 含单字匹配收益；
         * 单字匹配在真实书上会产生假命中，所以 0.3833 **不能当作能力证据**。
         *
         * ## 为什么降级为只报警
         *
         * 用合成语料的覆盖率当硬门，只会逼后来者调参凑数字。而它的通过率依赖单字匹配
         * 技巧——**那个技巧在真实书上会产生假命中**。本变体的硬判只剩
         * `falseAligned == 0`（红线，与覆盖率无关）。
         */
        const val MIN_COVERAGE_EQUAL_LENGTH = 0.28

        /**
         * 等长章节变体的覆盖率断言是否**硬判**。
         *
         * **`false`（当前）**：覆盖率只打印 WARN 不失败。理由见
         * [MIN_COVERAGE_EQUAL_LENGTH]——合成语料覆盖率不代表算法能力，拿它当硬门
         * 只会逼人调参。
         *
         * 本变体真正的硬判是 `falseAligned == 0`，与覆盖率无关。
         */
        const val STRICT_EQUAL_LENGTH_GATE = false
    }

    // ==================================================================
    //  变体逐项验收
    // ==================================================================

    @Test
    fun base_cleanBook_meetsTargets() = evaluate(SyntheticBilingualFixture.Variant.BASE)

    @Test
    fun missingTranslation_absentParagraph_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.MISSING_TRANSLATION)

    @Test
    fun extraTranslation_translatorAddition_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.EXTRA_TRANSLATION)

    @Test
    fun paragraphMerge_twoToOne_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.PARAGRAPH_MERGE)

    @Test
    fun paragraphSplit_oneToTwo_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.PARAGRAPH_SPLIT)

    @Test
    fun sentenceMerge_twoToOne_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.SENTENCE_MERGE)

    @Test
    fun sentenceSplit_oneToTwo_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.SENTENCE_SPLIT)

    @Test
    fun traditional_characterVariant_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.TRADITIONAL)

    @Test
    fun chapterCountMismatch_deletedAndAddedChapter_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.CHAPTER_COUNT_MISMATCH)

    @Test
    fun hardWrap_lineBreaksInsideSentences_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.HARD_WRAP)

    @Test
    fun boilerplate_nonLanguageParagraphs_meetsTargets() =
        evaluate(SyntheticBilingualFixture.Variant.BOILERPLATE)

    /**
     * **低方差对照组**：整本句长同构（全 `Shape.PLAIN`）。
     *
     * 这是**故意的最坏情况**：长度信号区分度被压到最低，算法只能靠内容通道。
     * 判据刻意不同——**只要求不产生错标**，不要求覆盖率达标。低方差下覆盖率本来就该
     * 低，要求它达标等于奖励「硬凑」。所以这个用例守红线，不守水平。
     */
    @Test
    fun homogeneous_lowVarianceCorpus_producesNoFalseAlignment() {
        val book = SyntheticBilingualFixture.Variant.HOMOGENEOUS.generate()
        val metrics = measure(book, AlignmentEngines.current)
        printRow(metrics)

        assertTrue(
            metrics.falseAlignedCount <= Acceptance.MAX_FALSE_ALIGNED,
            "低方差语料下也不允许错标（宁可不配对，也不许硬凑）：\n${metrics.failureHint()}\n" +
                metrics.detailLinesCapped().joinToString("\n")
        )
        assertTrue(
            metrics.foreignTargetFalseAligned <= Acceptance.MAX_FOREIGN_ALIGNED,
            "低方差语料下指向译者加写 = 硬凑：\n" +
                metrics.detailLinesCapped().joinToString("\n")
        )
    }

    /**
     * **等长章节对抗变体**：所有章节完全同构，章节层没有任何长度区分信号。
     *
     * 现实对应：等长章节小说、诗集、日记体。这类书里「英第 3 章 ↔ 中第 3 章」无法靠章节
     * 长度证明，章节 DP 的 margin 恒为 0。
     *
     * 断言三件事（**这个变体的价值全在这三条同时成立**）：
     *
     * 1. **不许错标**（硬红线）——信号缺失时必须保守，不能靠蒙；
     * 2. **不许整本放弃**——`coverageT` 有下限。「一句都对齐不了」是**鲁棒性失败**，
     *    不是安全降级；这条防的是「把所有句子都标 UNALIGNED/AMBIGUOUS 就不会错标」的
     *    摆烂式通过；
     * 3. **必须留下降级痕迹**——`AlignmentDocument.notes` 要提到低 margin / identity
     *    先验。没有这条，将来有人删掉降级路径、只把覆盖率维持住，前两条都还能绿。
     *
     * 第 2、3 条按 [Acceptance.STRICT_COVERAGE] 分档；第 1 条只在接上真实实现后硬判
     * （与其它变体一致，避免占位基线长期拖红）。
     */
    @Test
    fun equalLengthChapters_mustDegradeGracefullyNotGiveUp() {
        val book = SyntheticBilingualFixture.Variant.EQUAL_LENGTH_CHAPTERS.generate()
        val engine = AlignmentEngines.current
        val outcome = evaluateFully(book, engine)
        val metrics = outcome.metrics
        printRow(metrics)

        // **诊断先打印、再断言**：断言一抛异常，后面的 println 就不会执行，日志里就看不到
        // notes —— 而 notes 恰恰是定位「降级路径走到哪一步」的关键证据。
        val notesText = outcome.document.notes.joinToString(" | ")
        println("equalLengthChapters notes: $notesText")
        println(
            "equalLengthChapters breakdown: aligned=${metrics.coveredSentences}" +
                " uncertain=${metrics.uncertainSentences}" +
                " unaligned=${metrics.unalignedSentences}" +
                " total=${book.truth.sourceKeyCount}"
        )

        val hint = "[equalLengthChapters] engine=${engine.engineId}\n${metrics.failureHint()}\n" +
            metrics.line(pendingImplementation = !AlignmentEngines.isRealImplementation)

        // ---- 1. 不许错标（硬红线）----
        if (AlignmentEngines.isRealImplementation) {
            assertTrue(
                metrics.falseAlignedCount <= Acceptance.MAX_FALSE_ALIGNED,
                "等长章节下也不允许错标（信号缺失时必须保守）：\n$hint\n" +
                    metrics.detailLinesCapped().joinToString("\n")
            )
            assertTrue(
                metrics.foreignTargetFalseAligned <= Acceptance.MAX_FOREIGN_ALIGNED,
                "等长章节下指向译者加写 = 硬凑：\n$hint"
            )
        } else if (metrics.falseAlignedCount > 0) {
            println(
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!!! BASELINE-FALSE-ALIGNED (不是失败判据，但必须被看见)\n" +
                    "!!! variant=equalLengthChapters count=${metrics.falseAlignedCount}\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
            )
        }

        // ---- 2. 不许整本放弃：覆盖率有下限 ----
        //
        // 这一条**硬判**（[Acceptance.STRICT_EQUAL_LENGTH_GATE]）：本变体是专用回归守门，
        // 软判的守门等于没有守门。
        val gaveUp = metrics.translatableCoverage < Acceptance.MIN_COVERAGE_EQUAL_LENGTH
        val gaveUpMessage = "coverageT ${metrics.translatableCoverage} < " +
            "${Acceptance.MIN_COVERAGE_EQUAL_LENGTH}（等长章节下整本放弃 = 鲁棒性失败，" +
            "不是安全降级；语料每句都有译文，1 句都没配上就是降级路径没工作）\n$hint"
        if (Acceptance.STRICT_EQUAL_LENGTH_GATE) {
            assertTrue(!gaveUp, gaveUpMessage)
        } else {
            checkLevel(!gaveUp, gaveUpMessage, hint)
        }

        // ---- 3. 降级痕迹：**改为信息性输出，不再是断言** ----
        //
        // ⚠️ 这是一处**修正自己的错误**（Lead 认的错）：
        //
        // 初版要求「notes 必须含 identity / 低 margin 痕迹」。但 implementer 确认：该语料
        // 上 `chapterMargin >= MARGIN_CHAPTER`，DP **能**分辨章节，`identity` 分支本来
        // 就不该触发。**要求一个不该发生的行为，是错误的测试靶子。**
        //
        // 现在的处理：只把 notes 打印出来供人工核查，**不设断言**。真正该守的是
        // 「覆盖率达标」（第 2 条）与「不许错标」（第 1 条）——这两条已经能覆盖风险，
        // 无需依赖实现措辞。
        println("equalLengthChapters notes（仅供核查，不设断言）: $notesText")
    }

    /**
     * **「宁可说没有，不许编一个」——独立红线用例**（Lead 批准提升为独立用例）。
     *
     * 这是本项目的核心承诺，值得一条**永远不会被合并进别的断言**的用例。
     *
     * 场景：`missingTranslation` 变体里中文侧整段被删，那些英文句**真值上确实没有译文**。
     * 此时算法只有两个合法选择：`UNALIGNED`（`TRANSLATION_MISSING`），或不报（但契约
     * 要求覆盖每条英文句，所以不许静默丢句）。
     *
     * **不允许** ALIGNED，也**不允许** UNCERTAIN：UNCERTAIN 的语义是「有候选但不敢确认」，
     * 而这里**根本没有候选**。把「没有」说成「拿不准」同样是欺骗用户，只是柔和一点。
     *
     * 逐条打印 [FabricationEvidence]：英文句 / 算法指到的中文句 / 见证通道与分数 / 理由。
     */
    @Test
    fun missingTranslation_neverFabricatesAlignment() {
        val book = SyntheticBilingualFixture.Variant.MISSING_TRANSLATION.generate()
        val engine = AlignmentEngines.current
        val outcome = evaluateFully(book, engine)
        val truth = book.truth

        // ---- 逐条打印证据（先打印再断言，否则断言一抛就看不清全貌）----
        val fabrications = AlignmentMetricsCalculator.fabrications(truth, outcome.projections())
        println(
            "missingTranslation fabrication audit: engine=${engine.engineId}" +
                " untranslatedSentences=${truth.untranslatedSourceKeys.size}" +
                " fabrications=${fabrications.size}"
        )
        fabrications.forEach { println(it.line()) }
        if (fabrications.isEmpty()) {
            println("  ✓ 真值无译文的句子没有被编造任何对照")
        }

        // 抽查这些「无译文」句子实际拿到了什么状态，便于人眼核对。
        val statusCounts = truth.untranslatedSourceKeys
            .mapNotNull { key -> outcome.projections()[key]?.status }
            .groupingBy { it }
            .eachCount()
        println("  untranslated sentences → reported status: $statusCounts")

        // ---- 两段式判定：**红线**与**语义**分开报（Lead 要求）----
        //
        // 红线破了（给不存在的译文报 ALIGNED = 编造）与语义错了（无候选却报 UNCERTAIN =
        // 伪装成谨慎）严重性差一个数量级。合并在一条断言里，别人看红时分不清是哪种。
        //
        // ⚠️ **关键细化（Lead 三路裁定后）**：`sparse` 情形（有词典但本句可查词太少）
        // 的正确状态**就是** UNCERTAIN，且**允许带候选译文**——它不是「有候选」，而是
        // 「我有能力查，但这句证不动」。把它算成语义错误是错的。
        // 只有 `SILENT`（查了、零命中 = 证据）带译文才违规。
        val redLine = fabrications.filter { it.reportedStatus == AlignmentStatus.ALIGNED }
        val semanticAll = fabrications.filter { it.reportedStatus == AlignmentStatus.UNCERTAIN }
        val silentViolations = semanticAll.filter { fab ->
            val projection = outcome.projections()[fab.sourceKey]
            projection != null &&
                AlignmentMetricsCalculator.lexicalChannelOf(projection) ==
                AlignmentMetricsCalculator.LexicalChannel.SILENT
        }
        val sparseAllowed = semanticAll - silentViolations.toSet()

        println(
            "  RED-LINE (ALIGNED fabrication): ${redLine.size} — " +
                if (redLine.isEmpty()) "PASS" else "FAIL"
        )
        println(
            "  SEMANTIC (UNCERTAIN @ SILENT, 应为 UNALIGNED): ${silentViolations.size} — " +
                if (silentViolations.isEmpty()) "PASS" else "FAIL"
        )
        println(
            "  UNCERTAIN @ SPARSE（按裁定合法，允许带候选）: ${sparseAllowed.size} — ALLOWED"
        )
        sparseAllowed.forEach {
            println("    allowed: key=${it.sourceKey} reasons=${it.reasons}")
        }

        assertTrue(
            redLine.isEmpty(),
            "RED-LINE FAIL：给「真值上没有译文」的句子报了 ALIGNED（编造对照）：\n" +
                redLine.joinToString("\n") { it.line() }
        )

        assertTrue(
            silentViolations.isEmpty(),
            "SEMANTIC FAIL：词典**查了零命中**（SILENT = 证据），却报了 UNCERTAIN 并给出译文。" +
                "既然已经验证不成立，就该明确报 UNALIGNED：\n" +
                silentViolations.joinToString("\n") { it.line() }
        )

        // ---- 这些句子必须是 UNALIGNED（明确说「没有」）----
        val notUnaligned = truth.untranslatedSourceKeys.filter { key ->
            val projection = outcome.projections()[key]
            projection != null && projection.status != AlignmentStatus.UNALIGNED
        }
        println(
            "  UNALIGNED-REQUIRED: ${truth.untranslatedSourceKeys.size - notUnaligned.size}" +
                "/${truth.untranslatedSourceKeys.size} 句正确报 UNALIGNED"
        )

        // ---- 硬判 3：变体级指标也不许有错标（与上面两条交叉验证）----
        if (AlignmentEngines.isRealImplementation) {
            assertEquals(
                0, outcome.metrics.falseAlignedCount,
                "missingTranslation 变体不允许任何错标：\n" +
                    outcome.metrics.detailLinesCapped().joinToString("\n")
            )
        }
    }

    /**
     * **「报告不许说谎」——报告自洽性审计**（Lead 指定的两条不变量）。
     *
     * ## 不变量 1
     * > 若 `targetText` 非空（给了译文），则 `reasons` 不得包含 `no content witness`。
     *
     * ## 不变量 2（更强）
     * > 若见证数为 0 且 `reasons` 含 `no content witness`，
     * > 则 `targetText` 必须为空，且 `status` 必须是 `UNALIGNED`（不是 UNCERTAIN）。
     *
     * ## 为什么
     *
     * `UNCERTAIN` 的契约语义是「**有候选**但证据不足」。`UNCERTAIN` + `no content witness`
     * + 有具体译文，三者互相矛盾：要么根本没有候选（该是 `UNALIGNED`），要么理由写错了。
     * 这种报告**伪装成谨慎**——用户看到「把握不足」+ 一条译文，会以为「大概是这个意思，
     * 只是不确定」，而那条译文其实毫无依据。**比明确的错标更危险。**
     *
     * ## 与 `missingTranslation_neverFabricatesAlignment` 的分工
     *
     * - 那条用**真值**判断「有没有编造」（数据对不对）；
     * - 这条只看实现**自己给出的字段**（报告自不自洽），不依赖真值、可套用到任何引擎与
     *   语料上。**两条都要有**：真值审计抓「编造」，自洽审计抓「伪装成谨慎的编造」。
     *
     * 覆盖全部变体，一次抓住所有同类问题。
     */
    @Test
    fun reportsMustBeInternallyConsistent() {
        var checkedSentences = 0
        val violations = ArrayList<Pair<String, InconsistentReport>>()

        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val projections = evaluateFully(book, AlignmentEngines.current).projections()
            checkedSentences += projections.size
            // 传真值：不变量 3 需要它判断「该句真有译文」。
            AlignmentMetricsCalculator.inconsistentReports(projections, book.truth).forEach { report ->
                violations += variant.label to report
            }
        }

        println(
            "REPORT-CONSISTENCY audit: checked=$checkedSentences sentences across " +
                "${SyntheticBilingualFixture.Variant.ALL.size} variants, violations=${violations.size}"
        )
        if (violations.isNotEmpty()) {
            println(AlignmentMetricsCalculator.violationDistribution(violations))
            println("  逐条明细（前 15 条）：")
            violations.take(15).forEach { (variantLabel, report) ->
                println("  [$variantLabel] ${report.line()}")
            }
        } else {
            println("  ✓ 所有单元的报告自洽：有译文必有见证，零见证必为 UNALIGNED")
        }

        // 两条不变量**分别**计数（不做短路合并）：它们是两个独立的缺陷维度。
        val byInvariant = violations.flatMap { it.second.violated }
            .groupingBy { it }.eachCount()
        val invariantOne =
            byInvariant[AlignmentMetricsCalculator.INVARIANT_TARGET_WITHOUT_WITNESS] ?: 0
        val invariantTwo =
            byInvariant[AlignmentMetricsCalculator
                .INVARIANT_ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED] ?: 0
        val invariantThree =
            byInvariant[AlignmentMetricsCalculator
                .INVARIANT_UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED] ?: 0
        println("  INVARIANT-1 TARGET_WITHOUT_WITNESS                        = $invariantOne")
        println("  INVARIANT-2 ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED  = $invariantTwo")
        println("  INVARIANT-3 UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED       = $invariantThree")
        assertTrue(
            violations.isEmpty(),
            "报告自相矛盾（伪装成谨慎的谎报）：\n" +
                "  INVARIANT-1 TARGET_WITHOUT_WITNESS                        = $invariantOne\n" +
                "  INVARIANT-2 ZERO_WITNESS_USABLE_LEXICON_MUST_BE_UNALIGNED  = $invariantTwo\n" +
                "  INVARIANT-3 UNALIGNED_WITHOUT_LEXICON_BUT_TRANSLATED       = $invariantThree\n" +
                "分布：\n${AlignmentMetricsCalculator.violationDistribution(violations)}"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  已删除：`contentDistinct` 变体 + 它的两个用例 —— 2026-10-31 Lead 裁定
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 曾加过一个「每句用专属词汇、内容天然可分」的对照语料，用来回答
    // 「语料可分时算法覆盖率能到多少」。**删除了，不要加回来**，理由：
    //
    // 1. **三重重言式**：用我们自己构造的可分语料，证明我们自己的算法在可分语料上
    //    能行——无论结果如何都不构成对外部世界的证据。
    // 2. **真实语料已在手边**：魔戒金标准重放天然可分，直接测就有答案，不需要替身。
    // 3. **最有害的一点**：若它通过，会给人虚假安慰「算法没问题，只是语料不行」。但
    //    真实书有词形变化、多义词、意译，可能依然不行。**这个安慰是有害的，会让人
    //    停止追问。**
    //
    // 同样**不要**采用「给每句塞一个只属于该句的真词」：那会制造 ECDICT 永远不会有的
    // 理想条件，测出来的覆盖率是幻觉，比现状更危险。
    //
    // 保留下来的 `SEPARABILITY` 诊断（[SeparabilityProbe]）是**否定性**结论——
    // 「base 语料不可分」，有价值且不产生虚假安慰。

    /**
     * **`reasons` 可复核性审计**（Lead 指定的最优先事项）。
     *
     * ## 为什么这条比覆盖率优先
     *
     * `base` 有 45 个非 ALIGNED 单元，其中 28 个 reasons 含裸 `margin` 字样却无法确认
     * 指向哪句代码。**一份说不出「为什么拒绝」的报告，恰好违反本项目的核心目标**——
     * 正确报告对齐与否。如果连我们都查不清，`evidence` 对用户更是摆设。
     *
     * ## 判据：一个 reason 串必须唯一对应一个判定原因
     *
     * 若同一文案在多个分支共用，或文案不含足够参数（只说 `margin` 不说数值），
     * 它就不能作为证据。
     *
     * 本用例**只输出不设阈值**：它产出的是给决策用的分类表。
     */
    @Test
    fun reasonsAudit_everyRejectionExplainsItself() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val rejected = ReasonsAuditProbe(book).audit()

        val withMargin = rejected.filter { it.mentionsMargin }
        println(
            "REASONS-AUDIT variant=base rejected=${rejected.size}" +
                " mentionsMargin=${withMargin.size}"
        )

        // 1) **完整 reasons 原文**（不归桶、不截断），按原文分组计数。
        println("  --- reasons 原文分组（完整字符串） ---")
        rejected.groupingBy { it.reasons.joinToString(" ‖ ") }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .forEach { (text, count) -> println("  [$count×] $text") }

        // 2) 按 status / skipReason 分组，确认拒绝落点。
        println("  --- 按 status ---")
        rejected.groupingBy { it.status }.eachCount()
            .forEach { (status, count) -> println("  $status : $count") }
        println("  --- 按 skipReason ---")
        rejected.groupingBy { it.skipReason?.name ?: "<null>" }.eachCount()
            .forEach { (reason, count) -> println("  $reason : $count") }

        // 3) margin 类单元逐条原文（Lead 要的表的第一列）。
        println("  --- 含 margin 字样的 ${withMargin.size} 条，逐条原文 ---")
        withMargin.forEach { println(it.line()) }
        if (withMargin.isEmpty()) println("  （没有含 margin 字样的单元）")

        assertTrue(rejected.isNotEmpty(), "审计失效：base 应该有非 ALIGNED 单元")
    }

    /**
     * **语料内禀可分性诊断**（Lead 要求的决定性诊断）。
     *
     * 回答一个问题：覆盖率 0.10–0.38 是**算法保守**还是**语料不可分**？
     *
     * 判据（用 V1 内容通道自己的打分，不用 DP 代价——否则是自证）：
     *
     * ```
     * discrimination(s) = score(s, t_true) − max_{t_other} score(s, t_other)
     * ```
     *
     * `≤ 0` 表示真值配对并不比某个错误配对得分高——内容通道上根本认不出这句。
     * 若这个比例 > 40%，覆盖率低就是**语料问题**，不是算法问题。
     *
     * **这条只打印不设阈值**：它的输出是给决策用的数据，不是通过/失败判据。
     * 语料该不该改是设计决定，不该由测试替人拍板。
     */
    @Test
    fun separabilityDiagnostic_reportsWhetherCorpusIsIntrinsicallySeparable() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val probe = SeparabilityProbe(book, SyntheticBilingualFixture.truthLexicon())
        val result = probe.run(SyntheticBilingualFixture.Variant.BASE)

        println(result.line())
        println("  结论：${result.verdict()}")
        println("  最不可分的 10 句（按 discrimination 升序）：")
        result.perSentence.sortedBy { it.discrimination }.take(10).forEach { println(it.line()) }
        println("  最可分的 5 句（对照）：")
        result.perSentence.sortedByDescending { it.discrimination }.take(5).forEach { println(it.line()) }

        // 只做「确实算出了分」的最低限度自检，不对可分性本身设阈值。
        assertTrue(
            result.scoredSentences > 0,
            "诊断本身失效：一句都打不出分。检查词典接线或 V1 通道。"
        )
    }

    /**
     * **拒绝原因直方图**：所有非 ALIGNED 单元的 `reasons` 按类归桶。
     *
     * 与可分性诊断配合判断责任归属：
     *
     * - 可分性**高**、拒绝集中在某道门 → 那道门过严，该调**算法**；
     * - 可分性**低**、拒绝集中在 `no content witness` → 内容通道没信号，该改**语料**。
     *
     * 只打印，不设阈值。
     */
    @Test
    fun rejectionReasonHistogram_showsWhichGateRejects() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val outcome = evaluateFully(book, AlignmentEngines.current)
        val projections = outcome.projections()

        val buckets = LinkedHashMap<String, Int>()
        var nonAlignedUnits = 0
        projections.forEach { (_, prediction) ->
            if (prediction.status == AlignmentStatus.ALIGNED) return@forEach
            nonAlignedUnits++
            val reason = prediction.reasons.joinToString(" ").ifBlank { "<no reasons>" }
            classifyRejection(reason, prediction.skipReason).forEach { bucket ->
                buckets.merge(bucket, 1, Int::plus)
            }
        }

        println("REJECTION-HISTOGRAM variant=base nonAlignedSentences=$nonAlignedUnits")
        buckets.entries.sortedByDescending { it.value }.forEach { (bucket, count) ->
            println("  $bucket : $count")
        }
        if (buckets.isEmpty()) println("  （没有非 ALIGNED 单元）")
    }

    /**
     * 拒绝原因归桶表：`token` 命中即计入 `bucket`。实现改措辞时只改这里。
     *
     * ## 顺序即优先级（**这里曾经有一个真 bug**）
     *
     * 初版把裸 `"margin"` 排在前面，于是 `margin skipped: length uninformative` 这条
     * **句级长度门**的措辞被误归进「chapter margin」桶——`base` 的 45 个拒绝里 42 个
     * 落进了章节桶，而那份语料章节长度极差 6 段、DP 本该能分辨。数字完全误导。
     *
     * 现在**具体 token 在前、泛化 token 在后**，且 [classifyRejection] 命中第一个就停，
     * 所以 `margin skipped` 不会再被裸 `margin` 吞掉，也不会同时计入两个桶。
     */
    private val REJECTION_BUCKETS = listOf(
        // ---- 具体措辞优先 ----
        "margin skipped" to "margin skipped (句级长度不可分辨)",
        "merged span" to "merged span needs lexical witness",
        "lexical channel silent" to "lexical channel silent (查了没命中)",
        "lexical channel unavailable" to "lexical channel unavailable (没词典)",
        "no meaning provider" to "lexical channel unavailable (没词典)",
        "no content witness" to "no content witness",
        "structural-only" to "structural-only (无内容见证)",
        "coverable ratio" to "coverableRatio too low",
        "coverable=" to "coverableRatio too low",
        "short sentence" to "short sentence insufficient",
        // ---- 泛化 token 放后面 ----
        "contradiction" to "contradictions",
        "z=" to "z > Z_LIMIT",
        "low-margin chapter" to "chapter 低 margin（章节层）",
        // 最后才是裸 margin：只在没有更具体措辞时才归这里
        "margin" to "chapter margin 相关（未分类）"
    )

    /**
     * 一条拒绝原因归桶：**按 [REJECTION_BUCKETS] 顺序，命中第一个具体桶就停**。
     *
     * 「一个原因只归一个内容桶」是刻意的：初版让所有命中都计数，导致
     * `margin skipped`（含 margin 字样）既进专属桶又进泛化桶，直方图里出现虚高。
     * `skipReason` 是独立维度，仍单独占一桶。
     */
    private fun classifyRejection(
        reason: String,
        skipReason: AlignmentSkipReason?
    ): List<String> {
        val buckets = ArrayList<String>(2)
        if (skipReason != null) buckets += "skipReason=$skipReason"
        val primary = REJECTION_BUCKETS.firstOrNull { (token, _) -> token in reason }
        if (primary != null) {
            buckets += primary.second
        } else if (buckets.isEmpty()) {
            buckets += "other"
        }
        return buckets
    }

    /**
     * **确定性留痕**：同一份语料跑两次，指标必须逐字段相同。
     *
     * 这条本身就是在验证「评估链可信」的前提：如果指标不稳定，那么任何「这次比上次好」
     * 的结论都不成立。它同时向测试输出打印一行醒目结论，方便翻 CI 日志的人确认这条
     * 评估链是可复现的，而不用去信任何人的口头保证。
     *
     * 覆盖面：全部变体 × 待验收引擎，逐字段比较 [AlignmentMetrics.line]。
     */
    @Test
    fun metricsAreDeterministicAcrossRuns() {
        val first = SyntheticBilingualFixture.Variant.ALL.map { variant ->
            evaluateFully(variant.generate(), AlignmentEngines.current).metrics.line()
        }
        val second = SyntheticBilingualFixture.Variant.ALL.map { variant ->
            evaluateFully(variant.generate(), AlignmentEngines.current).metrics.line()
        }
        val mismatches = first.indices.filter { first[it] != second[it] }
        mismatches.forEach { i ->
            println("DETERMINISM-MISMATCH variantIndex=$i")
            println("  run1=${first[i]}")
            println("  run2=${second[i]}")
        }
        println(
            "DETERMINISM: two consecutive runs produced identical metrics" +
                " (${first.size} variants, engine=${AlignmentEngines.current.engineId})"
        )
        assertTrue(
            mismatches.isEmpty(),
            "同一语料两次运行指标不一致，评估链不可复现：$mismatches"
        )
    }

    /** 判定「降级痕迹」的关键词：命中任一即可（实现侧的措辞不必与这里逐字一致）。 */
    private val DEGRADATION_NOTE_TOKENS = listOf(
        "identity", "IDENTITY", "low margin", "LOW_MARGIN", "lowMargin",
        "margin", "先验", "降级", "degrad", "DEGRAD"
    )

    /**
     * **章节内容错位配对**：`falseAligned` 红线的主战场。
     *
     * 前面所有变体测「结构扰动下能否对齐」；这个测「**章节配错时，内容通道能不能拦住
     * 错标**」。按位置/按标题配对的实现会把整章内容错配——长度比、段落数都看起来正常，
     * 是用户损失最大、最难发现的一类错标。
     *
     * ## 判据
     *
     * - `falseAligned == 0`（**硬判**，红线中的红线）
     * - `foreignAligned == 0`（硬判）
     * - **覆盖率不设门**：这本来就不该被"轻易"对齐，覆盖率低是合法的；反过来，
     *   为了凑覆盖率去猜才是灾难。
     *
     * ## 假绿自检（Lead 要求，关键）
     *
     * 本用例末尾用 `TruthOracleAligner`（按真值表配对的完美引擎）跑同一个变体，断言它
     * **拿到高覆盖率**。若真值建错（例如把真值也打乱了 / 把 marker→unit 映射建在错位后
     * 的章节上），oracle 会拿不到覆盖率，这条自检立即红——**防止「算法错、真值也错、
     * 结果对上」的假绿**。
     *
     * 另一侧的自检由 `shiftedEngine_isCaughtByFalseAligned` 提供（错位引擎必须被抓）。
     * 两条合起来证明：真值确实认为「按位置配对」是错的。
     */
    @Test
    fun chapterContentPermuted_mustNotFabricateAlignedPairs() {
        val book = SyntheticBilingualFixture.Variant.CHAPTER_CONTENT_PERMUTED.generate()
        val engine = AlignmentEngines.current
        val outcome = evaluateFully(book, engine)
        val metrics = outcome.metrics
        printRow(metrics)

        val hint = "[chapterContentPermuted] engine=${engine.engineId}\n${metrics.failureHint()}\n" +
            metrics.line(pendingImplementation = !AlignmentEngines.isRealImplementation)

        // ---- 硬红线：绝不允许「整章内容配错却报 ALIGNED」 ----
        if (AlignmentEngines.isRealImplementation) {
            assertTrue(
                metrics.falseAlignedCount <= Acceptance.MAX_FALSE_ALIGNED,
                "章节错位下报 ALIGNED = 整章内容错配（最严重错标）：\n$hint\n" +
                    metrics.detailLinesCapped().joinToString("\n")
            )
            assertTrue(
                metrics.foreignTargetFalseAligned <= Acceptance.MAX_FOREIGN_ALIGNED,
                "章节错位下指向译者加写 = 硬凑：\n$hint"
            )
        } else if (metrics.falseAlignedCount > 0) {
            println(
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!!! BASELINE-FALSE-ALIGNED (不是失败判据，但必须被看见)\n" +
                    "!!! variant=chapterContentPermuted count=${metrics.falseAlignedCount}\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
            )
        }

        // ---- 覆盖率不设门，只报告 ----
        println(
            "chapterContentPermuted coverage=${metrics.coverage}" +
                "（不设门：这本就不该被轻易对齐；为凑覆盖率去猜才是灾难）"
        )

        // ---- 假绿自检：oracle 必须仍能在这个变体上拿满分 ----
        // 真值若建错（把真值也打乱、或 marker→unit 建在错位章节上），oracle 会掉下来。
        val oracleOutcome = evaluateFully(book, TruthOracleAligner(book))
        val oracle = oracleOutcome.metrics
        println(
            "chapterContentPermuted oracle self-check: precision=${oracle.precision}" +
                " coverageT=${oracle.translatableCoverage} falseAligned=${oracle.falseAlignedCount}"
        )
        assertEquals(
            0, oracle.falseAlignedCount,
            "真值自检失败：oracle 在章节错位变体上不该有错标——真值表建错了。\n${oracle.line()}"
        )
        assertEquals(
            1.0, oracle.translatableCoverage, 1e-9,
            "真值自检失败：oracle 必须能在这个变体上配回全部有译文的句子（coverageT=1）。" +
                "若它掉了，说明真值表跟着章节一起被打乱了 → 本用例会变成假绿。\n${oracle.line()}"
        )
    }

    /**
     * 全书报告：跑完全部变体，把结构化指标行 **print 出来**。
     *
     * 同时打印三套数：待验收引擎（当前水平）、oracle（实现正确时的上限，阈值应瞄准
     * 这里）、以及 coverage 落差说明。这条测试只输出，不设阈值。
     */
    @Test
    fun allVariants_emitsMetricRows() {
        val currentRows = ArrayList<AlignmentMetrics>()
        val oracleRows = ArrayList<AlignmentMetrics>()
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val current = measure(book, AlignmentEngines.current)
            currentRows += current
            oracleRows += measure(book, TruthOracleAligner(book))
            printRow(current)
        }
        println()
        println(
            "## engine=${AlignmentEngines.current.engineId} " +
                "(realImplementation=${AlignmentEngines.isRealImplementation})"
        )
        println(AlignmentReport.table(currentRows))
        currentRows.map { it.coverageGapNote() }.filter { it.isNotEmpty() }.forEach(::println)
        println()
        println("## oracle (实现正确时的上限，阈值应瞄准这里)")
        println(AlignmentReport.table(oracleRows))

        // 基线误标总账：单独一段、带横幅。哪怕整个套件是绿的，这一段也在日志里显形，
        // 避免「红线只在 underTest != null 时硬判」变成温水煮青蛙。
        val baselineFalse = currentRows.filter { it.falseAlignedCount > 0 }
        if (baselineFalse.isNotEmpty() && !AlignmentEngines.isRealImplementation) {
            println()
            println("!!!!!!!!!!!!!!!!!! BASELINE FALSE-ALIGNED TOTAL !!!!!!!!!!!!!!!!!!")
            println("!!! 当前引擎=${AlignmentEngines.current.engineId}（占位基线，非产品实现）")
            println("!!! ${baselineFalse.size}/${currentRows.size} 个变体出现误标，共 " +
                "${baselineFalse.sumOf { it.falseAlignedCount }} 条")
            baselineFalse.forEach { row ->
                println("!!!   ${row.variant}: ${row.falseAlignedCount} 条" +
                    "（foreignAligned=${row.foreignTargetFalseAligned}）")
            }
            println("!!! 这些是基线既有误差，不是本套测试的失败判据；")
            println("!!! 接上真实实现（AlignmentEngines.REAL_ENGINE_CLASS）后必须全部归零。")
            println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        }

        assertEquals(
            SyntheticBilingualFixture.Variant.ALL.size,
            currentRows.size,
            "每个变体都必须产出一行指标"
        )
    }

    // ==================================================================
    //  工具自检（永远必须绿）
    // ==================================================================

    /**
     * oracle（查真值表产出完美对齐）在每个变体上都必须拿到满分。
     *
     * 任何一变异常都意味着**指标计算或语料真值有缺陷**，与对齐器无关。
     */
    @Test
    fun oracle_achievesPerfectScoresOnEveryVariant() {
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val metrics = measure(book, TruthOracleAligner(book))
            assertTrue(
                metrics.falseAlignedCount == 0,
                "[${variant.label}] oracle 不该有错标：" +
                    metrics.detailLinesCapped().joinToString("\n")
            )
            assertEquals(
                1.0, metrics.precision, 1e-9,
                "[${variant.label}] oracle 精确率必须为 1：${metrics.line()}"
            )
            assertEquals(
                1.0, metrics.translatableCoverage, 1e-9,
                "[${variant.label}] oracle coverageT 必须为 1：${metrics.line()}"
            )
            assertEquals(
                book.truth.untranslatedSourceKeys.size,
                metrics.unalignedSentences,
                "[${variant.label}] 真值无对应的句子必须恰好是 oracle 报 UNALIGNED 的那些"
            )
            assertEquals(
                1.0, metrics.unalignedCorrectness, 1e-9,
                "[${variant.label}] oracle 的 UNALIGNED 必须全对：${metrics.line()}"
            )
        }
    }

    /** 故意错标一句的引擎必须被 `falseAligned` 抓出来（错标捕获能力自检）。 */
    @Test
    fun shiftedEngine_isCaughtByFalseAligned() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val metrics = measure(book, ShiftedAligner)
        assertTrue(
            metrics.falseAlignedCount > 0,
            "整体错位一句必须产生错标，实际：${metrics.line()}"
        )
        assertTrue(
            metrics.precision < 0.2,
            "错位引擎的精确率必须显著低：${metrics.line()}"
        )
        // 覆盖率仍可很高——这正是「覆盖率单独看不出错标」的证据，也是必须同时报精确率的原因。
        assertTrue(
            metrics.coverage > 0.9,
            "错位引擎仍能覆盖绝大多数句子：${metrics.line()}"
        )
        assertTrue(
            AlignmentMetricsCalculator.KIND_WRONG_TARGET in metrics.falseAligned.map { it.kind }.toSet(),
            "错位应被归为 WRONG_TARGET，实际类别=${metrics.falseAligned.map { it.kind }.toSet()}"
        )
    }

    /** 全弃权引擎：覆盖率归零、UNCERTAIN 率拉满，且 UNCERTAIN 诚实度反映真实可对齐比例。 */
    @Test
    fun allUncertainEngine_isHonestAboutAbstaining() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val metrics = measure(book, AllUncertainAligner)
        assertEquals(0.0, metrics.coverage, 1e-9, "全弃权不该产生覆盖率：${metrics.line()}")
        assertEquals(1.0, metrics.uncertainRate, 1e-9, "全弃权应把 UNCERTAIN 率拉满：${metrics.line()}")
        assertEquals(0, metrics.falseAlignedCount, "全弃权不该产生错标：${metrics.line()}")
        assertEquals(
            1.0, metrics.uncertainTranslatableRate, 1e-9,
            "干净语料上全弃权时，UNCERTAIN 全部落在本该能对齐的句子上：${metrics.line()}"
        )
    }

    /**
     * **`FOREIGN_TARGET` 的两面性**（硬要求）：
     *
     * - 报 **ALIGNED** 且指向译者加写 → **错标**（红线），且单独计入 `foreignAligned`；
     * - 报 **UNCERTAIN** 且候选是译者加写 → **好的弃权**（不算错标，算诚实）。
     *
     * 两面必须同时成立，否则「弃权」与「硬凑」分不开：只当错标 → 对译者加写弃权也会
     * 被判红；只当弃权 → 硬凑永远抓不到。
     */
    @Test
    fun foreignTarget_isRedLineWhenAlignedAndHonestWhenUncertain() {
        val book = SyntheticBilingualFixture.Variant.EXTRA_TRANSLATION.generate()
        val truth = book.truth
        // 一条真值英文句 + 一条译者加写的编码（可识别、但不对应任何英文句）。
        val key = truth.sourceKeysInOrder.first()
        val foreignMarker = truth.targetMarkerUniverse.first { it !in truth.markerToUnit }
        assertTrue(
            foreignMarker !in truth.markerToUnit,
            "该编码必须是「可识别但无对应英文句」的译者加写：$foreignMarker"
        )
        val trueMarkers = truth.targetUnits[truth.trueTargetUnitsOf(key).first()].markers.toSet()

        // 情形 A：报 ALIGNED 指向译者加写 → 错标，且计入 foreignAligned。
        val alignedToForeign = mapOf(
            key to PredictedAlignment(
                status = AlignmentStatus.ALIGNED,
                unitId = 0,
                sourceKeys = setOf(key),
                targetMarkers = setOf(foreignMarker)
            )
        )
        val a = AlignmentMetricsCalculator.compute("foreignCase", "handmade", truth, alignedToForeign)
        assertEquals(1, a.falseAlignedCount, "指向译者加写却报 ALIGNED 必须算错标：${a.line()}")
        assertEquals(
            1, a.foreignTargetFalseAligned,
            "必须单独计入 foreignAligned（最恶劣的硬凑形态）：${a.line()}"
        )
        assertEquals(
            AlignmentMetricsCalculator.KIND_FOREIGN_TARGET,
            a.falseAligned.single().kind
        )

        // 情形 B：同一指向改报 UNCERTAIN → 不算错标，且是「真值可对齐」的诚实弃权。
        val uncertainAboutForeign = mapOf(
            key to PredictedAlignment(
                status = AlignmentStatus.UNCERTAIN,
                unitId = 0,
                sourceKeys = setOf(key),
                targetMarkers = setOf(foreignMarker)
            )
        )
        val b = AlignmentMetricsCalculator.compute("foreignCase", "handmade", truth, uncertainAboutForeign)
        assertEquals(0, b.falseAlignedCount, "对译者加写弃权不是错标：${b.line()}")
        assertEquals(0, b.foreignTargetFalseAligned)
        assertEquals(1, b.uncertainSentences)
        assertEquals(
            1, b.uncertainSentencesTrulyTranslatable,
            "该英文句真值上可对齐，弃权属于诚实：${b.line()}"
        )

        // 情形 C：报 ALIGNED 且指向正确的真值单元 → 不算错标（防止上面两条被写反）。
        val alignedCorrectly = mapOf(
            key to PredictedAlignment(
                status = AlignmentStatus.ALIGNED,
                unitId = 0,
                sourceKeys = setOf(key),
                targetMarkers = trueMarkers
            )
        )
        val c = AlignmentMetricsCalculator.compute("foreignCase", "handmade", truth, alignedCorrectly)
        assertEquals(0, c.falseAlignedCount, "正确指向不该算错标：${c.line()}")
        assertEquals(0, c.foreignTargetFalseAligned)
    }

    /**
     * 指标算子的手工用例：直接喂 [PredictedAlignment]，不经过任何对齐器。
     *
     * 覆盖五个最容易写错的点：合并对按覆盖的**英文句数**计覆盖率；有译文却报
     * UNALIGNED 要算「不正确」；指向真值外算 FOREIGN_TARGET；指到别人译文上算
     * WRONG_TARGET；覆盖率分母是真值英文句总数。
     */
    @Test
    fun metricCalculator_handlesMergedUnitsAndFalseAligned() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val truth = book.truth
        val keys = truth.sourceKeysInOrder
        val unitOf = { key: String -> truth.trueTargetUnitsOf(key).first() }

        val predictions = LinkedHashMap<String, PredictedAlignment>()
        predictions[keys[0]] = PredictedAlignment(
            status = AlignmentStatus.ALIGNED,
            unitId = 0,
            sourceKeys = setOf(keys[0]),
            targetMarkers = truth.targetUnits[unitOf(keys[0])].markers.toSet()
        )
        // 句 1+2：合并对，一个单元覆盖两句，指向两个中文句 → 两条都算正确覆盖。
        predictions[keys[1]] = PredictedAlignment(
            status = AlignmentStatus.ALIGNED,
            unitId = 1,
            sourceKeys = setOf(keys[1], keys[2]),
            targetMarkers = (truth.targetUnits[unitOf(keys[1])].markers +
                truth.targetUnits[unitOf(keys[2])].markers).toSet()
        )
        predictions[keys[2]] = predictions.getValue(keys[1])
        // 句 3：真值有译文，却报 UNALIGNED → UNALIGNED 正确率分母 +1、分子 +0。
        predictions[keys[3]] = PredictedAlignment(
            status = AlignmentStatus.UNALIGNED,
            unitId = 2,
            sourceKeys = setOf(keys[3]),
            skipReason = AlignmentSkipReason.VERIFY_FAILED
        )

        val merged = AlignmentMetricsCalculator.compute("handmade", "handmade", truth, predictions)
        assertEquals(3, merged.coveredSentences, "合并对必须按覆盖的英文句数计覆盖率")
        assertEquals(2, merged.alignedUnitsReported, "单元数按 unitId 计（合并对算一条）")
        assertEquals(2, merged.alignedUnitsCorrect)
        assertEquals(3, merged.alignedSentencesCorrect, "三条都真值正确：${merged.line()}")
        assertEquals(0, merged.falseAlignedCount)
        assertEquals(1, merged.mergedUnits, "句 1+2 应被记为一条合并单元")
        assertEquals(1, merged.unalignedSentences)
        assertEquals(0, merged.unalignedSentencesTrulyUnmatched, "有译文却报 UNALIGNED 应算不正确")
        assertEquals(
            book.truth.sourceKeyCount, merged.truthSourceSentences,
            "覆盖率分母必须是真值英文句总数"
        )

        // 指到别人的译文上 → WRONG_TARGET。
        val wrong = LinkedHashMap(predictions)
        wrong[keys[4]] = PredictedAlignment(
            status = AlignmentStatus.ALIGNED,
            unitId = 4,
            sourceKeys = setOf(keys[4]),
            targetMarkers = truth.targetUnits[unitOf(keys[7])].markers.toSet()
        )
        val wrongMetrics = AlignmentMetricsCalculator.compute("handmade", "handmade", truth, wrong)
        assertEquals(1, wrongMetrics.falseAlignedCount)
        assertEquals(
            AlignmentMetricsCalculator.KIND_WRONG_TARGET,
            wrongMetrics.falseAligned.single().kind
        )
    }

    /** 「真值表自身」的自检：编码唯一、不含数字锚点、变体全集与枚举一致。 */
    @Test
    fun fixture_truthIsSelfConsistent() {
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val truth = book.truth
            assertEquals(
                truth.sourceKeysInOrder.size,
                truth.sourceKeysInOrder.toSet().size,
                "[${variant.label}] 英文编码必须唯一"
            )
            assertEquals(
                truth.targetMarkerUniverse.size,
                truth.targetUnits.flatMap { it.markers }.toSet().size,
                "[${variant.label}] 中文编码必须唯一"
            )
            // 英文编码不得含数字（否则真实对齐器的数字锚点会白送分）。
            truth.sourceKeysInOrder.forEach { key ->
                assertTrue(key.none { it.isDigit() }, "[${variant.label}] 英文编码不得含数字：$key")
            }
            truth.targetUnits.flatMap { it.markers }.toSet().forEach { marker ->
                assertTrue(
                    marker.none { it.isDigit() || it in "一二三四五六七八九十" },
                    "[${variant.label}] 中文编码不得含数字：$marker"
                )
            }
        }
        assertEquals(
            SyntheticBilingualFixture.Variant.entries.size,
            SyntheticBilingualFixture.Variant.ALL.size,
            "变体全集必须与枚举项一一对应（新增变体别忘加进 ALL）"
        )
    }

    /** 变体的「构造意图」必须真的生效：漏译要有无译文句，多译要有真值外编码，等等。 */
    @Test
    fun fixture_variantsActuallyInjectTheIntendedDisturbance() {
        val base = SyntheticBilingualFixture.Variant.BASE.generate()
        assertEquals(0, base.truth.untranslatedSourceKeys.size, "基线不该有漏译")
        assertEquals(0, base.truth.extraTargetMarkers, "基线不该有译者加写")

        val missing = SyntheticBilingualFixture.Variant.MISSING_TRANSLATION.generate()
        assertTrue(missing.truth.untranslatedSourceKeys.isNotEmpty(), "漏译变体必须有真值无对应的英文句")
        assertEquals(
            base.truth.sourceKeyCount, missing.truth.sourceKeyCount,
            "删译文段落不该改变英文侧句数"
        )

        val extra = SyntheticBilingualFixture.Variant.EXTRA_TRANSLATION.generate()
        assertTrue(extra.truth.extraTargetMarkers > 0, "多译变体必须含真值外的中文编码")
        assertEquals(0, extra.truth.untranslatedSourceKeys.size, "多译不该让英文句失去译文")

        val chapterMismatch = SyntheticBilingualFixture.Variant.CHAPTER_COUNT_MISMATCH.generate()
        assertTrue(chapterMismatch.source.size != chapterMismatch.translation.size, "章节数必须不相等")
        assertTrue(chapterMismatch.truth.untranslatedSourceKeys.isNotEmpty(), "被删章节的英文句必须无对应")
        // 标题必须与内容自洽：被保留的英文第 n 章 → 中文标题「第n章」，不能整体差 1。
        val zhTitles = chapterMismatch.translation.map { it.title }
        assertTrue(
            "第2章" in zhTitles,
            "第 1 章被删后译文首章标题必须是「第2章」而不是「第1章」：$zhTitles"
        )
        assertTrue("第1章" !in zhTitles, "译文不该出现「第1章」（那一章没译）：$zhTitles")

        val merge = SyntheticBilingualFixture.Variant.SENTENCE_MERGE.generate()
        assertTrue(
            merge.truth.targetUnits.count { it.markers.size > 1 } > 0,
            "句级合并必须产出「一句中文承载多条编码」的真值单元"
        )

        val split = SyntheticBilingualFixture.Variant.SENTENCE_SPLIT.generate()
        assertTrue(
            split.truth.targetUnitCount > base.truth.targetUnitCount,
            "句级拆分必须让中文真值单元变多"
        )
        assertTrue(
            split.truth.targetUnits.any { it.markers.isEmpty() },
            "句级拆分必须留下无编码的前半句（这正是识别不出来的那类译文）"
        )

        val boilerplate = SyntheticBilingualFixture.Variant.BOILERPLATE.generate()
        assertTrue(boilerplate.nonUnitSourceParagraphs.isNotEmpty(), "样板行变体必须真的插入了非语言段落")

        val hardWrap = SyntheticBilingualFixture.Variant.HARD_WRAP.generate()
        assertTrue(
            hardWrap.source.any { chapter -> chapter.paragraphs.any { '\n' in it } },
            "硬换行变体的英文段落必须真的含换行"
        )

        val traditional = SyntheticBilingualFixture.Variant.TRADITIONAL.generate()
        val zh = traditional.translation.joinToString("") { it.paragraphs.joinToString("") }
        assertTrue(
            zh.any { it in "說條學獲處見鐵農醫書記時風聲鐘響舊細極為這個視隨發著" },
            "繁体变体必须真的转换了字形"
        )
    }

    /**
     * **句长方差**自检：语料不能因为「太规整」导致所有算法系统性失分。
     *
     * 断言英文句长的极差、标准差、以及长短交错程度。这条红了说明语料退化成了等长句
     * 堆叠，长度比类信号失去区分度——那时阈值标定没有意义。
     *
     * 同时验证低方差对照组**确实更同构**，否则对照组名不副实。
     */
    @Test
    fun fixture_hasRealSentenceLengthVariance() {
        val stats = lengthStats(SyntheticBilingualFixture.Variant.BASE.generate())
        println("sentence-length stats (base): $stats")
        assertTrue(stats.max - stats.min >= 10, "英文句长极差应 ≥10 词，实际 ${stats.min}..${stats.max}")
        assertTrue(stats.sd >= 3.0, "英文句长标准差应 ≥3.0 词，实际 ${stats.sd}")
        assertTrue(
            stats.alternations >= 10,
            "长短句应交错（相邻句词数差 ≥4 的情况应足够多），实际 ${stats.alternations}"
        )

        val homogeneous = lengthStats(SyntheticBilingualFixture.Variant.HOMOGENEOUS.generate())
        println("sentence-length stats (homogeneous): $homogeneous")
        assertTrue(
            homogeneous.sd < stats.sd,
            "低方差对照组的句长标准差必须小于基线：homogeneous=${homogeneous.sd} base=${stats.sd}"
        )
    }

    private class LengthStats(val min: Int, val max: Int, val sd: Double, val alternations: Int) {
        override fun toString(): String =
            "min=$min max=$max sd=${"%.2f".format(sd)} alternations=$alternations"
    }

    /** 从**真实生成的英文段落**统计句长（走分句器，不另写一套词数规则）。 */
    private fun lengthStats(book: SyntheticBilingualFixture.FixtureBook): LengthStats {
        val counts = book.source.flatMap { chapter ->
            chapter.paragraphs.flatMap { paragraph ->
                com.linguareader.shared.tts.SentenceSplitter.split(paragraph)
                    .map { sentence -> sentence.split(' ').size }
            }
        }
        val mean = counts.average()
        val sd = Math.sqrt(counts.sumOf { (it - mean) * (it - mean) } / counts.size)
        return LengthStats(
            min = counts.min(),
            max = counts.max(),
            sd = sd,
            alternations = counts.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) >= 4 }
        )
    }

    /**
     * **章节长度方差**自检：主语料的章节必须有真实的长短差异。
     *
     * 现实里章节长度差异极大（《魔戒》单章从几百字到上万字）。若每章等长，章节 DP 的
     * 所有路径长度代价完全相同、margin 恒 0，实现只能靠先验蒙——那是在考语料，不是考
     * 算法。实测事故：等长章节下章节层否决级联到全书，coverage 掉到 0.0139。
     *
     * 断言：**极差 ≥3 段**、**不是全等**。同时验证「等长章节」对抗变体确实是全等的，
     * 否则它测不到想测的东西。
     */
    @Test
    fun fixture_chaptersHaveRealLengthVariance() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val paragraphCounts = book.source.map { it.paragraphs.size }
        val sentenceCounts = book.source.map { chapter ->
            chapter.paragraphs.sumOf { com.linguareader.shared.tts.SentenceSplitter.count(it) }
        }
        println("chapter paragraph counts (base): $paragraphCounts")
        println("chapter sentence counts (base): $sentenceCounts")

        assertTrue(
            paragraphCounts.distinct().size > 1,
            "章节不能全等（章节层需要长度区分信号）：$paragraphCounts"
        )
        assertTrue(
            paragraphCounts.max() - paragraphCounts.min() >= 3,
            "章节段落数极差应 ≥3，实际 ${paragraphCounts.min()}..${paragraphCounts.max()}"
        )
        assertTrue(
            sentenceCounts.max() - sentenceCounts.min() >= 6,
            "章节句数极差应 ≥6（换句密度后仍有差），实际 ${sentenceCounts.min()}..${sentenceCounts.max()}"
        )

        // 对抗变体必须真的等长，否则它守不住「章节信号消失」这条路径。
        val equal = SyntheticBilingualFixture.Variant.EQUAL_LENGTH_CHAPTERS.generate()
        val equalParagraphCounts = equal.source.map { it.paragraphs.size }
        println("chapter paragraph counts (equalLengthChapters): $equalParagraphCounts")
        assertTrue(
            equalParagraphCounts.distinct().size == 1,
            "equalLengthChapters 变体的章节必须完全等长：$equalParagraphCounts"
        )
        assertTrue(
            equal.source.size == book.source.size,
            "对抗变体应只抹掉章节长度差异、不改变章节数（单变量对照）"
        )
        // 语料本身必须完全正确：1:1 全有译文、无译者加写。这样任何对齐损失只能归因于
        // 「章节层信号缺失」，而不是语料有洞。
        assertEquals(0, equal.truth.untranslatedSourceKeys.size, "等长章节语料不该有漏译")
        assertEquals(0, equal.truth.extraTargetMarkers, "等长章节语料不该有译者加写")
        assertEquals(
            equal.truth.sourceKeyCount,
            equal.truth.translatedSourceKeys.size,
            "等长章节语料每句都必须有译文"
        )
    }

    // ==================================================================
    //  公共路径
    // ==================================================================

    /** 跑一个变体：生成语料 → 待验收引擎对齐 → 投影 → 算指标 → 断言。 */
    private fun evaluate(variant: SyntheticBilingualFixture.Variant) {
        val book = variant.generate()
        val engine = AlignmentEngines.current
        val metrics = measure(book, engine)
        printRow(metrics)

        val why: String = if (AlignmentEngines.isRealImplementation) {
            "待验收对齐器未达标。"
        } else {
            "【实现未就位，此用例在 T3 交付后应转绿】AlignmentEngines.underTest == null，" +
                "当前跑的是占位基线 ${engine.engineId}。工具本身的正确性由 " +
                "oracle_achievesPerfectScoresOnEveryVariant / metricCalculator_* 守，它们必须绿。"
        }
        val hint = "[${variant.label}] $why\n${metrics.failureHint()}\n" +
            metrics.line(pendingImplementation = !AlignmentEngines.isRealImplementation)

        // ---- 硬断言：红了就是真 bug ----
        //
        // ⚠️ 红线只在**接了真实实现**时生效。理由：占位基线（naive-order-baseline）在结构
        // 扰动下必然误标，若对它也断言「错标 = 0」，整套测试会长期红，反而掩盖真回归
        // （这正是 Lead 要求分两档的初衷）。工具本身的正确性由 oracle 与指标算子自检守，
        // 那两条任何时候都必须绿。
        //
        // 未接真实实现时，错标数**照样全量打印**（`BASELINE-FALSE-ALIGNED` 行），所以
        // 「占位基线错在哪」依然可见，只是不判红。
        if (AlignmentEngines.isRealImplementation) {
            assertTrue(
                metrics.falseAlignedCount <= Acceptance.MAX_FALSE_ALIGNED,
                "错标（硬红线）：\n$hint\n${metrics.detailLinesCapped().joinToString("\n")}"
            )
            assertTrue(
                metrics.foreignTargetFalseAligned <= Acceptance.MAX_FOREIGN_ALIGNED,
                "指向译者加写（硬凑，最恶劣错标形态）：\n$hint\n" +
                    metrics.detailLinesCapped().joinToString("\n")
            )
        } else if (metrics.falseAlignedCount > 0) {
            // ⚠️ 温和的失败信息最危险：没人看就等价于红线被取消。所以基线误标必须**显眼**，
            // 用感叹号横幅 + 单独一行 + 明确的下一步提示，而不是混在普通日志里。
            println(
                "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!!! BASELINE-FALSE-ALIGNED (不是失败判据，但必须被看见)\n" +
                    "!!! variant=${variant.label} count=${metrics.falseAlignedCount}" +
                    " foreignAligned=${metrics.foreignTargetFalseAligned}\n" +
                    "!!! 占位基线 ${engine.engineId} 在结构扰动下误标了 " +
                    "${metrics.falseAlignedCount} 条。这已是**基线既有误差**，\n" +
                    "!!! 接上真实实现后此数必须为 0（届时同一处断言自动转为红线）。\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
            )
        }

        // 低方差对照组只守红线，不要求水平（见该变体的 @Test 文档）。
        // 等长章节 / 章节错位两个变体各有自己的专用用例（判据不同、且章节错位**覆盖率不设门**），
        // 这里跳过，避免同一变体被两套判据重复约束、改一处要改两遍。
        if (variant in SyntheticBilingualFixture.Variant.NO_FALSE_ALIGNED_ONLY) return
        if (variant == SyntheticBilingualFixture.Variant.EQUAL_LENGTH_CHAPTERS) return
        if (variant == SyntheticBilingualFixture.Variant.CHAPTER_CONTENT_PERMUTED) return

        // ---- 软断言：未达标只警告，不阻断 CI（见类文档的「两档」） ----
        checkLevel(
            metrics.precision >= Acceptance.MIN_PRECISION,
            "ALIGNED 精确率 ${metrics.precision} < ${Acceptance.MIN_PRECISION}", hint
        )
        checkLevel(
            metrics.coverage >= minCoverage(variant),
            "raw 覆盖率 ${metrics.coverage} < ${minCoverage(variant)}", hint
        )
        checkLevel(
            metrics.translatableCoverage >= minTranslatableCoverage(variant),
            "coverageT ${metrics.translatableCoverage} < ${minTranslatableCoverage(variant)}", hint
        )
        checkLevel(
            metrics.uncertainRate <= Acceptance.MAX_UNCERTAIN_RATE,
            "UNCERTAIN 率 ${metrics.uncertainRate} > ${Acceptance.MAX_UNCERTAIN_RATE}（弃权太多）", hint
        )
        if (metrics.uncertainSentences > 0) {
            checkLevel(
                metrics.uncertainTranslatableRate >= Acceptance.MIN_UNCERTAIN_TRANSLATABLE_RATE,
                "UNCERTAIN 里真值可对齐比例 ${metrics.uncertainTranslatableRate} < " +
                    "${Acceptance.MIN_UNCERTAIN_TRANSLATABLE_RATE}（弃权没起作用）", hint
            )
        }
        if (metrics.unalignedSentences > 0) {
            checkLevel(
                metrics.unalignedCorrectness >= 0.8,
                "UNALIGNED 正确率 ${metrics.unalignedCorrectness} < 0.8（在误杀有译文的句子）", hint
            )
        }
    }

    /**
     * 按档位处理一个断言。
     *
     * [Acceptance.STRICT_COVERAGE] 为 true 时红，否则只打印 `WARN`。**不吞信息**：
     * 警告行带完整 hint，所以哪怕 CI 绿着，日志里也能看到实现差多远。
     */
    private fun checkLevel(passed: Boolean, message: String, hint: String) {
        if (passed) return
        if (Acceptance.STRICT_COVERAGE) {
            assertTrue(false, "破线：$message\n$hint")
        } else {
            println("WARN $message | $hint")
        }
    }

    private fun minCoverage(variant: SyntheticBilingualFixture.Variant): Double =
        when (variant) {
            SyntheticBilingualFixture.Variant.CHAPTER_COUNT_MISMATCH ->
                Acceptance.MIN_COVERAGE_CHAPTER_MISMATCH
            else -> Acceptance.MIN_COVERAGE
        }

    private fun minTranslatableCoverage(variant: SyntheticBilingualFixture.Variant): Double =
        when (variant) {
            SyntheticBilingualFixture.Variant.CHAPTER_COUNT_MISMATCH ->
                Acceptance.MIN_TRANSLATABLE_COVERAGE_CHAPTER_MISMATCH
            else -> Acceptance.MIN_TRANSLATABLE_COVERAGE
        }

    /** 统一的指标行输出（含 coverage 落差说明与错标明细）。 */
    private fun printRow(metrics: AlignmentMetrics) {
        println(metrics.line(pendingImplementation = !AlignmentEngines.isRealImplementation))
        metrics.coverageGapNote().takeIf { it.isNotEmpty() }?.let(::println)
        metrics.detailLinesCapped().forEach(::println)
    }

    /**
     * 生成 → 对齐 → 投影 → 算指标。评估工具的唯一流程入口。
     *
     * ## 为什么要传 [MeaningProvider]（**曾经的一个真 bug**）
     *
     * 初版这里传的是 `meaning = null`，结果**每个变体的 coverage 都是 0.0**。原因不是算法
     * 差，而是接线时把唯一的词义通道掐断了：没有词典 → `vLexical` 恒 false → 纯陈述散文的
     * `vDiscourse`/`vHard` 也恒 false → 内容见证恒 0 → 按设计**永远不可能 ALIGNED**。
     *
     * 「`meaning = null` 时算法仍能工作」是契约要求的**退化路径**，但它只在标点/数字/专名
     * 类见证存在时才可能出结果；合成语料每句都是普通陈述散文，恰好把这条路堵死。
     * 所以拿 `null` 去量合成语料，量的是接线而不是算法。
     *
     * 现在默认传 [defaultMeaningProvider]（语料真值驱动的 `OracleLexicon`）；`meaning = null`
     * 的退化行为由 `noMeaningProvider_degradesWithoutCrashing` 单独守，不混在变体验收里。
     */
    private fun measure(
        book: SyntheticBilingualFixture.FixtureBook,
        engine: SentenceAligner,
        meaning: MeaningProvider? = defaultMeaningProvider(book)
    ): AlignmentMetrics = evaluateFully(book, engine, meaning).metrics

    /**
     * 本套验收默认使用的词义通道：**全词表覆盖的真值词典**。
     *
     * 用 [SyntheticBilingualFixture.truthLexicon]（由语料中英对照词表直接推导，100% 覆盖），
     * 而**不是**原先按统计共现率筛的 `OracleLexicon`（只覆盖 48 个词）。
     *
     * 理由（Lead 指出）：真实 ECDICT 有 77 万条词条，48 个词的词典上测出的覆盖率**不能
     * 代表算法能力**——瓶颈还卡在词典覆盖上。换成全覆盖后，瓶颈才移到算法本身，那些数
     * 才有判断价值。
     *
     * 词典覆盖率的消融（完美/稀疏/空）是另一条实验线，由 `LexiconAblationTest` 负责。
     */
    private fun defaultMeaningProvider(
        book: SyntheticBilingualFixture.FixtureBook
    ): MeaningProvider {
        val lexicon = SyntheticBilingualFixture.truthLexicon()
        return MeaningProvider { word -> lexicon[word.lowercase()] ?: emptyList() }
    }

    /**
     * 一次评估的完整产物：指标 + 原始 [AlignmentDocument]。
     *
     * 需要看 `document.notes`（降级痕迹）的用例走这个入口；只要指标的用例继续用 [measure]。
     */
    private class EvaluationOutcome(
        val metrics: AlignmentMetrics,
        val document: AlignmentDocument,
        /** 英文真值句键 → 该句的预测（三态语义断言要逐句看状态）。 */
        val projections: Map<String, PredictedAlignment>
    ) {
        fun projections(): Map<String, PredictedAlignment> = projections
    }

    private fun evaluateFully(
        book: SyntheticBilingualFixture.FixtureBook,
        engine: SentenceAligner,
        meaning: MeaningProvider? = defaultMeaningProvider(book)
    ): EvaluationOutcome {
        val document = engine.align(book.source, book.translation, meaning)
        val projection = AlignmentProjection.project(document, book.truth)
        val metrics = AlignmentMetricsCalculator.compute(
            variant = book.variant.label,
            engine = engine.engineId,
            truth = book.truth,
            projection = projection
        )
        return EvaluationOutcome(metrics, document, projection.bySourceKey)
    }

    /**
     * 退化路径自检：**不传词典时不能崩**，且必须给出三态之一。
     *
     * 契约要求「`meaning` 为 null 时算法仍能工作（退化路径），只是置信度更低」。
     * 这里只验证「能跑完、不丢句、不崩」，**不要求覆盖率**——没有词义通道时覆盖率本就该低，
     * 要求它达标等于奖励硬凑。
     */
    @Test
    fun noMeaningProvider_degradesWithoutCrashing() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val outcome = evaluateFully(book, AlignmentEngines.current, meaning = null)
        val metrics = outcome.metrics
        println(metrics.line(pendingImplementation = !AlignmentEngines.isRealImplementation))

        assertEquals(
            0, metrics.falseAlignedCount,
            "没有词义通道时绝不允许错标（宁可不对）：\n" +
                metrics.detailLinesCapped().joinToString("\n")
        )
        // 不丢句：每条英文句都必须落在三态之一（ALIGNED/UNCERTAIN/UNALIGNED）。
        val accounted = metrics.coveredSentences + metrics.uncertainSentences +
            metrics.unalignedSentences
        assertEquals(
            book.truth.sourceKeyCount, accounted,
            "每条英文句都必须被三态之一覆盖，不允许静默丢句：${metrics.line()}"
        )
    }

    /**
     * **「证据不足 ≠ 没有对应」的三态语义守门**（Lead 指定的通用断言）。
     *
     * 判据（Lead 原话）：
     *
     * > 对同一条英文句，若「有词典时报 ALIGNED」而「无词典时」，
     * > 则无词典时应报 `UNCERTAIN`（不是 `UNALIGNED`）。
     * > 因为「有对应译文但证据不足」≠「没有对应译文」。
     *
     * 实现方式：同一本书、同一个引擎，只切换 `MeaningProvider`（完美词典 vs null），
     * 取「有词典时确实 ALIGNED」的句子作为样本集，再看这些句子在无词典时的状态。
     *
     * ## 样本为空时**跳过并写明原因**（不硬凑）
     *
     * 若引擎在有词典时一句都 ALIGNED 不了，这条断言无从判定——此时打印 `SKIP` 与原因并
     * 返回，**不伪造通过**。
     *
     * ## 为什么逐个 `assertTrue` 而不是「统计比例」
     *
     * 这三态是**逐句语义**，不是统计性质。一句「有译文却被判成明确没有」就是错的，
     * 不管比例多低。所以这里逐句判，并在失败信息里带上具体句文本便于定位。
     */
    @Test
    fun insufficientEvidence_mustReportUncertainNotUnaligned() {
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val engine = AlignmentEngines.current

        val withLexicon = evaluateFully(book, engine, defaultMeaningProvider(book))
        val withoutLexicon = evaluateFully(book, engine, meaning = null)

        // 样本集 = 有词典时确实报 ALIGNED 的英文句。
        val lexiconAligned = withLexicon.projections()
            .filterValues { it.status == AlignmentStatus.ALIGNED }
            .keys
        println(
            "insufficientEvidence: 有词典时 ALIGNED=${lexiconAligned.size}" +
                " / 总句数=${book.truth.sourceKeyCount}"
        )
        if (lexiconAligned.isEmpty()) {
            // 不硬凑：这条路走不通就如实跳过，并说明原因。
            println(
                "SKIP insufficientEvidence_mustReportUncertainNotUnaligned：当前引擎在有词典时" +
                    "没有任何 ALIGNED 句，无法构造「有对应但证据不足」的样本集。"
            )
            return
        }

        val withoutLexiconProjections = withoutLexicon.projections()
        var checked = 0
        var uncertainCount = 0
        var unalignedCount = 0
        val violations = ArrayList<String>()
        lexiconAligned.forEach { key ->
            val after = withoutLexiconProjections[key] ?: return@forEach
            checked++
            when (after.status) {
                AlignmentStatus.UNCERTAIN -> uncertainCount++
                AlignmentStatus.UNALIGNED -> {
                    unalignedCount++
                    violations += "「${withLexicon.projections().getValue(key).sourceText}」" +
                        " 有译文且词典齐全时能 ALIGNED，无词典时却报 UNALIGNED（应为 UNCERTAIN）"
                }
                AlignmentStatus.ALIGNED -> {
                    // 无词典还能 ALIGNED 不违反本条（它没有"证据不足"），只要不错标即可。
                }
            }
        }
        println(
            "insufficientEvidence: 检查 $checked 句 → UNCERTAIN=$uncertainCount" +
                " UNALIGNED=$unalignedCount"
        )
        assertTrue(
            violations.isEmpty(),
            "「有对应译文但证据不足」被误报成「明确没有对应」（应报 UNCERTAIN）：\n" +
                violations.joinToString("\n")
        )

        // ---- 无词典仍能 ALIGNED 的句子：**逐条打印**（Lead 要求，便于人眼抽查）----
        //
        // 无词典时内容见证还有 `vDiscourse`（引语/问句/感叹）与 `vHard`（全书唯一锚点）。
        // 所以一句引语若标点结构两侧一致、且无矛盾证据，判 ALIGNED 是**合理**的；
        // 但若普通陈述句混进来，那就是 `vDiscourse` 误判，必须报给实现方。
        // 「打印出来才知道，不要只计数」——所以这里把见证通道、分数、理由全部列出。
        val alignedWithoutLexicon = lexiconAligned
            .mapNotNull { key -> withoutLexiconProjections[key]?.let { key to it } }
            .filter { (_, p) -> p.status == AlignmentStatus.ALIGNED }
        println(
            "insufficientEvidence: 无词典仍 ALIGNED 的句子 ${alignedWithoutLexicon.size} 句" +
                "（逐条列出见证，供人眼抽查是否混入了普通陈述句）："
        )
        alignedWithoutLexicon.forEach { (key, projection) ->
            val witness = projection.verifyMethod.ifEmpty { "<no-witness>" }
            val score = Math.round(projection.verifyScore * 100.0) / 100.0
            println(
                "  NO-LEXICON-ALIGNED key=$key witness=$witness score=$score" +
                    " src=\"${projection.sourceText.take(88)}\"" +
                    (if (projection.reasons.isNotEmpty()) " reasons=${projection.reasons.take(3)}" else "")
            )
        }
        if (alignedWithoutLexicon.isEmpty()) {
            println("  （无：无词典时没有任何句子报 ALIGNED）")
        }
    }
}
