package com.linguareader.app.res

import androidx.annotation.StringRes
import com.linguareader.app.R
import com.linguareader.shared.res.SharedString

/**
 * Android 端的 [SharedString] 解析表（共享代码资源间接层，见 shared/res/SharedString.kt）。
 *
 * 穷举 when：`:shared` 新增键时这里不补就编译失败，杜绝「运行时才炸的资源空洞」。
 * UI 侧用法：`stringResource(theme.labelRes.resolve())`。
 */
@StringRes
fun SharedString.resolve(): Int = when (this) {
    SharedString.POS_NOUN -> R.string.pos_noun
    SharedString.POS_VERB -> R.string.pos_verb
    SharedString.POS_ADJECTIVE -> R.string.pos_adjective
    SharedString.POS_ADVERB -> R.string.pos_adverb
    SharedString.POS_PRONOUN -> R.string.pos_pronoun
    SharedString.POS_PREPOSITION -> R.string.pos_preposition
    SharedString.POS_CONJUNCTION -> R.string.pos_conjunction
    SharedString.POS_NUMERAL -> R.string.pos_numeral
    SharedString.POS_ARTICLE -> R.string.pos_article
    SharedString.POS_INTERJECTION -> R.string.pos_interjection
    SharedString.POS_AUXILIARY -> R.string.pos_auxiliary
    SharedString.POS_ABBREVIATION -> R.string.pos_abbreviation
    SharedString.POS_UNKNOWN -> R.string.pos_unknown
    SharedString.THEME_PAPER -> R.string.reader_theme_paper
    SharedString.THEME_WHITE -> R.string.reader_theme_white
    SharedString.THEME_SEPIA -> R.string.reader_theme_sepia
    SharedString.THEME_GREEN -> R.string.reader_theme_green
    SharedString.THEME_MORANDI -> R.string.reader_theme_morandi
    SharedString.THEME_DARK -> R.string.reader_theme_dark
    SharedString.THEME_AMOLED -> R.string.reader_theme_amoled
    SharedString.FONT_SERIF -> R.string.reader_font_serif
    SharedString.FONT_SANS -> R.string.reader_font_sans
    SharedString.FONT_MONO -> R.string.reader_font_mono
    SharedString.FONT_CONDENSED -> R.string.reader_font_condensed
    SharedString.FONT_CURSIVE -> R.string.reader_font_cursive
    SharedString.REVIEW_MODE_IMMERSIVE -> R.string.review_mode_immersive
    SharedString.REVIEW_MODE_IMMERSIVE_DESC -> R.string.review_mode_immersive_desc
    SharedString.REVIEW_MODE_GENTLE -> R.string.review_mode_gentle
    SharedString.REVIEW_MODE_GENTLE_DESC -> R.string.review_mode_gentle_desc
    SharedString.REVIEW_MODE_DILIGENT -> R.string.review_mode_diligent
    SharedString.REVIEW_MODE_DILIGENT_DESC -> R.string.review_mode_diligent_desc
    SharedString.REVIEW_PACE_CUSTOM -> R.string.review_pace_custom
}
