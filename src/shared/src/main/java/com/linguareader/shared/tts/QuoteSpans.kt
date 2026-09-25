package com.linguareader.shared.tts

/**
 * 引语区间的统一扫描器——发言/旁白分离的公共底层。
 *
 * ⚠️ **改这里（或依赖它的 [TtsChapter] 片段化）必须 bump [TtsPipelineContract.VERSION]**：
 * 片段划分决定缓存键里的段号，改了不 bump 就会播出错位的音频。
 *
 * [SpeakerRuleTagger]（规则打标，:app）与 [TtsChapter]（句内片段化）都要回答
 * 「这些字符在不在引语里」，此前各自实现一份；现在共用这里，避免两条口径漂移。
 *
 * 坐标不变量：[normalizeQuotes] 是逐字符等长替换（弯引号 → ASCII `"`，长度不变），
 * 替换前后下标一一对应，因此这里返回的区间可直接用于**原始**块文本的 substring
 * 与 indexOf 定位。该不变量由 TtsChapterUtteranceTest 锁定。
 */
object QuoteSpans {

    /** Curly double/single quotes collapse onto ASCII `"`; ASCII 撇号有意保留
     *  （所有格/缩写 don't、'tis 不能被当成引号）。
     *
     *  **词内 U+2019 与 ASCII `'` 同等对待**：`her’s` / `don’t` / `O’Brien` 里的 `’`
     *  是撇号、不是引号，折成 `"` 会凭空开出一个未闭合引语区间（issue #2：
     *  引语区间内 [TtsChapter] 裂句 + `SpeakerRuleTagger` 误判 dialogue → 撇号处断读，
     *  未闭合状态还会 carry 进下一块、污染下一段的引号配对）。
     *  词尾的 `’` 保留引号语义（`‘Stop’` 要成对），所以成对弯单引号行为不变。
     *  本函数仍是**逐字符等长替换**——坐标不变量不受影响。 */
    fun normalizeQuotes(block: String): String {
        val out = StringBuilder(block.length)
        for ((index, char) in block.withIndex()) {
            val quote = char == '‘' || char == '“' || char == '”' ||
                (char == '’' && !isWordInternalApostrophe(block, index))
            out.append(if (quote) '"' else char)
        }
        return out.toString()
    }

    /** `’` 两侧都是词字符（`her’s`、`don’t`）时它是撇号，不是引号。 */
    private fun isWordInternalApostrophe(block: String, index: Int): Boolean =
        index > 0 && index < block.lastIndex &&
            block[index - 1].isLetterOrDigit() && block[index + 1].isLetterOrDigit()

    /**
     * 每个块的引语区间（原块文本坐标，闭区间）。
     *
     * 块尾未闭合的引语延伸到块尾，并把「处于引语内」的状态带入下一块
     * （跨段对话：`He said, "I am coming.` / `I really am." Then he left.`）。
     */
    fun spans(blocks: List<String>): List<List<IntRange>> {
        val result = mutableListOf<List<IntRange>>()
        var inQuote = false
        for (block in blocks) {
            val text = normalizeQuotes(block)
            val spans = mutableListOf<IntRange>()
            var open = if (inQuote) 0 else -1
            for ((index, char) in text.withIndex()) {
                if (char != '"') continue
                if (open < 0) {
                    open = index
                } else {
                    spans += open..index
                    open = -1
                }
            }
            if (open >= 0 && text.isNotEmpty()) {
                spans += open..text.lastIndex
                inQuote = true
            } else {
                // 空块在引语中间：carry 原样传入下一块。
                inQuote = open >= 0
            }
            result += spans
        }
        return result
    }
}
