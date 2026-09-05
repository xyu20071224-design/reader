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
}
