package com.linguareader.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class DictionaryRecoveryInstrumentedTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: DictionaryRepository

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "dictionary-recovery-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
    }

    @After fun tearDown() {
        if (::repository.isInitialized) repository.invalidate()
        root.deleteRecursively()
    }

    private fun copyFile() = File(root, "dictionary/ecdict-v2.sqlite")

    private fun emptyDictionary(file: File) {
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)")
            db.execSQL("CREATE TABLE forms(form TEXT, lemma TEXT)")
        }
    }

    private fun assertHorse() = runBlocking {
        val sentence = "The horse runs."
        val entry = repository.lookup(WordLookup("horse", sentence, sentence, 4, 0f, 0f)).entry
        assertNotNull("horse must be found in the bundled dictionary", entry)
        assertTrue(entry!!.senses.any { it.text.contains("马") })
    }

    @Test fun nonzeroEmptyBuiltInCopyIsReplaced() {
        emptyDictionary(copyFile())
        assertTrue(copyFile().length() > 0)
        repository = DictionaryRepository(context)
        assertHorse()
        repository.invalidate()
        assertHorse()
    }

    @Test fun truncatedBuiltInCopyIsReplaced() {
        copyFile().parentFile!!.mkdirs()
        copyFile().writeText("SQLite format 3\u0000broken")
        repository = DictionaryRepository(context)
        assertHorse()
    }

    @Test fun emptyActivePackFallsBackWithoutModifyingPack() {
        val pack = File(root, "empty-pack.sqlite")
        emptyDictionary(pack)
        val original = pack.readBytes()
        repository = DictionaryRepository(context) { pack }
        assertHorse()
        assertTrue(original.contentEquals(pack.readBytes()))
    }

    @Test fun corruptActivePackFallsBackWithoutDeletingPack() {
        val pack = File(root, "broken-pack.sqlite").apply { writeText("SQLite format 3\u0000broken") }
        val original = pack.readBytes()
        repository = DictionaryRepository(context) { pack }
        assertHorse()
        assertTrue(pack.exists())
        assertTrue(original.contentEquals(pack.readBytes()))
    }

    @Test fun healthyPackKeepsItsOwnDefinitionsAndDoesNotCopyBuiltIn() = runBlocking {
        val pack = File(root, "custom-pack.sqlite")
        emptyDictionary(pack)
        SQLiteDatabase.openDatabase(pack.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO entries VALUES ('horse', '', 'n. 自定义马', '')")
        }
        repository = DictionaryRepository(context) { pack }
        val entry = repository.lookup(WordLookup("horse", "horse", "horse", 0, 0f, 0f)).entry
        assertEquals("n. 自定义马", entry!!.senses.single().text)
        assertFalse(copyFile().exists())
    }
}
