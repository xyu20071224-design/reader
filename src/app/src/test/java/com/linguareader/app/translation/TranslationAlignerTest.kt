package com.linguareader.app.translation

import com.linguareader.app.tts.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationAlignerTest {

    @Test
    fun `aligns sentences one to one inside a matching chapter`() {
        val en = listOf(listOf("The hobbit lived in a hole. It was a comfortable hole."))
        val zh = listOf(listOf("哈比人住在洞里。那是个舒服的洞。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(2, pairs.size)
        assertEquals(0, pairs[0].enChapter)
        assertEquals(0, pairs[0].zhChapter)
        assertTrue(pairs[0].enSentence.contains("hobbit"))
        assertEquals("哈比人住在洞里。", pairs[0].zhSentence)
        assertTrue(pairs[1].enSentence.contains("comfortable"))
        assertEquals("那是个舒服的洞。", pairs[1].zhSentence)
        assertTrue(pairs[0].confidence > TranslationAligner.MIN_CONFIDENCE)
    }

    @Test
    fun `all sentence pairs of one paragraph share the same paragraph instance`() {
        val en = listOf(listOf("First one here. Second one here."))
        val zh = listOf(listOf("第一句在这里。第二句在这里。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(2, pairs.size)
        // 段落是引用共享的，内存里不按句复制整段文本。
        assertTrue(pairs[0].enParagraph === pairs[1].enParagraph)
        assertTrue(pairs[0].zhParagraph === pairs[1].zhParagraph)
    }

    @Test
    fun `maps chapter indices from the dp path even when two chapters are identical`() {
        // 回归：旧实现用 enTexts.indexOf(text) 回查下标，两章正文完全相同时
        // 会把它们全部映射到第一处，导致整章错配。
        val duplicate = listOf("Chapter text repeated word for word.")
        val en = listOf(
            duplicate,
            duplicate,
            listOf("A third chapter with entirely different wording inside.")
        )
        val zh = listOf(listOf("重复的章节文字。"), listOf("重复的章节文字。"))

        val pairs = TranslationAligner.align(en, zh)

        assertEquals(listOf(0, 1), pairs.map { it.enChapter }.distinct().sorted())
        assertEquals(listOf(0, 1), pairs.map { it.zhChapter }.distinct().sorted())
    }

    @Test
    fun `paragraphs the dp had to skip fall back to the nearest translated paragraph`() {
        // 段落级 DP 只能 1:1 / 2:1 / 1:2，5 段英文对 2 段中文必然要合并 + 跳过；
        // 无论走哪条路，每个源段落都必须能查到对照（合并成分条目 / 邻近段落兜底）。
        val en = listOf(
            listOf(
                "Alpha one here.",
                "Beta two here.",
                "Gamma three here.",
                "Delta four here.",
                "Epsilon five here."
            )
        )
        val zh = listOf(listOf("中文第一段。", "中文第二段。"))

        val pairs = TranslationAligner.align(en, zh)
        val index = TranslationMemoryIndex(
            TranslationMemory(
                sourceBookId = "s",
                sourceTitle = "t",
                translationBookId = "z",
                translationTitle = "译本",
                alignedAt = 0L,
                pairs = pairs
            )
        )

        en[0].forEach { paragraph ->
            val hit = index.lookup(0, paragraph, paragraph)
            assertNotNull("段落查不到任何对照：$paragraph", hit)
            assertTrue("对照必须落在真实中文段落上：${hit!!.chinese}", hit.chinese.startsWith("中文"))
        }

        val paragraphLevel = pairs.filter { it.enSentence.isBlank() }
        assertTrue("应产出段级条目（合并成分或邻近兜底）", paragraphLevel.isNotEmpty())
        assertTrue(
            "段级条目不得低于查询门槛，否则落盘白占体积",
            paragraphLevel.all { it.confidence >= TranslationMemorySearch.MIN_ACCEPT_CONFIDENCE }
        )
    }

    @Test
    fun `empty input yields no pairs`() {
        assertTrue(TranslationAligner.align(emptyList(), listOf(listOf("中文"))).isEmpty())
        assertTrue(TranslationAligner.align(listOf(listOf("English")), emptyList()).isEmpty())
    }

    @Test
    fun `spaced initials do not end an english sentence`() {
        // 对齐质量依赖分句：J. R. R. 这类带空格缩写必须整体保留在同一句里。
        val sentences = SentenceSplitter.split("J. R. R. Tolkien wrote it. Then he slept.")

        assertEquals(2, sentences.size)
        assertTrue(sentences[0].trim().startsWith("J. R. R. Tolkien"))
    }
    @Test
    fun `closing quotes after a chinese terminator are never orphaned`() {
        // 整段引文以「。」+「」结尾：旧分句会切出纯「』」残渣（魔戒真档 734 条 zs=「」）；
        // 修复后整段引文并为一句，不再产生脏句对。
        val en = listOf(listOf("He said hello. Then he left."))
        val zh = listOf(listOf("「他說：『你好。』」然後他走了。"))

        val pairs = TranslationAligner.align(en, zh)

        assertTrue(pairs.isNotEmpty())
        assertTrue(
            "不得出现纯引号残渣句对：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.isNotBlank() && it.zhSentence.none { c -> c.isLetterOrDigit() } }
        )
        val joined = pairs.map { it.zhSentence }.joinToString("")
        assertTrue(joined.contains("然後他走了"))
    }

    @Test
    fun `a chinese quote-led caption merges into the previous sentence`() {
        // 「。」后紧跟闭合引号 + 引导语：』他大喊：「……」 旧规则切成独立片段；
        // 修复后与引文同句。
        val en = listOf(listOf("\"You are crazy!\" he shouted. \"Go! Go!\""))
        val zh = listOf(listOf("「你有病啊！」他大喊：「快走啊！」"))

        val pairs = TranslationAligner.align(en, zh)

        assertTrue(pairs.isNotEmpty())
        assertTrue(
            "引导语残句必须并入引文：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.trim().startsWith("」") || it.zhSentence.trim().startsWith("』") }
        )
    }

    @Test
    fun `meaningAnchorsSteerTheDpToTheRightSentence`() {
        // 词义锚点（用户方案）：英文词义短语在中文句里的命中数参与 pairCost。
        // 场景：中文段 3 句、英文段 2 句（"He" 必须大写：R3 规则会把
        // 「'…!' he cried」留成一句，锚点无从发力）。无锚时 DP 会把
        // sorry 句配到更长的第三句（纯长度比占优）；带锚后应命中含
        // 「對不起」的第二句。
        val meaning = object : MeaningIndex {
            override fun phrasesOf(word: String): Set<String> = when (word) {
                "sorry" -> setOf("对不起", "抱歉")
                "concern" -> setOf("关怀", "关切")
                "cried" -> setOf("大喊", "哭泣")
                else -> emptySet()
            }
        }
        val en = listOf(listOf(
            "'I am sorry, Frodo!' He cried, full of concern."
        ))
        val zh = listOf(listOf(
            "他上前和亞拉岡說了幾句話。對不起，佛羅多！他滿懷關切地說：「今天發生了好多事。」"
        ))

        val anchoredPairs = TranslationAligner.align(en, zh, meaning)
        val sorry = anchoredPairs.firstOrNull { it.enSentence.contains("I am sorry") }
            ?: error("未找到 sorry 句对")
        assertTrue(
            "词义锚应把 sorry 句配到含「對不起」的中文句：${sorry.zhSentence}",
            sorry.zhSentence.contains("對不起") || sorry.zhSentence.contains("对不起")
        )
    }

    // ---------- V3：句级 1:N 合并门槛 / 兜底段句级 DP / 切句卫生 ----------

    private fun mockIndex(vararg entries: Pair<String, Set<String>>) = object : MeaningIndex {
        override fun phrasesOf(word: String): Set<String> =
            entries.firstOrNull { it.first == word }?.second ?: emptySet()
    }

    @Test
    fun `sentenceLevelMergeJoinsSplitTranslation`() {
        // 译文把一个英文长句拆成两句中文：合并代价远优于 1:1，且门槛三关全过
        // （命中≥1、尺寸达标、比局部最优 1:1 便宜 0.12 以上）→ 应产出 1:2 合并句对。
        val meaning = mockIndex(
            "journey" to setOf("山路"),
            "mountains" to setOf("高山")
        )
        val en = listOf(listOf("The long journey across the high mountains took many days and cost many lives."))
        val zh = listOf(listOf("漫長的山路走了好多天。", "代價是許多條人命。"))

        val pairs = TranslationAligner.align(en, zh, meaning)
        val merged = pairs.firstOrNull { it.enSentence.contains("journey") }
            ?: error("英文句应至少产出一个句对")
        assertTrue(
            "长句应合并两句中文：${merged.zhSentence}",
            merged.zhSentence.contains("山路") && merged.zhSentence.contains("人命")
        )
        assertTrue("合并句对应带 0.85 置信度折扣：${merged.confidence}", merged.confidence < 1f)
    }

    @Test
    fun `mergeGateBlocksHeadingAbsorption`() {
        // 「STRIDER」类标题（1 词）即使有锚点命中也禁合并（s66 教训）。
        // 3 个英文段对 1 个中文段，段落级 DP 必然 2:1 合并并把标题卷进长句；
        // 句级门槛应把它挡下：标题不得出现在任何句对里。
        val meaning = mockIndex("enemy" to setOf("敵人"))
        val en = listOf(
            "STRIDER!",
            "He that is the enemy of the ring must be strong and swift and fearsome to behold.",
            "Night fell upon the sleeping village."
        )
        val zh = listOf("魔戒的敵人必須強壯敏捷令人畏懼。")

        val pairs = TranslationAligner.align(listOf(en), listOf(zh), meaning)
        assertTrue(
            "标题不得出现在任何句对里：${pairs.map { it.enSentence }}",
            pairs.none { it.enSentence.startsWith("STRIDER") }
        )
    }

    @Test
    fun `mergeGateRequiresMeaningOverlap`() {
        // 合并哪怕在长度上占优，没有词义命中也不准合（无语义证据不合并）。
        val meaning = mockIndex()
        val en = listOf(listOf("Alpha beta gamma delta epsilon zeta eta theta."))
        val zh = listOf(listOf("甲乙丙丁。", "戊己庚辛壬。"))

        val pairs = TranslationAligner.align(en, zh, meaning)
        assertTrue(
            "无命中的合并应被拒：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.contains("甲") && it.zhSentence.contains("庚") }
        )
    }

    @Test
    fun `mergeGateRespectsMargin`() {
        // 合并只比局部最优 1:1 好一点点（< 0.12）时禁止合并（s11 教训：
        // 本来就配得好的句子会被贪婪合并抢走正确译文）。
        val meaning = mockIndex("cats" to setOf("養貓"))
        val en = listOf(listOf("The old man kept cats in his house."))
        val zh = listOf(listOf("老人在小屋裡養貓。", "後來他們走了。"))

        val pairs = TranslationAligner.align(en, zh, meaning)
        assertTrue(
            "边际不足的合并应被拒：${pairs.map { it.zhSentence }}",
            pairs.none { it.zhSentence.contains("養貓") && it.zhSentence.contains("走了") }
        )
        val kept = pairs.firstOrNull { it.enSentence.contains("cats") }
        assertTrue(
            "原 1:1 正确配对应保留：${kept?.zhSentence}",
            kept != null && kept.zhSentence.contains("養貓")
        )
    }

    @Test
    fun `skippedParagraphGetsSentenceLevelFallback`() {
        // 段落 DP 跳过的段落（V3 起）应与最近已对齐段落跑句级兜底，
        // 产出句级条目（置信度乘 0.55 兜底折扣）而非只有整段一锅端。
        val meaning = mockIndex("cats" to setOf("養貓"))
        val en = listOf(
            "The old man kept cats in his house.",
            "Tom gave his brother a silver coin yesterday evening. Then he walked home in the rain.",
            "Night fell upon the sleeping village. Stars appeared above the dark hills. A cold wind blew from the mountains."
        )
        val zh = listOf("老人在小屋裡養貓。")

        val pairs = TranslationAligner.align(listOf(en), listOf(zh), meaning)
        val skipped = en[2]
        val sentenceFallback = pairs.filter { it.enParagraph == skipped && it.enSentence.isNotBlank() }
        assertTrue("被跳过段落应有句级兜底条目：${pairs.map { it.enParagraph to it.enSentence }}", sentenceFallback.isNotEmpty())
        assertTrue(
            "句级兜底置信度应落在折扣区间 [0.30, 0.56]：${sentenceFallback.map { it.confidence }}",
            sentenceFallback.all { it.confidence >= 0.30f && it.confidence <= 0.56f }
        )
        // 段级兜底条目只在自身置信度 ≥0.30 时才落盘（本例 C 段与中文段长度比失衡，
        // 段级置信度 ~0.27 被正确丢弃——查询侧 4/5 级反正会拒，落盘只是白占体积）。
        assertTrue(
            "段级兜底条目不得低于查询门槛：${pairs.filter { it.enParagraph == skipped && it.enSentence.isBlank() }.map { it.confidence }}",
            pairs.filter { it.enParagraph == skipped && it.enSentence.isBlank() }
                .all { it.confidence >= 0.30f }
        )
    }

    @Test
    fun `punctuationOnlySentencesAreDropped`() {
        // 切句残渣「 . 」不参与对齐、不产出句对（U 层垃圾句的教训）。
        val en = listOf(listOf("He said. . The end came quickly."))
        val zh = listOf(listOf("他說。", "末日很快就來了。"))

        val pairs = TranslationAligner.align(en, zh)
        assertTrue(
            "纯标点句不得出现在句对里：${pairs.map { it.enSentence }}",
            pairs.none { it.enSentence.trim() == "." }
        )
        assertTrue(
            "正常句子应保留句对：${pairs.map { it.enSentence }}",
            pairs.any { it.enSentence.contains("The end") }
        )
    }
}
