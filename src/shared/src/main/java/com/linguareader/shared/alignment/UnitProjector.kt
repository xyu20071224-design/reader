package com.linguareader.shared.alignment

/**
 * 输出投影层（设计 §9.5，交接单 F4/F5）。**这一层是「不许静默丢句」的落地处。**
 *
 * | DP 边 | 输出 |
 * | --- | --- |
 * | `1:1` | 1 个单元，`sourceSpanSize=1`、`targetSpanSize=1` |
 * | `1:N` | **1 个单元**，`sourceSpanSize=1`、`targetSpanSize=N`，`targetText` 为 N 句拼接 |
 * | `N:1` | **N 个单元**，每个 `sourceSpanSize=1`、`targetSpanSize=1`，但 `targetText` **都**填那一句中文 |
 * | `N:M` | **N 个单元**，每个持有同一段 `targetText`（M 句拼接），`targetSpanSize=M` |
 * | `1:0` | 1 个单元，`UNALIGNED`，`targetChapter=-1`、`targetText=""` |
 * | `0:1` | **不产出英文单元**（中文句被跳过，只影响坐标） |
 *
 * ### 为什么 `N:1` 要拆成 N 个单元
 *
 * 契约的 [AlignedUnit] 以句为单位，UI 点的是**某一条英文句**。若把两条英文句并成一个单元，
 * 点第二条时索引定位会落到第一条上。拆开后两条单元共享 `targetText`，UI 只需按
 * `targetText` 判等就能知道「这两条是同一组译文」，不需要额外字段（设计 §15.2 D4）。
 *
 * ### `mergedUnits` 的两个不同口径——别混
 *
 * - `AlignmentStats.mergedUnits` **按 DP 边计**（`spanEn > 1 || spanZh > 1` 的边数）：
 *   按单元字段数会漏掉全部 `N:1`（拆开后每个单元都是 `1:1`），警报器会静默失灵。
 * - 单元级的 `targetSpanSize` 仍然照上表填，用于 UI 判断「这条对照是合并来的」。
 */
internal object UnitProjector {

    /** 投影所需的全部上下文。 */
    class Input(
        val source: BookSide,
        val target: BookSide,
        val chapters: ChapterLayer,
        val drafts: Array<UnitDraft>,
        val edges: List<DpEdge>
    )

    /** 投影结果：单元列表 + 两个「按边计」的统计量。 */
    class Output(
        val units: List<AlignedUnit>,
        /** 按 DP 边计的合并对数（`spanEn > 1 || spanZh > 1`）。 */
        val mergedEdges: Int,
        /** 结构性章节里的英文有效句数。 */
        val structuralSkipped: Int
    )

    fun project(input: Input, degenerate: Boolean): Output {
        val source = input.source
        val target = input.target
        val units = ArrayList<AlignedUnit>(source.sentCount)
        var structuralSkipped = 0

        for (s in 0 until source.sentCount) {
            val paragraph = source.sentParagraph[s]
            val chapter = source.paraChapter[paragraph]
            val isUnit = source.sentLangUnit[s]
            val structural = source.isStructural(chapter)
            if (structural && isUnit) structuralSkipped++

            val draft = input.drafts.getOrNull(s)
            val edge = draft?.edge
            val merged = edge != null && edge.merged

            // ── target 侧：文本与坐标 ────────────────────────────────
            var targetChapter = -1
            var targetParagraph = ""
            var targetText = ""
            var targetIndexInParagraph = -1
            var targetSpanSize = 0
            // ── 契约：`targetText` 只在**最终状态允许展示**时才填 ──────────
            //
            // `ALIGNED` / `UNCERTAIN` 的语义都是「有候选」——前者有把握、后者证据不足
            // 但候选是真实的（来自 DP 的配对边），必须给出译文让用户能点开。
            // `UNALIGNED` 的语义是「明确没有对应」——它的 `targetText` **必须为空**。
            //
            // 历史 bug：这里只判断 `edge != null` 就填了 targetText，于是「DP 曾经给过
            // 一条边、但判定层否决了它」的句子会变成「状态是 UNALIGNED、却带着一条译文」。
            // 那种混合体最恶劣：用户看到「没有对应」+一条具体译文，会以为系统只是谨慎，
            // 实际上那条译文已被判定为不可信。这条修正让状态与文本保持一致。
            val status = draft?.status ?: AlignmentStatus.UNALIGNED
            val mayShowTarget = status != AlignmentStatus.UNALIGNED
            if (edge != null && mayShowTarget) {
                // §8.6：验证器偏好另一个候选时**只换展示**，不改配对（状态已是 UNCERTAIN）
                val zhFrom = if (draft != null && draft.salvageTarget >= 0) draft.salvageTarget else edge.zhFrom
                val zhSpan = if (draft != null && draft.salvageTarget >= 0) 1 else edge.spanZh
                targetChapter = target.paraChapter[target.sentParagraph[zhFrom]]
                targetParagraph = target.paraText[target.sentParagraph[zhFrom]]
                val builder = StringBuilder(32)
                for (k in 0 until zhSpan) {
                    if (k > 0) builder.append(' ')
                    builder.append(target.sentText[zhFrom + k])
                }
                targetText = builder.toString()
                targetIndexInParagraph = target.sentIdxInPara[zhFrom]
                targetSpanSize = zhSpan
            }

            units += AlignedUnit(
                status = status,
                sourceChapter = chapter,
                targetChapter = targetChapter,
                sourceParagraph = source.paraText[paragraph],
                targetParagraph = targetParagraph,
                sourceText = source.sentText[s],
                targetText = targetText,
                sourceIndexInParagraph = source.sentIdxInPara[s],
                targetIndexInParagraph = targetIndexInParagraph,
                // 契约 §9.5：每个单元恒为 1 条英文句（N:1 拆成 N 个单元、1:N 不拆）。
                sourceSpanSize = 1,
                targetSpanSize = targetSpanSize,
                confidence = draft?.confidence ?: 0.0,
                evidence = draft?.evidence ?: AlignmentEvidence(),
                skipReason = draft?.skipReason
            )
        }

        val mergedEdges = input.edges.count { it.merged }
        return Output(units, mergedEdges, structuralSkipped)
    }

    /** 统计口径（设计 §14.1 / 契约 `AlignmentStats` 的字段定义）。 */
    fun stats(
        units: List<AlignedUnit>,
        source: BookSide,
        target: BookSide,
        mergedEdges: Int,
        structuralSkipped: Int
    ): AlignmentStats {
        var total = 0
        var targetTotal = 0
        var alignedSentences = 0
        var alignedUnits = 0
        var uncertainUnits = 0
        var unalignedUnits = 0
        for (s in 0 until source.sentCount) {
            if (source.sentLangUnit[s]) total++
        }
        for (t in 0 until target.sentCount) {
            if (target.sentLangUnit[t]) targetTotal++
        }
        for (unit in units) {
            when (unit.status) {
                AlignmentStatus.ALIGNED -> {
                    alignedUnits++
                    alignedSentences += unit.sourceSpanSize
                }
                AlignmentStatus.UNCERTAIN -> uncertainUnits++
                AlignmentStatus.UNALIGNED -> unalignedUnits++
            }
        }
        return AlignmentStats(
            sourceSentenceTotal = total,
            targetSentenceTotal = targetTotal,
            sourceSentencesAligned = alignedSentences,
            alignedUnits = alignedUnits,
            uncertainUnits = uncertainUnits,
            unalignedUnits = unalignedUnits,
            mergedUnits = mergedEdges,
            alignedPrecision = null,
            structuralSkipped = structuralSkipped
        )
    }
}
