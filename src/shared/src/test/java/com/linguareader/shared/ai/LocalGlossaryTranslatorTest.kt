package com.linguareader.shared.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地轻量语境的专名判定：句首大写的普通功能词（And/As/At…）不得混进角色表
 * ——它们曾被当成 character 导入术语表，在多角色面板里显示为「角色音色」。
 */
class LocalGlossaryTranslatorTest {

    private fun chapters(vararg texts: String): List<ChapterText> =
        texts.mapIndexed { index, text -> ChapterText(index, "c$index", text) }

    @Test
    fun sentenceInitialFunctionWordsNeverBecomeCharacters() = runBlocking {
        val translator = LocalGlossaryTranslator()
        // And/As/At 每句开头都大写、跨章高频——旧实现会把它们全部收进 characters。
        val profile = translator.buildBookContext(
            "t",
            chapters(
                "And he went home. As it was late. At least he tried.",
                "And she stayed. As usual. At dawn they left."
            )
        )
        val names = profile.characters.map { it.term.lowercase() }
        assertFalse(names.contains("and"))
        assertFalse(names.contains("as"))
        assertFalse(names.contains("at"))
    }

    @Test
    fun properNounsRepeatedAcrossChaptersAreKept() = runBlocking {
        val translator = LocalGlossaryTranslator()
        val profile = translator.buildBookContext(
            "t",
            chapters(
                "Frodo left early. The road was long, and Frodo walked alone.",
                "Frodo rested. Gandalf arrived, and Frodo smiled."
            )
        )
        val names = profile.characters.map { it.term.lowercase() }
        assertTrue(names.contains("frodo"))
    }

    @Test
    fun capitalizedNameOnlyAtSentenceStartIsRejected() = runBlocking {
        val translator = LocalGlossaryTranslator()
        // 一个从不出现在句中位置的词，即使跨章大写也不算专名。
        val profile = translator.buildBookContext(
            "t",
            chapters(
                "Zyxwv went home.",
                "Zyxwv went home again."
            )
        )
        assertFalse(profile.characters.any { it.term.equals("Zyxwv", ignoreCase = true) })
    }
}
