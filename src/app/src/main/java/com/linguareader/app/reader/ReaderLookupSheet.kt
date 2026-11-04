package com.linguareader.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.ai.SentenceTranslatorFactory
import com.linguareader.app.components.LinguaIconTextButton
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.PartOfSpeech
import com.linguareader.app.data.ReaderFont
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.WordLookup
import com.linguareader.app.reader.EpubPage
import com.linguareader.app.reader.LocusOrigin
import com.linguareader.app.reader.ReaderBackAction
import com.linguareader.app.reader.ReaderContentsSheet
import com.linguareader.app.reader.ReaderController
import com.linguareader.app.reader.ReaderLookupSession
import com.linguareader.app.reader.ReaderLookupSheet
import com.linguareader.app.reader.ReaderOverlays
import com.linguareader.app.reader.ReaderPageJumpDialog
import com.linguareader.app.reader.ReaderPosition
import com.linguareader.app.reader.ReaderScripts
import com.linguareader.app.reader.ReaderSettingsSheet
import com.linguareader.app.reader.SentenceTranslationCache
import com.linguareader.app.res.resolve
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMatchLevel
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.app.tts.TtsPlaybackState
import com.linguareader.shared.reader.SavedWordMarks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import com.linguareader.app.UiTags
import com.linguareader.app.Accent
import com.linguareader.app.Ink
import com.linguareader.app.InkSoft
import com.linguareader.app.InkFaint
import com.linguareader.app.Paper
import com.linguareader.app.CardSurface
import com.linguareader.app.Danger
import com.linguareader.app.R
import com.linguareader.app.SettingsStatus
import com.linguareader.app.SettingsStatusText

/**
 * 查词面板（从 ReaderScreen 抽出，P3 最后一块）。
 *
 * **为什么抽出来**：它是阅读页最复杂的交互区（点词 → 弹面板 → 释义 / AI / 译文三个 Tab
 * + 多段条件渲染 + 53 处硬编码 dp），长期让 `ReaderScreen.kt` 停在 1600 行以上。
 *
 * **为什么可以独立**：它不持有状态（所有数据由参数传入）、不碰 `ReaderController` /
 * `window.lr*` 协议 / `ReaderBridge` 回调 / TTS 高亮——它只负责「把已经拿到的数据画出来」
 * 和「把用户的动作回调出去」。
 *
 * **样式 token 化（已完成）**：dp 53 → **0**、`MaterialTheme.typography` 28 → **0**、
 * 旧调色板 55 → **0**。与结构搬迁**分成了两笔**（搬迁的判据是逐行等价，改样式会破坏
 * 那个判据）——这正是上一版 KDoc 里要求的做法。
 *
 * **配色口径**：本面板全部取 [LocalDesignTokens]（外壳皮肤语义）。它**没有**任何颜色
 * 来自 `ReaderTheme`——查词面板是浮在正文之上的一层纸，跟着皮肤走才能与书架、其余
 * 弹层保持一致。这与顶栏/底栏/听书条刻意跟 `ReaderTheme` 是**相反**的取舍，
 * 因为后三者是「贴着正文的一条栏」，而本面板不是。
 *
 * ⚠️ **待真机验证**：渲染台只能覆盖 Compose 静态排版，以下一条都测不到——
 * 点词弹出的手感、三个 Tab 的切换、整句翻译/重翻后的刷新与行内反馈、
 * 长释义的滚动、以及深色皮肤下的实际对比度。
 */
/**
 * 译本对照的展示文本：词级对照命中时把中文句里对应的词着色加粗；未命中（或
 * 偏移越界）就原样显示整句 —— 宁可不高亮，不可错标。
 */
@Composable
internal fun highlightedTranslation(result: TranslationLookupResult): AnnotatedString {
    val text = result.chinese
    val alignment = result.wordAlignment
    if (alignment == null ||
        alignment.start < 0 ||
        alignment.endExclusive > text.length ||
        alignment.start >= alignment.endExclusive
    ) {
        return AnnotatedString(text)
    }
    val t = LocalDesignTokens.current
    val accent = t.colors.accent
    return buildAnnotatedString {
        append(text.substring(0, alignment.start))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(text.substring(alignment.start, alignment.endExclusive))
        }
        append(text.substring(alignment.endExclusive))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderLookupSheet(
    lookup: WordLookup,
    entry: ContextualDictionaryEntry?,
    relatedPhrase: ContextualDictionaryEntry?,
    loading: Boolean,
    saveStatus: SettingsStatus?,
    aiContext: AiLookupResult?,
    aiLoading: Boolean,
    aiFailed: Boolean,
    aiRemoteFailed: Boolean,
    sentenceTranslationReady: Boolean,
    hasTranslation: Boolean,
    translation: TranslationLookupResult?,
    translationLoading: Boolean,
    sentenceTranslation: SentenceTranslationResult?,
    sentenceTranslationError: String?,
    sentenceTranslationLoading: Boolean,
    isSaved: Boolean,
    isPhraseView: Boolean,
    showReviewEntry: Boolean,
    /** 句级重翻入口：AI 可用 + 当前命中是句级时才出现。 */
    retranslateAvailable: Boolean,
    retranslateLoading: Boolean,
    /** 每次重翻结束（无论成败）自增；编辑态据此折叠并清空反馈。 */
    retranslateDoneTick: Int,
    onRetranslate: (String) -> Unit,
    onReviewSaved: () -> Unit,
    onShowPhrase: () -> Unit,
    onShowWord: () -> Unit,
    onSpeak: () -> Unit,
    onToggleSave: () -> Unit,
    onTranslateSentence: () -> Unit,
    onDismiss: () -> Unit
) {
    // 设计 token：本面板的颜色全部是「外壳」语义。
    // 它不像顶栏/底栏/听书条那样贴正文——查词面板是浮在正文之上的一层纸，
    // 跟着皮肤走才能与书架、其余弹层保持一致（阅读主题只管正文那一层）。
    val t = LocalDesignTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = t.colors.paper,
        modifier = Modifier.testTag(UiTags.READER_LOOKUP_SHEET)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = t.spacing.l)
                .padding(bottom = t.spacing.xl)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    lookup.word,
                    style = t.typography.display,
                    fontWeight = FontWeight.SemiBold
                )
                if (!entry?.phonetic.isNullOrBlank()) {
                    Spacer(Modifier.width(t.spacing.s))
                    Text(
                        "/${entry?.phonetic}/",
                        // 5-6：音标属次级文字，原 .58 在浅色最弱表面 3.91:1
                        color = t.colors.inkSoft,
                        modifier = Modifier.padding(bottom = t.spacing.xs)
                    )
                }
            }
            // matchedPhrase 现在是跨模块（:shared）属性，Kotlin 不对其做智能转换，
            // 先捕获到局部量（语义与原来的 entry.matchedPhrase 完全一致）。
            val matchedPhrase = entry?.matchedPhrase
            if (matchedPhrase != null) {
                Text(
                    if (isPhraseView) {
                        stringResource(R.string.reader_phrase_view, matchedPhrase)
                    } else {
                        stringResource(R.string.reader_phrase_first, matchedPhrase)
                    },
                    style = t.typography.label,
                    color = t.colors.accent
                )
            } else if (entry != null && !entry.headword.equals(lookup.word, ignoreCase = true)) {
                Text(
                    stringResource(R.string.reader_lemma, entry.headword),
                    style = t.typography.label,
                    color = t.colors.accent
                )
            }
            if (relatedPhrase != null && !isPhraseView) {
                Row(
                    Modifier.fillMaxWidth().padding(top = t.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.reader_related_phrase,
                            relatedPhrase.matchedPhrase ?: relatedPhrase.headword
                        ),
                        style = t.typography.label,
                        color = t.colors.ink.copy(alpha = InkSecondaryAlpha),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onShowPhrase) { Text(stringResource(R.string.reader_view)) }
                }
            }
            if (isPhraseView) {
                TextButton(onClick = onShowWord) { Text(stringResource(R.string.reader_back_to_word)) }
            }
            if (entry != null && entry.inferredPartOfSpeech != PartOfSpeech.UNKNOWN) {
                Text(
                    stringResource(
                        R.string.reader_pos_inferred,
                        stringResource(entry.inferredPartOfSpeech.labelRes.resolve())
                    ),
                    style = t.typography.label,
                    // 5-6：原 .56 在浅色最弱表面（cardSurface）仅 3.70:1，不足 4.5:1
                    color = t.colors.inkSoft
                )
            }
            if (entry != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    TextButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(t.iconSize))
                        Spacer(Modifier.width(t.spacing.xs))
                        Text(stringResource(R.string.common_speak))
                    }
                    if (isSaved && showReviewEntry) {
                        TextButton(onClick = onReviewSaved) {
                            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(t.iconSize))
                            Spacer(Modifier.width(t.spacing.xs))
                            Text(stringResource(R.string.common_review))
                        }
                    }
                    TextButton(onClick = onToggleSave) {
                        Icon(
                            Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(t.iconSize),
                            tint = if (isSaved) t.colors.accent else t.colors.inkSoft
                        )
                        Spacer(Modifier.width(t.spacing.xs))
                        Text(
                            if (isSaved) stringResource(R.string.reader_remove_from_vocab)
                            else stringResource(R.string.reader_add_to_vocab)
                        )
                    }
                }
            }
            // 收藏/移出生词的行内反馈：弹层开着时全局 Snackbar 被盖住，只能画在面板里。
            if (saveStatus != null) {
                Spacer(Modifier.height(t.spacing.xs))
                SettingsStatusText(saveStatus)
            }
            if (aiLoading) {
                Spacer(Modifier.height(t.spacing.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(t.spacing.xxl + t.spacing.l)
                            .height(t.spacing.xs),
                        color = t.colors.accent,
                        trackColor = t.colors.accent.copy(alpha = ProgressTrackAlpha)
                    )
                    Spacer(Modifier.width(t.spacing.s))
                    Text(
                        stringResource(R.string.reader_ai_loading),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                }
            }
            // 远程 AI 尝试失败：无论本地兜底有没有给出结果，都要留下可见痕迹
            // （#3：Key/端点配错后点词「毫无动静」的根因就是失败被静默吞掉）。
            if (!aiLoading && aiRemoteFailed) {
                Spacer(Modifier.height(t.spacing.s))
                Text(
                    stringResource(
                        if (aiContext != null) R.string.reader_ai_lookup_degraded
                        else R.string.reader_ai_lookup_failed
                    ),
                    style = t.typography.label,
                    color = t.colors.danger
                )
            }
            if (!aiLoading && aiFailed && aiContext == null && !aiRemoteFailed) {
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.reader_ai_lookup_failed),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
            }
            if (!aiLoading && aiContext != null) {
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.reader_ai_context_title, aiContext.source),
                    style = t.typography.label,
                    color = t.colors.accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    aiContext.contextualMeaning,
                    style = t.typography.body,
                    fontWeight = FontWeight.Medium
                )
                aiContext.phrase?.let {
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        stringResource(R.string.reader_ai_phrase, it),
                        style = t.typography.label,
                        color = t.colors.inkSoft
                    )
                }
                if (aiContext.explanation.isNotBlank()) {
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        aiContext.explanation,
                        style = t.typography.body,
                        color = t.colors.ink.copy(alpha = InkSecondaryAlpha)
                    )
                }
            }
            if (translationLoading) {
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.reader_translation_loading),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
            }
            var pairingExpanded by remember(lookup.word, lookup.sentence) { mutableStateOf(false) }
            // 句级重翻的编辑态：反馈文本与展开折叠都是纯 UI 状态，放这里；
            // doneTick 参与 key，重翻结束（成败皆然）自动折叠清空。
            var retranslateEditing by remember(lookup.word, lookup.sentence, retranslateDoneTick) {
                mutableStateOf(false)
            }
            var retranslateFeedback by remember(lookup.word, lookup.sentence, retranslateDoneTick) {
                mutableStateOf("")
            }
            translation?.let { result ->
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.reader_translation_title),
                    style = t.typography.label,
                    color = t.colors.accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    highlightedTranslation(result),
                    style = t.typography.body,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    stringResource(
                        if (result.matchLevel == TranslationMatchLevel.SENTENCE) {
                            R.string.reader_translation_meta_sentence
                        } else {
                            R.string.reader_translation_meta_paragraph
                        },
                        result.translationTitle,
                        (result.confidence * 100).roundToInt()
                    ),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                if (pairingExpanded) {
                    // 整句对照：并排给出配对到的英文原句与译文段落，便于判断配对是否正确。
                    Spacer(Modifier.height(t.spacing.s))
                    Text(
                        stringResource(R.string.reader_translation_english, result.english),
                        style = t.typography.body,
                        color = t.colors.ink.copy(alpha = InkTertiaryAlpha)
                    )
                    if (result.chineseParagraph.isNotBlank() &&
                        result.chineseParagraph != result.chinese
                    ) {
                        Spacer(Modifier.height(t.spacing.xs))
                        Text(
                            stringResource(
                                R.string.reader_translation_paragraph,
                                result.chineseParagraph
                            ),
                            style = t.typography.body,
                            color = t.colors.ink.copy(alpha = InkTertiaryAlpha)
                        )
                    }
                }
            }
            // 配了译本却没查到：直说是「这段/这章没参与对齐」，而不是让面板凭空少一块。
            if (hasTranslation && translation == null && !translationLoading) {
                Spacer(Modifier.height(t.spacing.m))
                Text(
                    stringResource(R.string.reader_translation_miss),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
            }
            if (sentenceTranslationReady || translation != null) {
                Spacer(Modifier.height(t.spacing.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 云端整句翻译只在真配好了提供方时出现：没配就别摆一个按不动的按钮。
                    if (sentenceTranslationReady) {
                        TextButton(
                            onClick = onTranslateSentence,
                            enabled = !sentenceTranslationLoading
                        ) {
                            Icon(
                                Icons.Filled.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(t.iconSize),
                                tint = t.colors.accent
                            )
                            Spacer(Modifier.width(t.spacing.xs))
                            Text(stringResource(R.string.translate_sentence), color = t.colors.accent)
                        }
                        if (sentenceTranslationLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(t.spacing.xxl + t.spacing.l)
                                    .height(t.spacing.xs),
                                color = t.colors.accent,
                                trackColor = t.colors.accent.copy(alpha = ProgressTrackAlpha)
                            )
                        }
                    }
                    // 有中文译本时，用完全离线的「整句对照」顶上这个位置。
                    if (translation != null) {
                        TextButton(onClick = { pairingExpanded = !pairingExpanded }) {
                            Icon(
                                Icons.Filled.CompareArrows,
                                contentDescription = null,
                                modifier = Modifier.size(t.iconSize),
                                tint = t.colors.accent
                            )
                            Spacer(Modifier.width(t.spacing.xs))
                            Text(
                                stringResource(
                                    if (pairingExpanded) R.string.reader_translation_collapse
                                    else R.string.reader_translation_expand
                                ),
                                color = t.colors.accent
                            )
                        }
                    }
                    // 句级定点重翻：AI 可用且命中是句级时才出现（段落级兜底没有
                    // 确定的句对条目，不开放）。
                    val retranslateHit = translation
                    if (retranslateHit != null &&
                        retranslateHit.matchLevel == TranslationMatchLevel.SENTENCE &&
                        retranslateAvailable
                    ) {
                        TextButton(
                            onClick = { retranslateEditing = !retranslateEditing },
                            enabled = !retranslateLoading
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(t.iconSize),
                                tint = t.colors.accent
                            )
                            Spacer(Modifier.width(t.spacing.xs))
                            Text(
                                stringResource(R.string.reader_translation_retranslate),
                                color = t.colors.accent
                            )
                        }
                    }
                }
                // 重翻编辑态：反馈可留空（= 原样重试换一次结果）；进行态保留旧译文。
                if (retranslateEditing &&
                    translation?.matchLevel == TranslationMatchLevel.SENTENCE &&
                    retranslateAvailable
                ) {
                    Spacer(Modifier.height(t.spacing.s))
                    if (retranslateLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                modifier = Modifier.width(t.spacing.xxl + t.spacing.l).height(t.spacing.xs),
                                color = t.colors.accent,
                                trackColor = t.colors.accent.copy(alpha = ProgressTrackAlpha)
                            )
                            Spacer(Modifier.width(t.spacing.s))
                            Text(
                                stringResource(R.string.reader_translation_retranslating),
                                style = t.typography.label,
                                color = t.colors.inkSoft
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = retranslateFeedback,
                            onValueChange = { retranslateFeedback = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_hint),
                                    style = t.typography.caption
                                )
                            },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { retranslateEditing = false }) {
                                Text(stringResource(R.string.common_cancel), color = t.colors.inkSoft)
                            }
                            TextButton(onClick = { onRetranslate(retranslateFeedback) }) {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_go),
                                    color = t.colors.accent
                                )
                            }
                        }
                    }
                }
            }
            sentenceTranslationError?.let { message ->
                Spacer(Modifier.height(t.spacing.s))
                Text(
                    message,
                    style = t.typography.body,
                    color = t.colors.danger
                )
            }
            sentenceTranslation?.let { result ->
                Spacer(Modifier.height(t.spacing.s))
                Text(
                    stringResource(R.string.translate_sentence_result, result.provider, result.text),
                    style = t.typography.body,
                    color = t.colors.ink
                )
            }
            Spacer(Modifier.height(t.spacing.m))
            // 中文句里高亮那个词来自哪条义项（`WordAligner` 记下的 `sourceSense`，与
            // 面板 `sense.text` 是同一个串）：标出该行、并用与高亮词完全相同的
            // 强调色 + 粗体，用户才能把「高亮的词」与「具体义项」互相指认（Q1-t09 ④）。
            val alignedSense = translation?.wordAlignment?.sourceSense
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.colors.accent)
                entry != null -> {
                    entry.senses.take(8).forEachIndexed { index, sense ->
                        val preferred = sense.contextPreferred && index == 0
                        // 是来源义项但不是「本句优先」时才占用标号位：两枚标记都不丢。
                        val aligned = alignedSense != null && sense.text == alignedSense
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = t.spacing.xs),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                when {
                                    aligned && !preferred -> stringResource(R.string.reader_sense_aligned)
                                    preferred -> stringResource(R.string.reader_sense_preferred)
                                    else -> "•"
                                },
                                style = t.typography.label,
                                color = if (aligned || preferred) t.colors.accent
                                // 5-6：项目符号也是文字，原 .38 在浅/深最弱表面仅 2.27/3.01:1
                                else t.colors.inkFaint,
                                modifier = Modifier.width(
                                    if (aligned || preferred) t.spacing.xl * 2
                                    else t.spacing.gutter
                                )
                            )
                            Text(
                                sense.text,
                                style = t.typography.body,
                                // 与中文句里被着色的词同一套样式（t.colors.accent + Bold）。
                                color = if (aligned) t.colors.accent else Color.Unspecified,
                                fontWeight = if (aligned) FontWeight.Bold else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (entry.senses.isEmpty()) {
                        Text(
                            stringResource(R.string.reader_no_senses),
                            color = t.colors.inkSoft
                        )
                    }
                    if (entry.definitions.isNotEmpty()) {
                        Spacer(Modifier.height(t.spacing.s))
                        Text(
                            entry.definitions.take(2).joinToString("\n"),
                            color = t.colors.inkSoft,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> Text(
                    stringResource(R.string.reader_word_not_found),
                    color = t.colors.inkSoft
                )
            }
            Spacer(Modifier.height(t.spacing.m))
            Text(
                stringResource(R.string.reader_current_context),
                style = t.typography.label,
                color = t.colors.accent
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                lookup.sentence.ifBlank { lookup.paragraph },
                fontFamily = FontFamily.Serif,
                lineHeight = t.typography.body.lineHeight
            )
            Spacer(Modifier.height(t.spacing.m))
            Text(
                stringResource(R.string.reader_dict_source),
                style = t.typography.label,
                // 5-6：原 .45 在浅/深最弱表面仅 2.71/3.69:1；实色后 5.56/4.63:1
                color = t.colors.inkFaint
            )
        }
    }
}

// ── 本面板专有的 alpha 值 ──────────────────────────────────────────────
//
// token 里没有 alpha 梯，这些值保留原样（**一个都没改**：改值属于视觉改版，
// 不属于样式迁移），但给它们名字并写明含义，而不是散在调用点里。

/** 相关短语行的墨色透明度（次级于释义正文，但仍需可读）。 */
private const val InkSecondaryAlpha = .72f

/** 整句对照里英文/中文段落的墨色透明度（再压一档，与主译文拉开层级）。 */
private const val InkTertiaryAlpha = .78f

/** 加载进度条轨道相对强调色的透明度。 */
private const val ProgressTrackAlpha = .12f
