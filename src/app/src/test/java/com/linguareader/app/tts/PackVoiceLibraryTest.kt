package com.linguareader.app.tts

import com.linguareader.shared.packs.PackVoice
import com.linguareader.shared.packs.VoicePackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M4：音色包如何进入既有音色库。
 *
 * 两条路径分开测：**metadata** 是给既有音色 id 打补丁（叠加到自建服务器音色上），
 * **clone** 是新增音色（进 MiMo 音色库，id 带 `pack:` 命名空间）。
 */
class PackVoiceLibraryTest {

    private fun packVoice(
        key: String,
        mode: String,
        language: String = "en",
        gender: String = "female",
        style: List<String> = emptyList()
    ) = PackVoice(
        key = key,
        displayName = key,
        language = language,
        gender = gender,
        style = style,
        mode = mode,
        sample = if (mode == PackVoice.MODE_CLONE) "samples/$key.wav" else null
    )

    @Test
    fun `metadata pack overrides naming priors and server advertised values`() {
        val base = listOf(
            // 服务器只给了裸 id，靠 id 形状猜出 en/female
            VoiceInfo("clone_hero_en_f.wav", "en", "female", source = "server"),
            // 服务器广播了 zh/male，但包是用户显式装的纠错，包优先
            VoiceInfo("narrator.wav", "zh", "male", source = "server")
        )
        val merged = VoiceLibraryLoader.applyPackMetadata(
            base,
            mapOf(
                "narrator.wav" to packVoice("narrator.wav", PackVoice.MODE_METADATA, "en", "female", listOf("calm"))
            )
        )
        assertEquals(2, merged.size)
        val narrator = merged.first { it.id == "narrator.wav" }
        assertEquals("en", narrator.language)
        assertEquals("female", narrator.gender)
        assertEquals(listOf("calm"), narrator.style)
    }

    @Test
    fun `metadata pack can describe a voice the server has not advertised yet`() {
        val merged = VoiceLibraryLoader.applyPackMetadata(
            emptyList(),
            mapOf("future.wav" to packVoice("future.wav", PackVoice.MODE_METADATA, "zh", "male"))
        )
        assertEquals(1, merged.size)
        assertEquals("future.wav", merged.first().id)
        assertEquals("pack", merged.first().source)
    }

    @Test
    fun `empty metadata leaves the library untouched`() {
        val base = listOf(VoiceInfo("a", "en", "male"))
        assertEquals(base, VoiceLibraryLoader.applyPackMetadata(base, emptyMap()))
    }

    @Test
    fun `clone pack voices join the MiMo library under the pack namespace`() {
        val clone = VoicePackSource.CloneVoice(
            packId = "voices-1",
            packName = "音色包",
            packRoot = File("/nonexistent"),
            voice = packVoice("hero", PackVoice.MODE_CLONE, "en", "male", listOf("deep"))
        )
        val library = withPackVoices(listOf(VoiceInfo("Mia", "en", "female")), listOf(clone))

        assertEquals(2, library.size)
        val hero = library.first { it.source == "pack" }
        assertEquals("pack:voices-1/hero", hero.id)
        assertEquals("en", hero.language)
        assertEquals("male", hero.gender)
        assertEquals(listOf("deep"), hero.style)
        // 命名空间让「包里的 Mia」与 MiMo 预置的 Mia 各自成条，不会互相顶掉
        val sameName = withPackVoices(
            listOf(VoiceInfo("Mia")),
            listOf(clone.copy(voice = packVoice("Mia", PackVoice.MODE_CLONE)))
        )
        assertEquals(listOf("Mia", "pack:voices-1/Mia"), sameName.map { it.id })
    }

    @Test
    fun `pack voice ids never collide with built in prefixes`() {
        assertTrue(VoicePackSource.isPackVoice("pack:x/hero"))
        assertTrue(!VoicePackSource.isPackVoice("mimo-clone:hero"))
        assertTrue(!VoicePackSource.isPackVoice("mimo-design:hero"))
    }
}
