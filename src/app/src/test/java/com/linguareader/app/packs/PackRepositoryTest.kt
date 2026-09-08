package com.linguareader.app.packs

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.shared.importer.ImportSupport
import com.linguareader.shared.packs.PackFormatException
import com.linguareader.shared.packs.PackHasher
import com.linguareader.shared.packs.PackManifest
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.tts.TtsCacheKey
import com.linguareader.shared.tts.TtsPipelineContract
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * M1 验收：安装 / 启用 / 卸载 / 校验的磁盘行为，以及**换源后查词真的换了词典**。
 *
 * 最后一条是本里程碑最容易「看起来对」的坑：`DictionaryRepository` 的 `database`
 * 句柄与 `:shared` 的 256 项词条 LRU 都缓存着旧库的内容，不 invalidate 就会拿旧词典
 * 回答新词典的查询 —— 这里用两个词条译文不同的包把它钉死。
 */
@RunWith(RobolectricTestRunner::class)
class PackRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    private fun dictionarySqlite(file: File, word: String, translation: String) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE entries (word TEXT PRIMARY KEY COLLATE NOCASE, phonetic TEXT NOT NULL DEFAULT '', " +
                "translation TEXT NOT NULL DEFAULT '', definition TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE forms (form TEXT NOT NULL COLLATE NOCASE, lemma TEXT NOT NULL COLLATE NOCASE, " +
                "PRIMARY KEY (form, lemma))"
        )
        db.execSQL("INSERT INTO entries (word, translation) VALUES (?, ?)", arrayOf(word, translation))
        db.close()
    }

    private fun sha256(file: File) = ImportSupport.sha256(file)

    /** 造一个合法的词典包 zip（真 sqlite + manifest 清单对账）。 */
    private fun dictionaryPack(
        packId: String,
        version: String = "1.0.0",
        word: String = "hello",
        translation: String = "int. 你好",
        minAppVersion: Int = 0,
        schemaVersion: Int = 1,
        brokenHash: Boolean = false
    ): File {
        val staging = tempDir("stage-")
        val sqlite = File(staging, "dictionary.sqlite")
        dictionarySqlite(sqlite, word, translation)
        val sha = if (brokenHash) "0".repeat(64) else sha256(sqlite)
        val manifest = JSONObject()
            .put("packId", packId)
            .put("type", "dictionary")
            .put("version", version)
            .put("nameZh", "词典包 $packId")
            .put("schemaVersion", schemaVersion)
            .put("minAppVersion", minAppVersion)
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("path", "dictionary.sqlite").put("sha256", sha)
                        .put("bytes", sqlite.length())
                )
            )
            .put(
                "dictionary",
                JSONObject().put("entryFile", "dictionary.sqlite").put("wordCount", 1)
                    .put("source", "test").put("license", "MIT")
            )
        val zip = File(staging, "$packId.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(PackManifest.FILE_NAME))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("dictionary.sqlite"))
            out.write(sqlite.readBytes())
            out.closeEntry()
        }
        return zip
    }

    private fun install(repo: PackRepository, zip: File) =
        runBlocking { repo.install(Uri.fromFile(zip)) }

    @Test
    fun `first dictionary pack is installed and activated`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val installed = install(repo, dictionaryPack("ecdict-zh"))

        assertEquals("ecdict-zh", installed.packId)
        assertEquals(PackType.DICTIONARY, installed.type)
        val registry = repo.registry()
        assertEquals("ecdict-zh", registry.activeDictionary)
        assertNotNull(repo.dictionarySource())
        assertTrue(repo.dictionarySource()!!.name == "dictionary.sqlite")
        // 落位目录按 type/packId/version 组织
        assertEquals("dictionary/ecdict-zh/1.0.0", installed.dir)
        assertTrue(File(repo.packsRoot, installed.dir).isDirectory)
        // 临时区已清空（不留半装残骸）
        assertEquals(0, File(repo.packsRoot, PackRepository.TEMP_DIR).listFiles().orEmpty().size)
    }

    @Test
    fun `second dictionary pack does not steal activation`() {
        val repo = PackRepository(context, appVersionCode = 15)
        install(repo, dictionaryPack("first"))
        install(repo, dictionaryPack("second", word = "world", translation = "n. 世界"))

        assertEquals("first", repo.registry().activeDictionary)
        assertEquals(2, repo.registry().packs.size)

        runBlocking { repo.setActiveDictionary("second") }
        assertEquals("second", repo.registry().activeDictionary)
        assertTrue(repo.dictionarySource()!!.path.contains("second"))

        runBlocking { repo.setActiveDictionary(null) }
        assertNull(repo.dictionarySource())
    }

    @Test
    fun `switching pack actually changes lookups`() = runBlocking {
        val repo = PackRepository(context, appVersionCode = 15)
        install(repo, dictionaryPack("a", word = "hello", translation = "int. 你好"))
        val dictionary = DictionaryRepository(context) { repo.dictionarySource() }
        val lookup = WordLookup(
            word = "hello", sentence = "hello", paragraph = "hello",
            sentenceOffset = 0, x = 0f, y = 0f
        )

        assertEquals("int. 你好", dictionary.lookup(lookup).entry?.senses?.first()?.text)

        install(repo, dictionaryPack("b", word = "hello", translation = "int. 喂"))
        repo.setActiveDictionary("b")
        // 不 invalidate 的话这里会命中旧库的 LRU，返回「你好」—— 正是要防的回归
        dictionary.invalidate()
        assertEquals("int. 喂", dictionary.lookup(lookup).entry?.senses?.first()?.text)

        repo.setActiveDictionary(null)
        dictionary.invalidate()
        assertNull(repo.dictionarySource())
    }

    @Test
    fun `uninstall removes directory and clears activation`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val installed = install(repo, dictionaryPack("gone"))
        val dir = File(repo.packsRoot, installed.dir)
        assertTrue(dir.isDirectory)

        runBlocking { repo.uninstall("gone") }

        assertFalse(dir.exists())
        assertNull(repo.registry().activeDictionary)
        assertTrue(repo.registry().packs.isEmpty())
        assertNull(repo.dictionarySource())
    }

    @Test
    fun `hash mismatch is rejected and nothing is left behind`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val error = runCatching { install(repo, dictionaryPack("bad", brokenHash = true)) }.exceptionOrNull()

        assertTrue("应报格式/校验错误：$error", error is PackFormatException)
        assertTrue(error?.message.orEmpty(), error!!.message.orEmpty().contains("校验失败"))
        assertTrue(repo.registry().packs.isEmpty())
        assertFalse(File(repo.packsRoot, "dictionary/bad/1.0.0").exists())
        assertEquals(0, File(repo.packsRoot, PackRepository.TEMP_DIR).listFiles().orEmpty().size)
    }

    @Test
    fun `min app version gate blocks install`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val error = runCatching { install(repo, dictionaryPack("future", minAppVersion = 16)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error!!.message.orEmpty().contains("更高版本"))
    }

    @Test
    fun `schema version gate blocks install`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val error = runCatching { install(repo, dictionaryPack("v99", schemaVersion = 99)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error!!.message.orEmpty().contains("格式版本"))
    }

    @Test
    fun `non dictionary payload in dictionary slot is rejected`() {
        val repo = PackRepository(context, appVersionCode = 15)
        // 把 sqlite 换成普通文本：哈希对得上、manifest 也自洽，但表结构不存在
        val staging = tempDir("fake-")
        val payload = File(staging, "dictionary.sqlite").apply { writeText("not a database") }
        val manifest = JSONObject()
            .put("packId", "fake")
            .put("type", "dictionary")
            .put("version", "1.0.0")
            .put("nameZh", "假的")
            .put("schemaVersion", 1)
            .put("files", JSONArray().put(
                JSONObject().put("path", "dictionary.sqlite").put("sha256", sha256(payload))
                    .put("bytes", payload.length())
            ))
            .put("dictionary", JSONObject().put("entryFile", "dictionary.sqlite"))
        val zip = File(staging, "fake.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(PackManifest.FILE_NAME))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("dictionary.sqlite"))
            out.write(payload.readBytes())
            out.closeEntry()
        }

        val error = runCatching { install(repo, zip) }.exceptionOrNull()
        assertTrue("应被表结构探针拦下：$error", error is PackFormatException)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("词典包"))
    }

    @Test
    fun `verify detects tampering`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val installed = install(repo, dictionaryPack("tamper"))
        assertTrue(repo.verify("tamper").ok)

        File(File(repo.packsRoot, installed.dir), "dictionary.sqlite").appendBytes(byteArrayOf(0))
        val result = repo.verify("tamper")
        assertFalse(result.ok)
        assertTrue(
            (result as com.linguareader.shared.packs.PackValidationResult.Rejected).reason.contains("大小不符")
        )
    }


    /** 造一个合法的音频包 zip：audio/<章>/<音色目录>/s<句>-<段>.mp3。 */
    private fun audioPack(
        packId: String = "audio-1",
        bookId: String = "book-1",
        engineTag: String = "engine",
        voice: String = "default",
        pipelineVersion: Int = TtsPipelineContract.VERSION,
        brokenTree: Boolean = false
    ): File {
        val staging = tempDir("audio-stage-")
        val segment = TtsCacheKey.segmentDir(engineTag, voice, pipelineVersion)
        val contents = linkedMapOf(
            "audio/0/$segment/s0-0.mp3" to "one",
            "audio/0/$segment/s0-1.mp3" to "two",
            "audio/1/$segment/s1-0.mp3" to "three"
        )
        contents.forEach { (path, text) ->
            File(staging, path).apply { parentFile?.mkdirs(); writeText(text) }
        }
        val digests = contents.keys.associateWith { sha256(File(staging, it)) }
        fun tree(prefix: String) = PackHasher.treeSha256(prefix, digests)
        val manifest = JSONObject()
            .put("packId", packId)
            .put("type", "audio")
            .put("version", "1.0.0")
            .put("nameZh", "音频包 $packId")
            .put("schemaVersion", 1)
            .put("minAppVersion", 0)
            .put(
                "files",
                JSONArray().apply {
                    digests.entries.sortedBy { it.key }.forEach { (path, sha) ->
                        put(
                            JSONObject().put("path", path).put("sha256", sha)
                                .put("bytes", File(staging, path).length())
                        )
                    }
                }
            )
            .put(
                "audio",
                JSONObject()
                    .put("bookId", bookId)
                    .put("engineTag", engineTag)
                    .put("voice", voice)
                    .put("pipelineVersion", pipelineVersion)
                    .put(
                        "chapters",
                        JSONArray()
                            .put(
                                JSONObject().put("index", 0).put("files", 2)
                                    .put("treeSha256", if (brokenTree) "0".repeat(64) else tree("audio/0"))
                            )
                            .put(
                                JSONObject().put("index", 1).put("files", 1)
                                    .put("treeSha256", tree("audio/1"))
                            )
                    )
            )
        val zip = File(staging, "$packId.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(PackManifest.FILE_NAME))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
            contents.keys.sorted().forEach { path ->
                out.putNextEntry(ZipEntry(path))
                out.write(File(staging, path).readBytes())
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `audio pack installs and resolves by cache key`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val installed = install(repo, audioPack())

        assertEquals(PackType.AUDIO, installed.type)
        val relative = TtsCacheKey.relativePath(0, 0, 0, "engine", "default")
        val resolved = repo.audioPackFile("book-1", relative)
        assertNotNull(resolved)
        assertEquals("one", resolved!!.readText())
        // 别的书、别的音色、别的章都不命中 → 自动降级现场合成
        assertNull(repo.audioPackFile("book-2", relative))
        assertNull(repo.audioPackFile("book-1", TtsCacheKey.relativePath(0, 0, 0, "engine", "other")))
        assertNull(repo.audioPackFile("book-1", TtsCacheKey.relativePath(3, 0, 0, "engine", "default")))
    }

    @Test
    fun `audio pack from another pipeline version is rejected`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val error = runCatching {
            install(repo, audioPack(pipelineVersion = TtsPipelineContract.VERSION + 1))
        }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("朗读管线"))
        assertTrue(repo.registry().packs.isEmpty())
    }

    @Test
    fun `audio pack with tampered chapter tree is rejected`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val error = runCatching { install(repo, audioPack(brokenTree = true)) }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("不符"))
    }

    @Test
    fun `missing manifest is rejected`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val zip = File(tempDir("nomanifest-"), "x.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("hi".toByteArray())
            out.closeEntry()
        }
        val error = runCatching { install(repo, zip) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error!!.message.orEmpty().contains("manifest"))
    }

    @Test
    fun `path traversal inside pack is rejected`() {
        val repo = PackRepository(context, appVersionCode = 15)
        val staging = tempDir("traversal-")
        val manifest = JSONObject()
            .put("packId", "evil")
            .put("type", "dictionary")
            .put("version", "1.0.0")
            .put("nameZh", "evil")
            .put("schemaVersion", 1)
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("path", "dictionary.sqlite")
                        .put("sha256", "a".repeat(64)).put("bytes", 1)
                )
            )
            .put("dictionary", JSONObject().put("entryFile", "dictionary.sqlite"))
        val zip = File(staging, "evil.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(PackManifest.FILE_NAME))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("../escape.txt"))
            out.write("nope".toByteArray())
            out.closeEntry()
        }

        val error = runCatching { install(repo, zip) }.exceptionOrNull()
        assertNotNull(error)
        assertFalse(File(repo.packsRoot, "escape.txt").exists())
        assertFalse(File(repo.packsRoot.parentFile, "escape.txt").exists())
    }
}
