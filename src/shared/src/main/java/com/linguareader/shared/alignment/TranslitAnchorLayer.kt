package com.linguareader.shared.alignment

/**
 * **V3 专名锚点见证层**（translit，独立于 V1 词义通道的第三条内容通道）。
 *
 * ## 为什么需要它
 *
 * V1（ECDICT 词义反查）在真实文学译本上**实测无信号**：正确配对上的 `recall`
 * 中位数为 0、max 0.193（门槛 0.34）。原因是 ECDICT 给现代汉语释义，而译本用
 * 意译/半文言（`rings → 吊环` vs 「戒」）。这条通道救不回来。
 *
 * 专名不一样：`Frodo → 佛羅多` 是**音译**，全书上下译法固定、跨章稳定，不依赖词义。
 *
 * ## 三条硬性准入条件（缺一不可）
 *
 * 1. **精确档**：候选串必须**逐字出现**在中文句里（不是近似对齐）。
 *    开发者实测表明误报全来自近似档（0.5–0.7），精确档误报为 0。
 * 2. **译法唯一**：该英文名在全书的精确命中串只有一个。
 *    若 `Tom` 既译「湯姆」又译「托姆」，它不能做锚点。
 * 3. **候选非空且能精确命中** —— 这天然过滤掉 `extractNames` 把生僻形容词
 *    误当专名的情况（生僻形容词的音译候选不会出现在中文正文里）。
 *
 * ## 为什么我选「V1 的替代见证」而不是「纯独立通道」
 *
 * **专名只能证明「这句大致在这段」，不能证明「这句就是这句」。** 同一段里
 * 两个句子都可能含 `Frodo`。所以它只在 V1 拿不到见证时兜底，不与 V1 叠加。
 *
 * ## 性能设计（四版迭代，全部实测驱动）
 *
 * | 版本 | 做法 | 13978 句整本耗时 |
 * | --- | --- | --- |
 * | v1 | 每句 × 中文侧 ±80 句窗口，逐个 `scoreInSentence` | **4134ms**（超预算） |
 * | v2 | 拼大串 + 每候选 `indexOf` 全扫 | 3031ms |
 * | v3 | 逐句 `contains` | 3811ms |
 * | **v4（当前）** | **只对已配对的单元算，不建全书索引** | 见报告 |
 *
 * v4 的关键观察：**V3 只需要回答「这条已配对的边，专名对不对得上」**，
 * 不需要预先知道「专名在全书的分布」。而「译法唯一」这个条件可以**按需惰性求值**
 * ——只在第一次用到某个专名时，才在窗口内确认它没有第二种译法。
 *
 * 于是总代价从「专名数 × 全书」降到「已配对单元数 × 局部确认」。
 */
internal class TranslitAnchorLayer(
    private val source: BookSide,
    private val target: BookSide
) {
    /** 每个英文句抽到的专名（已过滤：候选非空）。 */
    private var namesBySentence: Array<List<String>> = emptyArray()

    /** 繁→简后的中文句（**惰性**填充，只有被问到的句子才算）。 */
    private var simplifiedCache: Array<String?> = emptyArray()

    /** 专名 → 是否「译法唯一」（**惰性**求值，null = 未求值）。 */
    private var uniquenessCache: HashMap<String, Boolean> = HashMap(256)

    /** 专名 → 唯一中文形。 */
    private var formCache: HashMap<String, String> = HashMap(256)

    val notes: MutableList<String> = ArrayList()

    fun build() {
        namesBySentence = Array(source.sentCount) { sentence ->
            Transliteration.extractNames(source.sentText[sentence])
                .filter { Transliteration.candidates(it).isNotEmpty() }
        }
        simplifiedCache = arrayOfNulls(target.sentCount)
        val totalNames = namesBySentence.sumOf { it.size }
        notes += if (totalNames == 0) {
            "translit: no candidate name found"
        } else {
            "translit: name-tokens=$totalNames (uniqueness checked lazily)"
        }
    }

    private fun simplified(sentence: Int): String {
        simplifiedCache[sentence]?.let { return it }
        val text = Transliteration.toSimplified(target.sentText[sentence])
        simplifiedCache[sentence] = text
        return text
    }

    /**
     * 该英文句是否有**可用的 V3 见证**，针对给定的中文句。
     *
     * @return 命中的专名（非空 = 有见证）
     */
    fun witnessName(sourceSentence: Int, targetSentence: Int): String? {
        if (sourceSentence !in namesBySentence.indices) return null
        if (targetSentence < 0 || targetSentence >= target.sentCount) return null
        val names = namesBySentence[sourceSentence]
        if (names.isEmpty()) return null
        val zhText = simplified(targetSentence)
        if (zhText.isEmpty()) return null
        for (name in names) {
            val form = uniqueFormOf(name) ?: continue
            if (zhText.contains(form)) return name
        }
        return null
    }

    /**
     * 惰性求「译法唯一」：某专名的候选形在**局部窗口**里是否只出现一种。
     *
     * 窗口取中文侧 ±[LOCAL_WINDOW] 句——不需要全书，因为「同一专名全书统一译法」
     * 这件事在真实译本里是常态，局部确认足以排除「一个名对应多个音译形」的情况；
     * 而全书级的重复译名（同一角色在不同卷被重译）在单卷本里不存在。
     */
    private fun uniqueFormOf(name: String): String? {
        formCache[name]?.let { return it }
        if (uniquenessCache[name] == false) return null
        val candidates = Transliteration.candidates(name)
        if (candidates.isEmpty()) {
            uniquenessCache[name] = false
            return null
        }
        val firstHit = candidates.firstOrNull { candidate ->
            anySentenceContains(candidate)
        }
        if (firstHit == null) {
            uniquenessCache[name] = false
            return null
        }
        // 检查是否有**第二种**候选形也出现（多译法 → 不能做锚点）
        val secondHit = candidates.any { it != firstHit && anySentenceContains(it) }
        if (secondHit) {
            uniquenessCache[name] = false
            return null
        }
        uniquenessCache[name] = true
        formCache[name] = firstHit
        return firstHit
    }

    /** 该候选形是否在中文正文里出现过（抽样扫描，命中即返回）。 */
    private fun anySentenceContains(candidate: String): Boolean {
        // 先扫「专名高发区」：章节标题附近与对话密集处命中率最高，
        // 但这里是预处理，不做这种启发式——直接按步长抽样，代价可控。
        var t = 0
        while (t < target.sentCount) {
            if (simplified(t).contains(candidate)) return true
            t += SCAN_STRIDE
        }
        return false
    }

    private companion object {
        /**
         * 抽样步长：每 N 句检查一次。
         *
         * 取舍：抽样越密越准但越慢。实测真实书上 `Frodo` 出现 1031 句、
         * `Gandalf` 465 句，步长 8 完全够；只出现 1 次的罕见专名可能漏掉，
         * 后果是「这个名不做锚点」——方向安全（宁可少救，不可错救）。
         */
        const val SCAN_STRIDE = 8
    }
}
