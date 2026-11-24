package com.linguareader.app.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.linguareader.app.R
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.WordLookup
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 点击生产查词面板，守住「词典未命中也能收藏」的入口。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class ReaderLookupVocabularyTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lookup = WordLookup("mithril", "A mithril coat.", "A mithril coat.", 2, 0f, 0f)

    private fun showLookup(
        entry: ContextualDictionaryEntry? = null,
        aiContext: AiLookupResult? = null,
        isSaved: Boolean = false,
        loading: Boolean = false,
        translation: com.linguareader.app.translation.TranslationLookupResult? = null,
        sentence: com.linguareader.app.ai.SentenceTranslationResult? = null,
        sentenceError: String? = null,
        changingVocabulary: Boolean = false,
        onToggleSave: () -> Unit
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDesignTokens provides SkinRegistry.toDesignTokens(SkinBuiltIns.light)
            ) {
                MaterialTheme {
                    ReaderLookupSheet(
                        lookup = lookup,
                        entry = entry,
                        relatedPhrase = null,
                        loading = loading,
                        saveStatus = null,
                        aiContext = aiContext,
                        aiLoading = false,
                        aiFailed = false,
                        aiRemoteFailed = false,
                        sentenceTranslationReady = false,
                        hasTranslation = false,
                        translation = translation,
                        translationLoading = false,
                        sentenceTranslation = sentence,
                        sentenceTranslationError = sentenceError,
                        sentenceTranslationLoading = false,
                        isSaved = isSaved,
                        isPhraseView = false,
                        showReviewEntry = true,
                        retranslateAvailable = false,
                        retranslateLoading = false,
                        retranslateDoneTick = 0,
                        onRetranslate = {},
                        onReviewSaved = {},
                        onShowPhrase = {},
                        onShowWord = {},
                        onSpeak = {},
                        onToggleSave = onToggleSave,
                        onTranslateSentence = {},
                        onDismiss = {},
                        changingVocabulary = changingVocabulary
                    )
                }
            }
        }
    }

    @Test fun dictionaryMeaningIsVisibleWithLongContextAndTranslation() {
        val meaning = "n. 秘银（词典释义）"
        val entry = ContextualDictionaryEntry(
            surfaceWord = "mithril", headword = "mithril", phonetic = "",
            senses = listOf(com.linguareader.app.data.DictionarySense(
                meaning, com.linguareader.app.data.PartOfSpeech.NOUN, false)),
            definitions = emptyList(), matchedPhrase = null,
            inferredPartOfSpeech = com.linguareader.app.data.PartOfSpeech.UNKNOWN)
        val translation = com.linguareader.app.translation.TranslationLookupResult(
            "title", "A mithril coat.", "一件秘银外衣。".repeat(80), "",
            com.linguareader.app.translation.TranslationMatchLevel.SENTENCE, .91f)
        showLookup(entry = entry,
            aiContext = AiLookupResult("mithril", "秘银", explanation = "这是一段很长的语境解释。".repeat(80), source = "test"),
            translation = translation) {}
        compose.onNodeWithText(meaning).assertIsDisplayed()
    }

    @Test fun failedTranslationShowsShortFeedbackWithoutTransportDetails() {
        showLookup(sentenceError = "HTTP 401 endpoint-diagnostic-sentinel") {}
        compose.onNodeWithText(context.getString(R.string.reader_sentence_translation_failed)).assertExists()
        compose.onNodeWithText("endpoint-diagnostic-sentinel", substring = true).assertDoesNotExist()
    }

    @Test fun lookupShowsMeaningsWithoutProviderAndMatchingMetadata() {
        val translation = com.linguareader.app.translation.TranslationLookupResult(
            "metadata-title-sentinel", "A mithril coat.", "一件秘银外衣。", "一件秘银外衣。",
            com.linguareader.app.translation.TranslationMatchLevel.SENTENCE, .91f)
        showLookup(aiContext = AiLookupResult("mithril", "秘银", source = "source-sentinel"),
            translation = translation,
            sentence = com.linguareader.app.ai.SentenceTranslationResult("整句译文", "provider-sentinel")) {}
        compose.onNodeWithText("秘银").assertExists()
        compose.onNodeWithText("一件秘银外衣。").assertExists()
        compose.onNodeWithText("整句译文").assertExists()
        compose.onNodeWithText("source-sentinel", substring = true).assertDoesNotExist()
        compose.onNodeWithText("metadata-title-sentinel", substring = true).assertDoesNotExist()
        compose.onNodeWithText("provider-sentinel", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.reader_dict_source)).assertDoesNotExist()
        compose.onNodeWithText("A mithril coat.").assertExists()
    }

    @Test
    fun dictionaryMissStillOffersAddToVocabulary() {
        var clicks = 0
        showLookup { clicks++ }
        compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab))
            .assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun savedDictionaryMissStillOffersRemoval() {
        var clicks = 0
        showLookup(isSaved = true) { clicks++ }
        compose.onNodeWithText(context.getString(R.string.reader_remove_from_vocab))
            .assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun aiOnlyResultStillOffersAddToVocabulary() {
        var clicks = 0
        showLookup(aiContext = AiLookupResult("mithril", "秘银", source = "test")) { clicks++ }
        compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab))
            .assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun loadingKeepsTheSaveActionVisibleButDisabled() {
        showLookup(loading = true) { error("词典查询未结束时不应收藏临时词条") }
        compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab))
            .assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun persistenceInProgressKeepsTheSaveActionVisibleButDisabled() {
        showLookup(changingVocabulary = true) { error("保存完成前不应重复请求") }
        compose.onNodeWithText(context.getString(R.string.reader_add_to_vocab))
            .assertIsDisplayed().assertIsNotEnabled()
    }
}
