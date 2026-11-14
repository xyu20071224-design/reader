package com.linguareader.app.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.WordLookup
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.SkinRegistry
import com.linguareader.shared.ui.skin.SkinBuiltIns
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 在设备窗口验证长 AI/译文不会挤走词典释义。 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ReaderDictionaryVisibilityTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val lookup = WordLookup("lantern", "A lantern glowed.", "A lantern glowed.", 2, 0f, 0f)

    private fun showLookup(
        entry: ContextualDictionaryEntry? = null,
        aiContext: AiLookupResult? = null,
        isSaved: Boolean = false,
        loading: Boolean = false,
        translation: com.linguareader.app.translation.TranslationLookupResult? = null,
        sentence: com.linguareader.app.ai.SentenceTranslationResult? = null,
        sentenceError: String? = null,
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
                        onDismiss = {}
                    )
                }
            }
        }
    }

    @Test fun dictionaryMeaningIsVisibleWithLongContextAndTranslation() {
        val sentence = "A lantern glowed."
        val entry = requireNotNull(kotlinx.coroutines.runBlocking {
            com.linguareader.app.data.DictionaryRepository(context).lookup(
                WordLookup("lantern", sentence, sentence, 2, 0f, 0f))
        }.entry)
        val meaning = entry.senses.first().text
        org.junit.Assert.assertTrue(meaning.contains("灯笼"))
        val translation = com.linguareader.app.translation.TranslationLookupResult(
            "title", sentence, "一盏灯笼发着光。".repeat(80), "",
            com.linguareader.app.translation.TranslationMatchLevel.SENTENCE, .91f)
        showLookup(entry = entry,
            aiContext = AiLookupResult("lantern", "灯笼", explanation = "这是一段很长的语境解释。".repeat(80), source = "test"),
            translation = translation) {}
        compose.onNodeWithText(meaning).assertIsDisplayed()
        // 语义断言可能早于系统合成最后一帧；截图前让设备窗口完成绘制。
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(500)
        // 收集设备首屏截图，供验证时人工检查。
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .takeScreenshot().let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "dictionary-first-screen.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }

    }

}
