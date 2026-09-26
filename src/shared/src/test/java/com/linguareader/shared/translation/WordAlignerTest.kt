package com.linguareader.shared.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAlignerTest {

    @Test
    fun `numbers align by anchor`() {
        val alignment = WordAligner.align(
            enWord = "1420",
            enSentence = "It happened in 1420.",
            zhSentence = "那是在1420年。",
            candidates = emptyList()
        )

        assertNotNull(alignment)
        assertEquals(WordAlignmentSource.ANCHOR, alignment!!.source)
        assertEquals("1420", alignment.word)
        assertEquals(3, alignment.start)
        assertEquals(7, alignment.endExclusive)
    }

    @Test
    fun `latin proper nouns align by anchor even inside a chinese sentence`() {
        val alignment = WordAligner.align(
            enWord = "Frodo",
            enSentence = "Frodo smiled.",
            zhSentence = "Frodo 笑了。",
            candidates = emptyList()
        )

        assertEquals(WordAlignmentSource.ANCHOR, alignment?.source)
        assertEquals(0, alignment?.start)
    }

    @Test
    fun `dictionary senses locate the chinese term when no anchor applies`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "in a hole in the ground",
            zhSentence = "在地下的洞穴里",
            candidates = listOf("洞穴；孔洞（地下的）"),
            enOffset = 5
        )

        assertNotNull(alignment)
        assertEquals(WordAlignmentSource.DICTIONARY, alignment!!.source)
        assertEquals("洞穴", alignment.word)
        assertEquals(4, alignment.start)
        assertEquals(6, alignment.endExclusive)
    }

    @Test
    fun `a book specific translation wins through the prefer bonus`() {
        val alignment = WordAligner.align(
            enWord = "ring",
            enSentence = "the ring was lost",
            zhSentence = "那枚魔戒不见了",
            candidates = emptyList(),
            enOffset = 4,
            prefer = mapOf("魔戒" to 0.3f)
        )

        assertEquals("魔戒", alignment?.word)
        assertEquals(WordAlignmentSource.DICTIONARY, alignment?.source)
    }

    @Test
    fun `single character senses align too`() {
        // 常见词的中文译法往往只有一个字（洞/水/火），以前被 length >= 2 过滤掉，
        // 结果这些词永远只有句级对照、没有词级高亮。
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "in a hole in the ground",
            zhSentence = "在地下的洞里",
            candidates = listOf("洞；孔"),
            enOffset = 5
        )

        assertNotNull(alignment)
        assertEquals("洞", alignment!!.word)
        assertEquals(WordAlignmentSource.DICTIONARY, alignment.source)
        assertEquals("洞", "在地下的洞里".substring(alignment.start, alignment.endExclusive))
    }

    @Test
    fun `single non chinese fragments are still rejected`() {
        // 「x」虽然出现在中文句里，也不能当候选去标注。
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "in a hole",
                zhSentence = "x 在这里",
                candidates = listOf("洞, x")
            )
        )
    }

    @Test
    fun `returns null when nothing matches so the caller can degrade`() {
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "in a hole",
                zhSentence = "完全无关的句子",
                candidates = listOf("洞穴")
            )
        )
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "in a hole",
                zhSentence = "",
                candidates = listOf("洞穴")
            )
        )
    }

    /**
     * BUG-039：`MIN_CONFIDENCE`（0.40）此前恒被满足 —— 位置惩罚上限只有 0.35，
     * 置信度理论下限 0.65，`takeIf { confidence >= MIN_CONFIDENCE }` 等于没有过滤。
     * 单字候选（的/地/上/中…）在译句里几乎必然命中，于是「高亮与释义对不上」。
     *
     * 本用例构造「位置最差」的单字候选：英文词在句首（enPos≈0）、中文候选在句末
     * （zhPos≈0.89）。修前置信度 ≈0.69 会被放行（本用例红），修后应被阈值拒绝（绿）。
     */
    @Test
    fun `worst positioned single character candidate is rejected by the threshold`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "hole in the ground",
            zhSentence = "他没有看见那个洞",
            candidates = listOf("洞"),
            enOffset = 0
        )

        assertNull(alignment)
    }

    // --- Q1-t09：语境优选加成 / 分档阈值 / 来源义项 -------------------------

    /**
     * Q1-t09 ②：`prefer` 加成用来在**同一批候选之间**分胜负 —— 位置更远但来自
     * 「语境优选」义项的候选要能胜出。这里「孔洞」比「洞穴」远离英文词 0.18 句长，
     * 不给加成时「洞穴」赢（1.007 vs 0.943），给了
     * [WordAligner.CONTEXT_PREFERRED_BONUS] 后「孔洞」赢。
     */
    @Test
    fun `context preferred candidate wins even when it sits farther from the english word`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "in a hole in the ground",
            zhSentence = "洞穴与孔洞",
            candidates = listOf("n. 洞穴", "n. 孔洞"),
            enOffset = 5,
            prefer = mapOf("孔洞" to WordAligner.CONTEXT_PREFERRED_BONUS)
        )

        assertEquals("孔洞", alignment?.word)
        assertEquals("n. 孔洞", alignment?.sourceSense)
        assertEquals(true, alignment?.sourceSensePreferred)
    }

    /**
     * Q1-t09 ②③ 的分界：加成只改**排序**，不改**接受门槛** —— 即便候选来自语境优选
     * 义项，位置最差的那个出现点仍被拒。否则单字位置护栏（BUG-039）会被加成重新
     * 打开：0.30（位置最差时的置信度）+ 0.30（加成）= 0.60 ≥ 0.40。
     */
    @Test
    fun `a context bonus cannot rescue a worst positioned candidate`() {
        assertNull(
            WordAligner.align(
                enWord = "hole",
                enSentence = "hole in the ground",
                zhSentence = "他没有看见那个洞",
                candidates = listOf("n. 洞"),
                enOffset = 0,
                prefer = mapOf("洞" to WordAligner.CONTEXT_PREFERRED_BONUS)
            )
        )
    }

    /**
     * Q1-t09 ③：阈值分档后多字候选也有了**可拒绝区间**。修前多字位置惩罚上限
     * 0.35 → 置信度下限 0.65 > 0.40，`takeIf` 对多字候选恒真（本用例红）。
     * 这里 2 字候选「洞穴」落在句末（Δ≈0.83）→ 置信度 0.708 < 0.72，被本档阈值拒绝；
     * 同一条候选位置正常时仍被保留（见 `dictionary senses locate...` 与
     * `the hit points back at the sense...`）。
     */
    @Test
    fun `worst positioned two character candidate is rejected by its own tier`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "hole in the ground",
            zhSentence = "他从来没有看见过那个洞穴",
            candidates = listOf("洞穴"),
            enOffset = 0
        )

        assertNull(alignment)
    }

    /**
     * Q1-t09 ②③ 的结构：**先过闸、再排序**。这里「孔洞」被加成顶成排序第一，但它落在
     * 句末（Δ≈0.83）过不了 2 字档阈值；若按修前「先按 score 选第一名、最后再 takeIf」
     * 的形态，位置正常的「洞穴」会被这个落选的第一名一起挡掉，整句白白丢掉高亮。
     */
    @Test
    fun `a rejected far candidate does not shadow a viable near one`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "hole in the ground",
            zhSentence = "洞穴与那个很大很大的孔洞",
            candidates = listOf("n. 洞穴", "n. 孔洞"),
            enOffset = 0,
            prefer = mapOf("孔洞" to WordAligner.CONTEXT_PREFERRED_BONUS)
        )

        assertEquals("洞穴", alignment?.word)
        assertEquals("n. 洞穴", alignment?.sourceSense)
    }

    /**
     * Q1-t09 ③ 的结构护栏：**每一档都必须有非空的可拒绝区间**。
     *
     * 可拒绝 ⟺ 存在相对位置差 Δ ∈ [0,1] 使 1 − wΔ < T；Δ 能取到 1，故等价于
     * `T > 1 − w`。修前多字档是 T=0.40、w=0.35 → 0.40 < 0.65，恒真。
     */
    @Test
    fun `every length tier keeps a non empty rejection window`() {
        for (length in 1..4) {
            val threshold = WordAligner.minConfidenceFor(length)
            val floor = 1.0 - WordAligner.positionWeightFor(length)
            assertTrue(
                "长度 $length 档：阈值 $threshold 不高于位置最差时的置信度 $floor，该档恒被放行",
                threshold > floor
            )
        }
    }

    /**
     * Q1-t09 ④：词级命中要能指回**具体义项** —— `sourceSense` 与面板
     * `DictionarySense.text` 是同一个串，UI 据此把高亮词与义项互相指认。
     */
    @Test
    fun `the hit points back at the sense it was extracted from`() {
        val alignment = WordAligner.align(
            enWord = "hole",
            enSentence = "in a hole in the ground",
            zhSentence = "在地下的洞穴里",
            candidates = listOf("n. 洞穴；孔洞", "v. 挖洞"),
            enOffset = 5
        )

        assertEquals("洞穴", alignment?.word)
        assertEquals("n. 洞穴；孔洞", alignment?.sourceSense)
        assertEquals(false, alignment?.sourceSensePreferred)
    }

    /** 锚点命中不来自任何义项行，来源义项必须是空的（否则 UI 会指错行）。 */
    @Test
    fun `anchor hits carry no sense origin`() {
        val alignment = WordAligner.align(
            enWord = "Frodo",
            enSentence = "Frodo smiled.",
            zhSentence = "Frodo 笑了。",
            candidates = listOf("n. 佛罗多")
        )

        assertEquals(WordAlignmentSource.ANCHOR, alignment?.source)
        assertNull(alignment?.sourceSense)
        assertEquals(false, alignment?.sourceSensePreferred)
    }
}
