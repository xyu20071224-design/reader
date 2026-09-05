package com.linguareader.shared.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 迁移 M2 刀2 的语义守门测试：DictionaryRepository 随驱动抽象进 :shared 后，
 * 用内存假驱动锁定查词主流程（词形还原、词组核心命中、词组非核心回落、
 * 启发式词形还原、未知词）——Android 真库的 SQL 语义另有
 * DictionarySqlParityTest 双引擎对账，两边互补。
 */
class DictionaryRepositoryTest {

    private class FakeDictionaryDb(
        private val entries: Map<String, RawDictionaryEntry>,
        private val forms: Map<String, List<String>> = emptyMap()
    ) : DictionaryDatabase {
        override fun lemmaCandidates(form: String): List<String> = forms[form] ?: emptyList()
        override fun queryEntry(word: String): RawDictionaryEntry? = entries[word]
    }

    private fun lookup(
        word: String,
        sentence: String,
        db: DictionaryDatabase
    ): DictionaryLookupResult = runBlocking {
        DictionaryRepository(db).lookup(
            WordLookup(
                word = word,
                sentence = sentence,
                paragraph = sentence,
                sentenceOffset = 0,
                x = 0f,
                y = 0f
            )
        )
    }

    @Test
    fun wordHit_lemmatizesThroughFormsTable() {
        val db = FakeDictionaryDb(
            entries = mapOf(
                "run" to RawDictionaryEntry("run", "rʌn", "v. 跑", "move fast on foot")
            ),
            forms = mapOf("running" to listOf("run"))
        )
        val result = lookup("running", "She is running fast.", db)
        assertEquals("run", result.entry?.headword)
        assertEquals(null, result.entry?.matchedPhrase)
        assertNull(result.relatedPhrase)
    }

    @Test
    fun phraseCoreHit_takesPhrasePriority() {
        val db = FakeDictionaryDb(
            entries = mapOf(
                "in spite of" to RawDictionaryEntry("in spite of", "", "尽管", ""),
                "spite" to RawDictionaryEntry("spite", "", "恶意", "")
            )
        )
        val result = lookup("spite", "He kept it in spite of the cost.", db)
        // "spite" 是词组 "in spite of" 的第一个实词 → 词组优先，relatedPhrase 为空。
        assertEquals("in spite of", result.entry?.headword)
        assertEquals("in spite of", result.entry?.matchedPhrase)
        assertNull(result.relatedPhrase)
    }

    @Test
    fun phraseNonCoreHit_fallsBackToWordWithRelatedPhrase() {
        val db = FakeDictionaryDb(
            entries = mapOf(
                "heavy rain" to RawDictionaryEntry("heavy rain", "", "大雨", ""),
                "rain" to RawDictionaryEntry("rain", "", "雨", "")
            )
        )
        val result = lookup("rain", "The heavy rain stopped.", db)
        // "rain" 在词组 "heavy rain" 里不是第一个实词 → 回落单词查询，词组降为 relatedPhrase。
        assertEquals("rain", result.entry?.headword)
        assertEquals(null, result.entry?.matchedPhrase)
        assertEquals("heavy rain", result.relatedPhrase?.headword)
        assertEquals("heavy rain", result.relatedPhrase?.matchedPhrase)
    }

    @Test
    fun unknownSurface_usesHeuristicStemming() {
        val db = FakeDictionaryDb(
            entries = mapOf(
                "house" to RawDictionaryEntry("house", "", "房子", "")
            )
        )
        val result = lookup("houses", "The houses are old.", db)
        assertEquals("house", result.entry?.headword)
    }

    @Test
    fun unknownWord_returnsNullEntryAndNoPhrase() {
        val db = FakeDictionaryDb(entries = emptyMap())
        val result = lookup("xyzzy", "It is quite xyzzy today.", db)
        assertNull(result.entry)
        assertNull(result.relatedPhrase)
    }
}
