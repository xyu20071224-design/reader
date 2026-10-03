package com.linguareader.shared.reader

import com.linguareader.shared.data.SavedWord

/**
 * 阅读页词面标记的载荷：把生词本折算成注入 WebView 的两份「形态表」。
 *
 * - [forms]：全部生词形态（headword + 正文里见过的表面形），画点状下划线（.lr-saved-word）。
 * - [dueForms]：其中已到复习时间的部分，画实线加粗下划线（.lr-due-word），两者同章共存。
 *
 * 抽成纯 Kotlin 是为了可单测：到期口径必须与阅读页/生词本页完全一致
 * （nextReviewAt <= now，等号计为到期）；而「展开 headword + surfaceForms、去重、
 * 按上限截断」这套字符串处理原本内联在 Composable 里，既测不到，也容易与 JS 侧
 * 的上限静默漂移（历史上就出过「按全量算 key、只注入前 300/600 个」的错）。
 */
data class SavedWordMarks(
    val forms: List<String>,
    val dueForms: List<String>
) {
    companion object {
        /**
         * @param savedWords 生词本全量（全局按 headword 去重，见 VocabularyRepository）。
         * @param now 判定到期的墙钟毫秒（阅读页传 nowTick，30s 刷新一次）。
         * @param limit 单份形态表上限，默认与 [ReaderScripts.MAX_SAVED_WORD_FORMS] 同源。
         */
        fun of(
            savedWords: List<SavedWord>,
            now: Long,
            limit: Int = ReaderScripts.MAX_SAVED_WORD_FORMS
        ): SavedWordMarks {
            if (savedWords.isEmpty() || limit <= 0) return SavedWordMarks(emptyList(), emptyList())
            val forms = LinkedHashSet<String>()
            val dueForms = LinkedHashSet<String>()
            for (word in savedWords) {
                // 与 ReaderScreen.dueWords / VocabularyScreen.dueWords 同口径：<= now 即到期。
                val isDue = word.nextReviewAt <= now
                val variants = ArrayList<String>(1 + word.surfaceForms.size)
                variants.add(word.headword)
                variants.addAll(word.surfaceForms)
                for (raw in variants) {
                    // 顺序保持生词本原序（等价于旧的 flatMap），LinkedHashSet 只去重不重排。
                    val form = raw.trim()
                    if (form.isEmpty()) continue
                    forms.add(form)
                    if (isDue) dueForms.add(form)
                }
            }
            return SavedWordMarks(
                forms = forms.take(limit),
                dueForms = dueForms.take(limit)
            )
        }
    }
}
