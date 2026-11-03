package com.linguareader.shared.alignment

import com.linguareader.shared.translation.TraditionalSimplified
import com.linguareader.shared.tts.SentenceSplitter

/**
 * 预处理层（设计 §2，交接单 §1.2 的 A1–A6）。
 *
 * **这一层是唯一允许做文本处理的地方**：切句、`lowercase`、繁简归一、字符扫描全部在
 * 这里一次性完成；之后章节层 / 段落层 / 句级 DP 与判定层只读 `Int` / `Double` 标量
 * （设计 §12.4 的热路径禁令）。
 *
 * 口径同源（设计 §2.1）：
 * - 分句只走 [SentenceSplitter.split]，**不传** `maxSentenceLength`（对齐线不做硬切）；
 * - 段落由上游按 `TtsTextExtractor` 的叶级块抽取后填进 [AlignmentChapter.paragraphs]；
 * - 繁→简只用于**匹配**（`sentNormText` / `sentNormHash`），不改输出给用户的原文。
 */

internal enum class SideKind { SOURCE, TARGET }

/** 句末类型（§2.2 的 `termClass`）。 */
internal object TermClass {
    const val STATEMENT = 0
    const val QUESTION = 1
    const val EXCLAMATION = 2
    const val ELLIPSIS = 3
    const val NONE = 4
}

/** `flags` 位定义（§2.2）。 */
internal object SentFlags {
    const val QUOTED = 1
    const val DASHED = 1 shl 1
    const val COLON = 1 shl 2
    const val ALL_CAPS = 1 shl 3
    const val NO_TERMINATOR = 1 shl 4
}

internal object Bags {
    val EMPTY_INT: IntArray = IntArray(0)
}

/** 可增长的 int 序列：预处理阶段避免装箱。 */
internal class IntBag(capacity: Int = 8) {
    private var data: IntArray = if (capacity <= 0) IntArray(0) else IntArray(capacity)
    var size: Int = 0

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(if (data.isEmpty()) 8 else data.size * 2)
        data[size++] = value
    }

    /** 下标读（保序去重用）。 */
    operator fun get(index: Int): Int = data[index]

    /** 紧凑拷贝（**不去重**：段落下标这类「连续相同值必须保留」的序列要用它）。 */
    fun toArray(): IntArray = if (size == 0) Bags.EMPTY_INT else data.copyOf(size)

    /** 清空复用（扫描累加器每句重置，避免 11k 次分配）。 */
    fun reset() {
        size = 0
    }

    fun isEmpty(): Boolean = size == 0

    /** 去重 + 升序，返回紧凑数组（锚点与词义桥都要求已排序，便于归并求交）。 */
    fun toSortedUnique(): IntArray {
        if (size == 0) return Bags.EMPTY_INT
        val copy = data.copyOf(size)
        copy.sort()
        var w = 1
        for (r in 1 until copy.size) if (copy[r] != copy[w - 1]) copy[w++] = copy[r]
        return if (w == copy.size) copy else copy.copyOf(w)
    }
}

/** 字符级扫描工具：全部无正则、无中间分配。 */
internal object TextScan {

    fun isCjk(c: Char): Boolean = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'

    fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun isDigit(c: Char): Boolean = c in '0'..'9'

    fun isFullwidthDigit(c: Char): Boolean = c in '０'..'９'

    fun digitValue(c: Char): Int =
        if (c in '0'..'9') c - '0' else (c - '０')

    private fun isClosing(c: Char): Boolean =
        c == '"' || c == '\'' || c == '”' || c == '’' || c == ')' || c == ']' ||
            c == '）' || c == '】' || c == '」' || c == '』' || c == '»' || c == '›'

    /** FNV-1a：预处理期一次性算好键，穷举/DP 里只比较 int。 */
    fun hash(s: CharSequence, from: Int = 0, to: Int = s.length): Int {
        var h = -2128831035
        for (i in from until to) {
            h = h xor s[i].code
            h *= 16777619
        }
        return h
    }

    fun hashLowerAscii(s: CharSequence, from: Int, to: Int): Int {
        var h = -2128831035
        for (i in from until to) {
            val ch = s[i]
            val code = if (ch in 'A'..'Z') ch.code + 32 else ch.code
            h = h xor code
            h *= 16777619
        }
        return h
    }

    /** 句末类型（§2.2 `termClass`）：越过空白与闭合符看最后一个实义字符。 */
    fun terminatorClass(s: String): Byte {
        var i = s.length - 1
        while (i >= 0 && (s[i].isWhitespace() || isClosing(s[i]))) i--
        if (i < 0) return TermClass.NONE.toByte()
        return when (s[i]) {
            '?', '？' -> TermClass.QUESTION.toByte()
            '!', '！' -> TermClass.EXCLAMATION.toByte()
            '…' -> TermClass.ELLIPSIS.toByte()
            '.', '。' -> {
                var dots = 0
                var k = i
                while (k >= 0 && s[k] == '.') { dots++; k-- }
                if (dots >= 3) TermClass.ELLIPSIS.toByte() else TermClass.STATEMENT.toByte()
            }
            else -> TermClass.NONE.toByte()
        }
    }

    /** 成对引号（`"` / `“”` / `「」` / `『』`）：至少一对才算。 */
    fun hasPairedQuotes(s: String): Boolean {
        var dq = 0
        var smart = 0
        var corner = 0
        for (c in s) {
            when (c) {
                '"' -> dq++
                '“', '”' -> smart++
                '「', '」', '『', '』' -> corner++
            }
        }
        return dq >= 2 || smart >= 2 || corner >= 2
    }

    fun hasDashOrColon(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '—', '–', '―', '：', ':' -> return true
                '-' -> if ((i > 0 && s[i - 1] == '-') || (i + 1 < s.length && s[i + 1] == '-')) return true
            }
            i++
        }
        return false
    }

    fun hasColon(s: String): Boolean {
        for (c in s) if (c == ':' || c == '：') return true
        return false
    }

    /** 全大写（≥2 个字母且无小写字母）：标题行的形态学特征。 */
    fun isAllCaps(s: String): Boolean {
        var letters = 0
        for (c in s) {
            if (c in 'A'..'Z') letters++
            else if (c in 'a'..'z') return false
        }
        return letters >= 2
    }

    /** 归一化哈希：跳过空白与标点，字母小写；调用方保证繁→简已完成。 */
    fun normalizedHash(s: String): Int {
        var h = -2128831035
        for (raw in s) {
            val c = when {
                raw in 'A'..'Z' -> (raw.code + 32).toChar()
                raw in '０'..'９' -> ('0' + digitValue(raw))
                else -> raw
            }
            when {
                c in 'a'..'z' || c in '0'..'9' || isCjk(c) -> {
                    h = h xor c.code
                    h *= 16777619
                }
            }
        }
        return h
    }
}

/**
 * 一侧（英文原件 / 中文译本）的全部预计算特征。
 *
 * 数组都是**扁平 primitive 数组**，不用对象列表：11k 句 × ~10 标量 ≈ 1.5 MB，
 * 且结构上不存在 O(N²) 存储（设计 §12.3）。
 */
internal class BookSide private constructor(
    val kind: SideKind,
    val chapters: List<AlignmentChapter>
) {
    val chapterCount: Int = chapters.size

    /** 章节标题归一化形（繁→简 + lowercase + trim）：结构词表与标题编号用。 */
    var chapterTitleNorm: Array<String> = emptyArray()
        private set

    var chapterWords: IntArray = Bags.EMPTY_INT
    var chapterChars: IntArray = Bags.EMPTY_INT
    var chapterSentCount: IntArray = Bags.EMPTY_INT
    var chapterParaCount: IntArray = Bags.EMPTY_INT
    var chapterDigitTailRatio: FloatArray = FloatArray(0)
    var chapterAvgParagraphChars: FloatArray = FloatArray(0)
    var chapterDialogueRatio: FloatArray = FloatArray(0)
    var chapterTitleDigits: Array<IntArray> = emptyArray()

    /** 段落级（全局段序 = 章节序 × 段序，阅读顺序摊平）。 */
    var paraCount: Int = 0
    var paraText: Array<String> = emptyArray()
    var paraChapter: IntArray = Bags.EMPTY_INT
    var paraIdxInChapter: IntArray = Bags.EMPTY_INT
    var paraFirstSent: IntArray = Bags.EMPTY_INT
    var paraSentCount: IntArray = Bags.EMPTY_INT
    var paraWords: IntArray = Bags.EMPTY_INT
    var paraChars: IntArray = Bags.EMPTY_INT

    /** 句子级（全局句序 = 阅读顺序摊平）。 */
    var sentCount: Int = 0

    /** 句子原文（输出给用户用；中文侧保留繁体原样）。 */
    var sentText: Array<String> = emptyArray()
    var sentParagraph: IntArray = Bags.EMPTY_INT
    var sentIdxInPara: IntArray = Bags.EMPTY_INT
    var sentWords: IntArray = Bags.EMPTY_INT
    var sentChars: IntArray = Bags.EMPTY_INT
    var sentLangUnit: BooleanArray = BooleanArray(0)
    var sentTerm: ByteArray = ByteArray(0)
    var sentFlags: IntArray = Bags.EMPTY_INT
    var sentNormHash: IntArray = Bags.EMPTY_INT

    /** 数字/拉丁锚点键（已升序去重）。 */
    var sentDigits: Array<IntArray> = emptyArray()
    var sentLatins: Array<IntArray> = emptyArray()

    /** 英文侧：句子里的词（小写哈希，已升序去重）——V1 词典桥取 `terms(E)` 用。 */
    var sentWordHashes: Array<IntArray> = emptyArray()

    /** 中文侧：繁→简归一后的文本（只用于匹配与 n-gram 扫描，输出仍用 [sentText]）。 */
    var sentNormText: Array<String> = emptyArray()

    /** 结构与目录页判定的结果（§2.4），以及判定依据（写进 notes 用）。 */
    private var structuralFlags: BooleanArray = BooleanArray(0)
    private var structuralReason: Array<String> = emptyArray()

    fun isStructural(chapter: Int): Boolean =
        chapter >= 0 && chapter < structuralFlags.size && structuralFlags[chapter]

    fun structuralReasonOf(chapter: Int): String =
        if (chapter >= 0 && chapter < structuralReason.size) structuralReason[chapter] else ""

    // ──────────────────────────────────────────────────────────────
    //  构建
    // ──────────────────────────────────────────────────────────────

    private fun build() {
        // ── A1 切句（口径：SentenceSplitter，不传上限）──────────────
        val perParagraph = ArrayList<ArrayList<String>>(chapterCount * 8)
        val paraChapterBag = IntBag()
        val paraIdxBag = IntBag()
        for (chapterIndex in chapters.indices) {
            val chapter = chapters[chapterIndex]
            chapter.paragraphs.forEachIndexed { indexInChapter, raw ->
                paraChapterBag.add(chapterIndex)
                paraIdxBag.add(indexInChapter)
                perParagraph.add(ArrayList(SentenceSplitter.split(raw)))
            }
        }
        paraCount = perParagraph.size
        // 段落下标是「连续相同值必须保留」的序列（同一章有多段），**不能去重**。
        paraChapter = paraChapterBag.toArray()
        paraIdxInChapter = paraIdxBag.toArray()

        paraText = Array(paraCount) { "" }
        paraFirstSent = IntArray(paraCount)
        paraSentCount = IntArray(paraCount)
        paraWords = IntArray(paraCount)
        paraChars = IntArray(paraCount)

        var totalSentences = 0
        for (p in 0 until paraCount) totalSentences += perParagraph[p].size

        sentCount = totalSentences
        sentText = Array(totalSentences) { "" }
        sentParagraph = IntArray(totalSentences)
        sentIdxInPara = IntArray(totalSentences)
        sentWords = IntArray(totalSentences)
        sentChars = IntArray(totalSentences)
        sentLangUnit = BooleanArray(totalSentences)
        sentTerm = ByteArray(totalSentences)
        sentFlags = IntArray(totalSentences)
        sentNormHash = IntArray(totalSentences)
        sentDigits = Array(totalSentences) { Bags.EMPTY_INT }
        sentLatins = Array(totalSentences) { Bags.EMPTY_INT }
        sentWordHashes = if (kind == SideKind.SOURCE) Array(totalSentences) { Bags.EMPTY_INT } else emptyArray()
        sentNormText = if (kind == SideKind.TARGET) Array(totalSentences) { "" } else emptyArray()

        val scan = ScanResult()
        var s = 0
        for (p in 0 until paraCount) {
            paraText[p] = chapters[paraChapter[p]].paragraphs[paraIdxInChapter[p]]
            paraFirstSent[p] = s
            val sentences = perParagraph[p]
            paraSentCount[p] = sentences.size
            var words = 0
            var chars = 0
            for (indexInPara in sentences.indices) {
                val raw = sentences[indexInPara]
                val matchText = if (kind == SideKind.TARGET) TraditionalSimplified.toSimplified(raw) else raw
                scan.scan(matchText, kind)
                sentText[s] = raw
                sentParagraph[s] = p
                sentIdxInPara[s] = indexInPara
                sentWords[s] = scan.words
                sentChars[s] = scan.chars
                sentLangUnit[s] = scan.langUnit
                sentTerm[s] = TextScan.terminatorClass(raw)
                var flags = 0
                if (scan.quoted) flags = flags or SentFlags.QUOTED
                if (scan.dashed) flags = flags or SentFlags.DASHED
                if (scan.colon) flags = flags or SentFlags.COLON
                if (kind == SideKind.SOURCE && scan.allCaps) flags = flags or SentFlags.ALL_CAPS
                if (sentTerm[s].toInt() == TermClass.NONE) flags = flags or SentFlags.NO_TERMINATOR
                sentFlags[s] = flags
                sentNormHash[s] = TextScan.normalizedHash(matchText)
                sentDigits[s] = scan.digits.toSortedUnique()
                sentLatins[s] = scan.latins.toSortedUnique()
                if (kind == SideKind.SOURCE) sentWordHashes[s] = scan.wordHashes.toSortedUnique()
                if (kind == SideKind.TARGET) sentNormText[s] = matchText
                words += scan.words
                chars += scan.chars
                s++
            }
            paraWords[p] = words
            paraChars[p] = chars
        }

        // ── A3 章节特征 ────────────────────────────────────────────
        chapterTitleNorm = Array(chapterCount) { normalizeTitle(chapters[it].title) }
        chapterWords = IntArray(chapterCount)
        chapterChars = IntArray(chapterCount)
        chapterSentCount = IntArray(chapterCount)
        chapterParaCount = IntArray(chapterCount)
        chapterDigitTailRatio = FloatArray(chapterCount)
        chapterAvgParagraphChars = FloatArray(chapterCount)
        chapterDialogueRatio = FloatArray(chapterCount)
        chapterTitleDigits = Array(chapterCount) { Bags.EMPTY_INT }

        val firstParaOfChapter = IntArray(chapterCount) { -1 }
        for (p in 0 until paraCount) {
            val c = paraChapter[p]
            if (firstParaOfChapter[c] < 0) firstParaOfChapter[c] = p
            chapterWords[c] += paraWords[p]
            chapterChars[c] += paraChars[p]
            chapterSentCount[c] += paraSentCount[p]
            chapterParaCount[c] += 1
        }
        for (c in 0 until chapterCount) {
            val n = chapterParaCount[c]
            val first = firstParaOfChapter[c]
            if (n > 0 && first >= 0) {
                var digitTails = 0
                var charSum = 0
                var dialogue = 0
                for (k in first until first + n) {
                    if (endsWithPageNumber(paraText[k])) digitTails++
                    charSum += paraText[k].length
                    if (TextScan.hasPairedQuotes(paraText[k])) dialogue++
                }
                chapterDigitTailRatio[c] = digitTails.toFloat() / n
                chapterAvgParagraphChars[c] = (charSum.toDouble() / n).toFloat()
                chapterDialogueRatio[c] = dialogue.toFloat() / n
            }
            chapterTitleDigits[c] = extractTitleDigits(chapterTitleNorm[c])
        }
        detectStructural()
    }

    /** 行尾页码（`— 3 —` / `... 12`）：目录页形态判据。 */
    private fun endsWithPageNumber(paragraph: String): Boolean {
        var i = paragraph.length - 1
        while (i >= 0 && (paragraph[i].isWhitespace() || paragraph[i] in ".:：。．-—–")) i--
        return i >= 0 && (TextScan.isDigit(paragraph[i]) || TextScan.isFullwidthDigit(paragraph[i]))
    }

    /** A4 结构性章节判定（§2.4）：只靠「短」不算，必须叠加形态学证据。 */
    private fun detectStructural() {
        structuralFlags = BooleanArray(chapterCount)
        structuralReason = Array(chapterCount) { "" }
        for (c in 0 until chapterCount) {
            if (chapterSentCount[c] == 0 || chapterWords[c] + chapterChars[c] == 0) {
                structuralFlags[c] = true
                structuralReason[c] = "empty chapter"
                continue
            }
            if (titleWordCount(c) < AlignTuning.STRUCTURAL_MAX_WORDS &&
                matchesStructuralTitle(chapterTitleNorm[c])
            ) {
                structuralFlags[c] = true
                structuralReason[c] = "structural title"
                continue
            }
            if (chapterDigitTailRatio[c] >= AlignTuning.TOC_DIGIT_TAIL_RATIO &&
                chapterParaCount[c] >= AlignTuning.TOC_MIN_PARAGRAPHS &&
                chapterAvgParagraphChars[c] < AlignTuning.TOC_MAX_AVG_CHARS
            ) {
                structuralFlags[c] = true
                structuralReason[c] = "table-of-contents shape"
            }
        }
    }

    /** 中文侧按「2 字 ≈ 1 词」折算，只为套用同一个 400 词门（§2.4 的口径是词数）。 */
    private fun titleWordCount(chapter: Int): Int =
        if (kind == SideKind.SOURCE) chapterWords[chapter] else (chapterChars[chapter] + 1) / 2

    private fun matchesStructuralTitle(normTitle: String): Boolean {
        if (normTitle.isEmpty()) return false
        for (word in AlignTuning.STRUCTURAL_TITLE_WORDS) {
            if (word.isEmpty()) continue
            val ascii = word[0].code < 128
            if (ascii) {
                if (containsAsciiWord(normTitle, word)) return true
            } else if (normTitle.contains(word)) {
                return true
            }
        }
        return false
    }

    private fun containsAsciiWord(haystack: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return false
            val beforeOk = at == 0 || !haystack[at - 1].isLetter()
            val afterAt = at + needle.length
            val afterOk = afterAt >= haystack.length || !haystack[afterAt].isLetter()
            if (beforeOk && afterOk) return true
            from = at + 1
        }
    }

    /** 章节的段落下标区间（含边界）。 */
    fun paragraphsOfChapter(chapter: Int): IntRange {
        if (chapter < 0 || chapter >= chapterCount) return IntRange.EMPTY
        var first = -1
        for (p in 0 until paraCount) {
            if (paraChapter[p] == chapter) { first = p; break }
        }
        if (first < 0) return IntRange.EMPTY
        return first until first + chapterParaCount[chapter]
    }

    /** 章节的句子下标区间。 */
    fun sentencesOfChapter(chapter: Int): IntRange {
        val paragraphs = paragraphsOfChapter(chapter)
        if (paragraphs.isEmpty()) return IntRange.EMPTY
        val first = paraFirstSent[paragraphs.first]
        val lastPage = paragraphs.last
        return first until paraFirstSent[lastPage] + paraSentCount[lastPage]
    }

    /**
     * **按（章节, 段内序号）查全局段落下标**。
     *
     * 用于 `ai-pair-v1`：它按「章节 identity + 段内序号 identity」定位段落，
     * 不需要也不应该做任何段落级搜索。
     */
    fun paraIndexOf(chapter: Int, indexInChapter: Int): Int {
        val range = paragraphsOfChapter(chapter)
        if (range.isEmpty()) return -1
        val index = range.first + indexInChapter
        return if (index < range.first + range.count()) index else -1
    }

    /** 某段落的句子下标区间（该段所有句子）。 */
    fun sentencesOfParagraph(paragraph: Int): IntArray {
        if (paragraph < 0 || paragraph >= paraCount) return Bags.EMPTY_INT
        val first = paraFirstSent[paragraph]
        val count = paraSentCount[paragraph]
        return IntArray(count) { first + it }
    }

    companion object {
        fun build(kind: SideKind, chapters: List<AlignmentChapter>): BookSide {
            val side = BookSide(kind, chapters)
            side.build()
            return side
        }

        private fun normalizeTitle(title: String): String =
            TraditionalSimplified.toSimplified(title).lowercase().trim()

        /**
         * 标题里的编号（`Chapter 12` / `PART TWO` / `第十二章` / `第 3 节`）。
         *
         * 只覆盖章节编号需要的范围（1–999），不做通用中文数字解析（设计 §6.1
         * 明确要求避免引入歧义）。
         */
        internal fun extractTitleDigits(normTitle: String): IntArray {
            val bag = IntBag()
            var i = 0
            val n = normTitle.length
            while (i < n) {
                val c = normTitle[i]
                when {
                    TextScan.isDigit(c) || TextScan.isFullwidthDigit(c) -> {
                        var value = 0
                        var digits = 0
                        while (i < n && (TextScan.isDigit(normTitle[i]) || TextScan.isFullwidthDigit(normTitle[i]))) {
                            value = value * 10 + TextScan.digitValue(normTitle[i])
                            digits++
                            i++
                        }
                        if (digits > 0) bag.add(value)
                    }
                    isChineseNumeral(c) -> {
                        val parsed = parseChineseNumeral(normTitle, i)
                        if (parsed != null) {
                            bag.add(parsed.second)
                            i = parsed.first
                        } else i++
                    }
                    else -> i++
                }
            }
            return bag.toSortedUnique()
        }

        private fun isChineseNumeral(c: Char): Boolean =
            c in '〇'..'九' || c == '十' || c == '百' || c == '零'

        /** 从 [start] 起解析一个 ≤999 的中文数字，返回 (结束后下标, 值)。 */
        private fun parseChineseNumeral(s: String, start: Int): Pair<Int, Int>? {
            var i = start
            var value = 0
            var current = 0
            var any = false
            while (i < s.length) {
                val c = s[i]
                when {
                    chineseDigit(c) >= 0 -> {
                        current = chineseDigit(c)
                        any = true
                        i++
                    }
                    c == '十' -> {
                        value += (if (current == 0) 1 else current) * 10
                        current = 0
                        any = true
                        i++
                    }
                    c == '百' -> {
                        value += (if (current == 0) 1 else current) * 100
                        current = 0
                        any = true
                        i++
                    }
                    else -> return if (any) i to (value + current) else null
                }
            }
            return if (any) i to (value + current) else null
        }

        private fun chineseDigit(c: Char): Int = when (c) {
            '零', '〇' -> 0
            '一' -> 1
            '二' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '七' -> 7
            '八' -> 8
            '九' -> 9
            else -> -1
        }
    }
}

/** 单句扫描累加器（预处理一次性使用，11k 次创建可忽略）。 */
private class ScanResult {
    var words = 0
    var chars = 0
    var langUnit = false
    var quoted = false
    var dashed = false
    var colon = false
    var allCaps = false
    val digits = IntBag()
    val latins = IntBag()
    val wordHashes = IntBag()
    private val digitText = StringBuilder(16)

    fun scan(s: String, kind: SideKind) {
        words = 0
        chars = 0
        langUnit = false
        digits.size = 0
        latins.size = 0
        wordHashes.size = 0
        digits.reset()
        latins.reset()
        wordHashes.reset()

        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (TextScan.isLetter(c)) {
                var j = i + 1
                while (j < n && isWordCont(s[j])) j++
                words++
                langUnit = true
                wordHashes.add(TextScan.hashLowerAscii(s, i, j))
                // 拉丁锚点键：`[A-Za-z][A-Za-z0-9'-]{1,}`（至少 2 字符）。
                if (j - i >= 2) latins.add(TextScan.hashLowerAscii(s, i, j))
                i = j
            } else if (TextScan.isDigit(c) || TextScan.isFullwidthDigit(c)) {
                var j = i
                var sawSeparator = false
                while (j < n) {
                    val ch = s[j]
                    if (TextScan.isDigit(ch) || TextScan.isFullwidthDigit(ch)) j++
                    else if (!sawSeparator && (ch == '.' || ch == ',') &&
                        j + 1 < n && (TextScan.isDigit(s[j + 1]) || TextScan.isFullwidthDigit(s[j + 1]))
                    ) {
                        sawSeparator = true
                        j++
                    } else break
                }
                digitText.setLength(0)
                for (k in i until j) {
                    val ch = s[k]
                    when {
                        ch in '0'..'9' -> digitText.append(ch)
                        ch in '０'..'９' -> digitText.append(('0'.code + TextScan.digitValue(ch)).toChar())
                        ch == ',' -> Unit // 千分位丢弃
                        else -> digitText.append(ch)
                    }
                }
                if (digitText.isNotEmpty()) digits.add(TextScan.hash(digitText))
                i = j
            } else if (TextScan.isCjk(c)) {
                chars++
                langUnit = true
                i++
            } else {
                i++
            }
        }
        quoted = TextScan.hasPairedQuotes(s)
        dashed = TextScan.hasDashOrColon(s)
        colon = TextScan.hasColon(s)
        allCaps = kind == SideKind.SOURCE && TextScan.isAllCaps(s)
    }

    private fun isWordCont(c: Char): Boolean =
        TextScan.isLetter(c) || TextScan.isDigit(c) || c == '\'' || c == '’' || c == '-'
}

/** 保序去重（段落下标必须保持阅读顺序，不能用 sort）。 */
internal fun IntBag.toSortedUniqueKeepingOrder(): IntArray {
    if (size == 0) return Bags.EMPTY_INT
    val out = IntArray(size)
    var w = 0
    for (i in 0 until size) {
        val v = get(i)
        if (w == 0 || out[w - 1] != v) out[w++] = v
    }
    return if (w == out.size) out else out.copyOf(w)
}
