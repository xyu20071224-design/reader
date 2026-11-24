package com.linguareader.app

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real MainActivity speech wiring above a real lookup modal on an isolated emulator. */
@RunWith(AndroidJUnit4::class)
class SingleWordSpeechFeedbackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun unavailableEngineStaysQuietUntilRequestedThenShowsAboveLookup() {
        assumeTrue("This test changes an emulator-only system package", Build.PRODUCT.startsWith("sdk_"))
        val engine = "com.google.android.tts"
        val wasDisabled = shell("pm list packages -d $engine").contains(engine)
        assumeTrue("Google TTS package required for controlled restore", shell("pm list packages $engine").contains(engine))
        val root = File(context.filesDir, "books/speech-feedback-fixture")
        try {
            shell("pm disable-user --user 0 $engine")
            seedBook(root)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                repeat(3) {
                    listOf(R.string.launch_greeting_start, R.string.common_got_it).forEach { key ->
                        val text = context.getString(key)
                        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                            compose.onNodeWithText(text).performClick()
                            compose.waitForIdle()
                        }
                    }
                }
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty()
                }
                val failureTitle = context.getString(R.string.tts_word_failure_title)
                assertFalse("Eager TTS initialization must not interrupt startup",
                    compose.onAllNodesWithText(failureTitle).fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithText(TITLE).performClick()
                val metrics = context.resources.displayMetrics
                var y = (metrics.heightPixels * .25f).toInt()
                compose.waitForIdle()
                while (y < metrics.heightPixels * .6f &&
                    compose.onAllNodesWithTag(UiTags.READER_LOOKUP_SHEET).fetchSemanticsNodes().isEmpty()) {
                    shell("input tap ${metrics.widthPixels / 2} $y")
                    SystemClock.sleep(500)
                    y += 60
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(context.getString(R.string.common_speak)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.common_speak)).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(failureTitle).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.tts_word_unavailable)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.common_got_it)).performClick()
                compose.onNodeWithText(context.getString(R.string.common_speak)).assertIsDisplayed()
            }
        } finally {
            if (!wasDisabled) shell("pm enable $engine")
            root.deleteRecursively()
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }

    private fun seedBook(root: File) {
        root.mkdirs()
        File(root, "chapter.xhtml").writeText("""<html xmlns="http://www.w3.org/1999/xhtml"><head>
            <meta charset="UTF-8"/></head><body>${(1..70).joinToString("") {
                "<p>" + List(35) { "cat" }.joinToString(" ") + ".</p>"
            }}</body></html>""")
        val book = Book(id = "speech-feedback-fixture", title = TITLE, author = "Test fixture",
            extractedDir = root.absolutePath, coverRelativePath = null,
            chapters = listOf(Chapter("Speech", "chapter.xhtml")),
            addedAt = System.currentTimeMillis(), sourceFormat = "epub")
        File(root, "metadata.json").writeText(book.toJson().toString())
    }

    private companion object { const val TITLE = "Speech feedback fixture" }
}
