package com.linguareader.app.translation

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linguareader.app.data.Book
import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * 真机端到端：用真实的中英对照书（魔戒首部曲：英文原版 EPUB + 朱學恆中譯本 EPUB）
 * 跑「导入英文书 → 关联/对齐中译本 → 点词查译本对照」全链路。
 *
 * 测试书位于 androidTest assets/test-books/（本地 git 忽略，不入库）。
 */
@RunWith(AndroidJUnit4::class)
class TranslationMemoryDeviceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    // 测试 APK（androidTest）的 assets 要通过 instrumentation context 读取；
    // 被测 App 的 context 没有这些 assets。
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context
    private lateinit var repo: TranslationMemoryRepository

    @Before
    fun setUp() {
        repo = TranslationMemoryRepository(app)
    }

    @After
    fun tearDown() {
        File(app.filesDir, "translations").deleteRecursively()
        File(app.filesDir, "translation-memory").deleteRecursively()
        File(app.cacheDir, "test-books").deleteRecursively()
    }

    private fun assetExists(name: String): Boolean = try {
        testContext.assets.open("test-books/$name").use { true }
    } catch (e: FileNotFoundException) {
        false
    }

    private fun extractAsset(name: String): File {
        val dir = File(app.cacheDir, "test-books").apply { mkdirs() }
        val out = File(dir, name)
        testContext.assets.open("test-books/$name").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    @Test
    fun importsAlignsAndLooksUpRealBookPair() {
        assumeTrue("缺少 assets/test-books/en.epub（商业书, 本地 git 忽略）", assetExists("en.epub"))
        assumeTrue("缺少 assets/test-books/zh.epub", assetExists("zh.epub"))

        val enFile = extractAsset("en.epub")
        val zhFile = extractAsset("zh.epub")

        runBlocking {
            // 1) 导入英文原版
            val importDir = File(app.filesDir, "src-import").apply { mkdirs() }
            val sourceBook: Book = BookImporter(app, importDir).import(Uri.fromFile(enFile))
            assertTrue("英语原版应有章节", sourceBook.chapters.isNotEmpty())

            // 2) 关联中译本并对齐
            val attached = repo.attach(sourceBook, Uri.fromFile(zhFile))
            assertTrue("对齐记忆应包含句对", attached.memory.pairs.isNotEmpty())
            assertTrue("译本标题不应为空", attached.memory.translationTitle.isNotBlank())
            assertTrue(attached.memory.pairs.all { it.enParagraph.isNotBlank() && it.zhParagraph.isNotBlank() })
            assertTrue(attached.memory.pairs.all { it.confidence >= TranslationAligner.MIN_CONFIDENCE })

            // 3) 用对齐出的第一对做查词往返：精确英句应命中并返回非空中文
            val first = attached.memory.pairs.first()
            val result = repo.lookup(
                sourceBook,
                first.enChapter,
                WordLookup(
                    word = "",
                    sentence = first.enSentence.ifBlank { first.enParagraph },
                    paragraph = first.enParagraph,
                    sentenceOffset = 0,
                    x = 0f,
                    y = 0f
                )
            )
            assertNotNull("句级/段级往返应能命中", result)
            assertTrue("返回的中文对照不应为空", result?.chinese?.isNotBlank() == true)

            // 4) 词级对照：取首句第一个英文单词做一次词对齐（词典辅助/锚点），不崩溃即可
            val firstSentence = first.enSentence.ifBlank { first.enParagraph }
            val firstWord = firstSentence.trim()
                .split(Regex("\\s+"))
                .firstOrNull { it.matches(Regex("[A-Za-z][A-Za-z']*")) }
            if (firstWord != null) {
                val withWord = repo.lookup(
                    sourceBook,
                    first.enChapter,
                    WordLookup(
                        word = firstWord,
                        sentence = firstSentence,
                        paragraph = first.enParagraph,
                        sentenceOffset = 0,
                        x = 0f,
                        y = 0f
                    )
                )
                // 允许命中或降级；只要求不崩溃且若命中中文非空。
                if (withWord != null) {
                    assertTrue(withWord.chinese.isNotBlank())
                }
            }
        }
    }
}
