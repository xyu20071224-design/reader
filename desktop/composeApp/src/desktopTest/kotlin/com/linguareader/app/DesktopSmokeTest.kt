package com.linguareader.app

import com.linguareader.app.data.BookImporter
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.ImportSupport
import com.linguareader.app.data.WordLookup
import com.linguareader.app.platform.appCacheDir
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopSmokeTest {

    @Test
    fun importSampleEpubAndLookupWord() = runBlocking {
        val sample = listOf(
            File("../../测试电子书-TheLanternLibrary.epub"),
            File("../测试电子书-TheLanternLibrary.epub")
        ).firstOrNull { it.exists() }
            ?: File("/Users/clannad/work/reader/reader-1.2.0/测试电子书-TheLanternLibrary.epub")
        assertTrue(sample.exists(), "sample EPUB missing: ${sample.absolutePath}")

        val prepared = ImportSupport.prepare(sample)
        val bookDir = File(appCacheDir, "smoke-${System.currentTimeMillis()}")
        try {
            val book = BookImporter(bookDir).import(prepared)
            assertTrue(book.chapters.isNotEmpty(), "EPUB produced no chapters")
            assertTrue(
                File(book.extractedDir, book.chapters.first().relativePath).exists(),
                "first chapter file missing"
            )

            val dictionary = DictionaryRepository()
            val result = dictionary.lookup(
                WordLookup(
                    word = "lantern",
                    sentence = "The lantern lit the room.",
                    paragraph = "The lantern lit the room.",
                    sentenceOffset = 4,
                    x = 0f,
                    y = 0f
                )
            )
            val found = result.entry != null || result.relatedPhrase != null
            assertTrue(found, "dictionary lookup returned nothing for 'lantern'")
        } finally {
            bookDir.deleteRecursively()
        }
    }
}
