package com.linguareader.shared.res

/**
 * 共享代码里的「字符串资源引用」间接层（桌面迁移方案 §5.1 决策 4）。
 *
 * `:shared` 拿不到 `:app` 生成的 `R` 类，但部分模型（词性、阅读主题、字体）天生
 * 需要一个「用户可见名称」的引用。这里只定义**符号**，不携带任何平台的资源 id；
 * 每个平台在自己的壳模块里提供穷举 `when` 的 resolve 映射：
 * - Android：`com.linguareader.app.res.AndroidStrings.resolve()` → `R.string.*`
 * - 桌面（M2+）：对端映射进 Compose Multiplatform 资源体系（`MR.strings.*`）
 *
 * 新增键时两端映射都会被编译器强制补全，不存在「共享代码引用了某端没有的资源」。
 * 仅收「由共享模型携带」的字符串；纯 UI 文案继续走各端自己的资源直引。
 */
enum class SharedString {
    // 词性（PartOfSpeech.labelRes，原 R.string.pos_*）
    POS_NOUN,
    POS_VERB,
    POS_ADJECTIVE,
    POS_ADVERB,
    POS_UNKNOWN,

    // 阅读主题显示名（ReaderTheme.labelRes，原 R.string.reader_theme_*）
    THEME_PAPER,
    THEME_WHITE,
    THEME_SEPIA,
    THEME_GREEN,
    THEME_MORANDI,
    THEME_DARK,
    THEME_AMOLED,

    // 字体显示名（ReaderFont.labelRes，原 R.string.reader_font_*）
    FONT_SERIF,
    FONT_SANS,
    FONT_MONO,
    FONT_CONDENSED,
    FONT_CURSIVE,

    // 复习节奏显示名（ReviewMode/ReviewPace，原 R.string.review_mode_* / review_pace_custom）
    REVIEW_MODE_IMMERSIVE,
    REVIEW_MODE_IMMERSIVE_DESC,
    REVIEW_MODE_GENTLE,
    REVIEW_MODE_GENTLE_DESC,
    REVIEW_MODE_DILIGENT,
    REVIEW_MODE_DILIGENT_DESC,
    REVIEW_PACE_CUSTOM
}
