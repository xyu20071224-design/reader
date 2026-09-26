package com.linguareader.app.translation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **仓库级词级高亮的集成回归**（此前全仓没有一条 JVM 测试覆盖这条路径，只有一条
 * 跑不了的仪器测试 —— v1.10.0 的回归正是从这里漏出去的）。
 *
 * 覆盖两件事：
 * 1. 正常情形：点词能拿到 `wordAlignment`（界面据此把译文里的中文词加粗高亮）；
 * 2. **短语词条劫持**（v1.10.0 回归的根因）：当点的那词是某条短语的语义核心时，
 *    上下文词典查询会返回**短语词条**并整片替换单词词条。候选若只取上下文词条，
 *    点 `walked`（walk out → 罢工/退场）就再也高亮不出译文里的「走」——修复后
 *    候选取「上下文词条 ∪ 单词词条」的并集，两边都不丢。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationLookupHighlightTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun writeBook(id: String, dir: File, paragraphs: List<String>): Book {
        dir.mkdirs()
        val body = paragraphs.joinToString("") { "<p>" + it + "</p>" }
        File(dir, "chapter_000.xhtml").writeText("<html><body>" + body + "</body></html>")
        return Book(
            id = id,
            title = "Book " + id,
            author = "Author",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = listOf(Chapter("Chapter 1", "chapter_000.xhtml")),
            addedAt = System.currentTimeMillis()
        )
    }

    private suspend fun lookupAfterAlign(
        id: String,
        english: List<String>,
        chinese: List<String>,
        word: String,
        sentenceIndex: Int = 0
    ): Pair<com.linguareader.shared.translation.TranslationLookupResult?, WordLookup> {
        val book = writeBook(id, File(context.filesDir, "books/$id"), english)
        val zh = writeBook("zh-$id", File(context.filesDir, "translations/zh-$id"), chinese)
        TranslationMemoryRepository(context).attachGenerated(book, zh)

        val sentence = english[sentenceIndex]
        val lookup = WordLookup(word, sentence, sentence, sentence.indexOf(word), 0f, 0f)
        return TranslationMemoryRepository(context).lookup(book, 0, lookup) to lookup
    }

    /** 正常情形：点的词与译文用词对得上，必须拿到高亮。 */
    @Test
    fun lookupProducesWordLevelHighlight() = runBlocking<Unit> {
        val (hit, _) = lookupAfterAlign(
            "hl-basic",
            listOf("In 1926 he left the town."),
            listOf("1926年他离开了小镇。"),
            "town"
        )

        assertNotNull(hit, "译本对照本身应命中")
        val alignment = assertNotNull(hit.wordAlignment, "点词应产出词级高亮（界面据此加粗译文里的中文词）")
        assertEquals("镇", alignment.word)
        assertTrue(alignment.sourceSense?.contains("镇") == true, "应能指回具体义项：${alignment.sourceSense}")
    }

    /**
     * 回归用例（v1.10.0 → v1.10.1）：短语词条劫持时，**单词词条**的义项仍必须参与候选。
     *
     * 依据：真实档案实测（`artifacts/ai-translation-memory-device.json`）——
     * `... and walked out into the rain.` / 「……走进雨中。」里点 `walked`，
     * 上下文查询返回短语词条 `walk out`（罢工/退场），不含「走」；只有并上单词词条
     * `walk`（走/步行/…）才高亮得出译文里的「走」。
     */
    @Test
    fun wordSenseSurvivesPhraseEntryHijack() = runBlocking<Unit> {
        val english = "Tom paid him for the keys and walked out into the rain."
        val chinese = "他付了钥匙钱，走进雨中。"

        // 先证明「劫持」确实发生：上下文词条的义项里没有「走」，说明它已被短语词条替换。
        val dictionary = DictionaryRepository(context)
        val lookup = WordLookup("walked", english, english, english.indexOf("walked"), 0f, 0f)
        val contextEntry = dictionary.lookup(lookup).entry
        val contextTexts = contextEntry?.senses?.map { it.text }.orEmpty()
        assertTrue(
            contextTexts.none { it.contains("走") },
            "本用例的前提是上下文词条已不含「走」（短语劫持）；若词典数据变了请换词。实际：$contextTexts"
        )

        val (hit, _) = lookupAfterAlign("hl-hijack", listOf(english), listOf(chinese), "walked")
        assertNotNull(hit, "译本对照本身应命中")
        val alignment = assertNotNull(
            hit.wordAlignment,
            "短语劫持时单词词条的义项必须仍在候选里，否则点词完全没有高亮（v1.10.0 回归）"
        )
        assertEquals("走", alignment.word, "高亮的应是译文里真实存在的「走」")
    }
}
