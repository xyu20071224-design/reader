package com.linguareader.desktop

import com.linguareader.shared.data.DictionaryRepository
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.packs.*
import com.linguareader.shared.tts.TtsCacheKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPackRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder()
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected rejection")
    }

    @Test fun installRestartSwitchAndUninstallAffectRealSharedLookup() = runBlocking {
        val home = home()
        val repository = DesktopPackRepository(home)
        val fallback = DesktopPackFixtures.database(home, "默认")
        val driver = DesktopPackDictionary(repository) { fallback }
        suspend fun meaning() = DictionaryRepository(driver)
            .lookup(WordLookup("apples", "I eat apples.", "", 6, 0f, 0f)).entry!!.senses.first().text
        assertTrue(meaning().contains("默认"))
        repository.install(DesktopPackFixtures.dictionary(home, "first", meaning = "第一词典"))
        assertTrue(meaning().contains("第一词典"))
        repository.install(DesktopPackFixtures.dictionary(home, "second", meaning = "第二词典"))
        assertTrue(meaning().contains("第一词典"))
        repository.setActiveDictionary("second")
        assertTrue(meaning().contains("第二词典"))
        val reopened = DesktopPackRepository(home)
        assertEquals(2, reopened.snapshot().registry.packs.size)
        assertEquals("second", reopened.snapshot().registry.activeDictionary)
        assertEquals("apple", DesktopPackDictionary(reopened) { fallback }.queryEntry("apple")!!.word)
        repository.uninstall("second")
        assertNull(reopened.snapshot().registry.activeDictionary)
        assertTrue(meaning().contains("默认"))
        assertFalse(File(repository.packsRoot, "dictionary/second/1.0.0").exists())
    }

    @Test fun restoreDefaultDoesNotDeleteInstalledDictionary() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home))
        repository.setActiveDictionary(null)
        assertNull(repository.snapshot().registry.activeDictionary)
        assertEquals(1, repository.snapshot().registry.packs.size)
        repository.setActiveDictionary("test-dictionary")
        assertEquals("test-dictionary", repository.snapshot().registry.activeDictionary)
    }

    @Test fun invalidHashIsRejectedWithoutPublicationOrStagingLeak() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        rejects { repository.install(DesktopPackFixtures.dictionary(home, badHash = true)) }
        assertTrue(repository.snapshot().registry.packs.isEmpty())
        assertTrue(File(repository.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
    }

    @Test fun undeclaredPayloadIsRejected() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        rejects { repository.install(DesktopPackFixtures.dictionary(home, extra = mapOf("hidden.bin" to byteArrayOf(1)))) }
        assertTrue(repository.snapshot().registry.packs.isEmpty())
    }

    @Test fun zipTraversalNeverWritesOutsideStaging() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        rejects { repository.install(DesktopPackFixtures.dictionary(home, extra = mapOf("../../escape.txt" to byteArrayOf(1)))) }
        assertFalse(File(repository.packsRoot, "escape.txt").exists())
        assertFalse(File(home, "escape.txt").exists())
        assertTrue(repository.snapshot().registry.packs.isEmpty())
    }

    @Test fun sqliteWithWrongSchemaCannotBecomeActive() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        assertTrue(rejects { repository.install(DesktopPackFixtures.dictionary(home, validSchema = false)) }
            .message.orEmpty().contains("表结构"))
        assertNull(repository.snapshot().registry.activeDictionary)
    }

    @Test fun futureAppVersionIsRejected() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        rejects { repository.install(DesktopPackFixtures.dictionary(home, minimum = DesktopPackRepository.APP_VERSION + 1)) }
        assertTrue(repository.snapshot().registry.packs.isEmpty())
    }

    @Test fun selectedCatalogIdentityMustMatchArchive() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        rejects { repository.install(DesktopPackFixtures.dictionary(home),
            DesktopPackRepository.ExpectedIdentity("different-id", "1.0.0", PackType.DICTIONARY)) }
        assertTrue(repository.snapshot().registry.packs.isEmpty())
    }

    @Test fun downgradePreservesCurrentPayloadAndSelection() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home, version = "1.10.0", meaning = "新"))
        rejects { repository.install(DesktopPackFixtures.dictionary(home, version = "1.9.0", meaning = "旧")) }
        assertEquals("1.10.0", repository.snapshot().registry.packs.single().version)
        assertTrue(DesktopPackDictionary(repository) { null }.queryEntry("apple")!!.translation.contains("新"))
    }

    @Test fun upgradePublishesNewDirectoryThenRemovesOldPayload() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val old = repository.install(DesktopPackFixtures.dictionary(home))
        repository.install(DesktopPackFixtures.dictionary(home, version = "2.0.0"))
        assertFalse(old.root(repository.packsRoot).exists())
        assertEquals("2.0.0", DesktopPackRepository(home).snapshot().registry.packs.single().version)
        assertEquals("test-dictionary", repository.snapshot().registry.activeDictionary)
    }

    @Test fun cancellationBeforeRegistryPublicationRestoresSameVersionPayload() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home, meaning = "原内容"))
        val next = DesktopPackFixtures.dictionary(home, meaning = "未提交内容")
        val task = launch(start = CoroutineStart.LAZY) { repository.install(next) }
        repository.beforePublication = { task.cancel() }
        task.start(); task.join()
        assertTrue(task.isCancelled)
        assertTrue(DesktopPackDictionary(repository) { null }.queryEntry("apple")!!.translation.contains("原内容"))
        repository.verify("test-dictionary")
        assertTrue(File(repository.packsRoot, ".tmp").listFiles().orEmpty().isEmpty())
    }

    @Test fun ioFailureAtPublicationRestoresPreviousPayloadAndRegistry() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        repository.install(DesktopPackFixtures.dictionary(home, meaning = "原内容"))
        val registry = repository.snapshot().registry
        repository.beforePublication = { throw java.io.IOException("injected publication failure") }
        rejects { repository.install(DesktopPackFixtures.dictionary(home, meaning = "未提交内容")) }
        assertEquals(registry, repository.snapshot().registry)
        assertTrue(DesktopPackDictionary(repository) { null }.queryEntry("apple")!!.translation.contains("原内容"))
    }

    @Test fun missingActivePayloadFallsBackWithoutDeletingRegistration() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val pack = repository.install(DesktopPackFixtures.dictionary(home))
        val fallback = DesktopPackFixtures.database(home, "默认")
        pack.dictionaryFile(repository.packsRoot)!!.delete()
        assertTrue(DesktopPackDictionary(repository) { fallback }.queryEntry("apple")!!.translation.contains("默认"))
        assertEquals(1, repository.snapshot().registry.packs.size)
        rejects { repository.verify(pack.packId) }
        Unit
    }

    @Test fun damagedRegistryIsVisibleAndNeverOverwrittenByImport() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        repository.packsRoot.mkdirs()
        val file = File(repository.packsRoot, "registry.json").apply { writeText("broken") }
        assertNotNull(repository.snapshot().error)
        rejects { repository.install(DesktopPackFixtures.dictionary(home)) }
        assertEquals("broken", file.readText())
    }

    @Test fun verificationDetectsPayloadChangedAfterInstall() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val pack = repository.install(DesktopPackFixtures.dictionary(home))
        pack.dictionaryFile(repository.packsRoot)!!.appendBytes(byteArrayOf(1))
        rejects { repository.verify(pack.packId) }
        Unit
    }

    @Test fun bundleInstallsMembersThroughSameValidatedPipeline() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val first = DesktopPackFixtures.dictionary(home, "first")
        val second = DesktopPackFixtures.dictionary(home, "second")
        repository.install(DesktopPackFixtures.bundle(home, listOf("first" to first, "second" to second)))
        assertEquals(setOf("first", "second", "test-bundle"), repository.snapshot().registry.packs.map { it.packId }.toSet())
        repository.verify("test-bundle")
    }

    @Test fun bundleMemberIdentityMismatchIsReportedAndNeverInstalledUnderFalseId() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val member = DesktopPackFixtures.dictionary(home, "actual")
        rejects { repository.install(DesktopPackFixtures.bundle(home, listOf("actual" to member), declaredId = "claimed")) }
        assertEquals(listOf("test-bundle"), repository.snapshot().registry.packs.map { it.packId })
    }

    @Test fun incompatibleAudioPipelineIsRejectedBeforeItCanBeListed() = runBlocking {
        val home = home(); val repository = DesktopPackRepository(home)
        val path = "audio/0/${TtsCacheKey.segmentDir("test", "voice")}/0000.mp3"
        val bytes = byteArrayOf(1)
        val manifest = PackManifest("audio", PackType.AUDIO, "1", "音频", "audio", 1, 0,
            listOf(PackFileEntry(path, DesktopPackFixtures.sha(bytes), 1)),
            PackPayload.Audio("book", "test", "voice", -1, listOf(AudioChapter(0, 1, ""))))
        rejects { repository.install(DesktopPackFixtures.archive(home, manifest, mapOf(path to bytes))) }
        assertTrue(repository.snapshot().registry.packs.isEmpty())
    }
}
