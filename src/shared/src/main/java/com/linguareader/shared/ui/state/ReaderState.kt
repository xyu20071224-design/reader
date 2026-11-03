package com.linguareader.shared.ui.state

import com.linguareader.shared.ai.AiSettings
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.ReviewPace
import com.linguareader.shared.data.ReviewReminders
import com.linguareader.shared.data.SavedWord

/**
 * 阅读页状态：阅读页真正需要的那一份切片。
 *
 * 现状 `ReaderScreen` 从全局状态里拿 6 个复习/AI 字段（`MainActivity.kt:214-229`）。
 * 阅读页**自己的**位置与弹层状态**不在这里**——它们已经有专门的、带单测的状态机
 * `app/reader/ReaderScreenState.kt`（`ReaderPosition` / `ReaderOverlays`），
 * 那边是可 Bundle 化的界面状态，这边是业务数据。两者不合并：一个要跨旋转恢复，
 * 一个跟着 ViewModel 走。
 *
 * 纯 Kotlin，可单测。
 */
data class ReaderState(
    /** 当前打开的书；null = 没在读书。 */
    val book: Book? = null,
    /** 查词/整句翻译/术语表用得到的 AI 配置。 */
    val aiSettings: AiSettings = AiSettings(),
    /** 已有生词（正文里据此画已收藏标记）。 */
    val savedWords: List<SavedWord> = emptyList(),
    /** 内联复习设置：与生词本共用同一份（切片允许重叠，见包说明）。 */
    val reviewPreset: ReviewMode? = ReviewMode.DEFAULT,
    val customReview: ReviewPace = ReviewPace.defaultCustom(),
    val reminders: ReviewReminders = ReviewReminders.DEFAULT
) {
    /** 生效的复习节奏：预设优先，自定义兜底。与 `AppUiState.reviewPace` 同一算式。 */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview

    val isOpen: Boolean get() = book != null
}
