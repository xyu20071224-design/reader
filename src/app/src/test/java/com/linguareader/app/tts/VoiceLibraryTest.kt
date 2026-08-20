package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 voice-library tests (PLAN-MULTI-VOICE §3.4): naming priors for the engines
 * that only expose bare voice ids, voice similarity used as the distinctness
 * penalty, and sentence language routing.
 */
class VoiceLibraryTest {

    @Test
    fun kokoroNamingPriorsGiveLanguageAndGender() {
        assertEquals(VoiceInfo("zf_001", "zh", "female", source = "kokoro"), VoiceNaming.infer("zf_001", "kokoro"))
        assertEquals(VoiceInfo("zm_009", "zh", "male", source = "kokoro"), VoiceNaming.infer("zm_009", "kokoro"))
        assertEquals(VoiceInfo("af_maple", "en", "female", source = "kokoro"), VoiceNaming.infer("af_maple", "kokoro"))
        assertEquals(VoiceInfo("am_onyx", "en", "male", source = "kokoro"), VoiceNaming.infer("am_onyx", "kokoro"))
        // British voices are still English for assignment purposes.
        assertEquals("en", VoiceNaming.infer("bf_alice").language)
        assertEquals("male", VoiceNaming.infer("bm_george").gender)
        assertEquals("ja", VoiceNaming.infer("jf_alpha").language)
    }

    @Test
    fun volcanoAndAzureIdsAreUnderstood() {
        val volc = VoiceNaming.infer("zh_female_shuangkuaisisi_uranus_bigtts", "volc")
        assertEquals("zh", volc.language)
        assertEquals("female", volc.gender)

        val volcEn = VoiceNaming.infer("en_male_tim_uranus_bigtts", "volc")
        assertEquals("en", volcEn.language)
        assertEquals("male", volcEn.gender)

        // Azure short names carry the locale; gender comes from metadata.
        val azure = VoiceNaming.infer("zh-CN-XiaoxiaoNeural", "azure")
        assertEquals("zh", azure.language)
        assertEquals("", azure.gender)
        assertEquals("en", VoiceNaming.infer("en-US-AriaNeural", "azure").language)
    }

    @Test
    fun unknownIdsStayUnconstrained() {
        val unknown = VoiceNaming.infer("default", "kokoro")
        assertEquals("", unknown.language)
        assertEquals("", unknown.gender)
        // A voice without a language can read anything (multilingual/unknown).
        assertTrue(unknown.speaks("zh"))
        assertTrue(unknown.speaks("en"))
    }

    @Test
    fun cloneVoiceNamesCarryLanguageAndGender() {
        // M1.5 / §12.2: clone_<角色>_<lang>_<gender>, extension optional.
        val gandalf = VoiceNaming.infer("clone_gandalf_en_m.wav", "server")
        assertEquals("clone_gandalf_en_m.wav", gandalf.id)
        assertEquals("en", gandalf.language)
        assertEquals("male", gandalf.gender)
        assertEquals("clone", VoiceNaming.infer("clone_x", "").source)

        val chinese = VoiceNaming.infer("clone_甘道夫_zh_female", "server")
        assertEquals("zh", chinese.language)
        assertEquals("female", chinese.gender)

        // A bare clone name is still recognised, just without constraints.
        val bare = VoiceNaming.infer("clone_gandalf", "server")
        assertEquals("", bare.language)
        assertEquals("", bare.gender)
        assertTrue(bare.speaks("zh"))
    }

    @Test
    fun audioExtensionsDoNotHideTheNamingPriors() {
        // IndexTTS advertises file names; the id stays intact for the request.
        val kokoro = VoiceNaming.infer("zf_001.wav", "server")
        assertEquals("zf_001.wav", kokoro.id)
        assertEquals("zh", kokoro.language)
        assertEquals("female", kokoro.gender)
        // Reference recordings without any convention stay unconstrained.
        assertEquals("", VoiceNaming.infer("voice_03.wav", "server").language)
        assertEquals("", VoiceNaming.infer("first_3s_1.wav", "server").gender)
    }

    @Test
    fun similarityRewardsSameGenderLanguageAndStyle() {
        val a = VoiceInfo("a", "en", "female", listOf("calm"))
        val b = VoiceInfo("b", "en", "female", listOf("calm"))
        val c = VoiceInfo("c", "en", "male", listOf("deep"))
        assertEquals(1f, VoiceLibrary.similarity(a, a))
        assertEquals(1f, VoiceLibrary.similarity(a, b))
        // Different gender, same language, no style overlap.
        assertEquals(0.2f, VoiceLibrary.similarity(a, c))
        assertTrue(VoiceLibrary.similarity(a, b) > VoiceLibrary.similarity(a, c))
    }

    @Test
    fun libraryFiltersByLanguageAndAvailability() {
        val library = VoiceLibrary(
            listOf(
                VoiceInfo("af_maple", "en", "female"),
                VoiceInfo("zf_001", "zh", "female"),
                VoiceInfo("multi", "", "female"),
                VoiceInfo("", "en", "male")
            ),
            engine = "server:test"
        )
        assertEquals(listOf("af_maple", "multi"), library.forLanguage("en").map { it.id })
        assertEquals(listOf("zf_001", "multi"), library.forLanguage("zh").map { it.id })
        assertEquals("af_maple", library.byId("af_maple")?.id)
        assertTrue(!library.isEmpty)
        assertTrue(VoiceLibrary().isEmpty)
    }

    @Test
    fun sentenceLanguageRouting() {
        assertEquals("zh", TtsLanguage.of("他说：走吧。"))
        assertEquals("en", TtsLanguage.of("Fly, you fools."))
        assertEquals("zh", TtsLanguage.of("Mixed 中文 text"))
    }
}
