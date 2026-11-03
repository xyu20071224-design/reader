package com.linguareader.shared.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音译匹配单测（纯逻辑，无 Robolectric，无网络）。
 *
 * 覆盖：
 * - 音节切分（元音核 + 辅音簇）
 * - 候选生成（2-5 汉字、有上限、无虚词污染）
 * - 繁简同分（`佛羅多` / `佛罗多`）
 * - 反例得低分（不含该名的中文句）
 * - 缓存有效（同一名字两次调用返回同一实例 & 缓存计数不增长）
 * - 性能（热路径不退化）
 * - 专名抽取
 */
class TransliterationTest {

    // ── 音节切分 ────────────────────────────────────────────────

    @Test
    fun `音节切分 - 双音节名按元音核切开`() {
        assertEquals(listOf("fro", "do"), Transliteration.debugSyllables("Frodo"))
        assertEquals(listOf("bil", "bo"), Transliteration.debugSyllables("Bilbo"))
    }

    @Test
    fun `音节切分 - 元音后单辅音归下一音节`() {
        assertEquals(listOf("gan", "dalf"), Transliteration.debugSyllables("Gandalf"))
        assertEquals(listOf("le", "go", "las"), Transliteration.debugSyllables("Legolas"))
    }

    @Test
    fun `音节切分 - 词尾不发音的 e 被去掉`() {
        assertEquals("shir", Transliteration.debugNormalize("Shire"))
        assertEquals(listOf("shir"), Transliteration.debugSyllables("Shire"))
    }

    @Test
    fun `音节切分 - 带重音与连字符的名字能归一`() {
        assertEquals("khazadum", Transliteration.debugNormalize("Khazad-dûm"))
        assertEquals("frodos", Transliteration.debugNormalize("Frodo's"))
        assertEquals(listOf("fro", "do"), Transliteration.debugSyllables("FRODO"))
    }

    @Test
    fun `音节切分 - 高元音在辅音后裂开`() {
        // Mo-ri-a / An-du-in
        assertEquals(3, Transliteration.debugSyllables("Moria").size)
        assertEquals(3, Transliteration.debugSyllables("Anduin").size)
    }

    // ── 候选生成 ────────────────────────────────────────────────

    @Test
    fun `候选 - 每个候选是 2 到 5 个汉字`() {
        val names = listOf(
            "Frodo", "Gandalf", "Bilbo", "Sam", "Aragorn", "Mordor", "Pippin",
            "Merry", "Boromir", "Legolas", "Gimli", "Elrond", "Saruman", "Gollum",
            "Sauron", "Rivendell", "Moria", "Isildur", "Bombadil", "Hobbiton",
            "Anduin", "Gondor", "Rohan", "Celeborn", "Haldir", "Glorfindel",
        )
        for (name in names) {
            val c = Transliteration.candidates(name)
            assertTrue("$name 应至少有一个候选", c.isNotEmpty())
            for (form in c) {
                assertTrue(
                    "$name -> 「$form」长度 ${form.length} 不在 2..5",
                    form.length in 2..5,
                )
                assertTrue("$name -> 「$form」应全为汉字", form.all { isCjk(it) })
            }
        }
    }

    @Test
    fun `候选 - 最多 8 个`() {
        for (name in listOf("Frodo", "Gandalf", "Aragorn", "Glorfindel", "Bombadil")) {
            assertTrue(
                "$name 候选数 ${Transliteration.candidates(name).size} 超过 8",
                Transliteration.candidates(name).size <= 8,
            )
        }
    }

    @Test
    fun `候选 - 不含虚词用字`() {
        val bad = "的了是在和有我你他她它们个不也就都这那要会对说很被把给让从因而所之其此"
        for (name in listOf("Frodo", "Gandalf", "Sam", "Bilbo", "Aragorn", "Mordor")) {
            for (form in Transliteration.candidates(name)) {
                for (ch in form) {
                    assertFalse("$name -> 「$form」含虚词字「$ch」", ch in bad)
                }
            }
        }
    }

    @Test
    fun `候选 - 高频名的正确译法出现在候选表内`() {
        // 这是"存在性"断言：不要求排第 1，但必须在候选表里。
        // 佛羅多 / 甘道夫 / 皮聘 / 梅里 / 索倫 / 洛汗 都在前 8。
        val expect = mapOf(
            "Frodo" to "佛罗多",
            "Gandalf" to "甘道夫",
            "Pippin" to "皮聘",
            "Merry" to "梅里",
            "Sauron" to "索伦",
            "Rohan" to "洛汉",
            "Saruman" to "萨鲁曼",
        )
        for ((name, want) in expect) {
            val c = Transliteration.candidates(name)
            assertTrue("$name 候选 $c 应包含「$want」", c.contains(want))
        }
    }

    @Test
    fun `候选 - 空输入与纯符号输入返回空表`() {
        assertTrue(Transliteration.candidates("").isEmpty())
        assertTrue(Transliteration.candidates("   ").isEmpty())
        assertTrue(Transliteration.candidates("123").isEmpty())
        assertTrue(Transliteration.candidates("!!!").isEmpty())
    }

    // ── 繁简兼容（关键需求） ─────────────────────────────────────

    @Test
    fun `繁简 - 繁体句与简体句同分`() {
        val cases = listOf(
            "Frodo" to ("佛羅多" to "佛罗多"),
            "Gandalf" to ("甘道夫" to "甘道夫"),
            "Bilbo" to ("比爾博" to "比尔博"),
            "Aragorn" to ("亞拉岡" to "亚拉冈"),
            "Sauron" to ("索倫" to "索伦"),
            "Rivendell" to ("瑞文戴爾" to "瑞文戴尔"),
        )
        for ((name, pair) in cases) {
            val (trad, simp) = pair
            val sTrad = Transliteration.scoreInSentence(name, "這一句裡提到了$trad，敘述者正在講述。")
            val sSimp = Transliteration.scoreInSentence(name, "这一句里提到了$simp，叙述者正在讲述。")
            assertEquals("$name 繁简应同分", sSimp, sTrad, 1e-9)
            assertTrue("$name 繁体句应得高分，实际 $sTrad", sTrad >= 0.5)
        }
    }

    @Test
    fun `繁简 - 繁体字形能被归一到简体`() {
        assertEquals("佛罗多", Transliteration.toSimplified("佛羅多"))
        assertEquals("比尔博", Transliteration.toSimplified("比爾博"))
        assertEquals("瑞文戴尔", Transliteration.toSimplified("瑞文戴爾"))
        // 未收录的字原样保留
        assertEquals("abc中", Transliteration.toSimplified("abc中"))
        // 幂等
        val once = Transliteration.toSimplified("亞拉岡與凱蘭崔爾")
        assertEquals(once, Transliteration.toSimplified(once))
    }

    // ── 匹配 / 反例 ─────────────────────────────────────────────

    @Test
    fun `匹配 - 含专名正确译法的句子得高分`() {
        val hi = listOf(
            "Frodo" to "他名叫佛羅多，是比爾博的養子。",
            "Gandalf" to "那個老巫師甘道夫等等。",
            "Mordor" to "他們一路上都在討論魔多的事情。",
            "Elrond" to "愛隆王坐在最上位。",
            "Legolas" to "勒苟拉斯一箭射了過去。",
        )
        for ((name, sentence) in hi) {
            val s = Transliteration.scoreInSentence(name, sentence)
            assertTrue("$name 在「$sentence」中应得高分，实际 $s", s >= 0.7)
        }
    }

    @Test
    fun `匹配 - 不含专名的句子得 0`() {
        val lo = listOf(
            "Frodo" to "這是一段完全無關的話，講的是天氣和糧食。",
            "Gandalf" to "他們把東西放進口袋，然後就走開了。",
            "Aragorn" to "河流從山谷裡流出來，一路向東。",
            "Mordor" to "早餐有麵包、奶油和蜂蜜。",
            "Glorfindel" to "夜色漸深，眾人圍著營火坐下。",
        )
        for ((name, sentence) in lo) {
            val s = Transliteration.scoreInSentence(name, sentence)
            assertEquals("$name 在反例句中应得 0，实际 $s", 0.0, s, 1e-9)
        }
    }

    @Test
    fun `匹配 - 得分始终在 0 到 1 之间`() {
        val sentences = listOf(
            "他名叫佛羅多。", "甘道夫與亞拉岡同行。", "這是一段無關的話。", "", "   ",
            "魔多在東邊，夏爾在西邊，中間隔著很遠的路。",
        )
        for (name in listOf("Frodo", "Gandalf", "Mordor", "Shire", "Nobody")) {
            for (s in sentences) {
                val v = Transliteration.scoreInSentence(name, s)
                assertTrue("$name / 「$s」 -> $v 越界", v in 0.0..1.0)
            }
        }
    }

    @Test
    fun `匹配 - 空串得 0`() {
        assertEquals(0.0, Transliteration.scoreInSentence("", "佛羅多"), 1e-9)
        assertEquals(0.0, Transliteration.scoreInSentence("Frodo", ""), 1e-9)
        assertEquals(0.0, Transliteration.scoreInSentence("Frodo", "   "), 1e-9)
    }

    // ── 缓存 ───────────────────────────────────────────────────

    @Test
    fun `缓存 - 同一名字两次返回同一实例`() {
        Transliteration.debugClearCache()
        val a = Transliteration.candidates("Glorfindel")
        val b = Transliteration.candidates("Glorfindel")
        assertTrue("应命中缓存返回同一实例", a === b)
        assertEquals(1, Transliteration.debugCacheSize())
    }

    @Test
    fun `缓存 - 大量不同名字不重复计算`() {
        Transliteration.debugClearCache()
        val names = listOf("Frodo", "Gandalf", "Bilbo", "Sam", "Aragorn", "Mordor")
        for (n in names) Transliteration.candidates(n)
        val sizeAfterFirst = Transliteration.debugCacheSize()
        assertEquals(names.size, sizeAfterFirst)
        // 第二轮不应增加缓存条目
        for (n in names) Transliteration.candidates(n)
        assertEquals(sizeAfterFirst, Transliteration.debugCacheSize())
    }

    @Test
    fun `缓存 - 首次生成后热路径足够快`() {
        Transliteration.debugClearCache()
        val names = listOf(
            "Frodo", "Gandalf", "Bilbo", "Sam", "Aragorn", "Mordor", "Shire",
            "Pippin", "Merry", "Boromir", "Legolas", "Gimli", "Elrond",
        )
        val t0 = System.nanoTime()
        for (n in names) Transliteration.candidates(n)
        val coldMs = (System.nanoTime() - t0) / 1_000_000.0

        val t1 = System.nanoTime()
        repeat(200) { for (n in names) Transliteration.candidates(n) }
        val warmMs = (System.nanoTime() - t1) / 1_000_000.0 / 200.0

        assertTrue("冷启动 ${coldMs}ms 过慢", coldMs < 5000)
        assertTrue("命中缓存后 ${warmMs}ms 过慢", warmMs < 5.0)
        // 缓存应显著快于冷启动（宽松判据，避免 CI 抖动）
        assertTrue("缓存未生效：cold=$coldMs warm=$warmMs", warmMs <= coldMs)
    }

    @Test
    fun `缓存 - scoreInSentence 单句耗时可接受`() {
        Transliteration.candidates("Aragorn")
        val sentence = "亞拉岡說：「我們必須在天亮之前離開這裡，否則就來不及了。」"
        val t0 = System.nanoTime()
        repeat(300) { Transliteration.scoreInSentence("Aragorn", sentence) }
        val perCall = (System.nanoTime() - t0) / 1_000_000.0 / 300.0
        assertTrue("scoreInSentence 单句 ${perCall}ms 过慢", perCall < 20.0)
    }

    // ── 专名抽取 ───────────────────────────────────────────────

    @Test
    fun `抽取 - 抽到大写开头的专名`() {
        val text = "Frodo met Gandalf in the Shire."
        val names = Transliteration.extractNames(text)
        assertTrue("应抽到 Frodo，实际 $names", names.any { it == "Frodo" })
        assertTrue("应抽到 Gandalf，实际 $names", names.any { it == "Gandalf" })
    }

    @Test
    fun `抽取 - 排除句首大写与常见词`() {
        val text = "The road went on. Then the fire died down."
        val names = Transliteration.extractNames(text)
        assertFalse("不应抽到句首 The", names.any { it.equals("The", ignoreCase = true) })
        assertFalse("不应抽到 Then", names.any { it.equals("Then", ignoreCase = true) })
    }

    @Test
    fun `抽取 - 保留连字符与撇号但去重`() {
        val text = "They passed Middle-earth. Then Sackville-Baggins arrived. Frodo laughed. Frodo left."
        val names = Transliteration.extractNames(text)
        assertTrue("应保留 Middle-earth，实际 $names", names.any { it == "Middle-earth" })
        assertTrue("应保留 Sackville-Baggins，实际 $names", names.any { it == "Sackville-Baggins" })
        assertEquals("Frodo 应去重", 1, names.count { it == "Frodo" })
    }

    @Test
    fun `抽取 - 空文本返回空表`() {
        assertTrue(Transliteration.extractNames("").isEmpty())
        assertTrue(Transliteration.extractNames("   ").isEmpty())
        assertTrue(Transliteration.extractNames("123 456").isEmpty())
    }

    // ── 边界 ───────────────────────────────────────────────────

    @Test
    fun `边界 - 短名与长名都不会崩`() {
        for (n in listOf("A", "Ab", "Xy", "Supercalifragilisticexpialidocious")) {
            assertNotNull(Transliteration.candidates(n))
            val v = Transliteration.scoreInSentence(n, "測試句子內容。")
            assertTrue("$n -> $v 越界", v in 0.0..1.0)
        }
    }

    @Test
    fun `边界 - 非 ASCII 输入不会崩`() {
        assertNotNull(Transliteration.candidates("Ægir"))
        assertNotNull(Transliteration.candidates("Ж"))
        assertTrue(Transliteration.scoreInSentence("Frodo", "😀🎉").let { it in 0.0..1.0 })
    }

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
