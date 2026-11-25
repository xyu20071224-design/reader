package com.linguareader.desktop

import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.RawDictionaryEntry
import com.linguareader.shared.packs.DictionarySource
import java.io.File

/**
 * Reader receives one stable driver, but each call resolves the current registry.
 * Connections close before returning so Windows can replace/uninstall the payload.
 * A missing/corrupt active payload falls back to the existing desktop default.
 */
class DesktopPackDictionary(
    private val repository: DesktopPackRepository,
    private val defaultFile: () -> File? = { DesktopDictionaryDatabase.resolve(repository.home) }
) : DictionaryDatabase {
    fun available(): Boolean = selectedFile() != null || defaultFile() != null

    private fun selectedFile() = DictionarySource.resolve(repository.snapshot().registry, repository.packsRoot)

    private fun <T> query(fallback: T, block: (DesktopDictionaryDatabase) -> T): T = synchronized(repository) {
        val files = listOfNotNull(selectedFile(), defaultFile()).distinctBy { it.absolutePath }
        for (file in files) {
            try { return DesktopDictionaryDatabase.open(file).use(block) }
            catch (_: java.sql.SQLException) { /* Continue to the default dictionary. */ }
        }
        fallback
    }

    override fun lemmaCandidates(form: String): List<String> = query(emptyList()) { it.lemmaCandidates(form) }
    override fun queryEntry(word: String): RawDictionaryEntry? = query(null) { it.queryEntry(word) }
}
