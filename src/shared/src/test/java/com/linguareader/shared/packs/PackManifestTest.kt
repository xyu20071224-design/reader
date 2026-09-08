package com.linguareader.shared.packs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackManifestTest {

    private fun dictionaryJson(
        packId: String = "ecdict-zh",
        version: String = "1.0.0",
        files: String = """[{"path":"dictionary.sqlite","sha256":"${"a".repeat(64)}","bytes":10}]""",
        payload: String = """{"entryFile":"dictionary.sqlite","wordCount":340000,"source":"ECDICT","license":"MIT"}""",
        schemaVersion: Int = 1,
        minAppVersion: Int = 0
    ): String = """
        {
          "packId": "$packId",
          "type": "dictionary",
          "version": "$version",
          "nameZh": "ECDICT 完整词典",
          "nameEn": "ECDICT Full Dictionary",
          "schemaVersion": $schemaVersion,
          "minAppVersion": $minAppVersion,
          "files": $files,
          "dictionary": $payload
        }
    """.trimIndent()

    @Test
    fun `parses dictionary manifest and round-trips`() {
        val manifest = PackManifest.parse(dictionaryJson())

        assertEquals("ecdict-zh", manifest.packId)
        assertEquals(PackType.DICTIONARY, manifest.type)
        assertEquals("1.0.0", manifest.version)
        assertEquals("ECDICT 完整词典", manifest.nameZh)
        assertEquals("dictionary.sqlite", manifest.dictionaryEntryFile)
        assertEquals(340_000, (manifest.payload as PackPayload.Dictionary).wordCount)
        assertEquals("dictionary.sqlite", manifest.fileEntry("dictionary.sqlite")?.path)

        val again = PackManifest.parse(manifest.toJson())
        assertEquals(manifest, again)
    }

    @Test
    fun `missing required fields are rejected with readable message`() {
        val error = assertFailsWith<PackFormatException> {
            PackManifest.parse("""{"type":"dictionary","version":"1.0.0"}""")
        }
        assertTrue(error.message.orEmpty().contains("packId"))
    }

    @Test
    fun `unknown type is rejected`() {
        val error = assertFailsWith<PackFormatException> {
            PackManifest.parse(dictionaryJson().replace("\"dictionary\",", "\"fonts\","))
        }
        assertTrue(error.message.orEmpty().contains("类型"))
    }

    @Test
    fun `packId and version must be path-safe`() {
        // packId 与 version 都会被拼进磁盘路径，`..` 必须当场拦下。
        assertFailsWith<PackFormatException> { PackManifest.parse(dictionaryJson(packId = "../evil")) }
        assertFailsWith<PackFormatException> { PackManifest.parse(dictionaryJson(version = "../../etc")) }
        assertFailsWith<PackFormatException> { PackManifest.parse(dictionaryJson(packId = "Ecdict")) }
    }

    @Test
    fun `payload path traversal is rejected`() {
        assertFailsWith<PackFormatException> {
            PackManifest.parse(
                dictionaryJson(
                    files = """[{"path":"../escape.sqlite","sha256":"${"a".repeat(64)}","bytes":10}]""",
                    payload = """{"entryFile":"../escape.sqlite"}"""
                )
            )
        }
        assertFailsWith<PackFormatException> {
            PackManifest.parse(
                dictionaryJson(files = """[{"path":"/abs.sqlite","sha256":"${"a".repeat(64)}","bytes":10}]""")
            )
        }
        assertFailsWith<PackFormatException> {
            PackManifest.parse(
                dictionaryJson(files = """[{"path":"a\\b.sqlite","sha256":"${"a".repeat(64)}","bytes":10}]""")
            )
        }
    }

    @Test
    fun `entryFile must be declared in files`() {
        val error = assertFailsWith<PackFormatException> {
            PackManifest.parse(
                dictionaryJson(
                    payload = """{"entryFile":"missing.sqlite"}"""
                )
            )
        }
        assertTrue(error.message.orEmpty().contains("files"))
    }

    @Test
    fun `duplicate file entries are rejected`() {
        val sha = "a".repeat(64)
        assertFailsWith<PackFormatException> {
            PackManifest.parse(
                dictionaryJson(
                    files = """[{"path":"a.sqlite","sha256":"$sha","bytes":1},
                                {"path":"a.sqlite","sha256":"$sha","bytes":1}]""",
                    payload = """{"entryFile":"a.sqlite"}"""
                )
            )
        }
    }

    @Test
    fun `audio payload carries pipeline version and chapters`() {
        val json = """
            {
              "packId": "book-audio", "type": "audio", "version": "1.0.0",
              "nameZh": "魔戒 听书包", "schemaVersion": 1, "minAppVersion": 15,
              "files": [{"path":"audio/0/e1~v1~voice/s1-0.mp3","sha256":"${"b".repeat(64)}","bytes":5}],
              "audio": {
                "bookId": "abc123", "engineTag": "server:http://x", "voice": "v1",
                "pipelineVersion": 1,
                "chapters": [{"index":1,"files":2,"treeSha256":"${"c".repeat(64)}"},
                             {"index":0,"files":1,"treeSha256":"${"d".repeat(64)}"}]
              }
            }
        """.trimIndent()
        val manifest = PackManifest.parse(json)
        val audio = manifest.audioPayload
        assertEquals("abc123", audio?.bookId)
        assertEquals(1, audio?.pipelineVersion)
        // 章号按升序归一化，读侧不用再排一遍
        assertEquals(listOf(0, 1), audio?.chapters?.map { it.index })
        assertEquals(manifest, PackManifest.parse(manifest.toJson()))
    }

    @Test
    fun `voice payload requires sample for clone mode`() {
        val base = """
            {
              "packId": "voices-x", "type": "voice", "version": "1.0.0",
              "nameZh": "音色包", "schemaVersion": 1,
              "files": [{"path":"samples/a.wav","sha256":"${"e".repeat(64)}","bytes":9}],
              "voice": {"voices": [%s]}
            }
        """.trimIndent()
        val metadata = String.format(base, """{"key":"narrator","displayName":"旁白","language":"en","mode":"metadata"}""")
        assertEquals(1, PackManifest.parse(metadata).voicePayload?.voices?.size)

        val cloneOk = String.format(
            base,
            """{"key":"hero","language":"en","mode":"clone","sample":"samples/a.wav"}"""
        )
        assertEquals("samples/a.wav", PackManifest.parse(cloneOk).voicePayload?.voices?.first()?.sample)

        val cloneMissing = String.format(
            base,
            """{"key":"hero","language":"en","mode":"clone","sample":"samples/missing.wav"}"""
        )
        assertFailsWith<PackFormatException> { PackManifest.parse(cloneMissing) }

        val cloneNoSample = String.format(base, """{"key":"hero","language":"en","mode":"clone"}""")
        assertFailsWith<PackFormatException> { PackManifest.parse(cloneNoSample) }
    }

    @Test
    fun `name falls back to nameZh`() {
        val manifest = PackManifest.parse(dictionaryJson().replace(""" "nameEn": "ECDICT Full Dictionary",""", ""))
        assertEquals("ECDICT 完整词典", manifest.name)
    }

    @Test
    fun `schema gate rejects newer payload format`() {
        val manifest = PackManifest.parse(dictionaryJson(schemaVersion = PackSchema.DICTIONARY + 1))
        val result = PackValidator.validateManifest(manifest, appVersionCode = 99)
        assertFalse(result.ok)
        assertTrue((result as PackValidationResult.Rejected).reason.contains("格式版本"))
    }

    @Test
    fun `min app version gate rejects too-old app`() {
        val manifest = PackManifest.parse(dictionaryJson(minAppVersion = 16))
        assertFalse(PackValidator.validateManifest(manifest, appVersionCode = 15).ok)
        assertTrue(PackValidator.validateManifest(manifest, appVersionCode = 16).ok)
    }

    @Test
    fun `audio pack with no chapters is rejected`() {
        val json = """
            {"packId":"x","type":"audio","version":"1.0.0","nameZh":"x","schemaVersion":1,
             "files":[{"path":"audio/0/a.mp3","sha256":"${"a".repeat(64)}","bytes":1}],
             "audio":{"bookId":"b","engineTag":"e","voice":"v","pipelineVersion":1,"chapters":[]}}
        """.trimIndent()
        assertFailsWith<PackFormatException> { PackManifest.parse(json) }
    }

    @Test
    fun `dictionary payload absent for other types`() {
        val json = dictionaryJson().replace("\"dictionary\":", "\"voice\":")
        assertFailsWith<PackFormatException> { PackManifest.parse(json) }
        assertNull(PackManifest.parse(dictionaryJson()).audioPayload)
    }
}
