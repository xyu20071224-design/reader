package com.linguareader.shared.packs

import com.linguareader.shared.tts.TtsCacheKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackAudioValidationTest {

    /** 与 manifest 声明的 engineTag/voice 一致的载荷目录段（审查 6-10 起两者必须对应）。 */
    private val seg = TtsCacheKey.segmentDir("engine", "voice")

    private fun root(vararg files: Pair<String, String>): File {
        val dir = File.createTempFile("audio-", "").let { it.delete(); it.mkdirs(); it }
        files.forEach { (path, text) ->
            File(dir, path).apply {
                parentFile?.mkdirs()
                writeText(text)
            }
        }
        return dir
    }

    private fun manifest(
        chapters: List<AudioChapter>,
        files: List<PackFileEntry>
    ): PackManifest = PackManifest(
        packId = "audio-x",
        type = PackType.AUDIO,
        version = "1.0.0",
        nameZh = "音频包",
        nameEn = "",
        schemaVersion = 1,
        minAppVersion = 0,
        files = files,
        payload = PackPayload.Audio(
            bookId = "book-1",
            engineTag = "engine",
            voice = "voice",
            pipelineVersion = 1,
            chapters = chapters
        )
    )

    private fun entries(dir: File): List<PackFileEntry> =
        dir.walkTopDown().filter { it.isFile }.map {
            PackFileEntry(
                it.relativeTo(dir).invariantSeparatorsPath,
                com.linguareader.shared.importer.ImportSupport.sha256(it),
                it.length()
            )
        }.sortedBy { it.path }.toList()

    @Test
    fun `tree hash from digests matches tree hash from disk`() {
        val dir = root(
            "audio/0/$seg/s0-0.mp3" to "one",
            "audio/0/$seg/s0-1.mp3" to "two",
            "audio/1/$seg/s1-0.mp3" to "three"
        )
        val digests = dir.walkTopDown().filter { it.isFile }
            .associate {
                it.relativeTo(dir).invariantSeparatorsPath to
                    com.linguareader.shared.importer.ImportSupport.sha256(it)
            }
        assertEquals(
            PackHasher.treeSha256(File(dir, "audio/0")),
            PackHasher.treeSha256("audio/0", digests)
        )
        assertEquals(PackHasher.treeSha256(dir), PackHasher.treeSha256("", digests))
    }

    @Test
    fun `well formed audio pack passes chapter validation`() {
        val dir = root(
            "audio/0/$seg/s0-0.mp3" to "one",
            "audio/0/$seg/s0-1.mp3" to "two",
            "audio/1/$seg/s1-0.mp3" to "three"
        )
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(
                AudioChapter(0, 2, PackHasher.treeSha256("audio/0", digests)),
                AudioChapter(1, 1, PackHasher.treeSha256("audio/1", digests))
            ),
            files = files
        )
        assertTrue(PackValidator.validateAudioChapters(manifest, digests).ok)
    }

    @Test
    fun `chapter count mismatch is rejected`() {
        val dir = root("audio/0/$seg/s0-0.mp3" to "one", "audio/0/$seg/s0-1.mp3" to "two")
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(AudioChapter(0, 1, PackHasher.treeSha256("audio/0", digests))),
            files = files
        )
        val result = PackValidator.validateAudioChapters(manifest, digests)
        assertFalse(result.ok)
        assertTrue((result as PackValidationResult.Rejected).reason.contains("文件数"))
    }

    @Test
    fun `chapter tree mismatch is rejected`() {
        val dir = root("audio/0/$seg/s0-0.mp3" to "one")
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(AudioChapter(0, 1, "f".repeat(64))),
            files = files
        )
        val result = PackValidator.validateAudioChapters(manifest, digests)
        assertFalse(result.ok)
        assertTrue((result as PackValidationResult.Rejected).reason.contains("内容"))
    }

    @Test
    fun `missing declared chapter is rejected`() {
        val dir = root("audio/0/$seg/s0-0.mp3" to "one")
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(
                AudioChapter(0, 1, PackHasher.treeSha256("audio/0", digests)),
                AudioChapter(1, 1, PackHasher.treeSha256("audio/1", digests))
            ),
            files = files
        )
        val result = PackValidator.validateAudioChapters(manifest, digests)
        assertFalse(result.ok)
        assertTrue((result as PackValidationResult.Rejected).reason.contains("缺少第 1 章"))
    }

    @Test
    fun `file outside declared chapters is rejected`() {
        val dir = root(
            "audio/0/$seg/s0-0.mp3" to "one",
            "audio/9/$seg/s9-0.mp3" to "nine"
        )
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(AudioChapter(0, 1, PackHasher.treeSha256("audio/0", digests))),
            files = files
        )
        val result = PackValidator.validateAudioChapters(manifest, digests)
        assertFalse(result.ok)
        assertTrue((result as PackValidationResult.Rejected).reason.contains("没有第 9 章"))
    }

    /**
     * 第四轮审查 6-10：载荷目录段必须与 manifest 声明的 engineTag/voice 对应，
     * 否则包「装得上但永不命中」（解析路径由 segmentDir 派生）= 静默不可用。
     */
    @Test
    fun `payload segment not matching declared engineTag and voice is rejected`() {
        val dir = root("audio/0/$seg/s0-0.mp3" to "one")
        val files = entries(dir)
        val digests = files.associate { it.path to it.sha256 }
        val manifest = manifest(
            chapters = listOf(AudioChapter(0, 1, PackHasher.treeSha256("audio/0", digests))),
            files = files
        ).let { base ->
            // 只改清单里的 engineTag，载荷目录仍是按旧 tag 算出来的那份
            val audio = base.audioPayload!!
            base.copy(payload = audio.copy(engineTag = "another-engine"))
        }

        val result = PackValidator.validateAudioChapters(manifest, digests)
        assertFalse(result.ok, "引擎身份与目录不符应拒装")
        val reason = (result as PackValidationResult.Rejected).reason
        assertTrue(
            reason.contains("音频包目录与清单不符"),
            "拒绝原因应说明目录与清单不符：$reason"
        )
    }
}
