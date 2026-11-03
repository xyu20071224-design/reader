package com.linguareader.shared.alignment

/**
 * 独立实现的句级英中双语对齐引擎（`engineId = "independent-v1"`）。
 *
 * ## 流水线（设计 §4，交接单 §1.2）
 *
 * ```
 * A1 切句 → A2 句特征 → A3 章节特征 → A4 structural → A5 r_total
 * A6 词频/锚点键频 → B1 costCh → B2 章节DP → B3 r_book
 * C1 候选钉 → C2 LIS → C3 剪枝（用 r_book）→ C4 切块 → C5 r_seed → C6 r_seg → C7 σ
 * D1 段落DP → D2 句级DP → D3 drift
 * E0 词典桥 → E1 IDF 扫描 → E2/E3 逐对验证
 * F1 UnitFeatures → F2 decide() → F3 漂移否决 → F4 投影 → F5 统计
 * ```
 *
 * ## 三条结构性保证（写在这里是为了让 review 一眼看到）
 *
 * 1. **验证层不渗回选路**：`MeaningProvider` 在配对阶段**根本拿不到**——[VerifyLayer]
 *    在 D2 之后才跑，且只读已冻结的配对。换词典不会改变配对序列（§8.5）。
 * 2. **判定层不可重算**：[decide] 只读 [UnitFeatures]，签名里没有文本、没有词典。
 * 3. **热路径零文本处理**：DP 内层只碰 primitive 数组下标；文本处理全在 [BookSide] 的
 *    预处理阶段完成一次（§12.4）。
 *
 * ## 纯函数
 *
 * 同样的输入产生同样的输出，不读写外部状态。`align()` 内部每次新建全部中间对象，
 * 因此可重入、可并发调用。
 */
class IndependentAligner : SentenceAligner {

    override val version: Int = ALIGNER_VERSION

    override val engineId: String = ENGINE_ID

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        // ── 退化：一侧为空（§10.10，不抛异常）─────────────────────
        if (source.isEmpty() || translation.isEmpty()) {
            return degenerateDocument(source, translation, "one side is empty")
        }

        val sourceSide = BookSide.build(SideKind.SOURCE, source)
        val targetSide = BookSide.build(SideKind.TARGET, translation)
        if (sourceSide.sentCount == 0 || targetSide.sentCount == 0) {
            return degenerateDocument(source, translation, "one side has no sentence")
        }

        val notes = ArrayList<String>(16)

        // ── A5：r_total（剔除结构性章节后的全量比）──────────────────
        // 两侧各数各的：英文侧给词数、中文侧给汉字数（见 DensityModel.computeTotal 的警告）。
        val density = DensityModel(sourceSide)
        val sourceStructural = BooleanArray(sourceSide.chapterCount) { sourceSide.isStructural(it) }
        density.totalRatioFromChapters(
            chineseChars = targetSide.chapterChars,
            englishWords = sourceSide.chapterWords,
            sourceStructural = sourceStructural
        )

        // ── A6 + C1–C3：锚点（键频 → 候选钉 → LIS → 一致性剪枝）─────
        val anchors = AnchorLayer(sourceSide, targetSide, density)
        anchors.build()

        // ── B1–B3：章节层 ─────────────────────────────────────────
        val chapters = ChapterLayer(sourceSide, targetSide, anchors, density)
        chapters.run()
        density.computeBook(chapters.acceptedPairChars, chapters.acceptedPairWords)

        // ── C5–C7：r_seed 交叉校验 + σ（几何混合，§16 E3）────────────
        density.computeSeed(
            anchors.pinResiduals(),
            targetSide.chapterChars,
            targetSide.chapterWords
        )
        notes += density.notes
        notes += anchors.notes
        notes += chapters.notes

        // ── D1–D3：段落层 + 句级 DP + 漂移否决 ────────────────────
        val pairing = PairingEngine(sourceSide, targetSide, density, anchors)
        pairing.run(chapters)
        pairing.computeNoPlausibleCandidate(chapters)
        notes += pairing.notes

        // ── E0–E3：独立验证层（只在配对冻结之后跑）────────────────
        val verify = VerifyLayer(sourceSide, targetSide, density, meaning)
        verify.build()
        notes += verify.notes

        // ── V3：专名锚点见证（translit，独立于 V1 的第三条内容通道）──────
        // 只在 V1 全灭时才有翻盘价值；它不参与选路，只作见证。
        val translit = TranslitAnchorLayer(sourceSide, targetSide)
        translit.build()
        notes += translit.notes

        // ── Lead 裁定 2：章节级内容复核（低 margin 降级的安全网）──────
        // 必须排在「配对冻结 + 验证层可用」之后、decide() 之前：它要用 V1/V2 的实际
        // 见证结果来判断「这个章节对是不是整体配错了」。
        chapters.hasPairOf = { sentence -> pairing.drafts[sentence].hasPair }
        val contentVetoed = chapters.applyChapterContentSanity { sentence ->
            witnessedByContent(sourceSide, targetSide, pairing, verify, sentence)
        }
        if (contentVetoed > 0) notes += "chapter pairs vetoed by content sanity: $contentVetoed"
        notes += chapters.notes

        // ── F1–F2：组装 UnitFeatures + 判定 ───────────────────────
        applyDecision(
            sourceSide, targetSide, chapters, pairing, verify, translit, density,
            hasMeaningProvider = meaning != null,
            notes = notes
        )

        // ── F4–F5：投影 + 统计 ───────────────────────────────────
        val projection = UnitProjector.project(
            UnitProjector.Input(sourceSide, targetSide, chapters, pairing.drafts, pairing.edges),
            degenerate = false
        )
        val stats = UnitProjector.stats(
            units = projection.units,
            source = sourceSide,
            target = targetSide,
            mergedEdges = projection.mergedEdges,
            structuralSkipped = projection.structuralSkipped
        )

        notes += buildString {
            append("engine=").append(ENGINE_ID)
            append(" en=").append(sourceSide.chapterCount).append("ch/").append(sourceSide.sentCount).append("s")
            append(" zh=").append(targetSide.chapterCount).append("ch/").append(targetSide.sentCount).append("s")
            append(" r=").append(round(density.rBook))
            append(" sigma=").append(round(density.sigma1))
            append(" structural=en").append(projection.structuralSkipped)
        }

        return AlignmentDocument(
            alignerVersion = version,
            engineId = engineId,
            units = projection.units,
            stats = stats,
            notes = notes
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  F1–F3
    // ──────────────────────────────────────────────────────────────

    /**
     * 该英文句是否拿到**内容见证**（V1 或 V2）。
     *
     * 章节级内容复核（Lead 裁定 2）用它判断「这个章节对整体上有没有内容证据」。
     * 注意这里**不看** `decide()` 的最终状态——复核发生在判定之前，且它必须只由内容
     * 通道回答（否则又是自证）。
     */
    private fun witnessedByContent(
        source: BookSide,
        target: BookSide,
        pairing: PairingEngine,
        verify: VerifyLayer,
        sentence: Int
    ): Boolean {
        val edge = pairing.drafts[sentence].edge ?: return false
        val lexical = verify.score(edge.enFrom, edge.spanEn, edge.zhFrom, edge.spanZh)
        if (lexical.available &&
            lexical.recall >= AlignTuning.VERIFY_RECALL_MIN &&
            (lexical.precision >= AlignTuning.VERIFY_PRECISION_MIN ||
                lexical.lift >= AlignTuning.VERIFY_LIFT_MIN)
        ) return true
        return verify.discourse(edge.enFrom, edge.spanEn, edge.zhFrom, edge.spanZh).witness
    }

    private fun applyDecision(
        source: BookSide,
        target: BookSide,
        chapters: ChapterLayer,
        pairing: PairingEngine,
        verify: VerifyLayer,
        translit: TranslitAnchorLayer,
        density: DensityModel,
        hasMeaningProvider: Boolean,
        notes: MutableList<String>
    ) {
        var driftDegraded = 0
        for (s in 0 until source.sentCount) {
            val draft = pairing.drafts[s]
            val paragraph = source.sentParagraph[s]
            val chapter = source.paraChapter[paragraph]
            val verdict = chapters.sourceVerdict.getOrNull(chapter) ?: ChapterVerdict.ACCEPTED
            // Lead 裁定 3：低 margin 章节对（结构信号不可分）里句级门要更严。
            val lowMargin = chapters.lowMarginChapters[chapter] == true

            val edge = draft.edge
            // ── 单元级 witness **必须按本句**算，不能用边的并集 ──────────────
            //
            // 为什么（Lead：「单元级 witness 是怎么算的、合并单元和单句是否一致」）：
            // `N:1` / `N:M` 边会被投影成 N 个单元（契约 §9.5），每个单元只对应**一条**
            // 英文句。若三个单元都拿「边的并集见证」，就会出现：兄弟句的内容证据被
            // 算到本句头上——本句自己零命中，却因为同边另一句命中了而拿到 ALIGNED。
            //
            // 实测后果（`sentenceMerge` 变体 42 条违反、占 37%）：合并单元里第二个编码
            // 对应的那部分零见证却仍带译文，正是这里聚合错了。
            //
            // 正确做法：用**本句 × 边的中文跨度**算单句见证。
            //   英文侧：只用 `s` 这一句（spanEn = 1）
            //   中文侧：仍用整条边覆盖的中文跨度（`spanZh`）——因为译文确实可能与兄弟句
            //   共享同一句中文，这不是「借」，是 N:1 的定义。
            val lexical = if (edge != null) {
                verify.score(s, 1, edge.zhFrom, edge.spanZh)
            } else {
                LexicalScore.UNAVAILABLE
            }
            val discourse = if (edge != null) {
                verify.discourse(s, 1, edge.zhFrom, edge.spanZh)
            } else {
                DiscourseScore(witness = false, conflict = false)
            }
            // V3：本句的专名是否精确命中配对的中文句（不参与选路，只作见证）。
            val translitName = if (edge != null) translit.witnessName(s, edge.zhFrom) else null

            val expected = if (edge != null) {
                density.rEffOfSentence(s)
            } else {
                density.rBook
            }
            // 可查词比例护栏（Lead 裁定）：本句英文词里词典查得到候选的比例。
            // 它衡量「这句的证据强度」，与有没有配对无关，所以总是算。
            val coverable = verify.coverableRatio(s)
            val observed = if (edge != null) {
                var words = 0
                var chars = 0
                for (k in 0 until edge.spanEn) words += source.sentWords[edge.enFrom + k]
                for (k in 0 until edge.spanZh) chars += target.sentChars[edge.zhFrom + k]
                density.observedRatio(words, chars)
            } else {
                0.0
            }

            val features = UnitFeatures(
                degenerate = false,
                isLangUnit = source.sentLangUnit[s],
                structural = source.isStructural(chapter),
                chapterVerdict = verdict,
                hasPair = draft.hasPair,
                spanEn = if (draft.hasPair) draft.spanEn else 0,
                spanZh = if (draft.hasPair) draft.spanZh else 0,
                enWords = source.sentWords[s],
                noPlausibleCandidate = draft.noPlausibleCandidate,
                z = if (draft.hasPair) draft.z else Double.MAX_VALUE,
                margin = draft.margin,
                lengthInformativeness = if (draft.hasPair) draft.lengthInformativeness
                else Double.MAX_VALUE,
                drift = draft.drift,
                driftBudget = draft.driftBudget,
                anchorHard = draft.anchorHard,
                anchorOrderOk = draft.anchorOrderOk,
                anchorConflict = false, // §8.4 anchorConflict 需要跨单元锚点互斥检测，见实现说明
                lexicalAvailable = lexical.available,
                lexicalRecall = lexical.recall,
                lexicalPrecision = lexical.precision,
                lexicalLift = lexical.lift,
                coverableRatio = coverable,
                coverableRatioTooLow = lexical.available &&
                    coverable < AlignTuning.MIN_COVERABLE_RATIO,
                // **二元事实**（Lead 裁定选项 3）：只回答「我有没有**可用的**词典」，
                // 不回答「这句能查多少」。`coverableRatio` 只参与 ALIGNED 判定。
                //
                // 用 `verify.lexicalAvailable` 而不是只看 provider 非 null：provider 存在
                // 但**一条可用词条都没吐出来**（空词典/垃圾词典）时，桥是空的，
                // 实际上仍然「没法查」——那种情况下必须诚实弃权，而不是断言「查过没有」。
                lexicalChannelUsable = hasMeaningProvider && verify.lexicalAvailable,
                // 合并组护栏：span 内每句英文都要有内容贡献（防「靠一句撑起整组」）。
                minHitsPerSourceSentence = if (edge != null) {
                    verify.minHitsPerSourceSentence(edge.enFrom, edge.spanEn, edge.zhFrom, edge.spanZh)
                } else {
                    Int.MAX_VALUE
                },
                discourseWitness = discourse.witness,
                discourseConflict = discourse.conflict,
                paragraphVerified = false, // D1 关闭时不影响判定
                gapExactlyOneToOne = false, // D1 关闭：恒 false，不计算
                neighboursAligned = false, // D1 关闭：恒 false，不计算
                rConfidenceLow = density.rConfidenceLow,
                // ── Lead 裁定 3：低 margin 章节对里，句级门更严而非更松 ──
                // 章节定位本身不可靠时，内容证据必须更强才能背书。
                lowMarginChapter = lowMargin,
                translitWitness = translitName != null,
                observedRatio = observed,
                expectedRatio = expected,
                matchedAnchors = buildList {
                    if (draft.matchedAnchors.isNotEmpty()) add(draft.matchedAnchors)
                    // V3 命中的专名也进证据（可复核：用户能看到「凭什么」）
                    if (translitName != null) add("name:$translitName")
                }
            )

            val decision = decide(features)
            var status = decision.status
            var reasons = decision.reasons
            var confidence = decision.confidence

            // ── F3 后置否决：块级/单点漂移（§7.7）──────────────────
            // 必须**后置**：它是对已判定结果的否决，不是判定输入（交接单 §1.2 F3）。
            // 而且只降级、不重排——重排就变成用同一套结构特征参与选路（自证）。
            if (draft.driftVetoed && status == AlignmentStatus.ALIGNED) {
                status = AlignmentStatus.UNCERTAIN
                confidence = minOf(confidence, 0.55)
                reasons = reasons + "drift veto"
                driftDegraded++
            }

            // ── §8.6 反向候选：只改展示，不改选，永不 ALIGNED ────────
            if (edge != null && status == AlignmentStatus.ALIGNED &&
                edge.spanEn == 1 && edge.spanZh == 1
            ) {
                val salvage = salvagedTarget(verify, edge, target)
                if (salvage != null) {
                    status = AlignmentStatus.UNCERTAIN
                    confidence = minOf(confidence, 0.60)
                    reasons = reasons + "verifier prefers j'=$salvage"
                    draft.salvageTarget = salvage
                }
            }

            draft.status = status
            draft.skipReason = decision.skipReason
            draft.confidence = confidence
            draft.reasons = reasons
            draft.evidence = evidenceOf(features, decision.copyReasons(reasons))
        }
        if (driftDegraded > 0) notes += "drift veto degraded ALIGNED→UNCERTAIN: $driftDegraded"
    }

    /**
     * 反向候选（§8.6）：邻域内另一个候选的内容证据明显更强。
     *
     * **不改选**——改选会让验证器变成生成器的一部分（自证）。返回更强候选的中文句下标，
     * 调用方据此只改 `targetText` 的展示并把状态强制为 `UNCERTAIN`。
     */
    private fun salvagedTarget(verify: VerifyLayer, edge: DpEdge, target: BookSide): Int? {
        if (!verify.lexicalAvailable) return null
        val chosen = verify.score(edge.enFrom, 1, edge.zhFrom, 1)
        if (!chosen.available) return null
        var bestIndex = -1
        var bestRecall = chosen.recall
        for (offset in intArrayOf(-2, -1, 1, 2)) {
            val candidate = edge.zhFrom + offset
            if (candidate < 0 || candidate >= target.sentCount) continue
            if (target.sentParagraph[candidate] != target.sentParagraph[edge.zhFrom]) continue
            val score = verify.score(edge.enFrom, 1, candidate, 1)
            if (!score.available) continue
            if (score.recall > bestRecall + AlignTuning.SALVAGE_DELTA) {
                bestRecall = score.recall
                bestIndex = candidate
            }
        }
        return if (bestIndex >= 0) bestIndex else null
    }

    // ──────────────────────────────────────────────────────────────

    private fun degenerateDocument(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        why: String
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        // 退化时仍然覆盖每一条英文句（契约：不许静默丢句），但不去分句（省掉无用的工作）。
        source.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                units += AlignedUnit(
                    status = AlignmentStatus.UNALIGNED,
                    sourceChapter = chapterIndex,
                    sourceParagraph = paragraph,
                    sourceText = paragraph,
                    sourceIndexInParagraph = paragraphIndex,
                    skipReason = AlignmentSkipReason.DEGENERATE_INPUT
                )
            }
        }
        return AlignmentDocument(
            alignerVersion = version,
            engineId = engineId,
            units = units,
            stats = AlignmentStats(
                sourceSentenceTotal = 0,
                targetSentenceTotal = 0,
                sourceSentencesAligned = 0
            ),
            notes = listOf("degenerate input: $why")
        )
    }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    companion object {
        /** 算法标识（写进 [AlignmentDocument.engineId]，供 A/B 与回归定位）。 */
        const val ENGINE_ID = "independent-v1"

        /**
         * 算法版本号。**算法或分句规则变化时必须 +1**（契约 [AlignmentDocument.alignerVersion]）。
         */
        const val ALIGNER_VERSION = 1
    }
}

private fun Verdict.copyReasons(reasons: List<String>): Verdict =
    Verdict(status, skipReason, confidence, reasons)
