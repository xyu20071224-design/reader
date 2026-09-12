package com.linguareader.app.tts

import com.linguareader.shared.tts.TtsCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四轮审查 R1 / 6-3：引擎身份必须包含**模型与音色设计指令**。
 *
 * 背景：缓存键与音频包路径都由 `engineKey` 派生。此前身份只有「服务器地址」，
 * 同服务器换 `serverModel` 或改 MiMo 风格指令后键不变 ⇒ 继续命中旧模型/旧风格音频，
 * 用户以为设置没生效（报告把此列为"严重"）。
 */
class EngineKeyIdentityTest {

    private fun server(model: String = "tts-1", style: String = "") = CloudTtsSettings(
        mode = TtsEngineMode.OPENAI_COMPAT,
        serverUrl = "http://A.local:8000/",
        serverModel = model,
        mimoStyleInstruction = style
    )

    private fun mimo(style: String) = CloudTtsSettings(
        mode = TtsEngineMode.MIMO,
        mimoStyleInstruction = style
    )

    @Test
    fun `server model is part of the engine identity`() {
        val a = VoiceLibraryLoader.engineKey(server(model = "tts-1"))
        val b = VoiceLibraryLoader.engineKey(server(model = "qwen3-tts"))
        assertNotEquals("换模型必须换身份（否则继续命中旧模型音频）", a, b)
        // 同 URL 同模型必须稳定（否则每次启动都当新引擎，把缓存全废）
        assertEquals(a, VoiceLibraryLoader.engineKey(server(model = "tts-1")))
    }

    @Test
    fun `server url normalisation is unchanged`() {
        // 末尾斜杠/大小写归一后应与不带斜杠的写法同键
        assertEquals(
            VoiceLibraryLoader.engineKey(server()),
            VoiceLibraryLoader.engineKey(
                CloudTtsSettings(
                    mode = TtsEngineMode.OPENAI_COMPAT,
                    serverUrl = "http://a.local:8000",
                    serverModel = "tts-1"
                )
            )
        )
    }

    @Test
    fun `empty server model falls back to the documented default`() {
        assertEquals(
            VoiceLibraryLoader.engineKey(server(model = "tts-1")),
            VoiceLibraryLoader.engineKey(server(model = "   "))
        )
    }

    @Test
    fun `mimo style instruction is part of the engine identity`() {
        val plain = VoiceLibraryLoader.engineKey(mimo(""))
        val styled = VoiceLibraryLoader.engineKey(mimo("用低沉沙哑的嗓音"))
        assertNotEquals("改风格指令必须换身份（否则继续播旧风格音频）", plain, styled)
        assertEquals(plain, VoiceLibraryLoader.engineKey(mimo("")))
        assertEquals(styled, VoiceLibraryLoader.engineKey(mimo("用低沉沙哑的嗓音")))
    }

    @Test
    fun `system engine identity is untouched (no model concept)`() {
        assertEquals("system", VoiceLibraryLoader.engineKey(CloudTtsSettings(mode = TtsEngineMode.SYSTEM)))
    }

    @Test
    fun `engine identity stays bounded in the derived cache path`() {
        // 身份本身含 `://`（既有设计，segmentDir 会对它取哈希），真正要守的是
        // **派生出的缓存目录名**必须短且不含路径分隔符 —— 否则会撑爆路径或跨目录。
        val key = VoiceLibraryLoader.engineKey(server(model = "m".repeat(400)))
        val segment = TtsCacheKey.segmentDir(key, "narrator.wav")
        assertTrue("派生目录名不应过长：${segment.length}", segment.length < 80)
        assertTrue("派生目录名不得含路径分隔符：$segment", '/' !in segment && '\\' !in segment)
        assertTrue("派生目录名应带引擎哈希前缀：$segment", segment.startsWith("e"))
    }
}
