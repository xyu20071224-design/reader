package com.linguareader.shared.ui.state

import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.ReviewReminders
import com.linguareader.shared.data.SavedWord

/**
 * 生词本 / 复习状态：词表 + 复习节奏 + 提醒渠道。
 *
 * 纯 Kotlin，可单测。复习节奏与阅读页共读同一份（[ReaderState.reviewPreset]），
 * 这是**有意的重叠**：切片是同一份状态的投影，不是各自持有一份拷贝。
 */
data class VocabState(
    val savedWords: List<SavedWord> = emptyList(),
    /** 内置复习节奏；null = 用自定义 [customReview]。 */
    val reviewPreset: ReviewMode? = ReviewMode.DEFAULT,
    val customReview: ReviewPace = ReviewPace.defaultCustom(),
    val reminders: ReviewReminders = ReviewReminders.DEFAULT
) {
    /** 生效的复习节奏：预设优先，自定义兜底。 */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview
}
