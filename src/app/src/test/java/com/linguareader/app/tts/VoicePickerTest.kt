package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色选择器逻辑（M4 面板）：Kokoro 一台服务器 100+ 音色，必须能搜索并按
 * 「推荐 / 语言·性别」分组，否则选不动。
 */
class VoicePickerTest {

    private val voices = listOf(
        VoiceInfo("af_maple", "en", "female", style = listOf("calm")),
        VoiceInfo("af_sol", "en", "female"),
        VoiceInfo("am_onyx", "en", "male"),
        VoiceInfo("zf_001", "zh", "female"),
        VoiceInfo("zm_009", "zh", "male"),
        VoiceInfo("clone_gandalf", "", ""),
        VoiceInfo("", "en", "female")
    )

    @Test
    fun `search matches id language gender and style`() {
        assertEquals(listOf("af_maple", "af_sol"), VoicePicker.filter(voices, "af_").map { it.id })
        assertEquals(listOf("af_maple"), VoicePicker.filter(voices, "calm").map { it.id })
        // 中文标签与英文码都能搜
        assertEquals(listOf("zf_001", "zm_009"), VoicePicker.filter(voices, "zh").map { it.id })
        assertTrue(VoicePicker.filter(voices, "女").map { it.id }.contains("zf_001"))
        assertEquals(7, VoicePicker.filter(voices, "  ").size)
        assertTrue(VoicePicker.filter(voices, "沒有這個").isEmpty())
    }

    @Test
    fun `recommended group comes first and honours language and gender`() {
        val groups = VoicePicker.groups(voices, language = "en", gender = "male")
        assertTrue(groups.first().title.startsWith("推荐"))
        val recommended = groups.first().options.map { it.voice.id }
        // 英文男 + 语言/性别未标注（不冲突）的音色进推荐
        assertEquals(listOf("am_onyx", "clone_gandalf"), recommended)
        assertTrue(groups.first().options.all { it.recommended })
        // 其余按语言·性别分组，且不含推荐项与不可用音色
        val rest = groups.drop(1).flatMap { it.options }.map { it.voice.id }
        assertEquals(listOf("af_maple", "af_sol", "zf_001", "zm_009").sorted(), rest.sorted())
        assertTrue(rest.none { it.isBlank() })
    }

    @Test
    fun `groups are labelled and the character language sorts first`() {
        val groups = VoicePicker.groups(voices, language = "zh", gender = "female")
        assertTrue(groups.first().title.startsWith("推荐"))
        val titles = groups.drop(1).map { it.title }
        // 中文组（角色语言）优先于英文组
        assertTrue(titles.first().startsWith("中文"))
        assertTrue(titles.any { it.startsWith("英文") })
        assertTrue(titles.all { it.contains("（") })
    }

    @Test
    fun `search narrows the groups and empty result yields no groups`() {
        val filtered = VoicePicker.groups(voices, "en", "female", query = "onyx")
        assertEquals(1, filtered.size)
        assertEquals(listOf("am_onyx"), filtered.single().options.map { it.voice.id })
        assertTrue(VoicePicker.groups(voices, "en", "female", query = "zzz").isEmpty())
        assertTrue(VoicePicker.groups(emptyList(), "en", null).isEmpty())
    }

    @Test
    fun `labels carry gender language and style`() {
        assertEquals("af_maple（女·英文·calm）", VoicePicker.label(voices[0]))
        assertEquals("am_onyx（男·英文）", VoicePicker.label(voices[2]))
        assertEquals("clone_gandalf", VoicePicker.label(voices[5]))
    }
}
