package com.linguareader.shared.alignment

/**
 * 可调阈值配置。生产默认值见 [PRODUCTION]（2026-11-01 按 Pareto 扫描结果改为「激进」）。
 *
 * ## 为什么把「错判」从红线改成可计价成本
 *
 * 之前把 `falseAligned == 0` 当硬红线，导致任何降低错判的规则都要牺牲
 * 40–85% 覆盖率（见 `.agents/memory/sentence-splitter-defects.md` SPLIT-007 的实测表）。
 * **红线让优化退化成二选一**：要么零错判，要么高覆盖，不能两者兼顾。
 *
 * 改成可计价成本后，问题变成「在给定错判容忍度下最大化覆盖率」。
 *
 * ## Pareto 扫描结论（两章 1481 句，32 组阈值，见 `AiTuningSweepProbe`）
 *
 * | 配置 | 覆盖率 | 静默错 | 体验分 |
 * | --- | --- | --- | --- |
 * | 严格（`requireWitness=true`） | 41.00% | 1 条（0.04%） | 76.8 |
 * | **激进（`requireWitness=false`）** | **65.92%** | **2 条（0.08%）** | **85.1** |
 * | 极限（再放开长度门） | 66.32% | 3 条（0.12%） | 85.0 |
 *
 * **边际收益**：关掉见证门 → 每多 1 条错判换 **+21.6 个点覆盖**；
 * 再放开长度门 → 每多 1 条只换 +0.34 个点。**所以停在这里，不再往下走。**
 *
 * 关键观察：**可用率在所有配置下都是 79.60%**——关掉见证门只是把答案从
 * `UNCERTAIN` 移到 `ALIGNED`，答案本身没变。用户感知的变化是「警告变少」，
 * 代价是多 1 条无警告的错答。
 */
data class AlignerTuning(
    /** 长度门：`z > zLimit` → UNCERTAIN。放宽会让更多句子过门，也可能放进错配。 */
    val zLimit: Double = 3.0,
    /** 词典见证下限。仅当 [requireWitness] = true 时生效。 */
    val lexicalWitnessMin: Double = 0.15,
    /** 段内句数不等时的唯一性门：`margin < marginMin` → UNCERTAIN。 */
    val marginMin: Double = 0.35,
    /** 锚点防错切：含锚点句对的通过率下限。 */
    val anchorPassMin: Double = 0.8,
    /**
     * 是否要求「有内容见证」才能 ALIGNED。
     *
     * **2026-11-01 起默认 `false`**（Lead 裁定）。关掉意味着：只要过了
     * 长度门 / 锚点门 / margin 门就判 ALIGNED。
     *
     * 依据：见证门是最大的成本项——关掉它让覆盖率 41% → 66%，
     * 代价仅错判 1 条 → 2 条（0.04% → 0.08%）。
     * 而那 2 条错判**同一根因**（SPLIT-007 句界偏移），修掉根因后会降到 0。
     *
     * **注意**：关掉后仍保留「有见证时 confidence 更高」——
     * 这样 UI 仍能区分强弱（0.97/0.92/0.90 vs 0.82），只是不再因此拒绝。
     */
    val requireWitness: Boolean = false,

    /**
     * 段内穷举是否允许「跳过一个句子」（1:0 / 0:1）。
     *
     * ## 为什么默认 `false`（2026-11-01）
     *
     * 句数不等的段（`en=5 zh=6`）若不允许跳过，穷举可能**无解**，整段落进档 3
     * 全部 `UNALIGNED`——实测第 3 章 45 条（占点击 6.45%）全属此类，
     * 而这些句子**其实有译文**。
     *
     * ## 为什么之前两次尝试失败
     *
     * 一放开跳过，穷举会**重新分配整个段的配对**：不只是补上原本无解的段，
     * 还会把原本正确的 1:1 配对也改掉。实测：
     *
     * | 跳过代价 | 第1章 UNALIGNED | 第3章 UNALIGNED | 静默错 |
     * | --- | --- | --- | --- |
     * | 不启用 | 96 | 45 | 1 |
     * | 0.12 | — | 189 | 5 |
     * | 0.60 | 106 | — | 0 |
     *
     * **根因**：跳过代价与「正常配对的长度偏离」量纲相同，无论取多少，
     * 都会在某些段落里让「跳过」比「配对」便宜。
     *
     * ## 本次改法：**两级求解**
     *
     * 先按「不允许跳过」求解；**只有无解时才**用允许跳过再求一次。
     * 这样已经能正常求解的段**完全不受影响**，只有真正无解的段才启用跳过。
     */
    val allowSkipOnDeadlock: Boolean = true
) {
    companion object {
        /**
         * 生产默认配置。
         *
         * 历史：
         * - 2026-10-31 之前：`requireWitness = true`（严格）
         * - **2026-11-01 起：`requireWitness = false`（激进）** —— 依据上面 KDoc 的
         *   Pareto 扫描，+25 个点覆盖率换 +1 条错判（0.04% → 0.08%）。
         * - 2026-11-01 追加：`allowSkipOnDeadlock = true`（两级求解），
         *   只救「穷举无解」的段，不动已能求解的段。
         */
        val PRODUCTION = AlignerTuning()

        /** 严格模式（保留给 A/B 对照与回归测试）。 */
        val STRICT = AlignerTuning(requireWitness = true, allowSkipOnDeadlock = false)
    }
}
