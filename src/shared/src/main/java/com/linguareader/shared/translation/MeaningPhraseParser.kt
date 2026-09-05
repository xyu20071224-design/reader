package com.linguareader.shared.translation

/**
 * ECDICT translation 字段 → 词义锚短语集合（纯函数，可 JVM 单测）。
 *
 * 规则：
 *  - 按行解析，词性前缀白名单：n. / v. / vt. / vi. / a. / adv. / adj.（实义词性）；
 *    prep. / conj. / pron. / num. / art. / int. / aux. / det. 等功能词性整行跳过；
 *  - 行内容取全部 2–4 字连续汉字子串（与 [TranslationAligner] 中文侧子串集合同构）；
 *  - 黑名单短语（高频虚词/泛词）剔除。
 */
object MeaningPhraseParser {

    /** 保留的实义词性前缀（ECDICT 行首标记）。 */
    private val POS_KEEP = setOf("n.", "v.", "vt.", "vi.", "a.", "adv.", "adj.")

    /** 功能词性前缀：整行跳过。 */
    private val POS_SKIP = setOf("prep.", "conj.", "pron.", "num.", "art.", "int.", "aux.", "det.", "interj.")

    /** 高频虚词/泛词短语：作为锚点会到处假命中，必须剔除。 */
    private val STOP_PHRASES = setOf(
        "一个", "一种", "一些", "什么", "怎么", "为什么", "可以", "能够", "只有", "就是", "还是",
        "不是", "没有", "这个", "那个", "这样", "那样", "然后", "所以", "但是", "因为", "如果",
        "以及", "而且", "并且", "或者", "于是", "有关", "非常", "十分", "相当", "比较", "更加",
        "继续", "开始", "结束", "出现", "发生", "存在", "成为", "变成", "位于", "对于", "关于",
        "任何", "所有", "其他", "别的", "其中", "通常", "一般", "主要", "重要", "很大",
        "很小", "很多", "很少", "快点", "立刻", "马上", "有些", "许多", "多数", "其余",
        "等等", "例如", "比如", "无论", "不管", "虽然", "即使", "然而", "不过", "此外", "另外",
        "所谓", "以此", "由此", "因此", "因而", "从而", "某一种", "各个"
    )

    private val HAN_RUN = Regex("[\\u4e00-\\u9fa5]+")

    fun parse(translation: String): Set<String> {
        val out = HashSet<String>()
        for (rawLine in translation.split('\n')) {
            val line = rawLine.trim()
            if (line.length < 2) continue
            val pos = line.substringBefore(' ').substringBefore(',').substringBefore('，').substringBefore('、')
            if (pos in POS_SKIP) continue
            val body = if (pos in POS_KEEP && line.length > pos.length) {
                line.substring(pos.length).trim()
            } else line
            collect(body, out)
        }
        return out
    }

    private fun collect(body: String, out: MutableSet<String>) {
        val runs = HAN_RUN.findAll(body)
        for (run in runs) {
            val s = run.value
            for (i in 0 until s.length) {
                for (len in 2..4) {
                    if (i + len > s.length) break
                    val ph = s.substring(i, i + len)
                    if (ph !in STOP_PHRASES) out.add(ph)
                }
            }
        }
    }
}
