package com.linguareader.app.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.linguareader.app.R
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
        request: WordLookup = lookup,
        onToggleSave: () -> Unit
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDesignTokens provides SkinRegistry.toDesignTokens(SkinBuiltIns.light)
            ) {
                MaterialTheme {
                    ReaderLookupSheet(
                        lookup = request,
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

    @Test fun fallbackWordSenseShowsItsRealSourceInsideThePhrasePanel() {
        val english = "Tom paid him for the keys and walked out into the rain."
        val request = WordLookup("walked", english, english, english.indexOf("walked"), 0f, 0f)
        val entry = kotlinx.coroutines.runBlocking { com.linguareader.app.data.DictionaryRepository(context).lookup(request) }.entry!!
        org.junit.Assert.assertTrue(entry.senses.none { it.text.contains("走") })
        val book = memoryFixture("visible-fallback-sense", english, "他付了钥匙钱，走进雨中。")
        try {
            val result = kotlinx.coroutines.runBlocking {
                com.linguareader.app.translation.TranslationMemoryRepository(context.applicationContext as android.app.Application).lookup(book, 0, request)
            }!!
            org.junit.Assert.assertEquals("walk", result.wordAlignment!!.sourceHeadword)
            showLookup(entry = entry, translation = result, request = request) {}
            compose.onNodeWithText(context.getString(R.string.reader_alignment_sense_source, "walk"))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(result.wordAlignment!!.sourceSense!!).performScrollTo().assertIsDisplayed()
            screenshot("dictionary-fallback-source.png")
        } finally { java.io.File(context.filesDir, "translation-memory/${book.id}.json").delete() }
    }

    @Test fun aNinthSenseCanBeScrolledIntoViewWithoutLosingItsSource() {
        val file = java.io.File(context.cacheDir, "ninth-sense-fixture.sqlite")
        file.delete()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)")
            db.execSQL("CREATE TABLE forms(form TEXT, lemma TEXT)")
            db.execSQL("INSERT INTO entries VALUES ('walk', '', ?, '')", arrayOf(
                listOf("v. 农耕", "v. 烹饪", "v. 建造", "v. 绘画", "v. 修理", "v. 演奏", "v. 编织", "v. 跳舞", "v. 走").joinToString("\n")))
        }
        val english = "We walk."
        val request = WordLookup("walk", english, english, 3, 0f, 0f)
        val book = memoryFixture("visible-ninth-sense", english, "我们走。")
        try {
            val entry = kotlinx.coroutines.runBlocking { com.linguareader.app.data.DictionaryRepository(context) { file }.lookup(request) }.entry!!
            val result = kotlinx.coroutines.runBlocking {
                com.linguareader.app.translation.TranslationMemoryRepository(context.applicationContext as android.app.Application) { file }.lookup(book, 0, request)
            }!!
            org.junit.Assert.assertEquals(entry.senses[8].text, result.wordAlignment!!.sourceSense)
            showLookup(entry = entry, translation = result, request = request) {}
            compose.onNodeWithText(context.getString(R.string.reader_alignment_sense_source, "walk"))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(entry.senses[8].text).performScrollTo().assertIsDisplayed()
            screenshot("dictionary-ninth-source.png")
        } finally {
            file.delete()
            java.io.File(context.filesDir, "translation-memory/${book.id}.json").delete()
        }
    }

    private fun memoryFixture(id: String, english: String, chinese: String): com.linguareader.app.data.Book {
        val memory = com.linguareader.shared.translation.TranslationMemory(id, "Sense fixture", "zh-$id", "译本", 0L,
            listOf(com.linguareader.shared.translation.AlignedSentencePair(0, 0, english, chinese, english, chinese, .9f)),
            alignerVersion = com.linguareader.shared.translation.TranslationAligner.VERSION)
        java.io.File(context.filesDir, "translation-memory/$id.json").apply { parentFile!!.mkdirs(); writeText(memory.toJson().toString()) }
        return com.linguareader.app.data.Book(id, "Sense fixture", "", context.cacheDir.path, null, emptyList(), 0L)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
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
