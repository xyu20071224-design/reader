package com.linguareader.shared.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/releases/latest` 响应解析：资产挑选、字段映射、坏输入。 */
class GitHubReleaseParserTest {

    private val sample = """
        {
          "tag_name": "v1.6.0",
          "name": "1.6.0",
          "body": "## 新特性\n- 自动更新",
          "published_at": "2026-08-30T00:00:00Z",
          "assets": [
            {
              "name": "source.zip",
              "browser_download_url": "https://github.com/xyu20071224-design/reader/archive/v1.6.0.zip"
            },
            {
              "name": "LinguaReader-v1.6.0.apk",
              "browser_download_url": "https://github.com/xyu20071224-design/reader/releases/download/v1.6.0/LinguaReader-v1.6.0.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses tag version apk url and notes`() {
        val info = GitHubReleaseParser.parse(sample)
        assertNotNull(info)
        assertEquals("v1.6.0", info!!.tag)
        assertEquals("1.6.0", info.versionName)
        assertEquals("LinguaReader-v1.6.0.apk", info.apkName)
        assertEquals(
            "https://github.com/xyu20071224-design/reader/releases/download/v1.6.0/LinguaReader-v1.6.0.apk",
            info.downloadUrl
        )
        assertTrue(info.releaseNotes.contains("自动更新"))
        assertEquals("2026-08-30T00:00:00Z", info.publishedAt)
    }

    @Test
    fun `release without apk asset returns null`() {
        val noApk = sample
            .replace("\"name\": \"LinguaReader-v1.6.0.apk\",", "\"name\": \"ignored.bin\",")
            .replace("releases/download/v1.6.0/LinguaReader-v1.6.0.apk", "download/v1.6.0/ignored.bin")
        assertNull(GitHubReleaseParser.parse(noApk))
    }

    @Test
    fun `bad json or missing tag returns null`() {
        assertNull(GitHubReleaseParser.parse("not json"))
        assertNull(GitHubReleaseParser.parse("{}"))
        assertNull(GitHubReleaseParser.parse("{\"tag_name\":\"v1.6.0\"}"))
        assertNull(GitHubReleaseParser.parse(""))
    }

    // ---- Q2-c03：Release 里的资源包资产 ----

    private val packSample = """
        {
          "tag_name": "v1.6.0",
          "assets": [
            {"name": "LinguaReader-v1.6.0.apk", "browser_download_url": "https://x/apk", "size": 11},
            {"name": "dictionary-en.lrpack", "browser_download_url": "https://x/dict", "size": 1024},
            {"name": "audio-hobbit.LRPACK", "browser_download_url": "https://x/audio", "size": 2048},
            {"name": "notes.txt", "browser_download_url": "https://x/txt", "size": 3},
            {"name": "broken.lrpack", "size": 5}
          ]
        }
    """.trimIndent()

    @Test
    fun `lists only lrpack assets with url and size`() {
        val packs = GitHubReleaseParser.parsePackAssets(packSample)

        assertEquals(2, packs.size)
        assertEquals("dictionary-en.lrpack", packs[0].name)
        assertEquals("https://x/dict", packs[0].downloadUrl)
        assertEquals(1024L, packs[0].bytes)
        // 扩展名大小写不敏感
        assertEquals("audio-hobbit.LRPACK", packs[1].name)
        // 缺 browser_download_url 的资产被跳过，不会产出坏链接
        assertTrue(packs.none { it.name == "broken.lrpack" })
    }

    @Test
    fun `pack assets parsing degrades to empty list on bad input`() {
        assertTrue(GitHubReleaseParser.parsePackAssets("not json").isEmpty())
        assertTrue(GitHubReleaseParser.parsePackAssets("{}").isEmpty())
        assertTrue(GitHubReleaseParser.parsePackAssets(sample).isEmpty())
        assertTrue(GitHubReleaseParser.parsePackAssets("").isEmpty())
    }
}
