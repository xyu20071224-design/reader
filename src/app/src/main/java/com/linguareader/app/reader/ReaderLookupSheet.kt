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
 * **没做的事**：本文件里的硬编码 dp 仍是原值（53 处），`MaterialTheme.typography` 引用
 * 也仍是原样。**那次样式 token 化没有覆盖到这块**——如果要补，请在单独一笔里做并出图
 * 对比，不要和结构搬迁混在一起（搬迁的判据是逐行等价，改样式会破坏这个判据）。
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
    val accent = Accent
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Paper,
        modifier = Modifier.testTag(UiTags.READER_LOOKUP_SHEET)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 30.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    lookup.word,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!entry?.phonetic.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "/${entry?.phonetic}/",
                        // 5-6：音标属次级文字，原 .58 在浅色最弱表面 3.91:1
                        color = InkSoft,
                        modifier = Modifier.padding(bottom = 3.dp)
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
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent
                )
            } else if (entry != null && !entry.headword.equals(lookup.word, ignoreCase = true)) {
                Text(
                    stringResource(R.string.reader_lemma, entry.headword),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent
                )
            }
            if (relatedPhrase != null && !isPhraseView) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(
                            R.string.reader_related_phrase,
                            relatedPhrase.matchedPhrase ?: relatedPhrase.headword
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = Ink.copy(alpha = .72f),
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
                    style = MaterialTheme.typography.labelMedium,
                    // 5-6：原 .56 在浅色最弱表面（cardSurface）仅 3.70:1，不足 4.5:1
                    color = InkSoft
                )
            }
            if (entry != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_speak))
                    }
                    if (isSaved && showReviewEntry) {
                        TextButton(onClick = onReviewSaved) {
                            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_review))
                        }
                    }
                    TextButton(onClick = onToggleSave) {
                        Icon(
                            Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isSaved) Accent else InkSoft
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isSaved) stringResource(R.string.reader_remove_from_vocab)
                            else stringResource(R.string.reader_add_to_vocab)
                        )
                    }
                }
            }
            // 收藏/移出生词的行内反馈：弹层开着时全局 Snackbar 被盖住，只能画在面板里。
            if (saveStatus != null) {
                Spacer(Modifier.height(4.dp))
                SettingsStatusText(saveStatus)
            }
            if (aiLoading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(72.dp)
                            .height(4.dp),
                        color = Accent,
                        trackColor = Accent.copy(alpha = .12f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.reader_ai_loading),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                }
            }
            // 远程 AI 尝试失败：无论本地兜底有没有给出结果，都要留下可见痕迹
            // （#3：Key/端点配错后点词「毫无动静」的根因就是失败被静默吞掉）。
            if (!aiLoading && aiRemoteFailed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (aiContext != null) R.string.reader_ai_lookup_degraded
                        else R.string.reader_ai_lookup_failed
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Danger
                )
            }
            if (!aiLoading && aiFailed && aiContext == null && !aiRemoteFailed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.reader_ai_lookup_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
            }
            if (!aiLoading && aiContext != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_ai_context_title, aiContext.source),
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    aiContext.contextualMeaning,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                aiContext.phrase?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.reader_ai_phrase, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft
                    )
                }
                if (aiContext.explanation.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        aiContext.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.copy(alpha = .72f)
                    )
                }
            }
            if (translationLoading) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_loading),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
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
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    highlightedTranslation(result),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
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
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
                if (pairingExpanded) {
                    // 整句对照：并排给出配对到的英文原句与译文段落，便于判断配对是否正确。
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.reader_translation_english, result.english),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.copy(alpha = .78f)
                    )
                    if (result.chineseParagraph.isNotBlank() &&
                        result.chineseParagraph != result.chinese
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.reader_translation_paragraph,
                                result.chineseParagraph
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink.copy(alpha = .78f)
                        )
                    }
                }
            }
            // 配了译本却没查到：直说是「这段/这章没参与对齐」，而不是让面板凭空少一块。
            if (hasTranslation && translation == null && !translationLoading) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.reader_translation_miss),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft
                )
            }
            if (sentenceTranslationReady || translation != null) {
                Spacer(Modifier.height(12.dp))
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
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.translate_sentence), color = Accent)
                        }
                        if (sentenceTranslationLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(72.dp)
                                    .height(4.dp),
                                color = Accent,
                                trackColor = Accent.copy(alpha = .12f)
                            )
                        }
                    }
                    // 有中文译本时，用完全离线的「整句对照」顶上这个位置。
                    if (translation != null) {
                        TextButton(onClick = { pairingExpanded = !pairingExpanded }) {
                            Icon(
                                Icons.Filled.CompareArrows,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    if (pairingExpanded) R.string.reader_translation_collapse
                                    else R.string.reader_translation_expand
                                ),
                                color = Accent
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
                                modifier = Modifier.size(16.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.reader_translation_retranslate),
                                color = Accent
                            )
                        }
                    }
                }
                // 重翻编辑态：反馈可留空（= 原样重试换一次结果）；进行态保留旧译文。
                if (retranslateEditing &&
                    translation?.matchLevel == TranslationMatchLevel.SENTENCE &&
                    retranslateAvailable
                ) {
                    Spacer(Modifier.height(6.dp))
                    if (retranslateLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                modifier = Modifier.width(72.dp).height(4.dp),
                                color = Accent,
                                trackColor = Accent.copy(alpha = .12f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.reader_translation_retranslating),
                                style = MaterialTheme.typography.labelMedium,
                                color = InkSoft
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = retranslateFeedback,
                            onValueChange = { retranslateFeedback = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_hint),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { retranslateEditing = false }) {
                                Text(stringResource(R.string.common_cancel), color = InkSoft)
                            }
                            TextButton(onClick = { onRetranslate(retranslateFeedback) }) {
                                Text(
                                    stringResource(R.string.reader_translation_retranslate_go),
                                    color = Accent
                                )
                            }
                        }
                    }
                }
            }
            sentenceTranslationError?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Danger
                )
            }
            sentenceTranslation?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.translate_sentence_result, result.provider, result.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink
                )
            }
            Spacer(Modifier.height(14.dp))
            // 中文句里高亮那个词来自哪条义项（`WordAligner` 记下的 `sourceSense`，与
            // 面板 `sense.text` 是同一个串）：标出该行、并用与高亮词完全相同的
            // 强调色 + 粗体，用户才能把「高亮的词」与「具体义项」互相指认（Q1-t09 ④）。
            val alignedSense = translation?.wordAlignment?.sourceSense
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                entry != null -> {
                    entry.senses.take(8).forEachIndexed { index, sense ->
                        val preferred = sense.contextPreferred && index == 0
                        // 是来源义项但不是「本句优先」时才占用标号位：两枚标记都不丢。
                        val aligned = alignedSense != null && sense.text == alignedSense
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                when {
                                    aligned && !preferred -> stringResource(R.string.reader_sense_aligned)
                                    preferred -> stringResource(R.string.reader_sense_preferred)
                                    else -> "•"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (aligned || preferred) Accent
                                // 5-6：项目符号也是文字，原 .38 在浅/深最弱表面仅 2.27/3.01:1
                                else InkFaint,
                                modifier = Modifier.width(if (aligned || preferred) 64.dp else 20.dp)
                            )
                            Text(
                                sense.text,
                                style = MaterialTheme.typography.bodyLarge,
                                // 与中文句里被着色的词同一套样式（Accent + Bold）。
                                color = if (aligned) Accent else Color.Unspecified,
                                fontWeight = if (aligned) FontWeight.Bold else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (entry.senses.isEmpty()) {
                        Text(
                            stringResource(R.string.reader_no_senses),
                            color = InkSoft
                        )
                    }
                    if (entry.definitions.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            entry.definitions.take(2).joinToString("\n"),
                            color = InkSoft,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                else -> Text(
                    stringResource(R.string.reader_word_not_found),
                    color = InkSoft
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.reader_current_context),
                style = MaterialTheme.typography.labelLarge,
                color = Accent
            )
            Spacer(Modifier.height(5.dp))
            Text(
                lookup.sentence.ifBlank { lookup.paragraph },
                fontFamily = FontFamily.Serif,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.reader_dict_source),
                style = MaterialTheme.typography.labelSmall,
                // 5-6：原 .45 在浅/深最弱表面仅 2.71/3.69:1；实色后 5.56/4.63:1
                color = InkFaint
            )
        }
    }
}
