package com.linguareader.shared.tts

import org.jsoup.Jsoup
import org.junit.Test
import java.io.File

/**
 * 第四轮审查 审查点2「分句器准确性」的可复现实验台。
 * 这不是项目正式测试，**不要提交**。用法见同目录 README.md。
 *
 * A. 边界用例电池（人工给定期望，逐例判对错）
 * B. 真实语料不变量（魔戒英/中全文）：有序子串契约、混淆计数、超长句硬切
 */
class ReproSplitterAuditTest {

    private data class Case(
        val category: String,
        val input: String,
        val expected: List<String>,
        val note: String = ""
    )

    private val cases = listOf(
        Case("中英混排", "He said \"Hello.\" 然后他走了。", listOf("He said \"Hello.\"", "然后他走了。")),
        Case("中英混排", "He said!她哭了。", listOf("He said!", "她哭了。")),
        Case("中英混排", "She answered.她走了", listOf("She answered.", "她走了")),
        Case("中英混排", "这是中文句。This is English.", listOf("这是中文句。", "This is English.")),
        Case("引号嵌套", "He said, \"She said 'hi.' Then left.\"", listOf("He said, \"She said 'hi.'", "Then left.\"")),
        Case("引号嵌套·中文", "他说：「她喊『救命！』然后跑了。」", listOf("他说：「她喊『救命！』然后跑了。」")),
        Case("引号嵌套·中文", "他问：「你听见『谁在敲门？』了吗？」", listOf("他问：「你听见『谁在敲门？』了吗？」")),
        Case("括号", "He left (and never came back.) Then she cried.", listOf("He left (and never came back.)", "Then she cried.")),
        Case("括号·中文", "他走了（再也没回来。）她哭了。", listOf("他走了（再也没回来。）", "她哭了。")),
        Case("引号归属", "She asked, \"Is it true?\" He nodded.", listOf("She asked, \"Is it true?\"", "He nodded.")),
        Case("引号归属", "\"I am sorry, Frodo!\" he cried. \"So be it.\"", listOf("\"I am sorry, Frodo!\" he cried.", "\"So be it.\"")),
        Case("省略号", "Wait... what?", listOf("Wait... what?")),
        Case("省略号", "He paused... Then he left.", listOf("He paused...", "Then he left.")),
        Case("省略号", "Wait… what?", listOf("Wait… what?")),
        Case("省略号·中文", "他走了……她哭了。", listOf("他走了……", "她哭了。")),
        Case("省略号", "I wonder..... Then I knew.", listOf("I wonder.....", "Then I knew.")),
        Case("数字小数", "Pi is 3.14 and e is 2.718. That is all.", listOf("Pi is 3.14 and e is 2.718.", "That is all.")),
        Case("数字小数", "It costs $5. Then he left.", listOf("It costs $5.", "Then he left.")),
        Case("数字小数", "In 2026. He arrived.", listOf("In 2026.", "He arrived.")),
        Case("数字小数", "Version 1.2.3 is out now. Upgrade today.", listOf("Version 1.2.3 is out now.", "Upgrade today.")),
        Case("缩写", "Dr. Smith arrived early. Prof. Lee agreed.", listOf("Dr. Smith arrived early.", "Prof. Lee agreed.")),
        Case("缩写", "They sell tools, etc. The shop closes at five.", listOf("They sell tools, etc.", "The shop closes at five.")),
        Case("缩写", "Read pp. 12 and pp. 13 for details.", listOf("Read pp. 12 and pp. 13 for details.")),
        Case("缩写", "The firm Acme Inc. was founded here.", listOf("The firm Acme Inc. was founded here.")),
        Case("缩写", "He works for the U.S. government. Next year he retires.", listOf("He works for the U.S. government.", "Next year he retires.")),
        Case("缩写", "J. R. R. Tolkien wrote it. Mr. Baggins lived in No. 3. Then he left.",
            listOf("J. R. R. Tolkien wrote it.", "Mr. Baggins lived in No. 3.", "Then he left.")),
        Case("缩写", "He said, \"No.\" Then he left.", listOf("He said, \"No.\"", "Then he left.")),
        Case("缩写·点号缩写", "The meeting is at 5 p.m. he said nothing.", listOf("The meeting is at 5 p.m. he said nothing.")),
        Case("缩写·点号缩写", "See the note below, i.e. the second one. It matters.", listOf("See the note below, i.e. the second one.", "It matters.")),
        Case("缩写·点号缩写", "That is another way, e.g. this one. Yes.", listOf("That is another way, e.g. this one.", "Yes.")),
        Case("无标点换行", "第一行文字\n第二行文字", listOf("第一行文字 第二行文字")),
        Case("无标点换行", "This is one long unbroken sentence", listOf("This is one long unbroken sentence")),
        Case("其他", "Mr. Smith Jr. went home. It rained.", listOf("Mr. Smith Jr. went home.", "It rained.")),
        Case("其他", "A sentence ending in a colon: and it continues.", listOf("A sentence ending in a colon: and it continues.")),
        Case("其他", "Chapter 1: The Start", listOf("Chapter 1: The Start")),
        Case("其他", "第10节　神行客", listOf("第10节 神行客")),
        Case("其他", "e.g. this one. And that one.", listOf("e.g. this one.", "And that one.")),
        Case("其他", "He arrived at 9 a.m. Then left at 5 p.m.", listOf("He arrived at 9 a.m. Then left at 5 p.m."))
    )

    @Test
    fun edgeCaseBattery() {
        var pass = 0
        val failures = mutableListOf<String>()
        val byCategory = LinkedHashMap<String, Pair<Int, Int>>()
        for (c in cases) {
            val actual = SentenceSplitter.split(c.input)
            val ok = actual == c.expected
            if (ok) pass++ else failures += "[${c.category}] 输入=«${c.input}»\n      期望=${c.expected}\n      实际=$actual ${c.note}"
            val cur = byCategory.getOrDefault(c.category, 0 to 0)
            byCategory[c.category] = (cur.first + if (ok) 1 else 0) to (cur.second + 1)
        }
        println("[splitter] 边界用例电池 通过=$pass/${cases.size}")
        byCategory.forEach { (k, v) -> println("[splitter]   %-14s %d/%d".format(k, v.first, v.second)) }
        failures.forEach { println("[splitter] ✗ $it") }
    }

    private fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book/metadata.json").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun readBlocks(dir: File, ordered: List<String>): List<List<String>> {
        val selector = "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, section, article, header, footer"
        return ordered.mapNotNull { rel ->
            val f = File(dir, rel)
            if (!f.isFile) return@mapNotNull null
            val doc = Jsoup.parse(f.readText())
            val candidates = doc.select(selector)
            val leaves = candidates.filter { c -> c.select(selector).all { it === c } }
            val blocks = leaves.map { it.text().replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
            blocks.ifEmpty { null }
        }
    }

    private fun enChapterPaths(root: File): List<String> {
        val meta = org.json.JSONObject(File(root, "artifacts/lotr-book/metadata.json").readText())
        val arr = meta.getJSONArray("chapters")
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("relativePath") }
    }

    @Test
    fun corpusInvariants() {
        val root = findRoot() ?: run { println("[splitter] 缺少 artifacts/lotr-book，跳过语料测量"); return }
        val enRoot = File(root, "artifacts/lotr-book")
        val enBlocks = readBlocks(enRoot, enChapterPaths(root))

        val zhRoot = File(root, "artifacts/lotr-zh")
        val container = File(zhRoot, "META-INF/container.xml").readText()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)!!.groupValues[1]
        val opf = Jsoup.parse(File(zhRoot, opfPath).readText(), "", org.jsoup.parser.Parser.xmlParser())
        val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val base = opfPath.substringBeforeLast('/', "")
        val zhPaths = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
            .map { if (base.isEmpty()) it else "$base/$it" }
        val zhBlocks = readBlocks(zhRoot, zhPaths)

        for ((label, chapters) in listOf("EN" to enBlocks, "ZH" to zhBlocks)) measureCorpus(label, chapters)
    }

    private class Corpus(
        val label: String,
        var blocks: Int = 0,
        var sentences: Int = 0,
        var hardSplitBlocks: Int = 0,
        var hardSplitChunks: Int = 0,
        var startsLowerAscii: Int = 0,
        var noTerminatorTail: Int = 0,
        var noTerminatorLong: Int = 0,
        var unbalancedQuote: Int = 0,
        var orderedSubstringFail: Int = 0,
        var maxLen: Int = 0,
        val examples: MutableList<String> = mutableListOf()
    )

    private val terminators = setOf('.', '!', '?', '…', '。', '！', '？')

    private fun measureCorpus(label: String, chapters: List<List<String>>) {
        val m = Corpus(label)
        for (blocks in chapters) for (block in blocks) {
            m.blocks++
            val sentences = SentenceSplitter.split(block, SentenceSplitter.TTS_MAX_SENTENCE_CHARS)
            m.sentences += sentences.size
            // 真实契约（TtsChapter.sentenceLocation）：每个句子必须是块文本的有序子串。
            var cursor = 0
            for (s in sentences) {
                val found = block.indexOf(s, cursor)
                if (found < 0) {
                    m.orderedSubstringFail++
                    if (m.examples.size < 5) m.examples += "有序子串失败 sentence=«${s.take(50)}» block=«${block.take(60)}»"
                    break
                }
                cursor = found + s.length
            }
            if (sentences.size > 1 && sentences.any { it.length >= SentenceSplitter.TTS_MAX_SENTENCE_CHARS }) {
                m.hardSplitBlocks++
                m.hardSplitChunks += sentences.size
            }
            for (s in sentences) {
                m.maxLen = maxOf(m.maxLen, s.length)
                if (s.isNotEmpty() && s[0] in 'a'..'z') m.startsLowerAscii++
                if (s.none { it in terminators }) {
                    m.noTerminatorTail++
                    if (s.length >= 100) m.noTerminatorLong++
                }
                val dq = s.count { it == '"' || it == '“' || it == '”' }
                if (dq % 2 != 0) m.unbalancedQuote++
            }
        }
        val fmt = "[splitter] $label 块=%d 句=%d 平均句/块=%.3f 有序子串失败=%d 超长硬切块=%d(产生%d句) " +
            "以a-z开头的句=%d 无终止符片段=%d(其中≥100字符=%d) 引号不配平句=%d 最长句=%d"
        println(
            fmt.format(
                m.blocks, m.sentences, m.sentences.toDouble() / m.blocks, m.orderedSubstringFail,
                m.hardSplitBlocks, m.hardSplitChunks, m.startsLowerAscii, m.noTerminatorTail,
                m.noTerminatorLong, m.unbalancedQuote, m.maxLen
            )
        )
        m.examples.distinct().take(12).forEach { println("[splitter] $label 例: $it") }
    }
}
