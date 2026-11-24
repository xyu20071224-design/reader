package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Test

/** 只调用生产对齐入口，损坏侧车须与不提供侧车的保守路径等价。 */
class SentenceSidecarIntegrityTest {
    private val english = "The hobbit lived in a hole. It was a comfortable hole."
    private val paragraph = "哈比人住在洞里。那是个舒服的洞。"

    private fun align(lines: List<String>?): List<AlignedSentencePair> = TranslationAligner.align(
        listOf(listOf(english)),
        listOf(listOf(paragraph)),
        meaning = null,
        zhSplit = TranslationAligner.ZhSplitPolicy.WITHOUT_SEMICOLON.copy(
            knownSentences = lines?.let { mapOf(paragraph to it) }
        )
    )

    @Test
    fun `sidecar containing different text is ignored`() {
        assertEquals(align(null), align(listOf("小精灵奔向森林。", "那是一片古老森林。")))
    }

    @Test
    fun `sidecar containing too many boundaries is ignored`() {
        assertEquals(align(null), align(listOf("哈比人", "住在洞里。", "那是个舒服的洞。")))
    }

    @Test
    fun `sidecar with blank boundaries is ignored`() {
        assertEquals(align(null), align(listOf("", paragraph)))
    }

    @Test
    fun `valid boundaries without inserted spaces are still accepted`() {
        assertEquals(listOf("哈比人住在洞里。", "那是个舒服的洞。"),
            align(listOf("哈比人住在洞里。", "那是个舒服的洞。")).map { it.zhSentence })
    }

    @Test
    fun `valid boundaries tolerate mixed spaces at chunk joins`() {
        val en = "First bird flew away. Second bird stayed home. Third bird sang softly."
        val lines = listOf("第一只鸟飞走了。", "第二只鸟留在家。", "第三只鸟轻轻唱歌。")
        val zh = lines[0] + " " + lines[1] + lines[2]
        val pairs = TranslationAligner.align(
            listOf(listOf(en)), listOf(listOf(zh)), null,
            TranslationAligner.ZhSplitPolicy.withKnownSentences(mapOf(zh to lines))
        )
        assertEquals(lines, pairs.map { it.zhSentence })
    }

    @Test
    fun `valid constructive boundaries may group several Chinese sentences`() {
        val en = "The old bird flew home and found a warm place to rest. The young bird stayed outside."
        val lines = listOf("老鸟飞回家了。它找到了温暖的休息处。", "小鸟留在外面。")
        val zh = lines.joinToString("")
        val pairs = TranslationAligner.align(
            listOf(listOf(en)), listOf(listOf(zh)), null,
            TranslationAligner.ZhSplitPolicy.withKnownSentences(mapOf(zh to lines))
        )
        assertEquals(lines, pairs.map { it.zhSentence })
    }
}
