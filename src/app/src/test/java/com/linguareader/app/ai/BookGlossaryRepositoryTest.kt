package com.linguareader.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 多角色面板「添加角色」的存储行为：手动条目落库、重名合并、空名拒绝，
 * 以及手动条目不被 [BookGlossary.sanitized] 过滤。
 */
@RunWith(RobolectricTestRunner::class)
class BookGlossaryRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bookId = "book-add-character"

    @Test
    fun addManualCharacterPersistsAsEnabledManualEntry() = runBlocking {
        val repository = BookGlossaryRepository(context)

        val glossary = repository.addManualCharacter(bookId, "Harry", gender = "male")

        assertNotNull(glossary)
        val entry = glossary!!.entries.single()
        assertEquals("Harry", entry.term)
        assertEquals(GlossaryEntry.KIND_CHARACTER, entry.kind)
        assertEquals("manual", entry.origin)
        assertEquals("male", entry.gender)
        assertTrue(entry.enabled)
        // 重读一遍：确实写进了文件，且不会被脏数据清理滤掉。
        val reloaded = repository.load(bookId).entries.single()
        assertEquals("Harry", reloaded.term)
    }

    @Test
    fun addingTheSameNameAgainMergesInsteadOfDuplicating() = runBlocking {
        val repository = BookGlossaryRepository(context)
        repository.addOrUpdate(bookId, "harry", translation = "哈利", kind = "place")

        val glossary = repository.addManualCharacter(bookId, "Harry", gender = "male")

        val entry = glossary!!.entries.single()
        assertEquals("Harry", entry.term)
        assertEquals(GlossaryEntry.KIND_CHARACTER, entry.kind)
        assertEquals("manual", entry.origin)
        // 用户明确说这是角色：kind 改判，但已有翻译保留。
        assertEquals("哈利", entry.translation)
    }

    @Test
    fun blankNameIsRejected() = runBlocking {
        val repository = BookGlossaryRepository(context)

        assertNull(repository.addManualCharacter(bookId, "   "))
        assertNull(repository.addManualCharacter("", "Harry"))
        assertTrue(repository.load(bookId).entries.isEmpty())
    }

    @Test
    fun manualCharacterShowsUpInTheSpeakerRoster() = runBlocking {
        val repository = BookGlossaryRepository(context)
        repository.addManualCharacter(bookId, "Gandalf", gender = "male")
        val speakerTags = SpeakerTagRepository(
            context,
            AiSettingsStore(context),
            repository
        )

        val roster = speakerTags.roster(bookId)

        assertEquals(listOf("Gandalf"), roster.names)
    }

    @Test
    fun aliasesAreAddedCaseInsensitivelyAndDeduplicated() = runBlocking {
        val repository = BookGlossaryRepository(context)
        repository.addManualCharacter(bookId, "Harry")

        assertNotNull(repository.addAlias(bookId, "harry", "Potter"))
        // 大小写不同的重复别名：不新增，原样返回。
        val unchanged = repository.addAlias(bookId, "Harry", "potter")!!
        assertEquals(1, unchanged.entries.single().aliases.size)
        // 与名字本身相同的别名没有意义：同样不新增。
        val sameAsTerm = repository.addAlias(bookId, "Harry", "HARRY")!!
        assertEquals(1, sameAsTerm.entries.single().aliases.size)
        // 条目不存在：null。
        assertNull(repository.addAlias(bookId, "Nobody", "X"))

        val stored = repository.load(bookId).entries.single()
        assertEquals(listOf("Potter"), stored.aliases)
    }

    @Test
    fun aliasesAreRemovedAndSurviveProfileReimport() = runBlocking {
        val repository = BookGlossaryRepository(context)
        repository.addManualCharacter(bookId, "Harry")
        repository.addAlias(bookId, "Harry", "Potter")

        // 档案重导入（auto 来源）不得覆盖手动加的别名。
        repository.importFromProfile(
            bookId,
            BookContextProfile(
                bookId = bookId,
                bookTitle = "t",
                characterProfiles = listOf(
                    CharacterProfile(name = "Harry", aliases = listOf("The Boy Who Lived"))
                )
            )
        )
        val merged = repository.load(bookId).entries.single()
        assertTrue(merged.aliases.contains("Potter"))
        assertTrue(merged.aliases.contains("The Boy Who Lived"))

        // 删除不存在的别名：原样返回；删除存在的别名：生效。
        val unchanged = repository.removeAlias(bookId, "Harry", "Not An Alias")!!
        assertEquals(2, unchanged.entries.single().aliases.size)
        assertNull(repository.removeAlias(bookId, "Nobody", "X"))

        val afterRemove = repository.removeAlias(bookId, "Harry", "potter")!!
        assertEquals(listOf("The Boy Who Lived"), afterRemove.entries.single().aliases)
    }

    @Test
    fun aliasesReachTheSpeakerRosterForLlmValidation() = runBlocking {
        val repository = BookGlossaryRepository(context)
        repository.addManualCharacter(bookId, "Gandalf")
        repository.addAlias(bookId, "Gandalf", "Mithrandir")
        val speakerTags = SpeakerTagRepository(
            context,
            AiSettingsStore(context),
            repository
        )

        val roster = speakerTags.roster(bookId)

        // canonical() 把别名（大小写不敏感）映射回正名——LLM 答别名也能命中。
        assertEquals("Gandalf", roster.canonical("Mithrandir"))
        assertEquals("Gandalf", roster.canonical("mithrandir"))
        assertEquals(listOf("Gandalf"), roster.names)
    }
}
