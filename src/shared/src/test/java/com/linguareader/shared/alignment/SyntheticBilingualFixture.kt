package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter

/**
 * 合成英中平行书：**配对真值由构造即知**，不需要任何人工判定，也不需要版权文本。
 *
 * ## 编码设计（为什么不是数字锚点）
 *
 * 每条真值英文句里嵌一个**伪词**编码，每条真值中文句里嵌一个**汉字三字组**编码：
 *
 * - 英文：`«zqk vur nif»` —— 由 [EN_SYLLABLES] 里 12 个音节按 12×12×12 = 1728 种组合
 *   生成，形态是三个小写「词」，**不含数字**。真实对齐器常见的「数字/编号锚点」捷径
 *   在这里完全失效：`「E0042」` 那种形式会被 `\d+` 正则白送分。
 * - 中文：`「叺丂丄」` —— 从 CJK 统一区挑的 12 个**罕用字**按 12³ 组三字组，同样
 *   **不含数字、不含常用字**。
 * - **两侧字符集不相交**（拉丁字母 vs 罕用汉字），所以「从任意单元里认出它覆盖了哪
 *   几句」是纯查表：英文侧按 3 词滑窗查，中文侧按汉字 3-gram 滑窗查
 *   （见 [AlignmentProjection]）。
 *
 * 编码同时充当**唯一身份**：`variant=BASE` 与 `variant=paragraphMerge` 生成的是同一批
 * 编码，所以「真值哪句对哪句」在变体前后不变——变体只改结构，不改身份。
 *
 * 编码之前还拼了规范化后的词表句子（`the elder spoke of the river at dusk`），让长度比、
 * 词义桥这类真实信号有东西可用；编码本身占文本比例很小，不足以单独撑起对齐判断。
 *
 * ## 确定性
 *
 * 全部内容由固定词表按下标组合而成，**无随机数、无时间、无 IO**。同一份代码永远生成
 * 同一份语料，指标变化只可能来自对齐器改动。
 *
 * ## 两个已经踩过的坑（**改这个文件前必读**）
 *
 * ### 1. Kotlin 字符串模板里 CJK 是合法标识符字符
 *
 * `"$tail早已不同"` 不会展开 `$tail`，而是被解析成标识符 `tail早已不同` → 编译错。
 * **任何紧邻中文的插值都必须写成 `${tail}`**。本文件所有生成句子的模板都遵守这条。
 *
 * ### 2. 句首必须大写，否则真值会静默失真
 *
 * `SentenceSplitter` 有一条规则：**闭合引号 + 后接小写词 = 说话人引导语，不切分**
 * （正确行为，对应 `'I am sorry, Frodo!' he cried` 是一句）。若语料里每句都以小写
 * `the` 开头，那么任何以 `."` 收尾的引语句都会把**下一句**并进来 —— 于是一个单元里
 * 出现两个编码，「一句 = 一个编码」的语料约定被破坏，指标随之失真，而且**不会报错**，
 * 只会让 oracle 莫名其妙地掉精确率（本套语料初版就踩过）。
 * 真实英文句首本来就大写，所以按大写生成既真实又符合分句契约。
 *
 * ## 扰动变体
 *
 * 每个变体独立可生成（[Variant.generate]），便于把「哪类结构坏了」与「哪个指标掉了」
 * 一一对上。变体清单见 [Variant] 各枚举项的 KDoc。
 *
 * **判据分档**：绝大多数变体要求「不误标 + 覆盖率达标」，但
 * [Variant.HOMOGENEOUS] 是**故意的最坏情况对照**，只守「不误标」红线，不要求覆盖率
 * （见 [Variant.NO_FALSE_ALIGNED_ONLY]）。分档是设计决定，不要写成散落的 if。
 */
internal object SyntheticBilingualFixture {

    // ---------------------------------------------------------------- 词表

    private val EN_SUBJECTS = listOf(
        "the elder", "the hunter", "the smith", "the singer",
        "the farmer", "the sailor", "the healer", "the scribe"
    )
    private val EN_VERBS = listOf(
        "spoke of", "remembered", "described", "followed",
        "watched", "answered", "waited for", "glimpsed"
    )

    /**
     * [EN_VERBS] 的**原形**，与之一一对应（`spoke of` → `speak of` 等）。
     *
     * 问句形态必须用原形：`did the farmer watch`，而不是 `did the farmer watched`。
     * 初版直接复用了过去式，产出 `Did the farmer watched the harvest…` 这种明显病句。
     * 语料的可信度比数字稳定性重要——带语法错误的语料会让人怀疑「是不是语料错了才
     * 导致算法异常」，那会削弱所有结论。
     *
     * ⚠️ **必须与 [EN_VERBS] 等长、按下标对齐**；`ensureValidated()` 里有长度断言守着。
     */
    private val EN_VERBS_BASE_FORM = listOf(
        "speak of", "remember", "describe", "follow",
        "watch", "answer", "wait for", "glimpse"
    )
    private val EN_OBJECTS = listOf(
        "the river", "the mountain", "the city", "the sea",
        "the harvest", "the harbour", "the forest", "the tower"
    )
    /** 句尾补语，让句子形态更接近真实散文、并给分句器多样的句界。 */
    private val EN_TAILS = listOf(
        "at dusk", "before the storm", "with great care",
        "in the old way", "as the bells rang", "without a word"
    )

    private val ZH_SUBJECTS = listOf(
        "那长者", "那猎人", "那铁匠", "那歌者",
        "那农夫", "那水手", "那医者", "那书吏"
    )
    private val ZH_VERBS = listOf(
        "说起了", "想起了", "描述了", "跟随了",
        "注视着", "回答了", "等待着", "望见了"
    )
    private val ZH_OBJECTS = listOf(
        "那条河", "那座山", "那座城", "那片海",
        "那次收获", "那处港口", "那座森林", "那座塔"
    )
    private val ZH_TAILS = listOf(
        "时天色已暗", "在暴风雨之前", "极为仔细", "依着旧例",
        "钟声响起时", "一言不发"
    )

    /**
     * **全词表真值词典**：语料里出现过的每一个英文内容词，都能查到它对应的中文译法。
     *
     * ## 为什么需要它（Lead 的要求）
     *
     * 原先测试用的是 `OracleLexicon`，它按「稳定共现率 ≥50%」从语料里**统计**出义项，
     * 结果只覆盖 48 个词。真实 ECDICT 有 77 万条词条，**48 个词上测出的覆盖率不能代表
     * 算法能力**——瓶颈还卡在词典覆盖上，而不是算法本身。
     *
     * 这里直接**从语料的中英对照词表推导**（`EN_SUBJECTS[i] ↔ ZH_SUBJECTS[i]` 等），
     * 所以是精确的、100% 覆盖的，不依赖任何统计阈值。覆盖率的瓶颈这才从「词典」移到
     * 「算法」。
     *
     * ## 它仍然是理想化的（必须声明）
     *
     * - 多义词被真值唯一化（一个英文词只给它在语料里的那个译法）；
     * - 无词形变化处理（`spoke` / `speak` 分别给同一个中文译法，靠词表下标对齐）。
     *
     * 这两点让它成为**能力上界**而非常规性能。真实 ECDICT 会更吵。
     */
    fun truthLexicon(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableSet<String>>()
        fun add(englishPhrase: String, chinesePhrase: String) {
            val glosses = chineseGlossFragments(chinesePhrase)
            if (glosses.isEmpty()) return
            englishPhrase.lowercase().split(' ').forEach { word ->
                if (word.length < 2 || word in LEXICON_FUNCTION_WORDS) return@forEach
                out.getOrPut(word) { LinkedHashSet() }.addAll(glosses)
            }
        }
        EN_SUBJECTS.forEachIndexed { i, en -> add(en, ZH_SUBJECTS[i]) }
        EN_VERBS.forEachIndexed { i, en -> add(en, ZH_VERBS[i]) }
        // 原形与过去式共用同一译法（语料里是同一个动作）。
        EN_VERBS_BASE_FORM.forEachIndexed { i, en -> add(en, ZH_VERBS[i]) }
        EN_OBJECTS.forEachIndexed { i, en -> add(en, ZH_OBJECTS[i]) }
        EN_TAILS.forEachIndexed { i, en -> add(en, ZH_TAILS[i]) }
        // 引语形态额外引入的词：中文对应短语是「早已不同」（`was never the same`）。
        listOf("said", "was", "never", "same").forEach { add(it, "早已不同") }
        return out.mapValues { (_, v) -> v.toList() }
    }

    /** 中文短语的 2–4 字子串（与引擎的 `TERM_MIN_CHARS..TERM_MAX_CHARS` 对齐）。 */
    private fun chineseGlossFragments(phrase: String): List<String> {
        val cjk = phrase.filter { it in '\u4E00'..'\u9FFF' }
        if (cjk.length < 2) return emptyList()
        val result = LinkedHashSet<String>()
        for (len in 2..4) {
            var from = 0
            while (from + len <= cjk.length) {
                result.add(cjk.substring(from, from + len))
                from++
            }
        }
        return result.toList()
    }

    /** 词典里不收录的英文功能词（与引擎的 `FUNCTION_WORDS` 同口径）。 */
    private val LEXICON_FUNCTION_WORDS = setOf(
        "the", "a", "an", "of", "for", "to", "in", "on", "at", "as", "by", "or", "if",
        "and", "but", "not", "is", "are", "was", "were", "be", "did", "does", "do",
        "with", "from", "that", "this", "it", "he", "she", "they", "we", "you", "his",
        "her", "its", "their", "there", "here", "have", "has", "had", "will", "would"
    )

    /**
     * 英文编码音节表：12 个**刻意不像英文单词**的小写音节，每次取三个拼成编码。
     *
     * 12³ = 1728 种组合，远超本套语料的句数，保证编码唯一。全部小写、无数字，
     * 保证它作为**三个普通 token** 存在，不依赖任何特殊字符形态。
     */
    private val EN_SYLLABLES = listOf(
        "zqk", "vur", "nif", "gle", "drax", "mub",
        "phet", "jox", "civ", "wam", "ryu", "kob"
    )

    /**
     * 中文编码汉字表：12 个罕用字，每次取三个拼成编码。
     *
     * 要求：都在 `\u4E00`..`\u9FFF` 区内（分句器与投影器按 CJK 区判）、且不是正文
     * 词表用字，避免与正文撞字导致假识别。
     */
    private val ZH_SYLLABLES = listOf(
        '叺', '丂', '丄', '乂', '仉', '厹',
        '夬', '奂', '屮', '巛', '彳', '恏'
    )

    /** 英文编码的 token 数：3。与 [AlignmentProjection.SOURCE_MARKER_TOKENS] 一致。 */
    private const val EN_MARKER_TOKENS = 3

    /** 中文编码的汉字数：3。与 [AlignmentProjection.TARGET_MARKER_LENGTH] 一致。 */
    private const val ZH_MARKER_LENGTH = 3

    /** 译者加写/附录用的编码起始下标：远离正文（正文只用 0..99）。 */
    private const val EXTRA_INDEX_BASE = 1500

    /** 译者加写区间要预留多少个编码位（最大变体只用十几个，留足余量）。 */
    private const val EXTRA_INDEX_SLOTS = 64

    /** 全书规模默认值：5 章 × 4 段 × 3 句 = 60 句，足以暴露结构缺陷又跑得够快。 */
    const val DEFAULT_CHAPTERS = 5
    const val DEFAULT_PARAGRAPHS = 4
    const val DEFAULT_SENTENCES = 3

    /**
     * 构造参数自检。
     *
     * 迟于 [TRADITIONAL_MAP] 的声明位置执行：Kotlin 属性按声明顺序初始化，若把这段放进
     * `init` 块（声明在 [TRADITIONAL_MAP] 之前），读到的会是未初始化值，编译器直接拒绝。
     * 因此改为首次生成编码时惰性校验一次。
     */
    private var validated = false

    private fun ensureValidated() {
        if (validated) return
        // 编码长度是投影器与生成器之间的硬约定，任何一侧改动都在这里立刻炸。
        require(EN_MARKER_TOKENS == AlignmentProjection.SOURCE_MARKER_TOKENS) {
            "英文编码 token 数与投影器不一致"
        }
        require(ZH_MARKER_LENGTH == AlignmentProjection.TARGET_MARKER_LENGTH) {
            "中文编码汉字数与投影器不一致"
        }
        // 编码空间必须覆盖到「译者加写」用的高位区间，且留足余量给未来的变体。
        val capacity = EN_SYLLABLES.size * EN_SYLLABLES.size * EN_SYLLABLES.size
        require(capacity > EXTRA_INDEX_BASE + EXTRA_INDEX_SLOTS) {
            "英文编码空间不足以覆盖到译者加写区间（容量=$capacity，需要≥${EXTRA_INDEX_BASE + EXTRA_INDEX_SLOTS}）"
        }
        val zhCapacity = ZH_SYLLABLES.size * ZH_SYLLABLES.size * ZH_SYLLABLES.size
        require(zhCapacity > EXTRA_INDEX_BASE + EXTRA_INDEX_SLOTS) {
            "中文编码空间不足以覆盖到译者加写区间（容量=$zhCapacity，需要≥${EXTRA_INDEX_BASE + EXTRA_INDEX_SLOTS}）"
        }
        require(ZH_SYLLABLES.none { TRADITIONAL_MAP[it] != null }) {
            "繁简表不得覆盖中文编码汉字"
        }
        // 原形动词表必须与过去式表按下标一一对齐，否则问句会拿到错的原形。
        require(EN_VERBS_BASE_FORM.size == EN_VERBS.size) {
            "原形动词表必须与 EN_VERBS 等长：${EN_VERBS_BASE_FORM.size} vs ${EN_VERBS.size}"
        }
        validated = true
    }

    /**
     * 英文编码的第 `index` 个（0-based），形如 `zqk vur nif`。
     *
     * 三个位置各加一个固定偏移（`+0 / +4 / +8`，模 12）。加常数模 12 是双射，所以
     * 唯一性不受影响，但视觉上不会出现 `zqk zqk zqk` 这种「前两段相同」的编码——
     * 那种形态容易被误以为「编码段重复」而掩盖真值错误。
     */
    fun englishMarker(index: Int): String {
        ensureValidated()
        val base = EN_SYLLABLES.size
        val a = (index % base + 0) % base
        val b = (index / base % base + 4) % base
        val c = (index / (base * base) % base + 8) % base
        return "${EN_SYLLABLES[a]} ${EN_SYLLABLES[b]} ${EN_SYLLABLES[c]}"
    }

    /** 中文编码的第 `index` 个（0-based），形如 `叺丂丄`。偏移规则同 [englishMarker]。 */
    fun chineseMarker(index: Int): String {
        ensureValidated()
        val base = ZH_SYLLABLES.size
        val a = (index % base + 0) % base
        val b = (index / base % base + 4) % base
        val c = (index / (base * base) % base + 8) % base
        return buildString { append(ZH_SYLLABLES[a]).append(ZH_SYLLABLES[b]).append(ZH_SYLLABLES[c]) }
    }

    /** 真值键：英文编码（小写、单空格）。中英两侧共用同一个键。 */
    fun keyOf(index: Int): String = englishMarker(index)

    // ------------------------------------------------------------ 变体定义

    /**
     * 一个扰动变体。`label` 用作指标行的 `variant=`，也是测试名与报告里的定位键。
     *
     * 每个变体只注入**一类**结构性缺陷，且都在基线之上做**局部**改动：未被扰动触及的
     * 句子编码与配对关系保持原样，所以「指标掉了多少」可以归因到这一类缺陷。
     */
    enum class Variant(val label: String) {
        /** 干净基线：1:1 句对齐，无任何扰动。所有其它变体都拿它当参照。 */
        BASE("base"),

        /** 中文侧整段漏译（每章漏第 2 段）。真值：这些英文句无对应。 */
        MISSING_TRANSLATION("missingTranslation"),

        /** 中文侧多出一段译者加写。真值：多出的中文句无对应英文。 */
        EXTRA_TRANSLATION("extraTranslation"),

        /** 段落 2:1 合并：中文两个短段落拼成一段（句数与编码不变，只是段界消失）。 */
        PARAGRAPH_MERGE("paragraphMerge"),

        /** 段落 1:2 拆分：中文一个段落按句拆成两段（句数与编码不变，段界变多）。 */
        PARAGRAPH_SPLIT("paragraphSplit"),

        /** 句级 2:1 合并：中文两句拼成一句（真值：一条中文句承载两条英文句）。 */
        SENTENCE_MERGE("sentenceMerge"),

        /** 句级 1:2 拆分：中文一句拆成两句（真值：一条英文句仍只有一个可识别译文句）。 */
        SENTENCE_SPLIT("sentenceSplit"),

        /** 中文改写为繁体（字形变化，检验繁简归一/字符匹配的鲁棒性）。 */
        TRADITIONAL("traditional"),

        /** 章节数不匹配：中文侧删掉第一章、追加一章附录（模拟封面/目录/附录偏差）。 */
        CHAPTER_COUNT_MISMATCH("chapterCountMismatch"),

        /** 英文句被硬换行切断（换行落在词间，分句器看到的是一段带 `\n` 的文本）。 */
        HARD_WRAP("hardWrap"),

        /** 混入样板行：纯标点段、标题行、页码行。真值：这些不是语言单位。 */
        BOILERPLATE("boilerplate"),

        /**
         * **低方差对照组**：整本语料只用 [Shape.PLAIN] 一种形态，句长高度同构。
         *
         * 这是**故意的最坏情况**，专门测「长度信号失效时，算法还能不能靠内容通道兜住」。
         * 真实小说不是这样，但真实语料里存在局部同构段（连续短对话、排比句），所以
         * 这个对照组有独立价值，不能因为「不真实」而删掉。
         *
         * 它的验收判据与其他变体不同：**只要求不产生错标**（`falseAligned == 0`），
         * 不要求覆盖率达标——低方差下覆盖率本来就该低，要求它达标等于奖励硬凑。
         */
        HOMOGENEOUS("homogeneous"),

        /**
         * **等长章节对抗变体**：所有章节完全同构（每章同样段数、同样句数），
         * 章节层**没有任何长度区分信号**。
         *
         * 对应现实场景：等长章节的小说、诗集、日记体、按行排版的古典文本。这些书里
         * 「英第 3 章 ↔ 中第 3 章」无法靠章节长度证明，章节 DP 的 margin 恒为 0。
         *
         * 用途是**专用回归守门**，钉死三件事（见测试类 `equalLengthChapters_*`）：
         *
         * 1. 不许错标（`falseAligned == 0`，硬红线）；
         * 2. **不许整本放弃**（`coverageT` 有下限）——「一句都对齐不了」是鲁棒性失败，
         *    不是安全降级；
         * 3. **必须留下降级痕迹**（`AlignmentDocument.notes` 提到低 margin / identity 先验），
         *    否则将来有人把降级路径「优化」掉，光看覆盖率还能蒙混过关。
         */
        EQUAL_LENGTH_CHAPTERS("equalLengthChapters"),

        /**
         * **章节内容错位配对**：英文章节顺序不动，中文侧的章节内容整体打乱后按位置排列。
         *
         * 构造：中文**文件位置** p 那章的标题仍是 `第(p+1)章`（真实损坏文件就是这样——
         * 标题跟着文件顺序走），但**内容是英文第 perm(p) 章**的译文。perm 是无不动点排列，
         * 所以没有任何一章恰好对位。
         *
         * ## 这是 `falseAligned` 红线的主战场
         *
         * 前面所有变体测的都是「结构扰动下能否对齐」；这个测的是「**章节配错时，内容通道
         * 能不能拦住错标**」。按位置或按标题配对的实现会把整章内容错配，而它的长度比、
         * 段落数都看起来完全正常——**这是最难发现、用户损失最大的一类错标**。
         *
         * ## 真值口径（**容易搞错，会让用例变假绿**）
         *
         * 真值仍然是**编码驱动**的：英文第 k 句 ↔ 含编码 k 的中文句。章节打乱**不改变**
         * 这个事实——那些译文客观就在书里，只是不在原来的位置。所以：
         *
         * - 内容通道强的实现**可以**找到正确配对（覆盖率可能不低，这是合法的）；
         * - 按位置配对的实现会大面积错标 → `falseAligned` 抓它；
         * - 覆盖率**不设门**：找不到也完全可以接受，**不许为了凑覆盖率去猜**。
         *
         * 刻意**不**把真值改成「全错」：那等于让真值表撒谎（译文明明存在），而且会让
         * 「报什么都不算错」成为通过方式，红线就失效了。
         */
        CHAPTER_CONTENT_PERMUTED("chapterContentPermuted");

        // ══════════════════════════════════════════════════════════════════════
        //  已删除：`contentDistinct`（内容不重叠对照组）—— 2026-10-31 Lead 裁定
        // ══════════════════════════════════════════════════════════════════════
        //
        // 曾尝试加一个「每句用专属词汇、内容天然可分」的对照语料，用来回答
        // 「语料可分时算法覆盖率能到多少」。**被删除了，不要加回来**，理由：
        //
        // 1. **三重重言式**：用我们自己构造的可分语料，证明我们自己的算法在可分语料上
        //    能行——无论结果如何都不构成对外部世界的证据。
        // 2. **真实语料已在手边**：魔戒金标准重放天然可分，直接测就有答案，不需要替身。
        // 3. **最有害的一点**：如果它通过了，会给人虚假安慰——「算法没问题，只是语料
        //    不行」。但真实书有词形变化、多义词、意译，可能依然不行。**这个安慰是有害
        //    的，会让人停止追问。**
        //
        // 同样**不要**采用「给每句塞一个只属于该句的真词」的做法：那会制造一个 ECDICT
        // 永远不会有的理想条件（每句有专属词），测出来的覆盖率是幻觉，比现状更危险。
        //
        // 保留下来的是 `SEPARABILITY` 诊断（[SeparabilityProbe]）—— 它证明的是
        // 「主语的 base 语料不可分」，那是**否定性**结论，有价值且不产生虚假安慰。

        fun generate(): FixtureBook = when (this) {
            BASE -> SyntheticBilingualFixture.buildBase()
            MISSING_TRANSLATION -> SyntheticBilingualFixture.buildMissingTranslation()
            EXTRA_TRANSLATION -> SyntheticBilingualFixture.buildExtraTranslation()
            PARAGRAPH_MERGE -> SyntheticBilingualFixture.buildParagraphMerge()
            PARAGRAPH_SPLIT -> SyntheticBilingualFixture.buildParagraphSplit()
            SENTENCE_MERGE -> SyntheticBilingualFixture.buildSentenceMerge()
            SENTENCE_SPLIT -> SyntheticBilingualFixture.buildSentenceSplit()
            TRADITIONAL -> SyntheticBilingualFixture.buildTraditional()
            CHAPTER_COUNT_MISMATCH -> SyntheticBilingualFixture.buildChapterCountMismatch()
            HARD_WRAP -> SyntheticBilingualFixture.buildHardWrap()
            BOILERPLATE -> SyntheticBilingualFixture.buildBoilerplate()
            HOMOGENEOUS -> SyntheticBilingualFixture.buildHomogeneous()
            EQUAL_LENGTH_CHAPTERS -> SyntheticBilingualFixture.buildEqualLengthChapters()
            CHAPTER_CONTENT_PERMUTED -> SyntheticBilingualFixture.buildChapterContentPermuted()
        }

        companion object {
            /** 变体全集，按「基线 → 结构扰动 → 噪声 → 对照组」顺序排列。 */
            val ALL: List<Variant> = listOf(
                BASE,
                MISSING_TRANSLATION,
                EXTRA_TRANSLATION,
                PARAGRAPH_MERGE,
                PARAGRAPH_SPLIT,
                SENTENCE_MERGE,
                SENTENCE_SPLIT,
                TRADITIONAL,
                CHAPTER_COUNT_MISMATCH,
                HARD_WRAP,
                BOILERPLATE,
                HOMOGENEOUS,
                EQUAL_LENGTH_CHAPTERS,
                CHAPTER_CONTENT_PERMUTED
            )

            /**
             * 判据组：**低方差对照组只要求不产生错标**，不要求覆盖率。
             *
             * 单独成组而不是在断言里 `if (variant == HOMOGENEOUS)`：判据分档是设计决定，
             * 摊在断言里会散落成魔法分支，改一次要翻遍测试。
             */
            val NO_FALSE_ALIGNED_ONLY: Set<Variant> = setOf(HOMOGENEOUS)
        }
    }

    // ------------------------------------------------------------ 语料数据类

    /**
     * 生成结果。中英两侧都是 [AlignmentChapter] 形态（叶级段落），可以直接喂给
     * [SentenceAligner.align]；真值表 [truth] 与之一一对应。
     */
    class FixtureBook(
        val variant: Variant,
        val source: List<AlignmentChapter>,
        val translation: List<AlignmentChapter>,
        val truth: AlignmentTruth,
        /** 该变体里刻意加入、真值判定为「非语言单位」的英文段落（样板行/硬换行残片）。 */
        val nonUnitSourceParagraphs: Set<String> = emptySet()
    ) {
        /** 英文句总数（按真值表，不经分句器）。 */
        val sourceSentenceCount: Int get() = truth.sourceKeyCount

        /** 换一个变体标签（语料内容不变）。供「同一语料、不同变体身份」的对照组使用。 */
        fun withVariant(newVariant: Variant): FixtureBook =
            FixtureBook(newVariant, source, translation, truth, nonUnitSourceParagraphs)

        fun describe(): String = buildString {
            append("variant=").append(variant.label)
            append(" chapters=").append(source.size).append('/').append(translation.size)
            append(" truth=").append(truth.summary())
        }
    }

    /** 中文侧一句（或合并后的一组），带它承载的编码与来源英文句键。 */
    private class ZhSentence(
        val text: String,
        val markers: List<String> = emptyList(),
        val sourceKeys: List<String> = emptyList()
    )

    /** 中文段落 = 若干句；[sentences] 拼起来就是段落文本。 */
    private class ZhParagraph(val sentences: List<ZhSentence>) {
        val text: String = sentences.joinToString("") { it.text }
    }

    /** 一个真值单元格：第 [index] 句的英中两版（文本已按形态生成好，此处只持有）。 */
    private class Cell(val index: Int, val en: String, val zh: String) {
        val enKey: String = keyOf(index)
        val zhMarker: String = chineseMarker(index)
    }

    // -------------------------------------------------------------- 生成主体

    /**
     * 句子形态。**语料必须有真实的句长方差**，否则长度比类信号的区分度被人为压平：
     * 若每句都是同样的 10 个词 / 17 个字，那么「正确对」与「邻位替代对」的结构代价
     * 只差 0.05 量级，任何基于长度比的判决阈值都会系统性地失效——那时红的不是算法，
     * 是语料太规整。
     *
     * 因此按 [shapeCycle] 轮转五种形态，让词数分布覆盖约 6～26 词（中文约 12～48 字），
     * 并且混入引语、问句这类在真实小说里高频、但在合成语料里常被漏掉的结构。
     */
    enum class Shape {
        /** 极短句：约 5～7 词。真实小说里大量存在（对话、动作）。 */
        SHORT,

        /** 常规陈述句：约 10～12 词。基线形态。 */
        PLAIN,

        /** 长复合句：约 22～26 词，用 `and` / `while` 串三个分句。 */
        LONG_COMPOUND,

        /** 问句：以 `?` 收尾，检验句界与长度比在非陈述句上的稳定性。 */
        QUESTION,

        /** 引语句：句内带成对引号，检验引号归属与句界。 */
        QUOTED
    }

    /**
     * 默认形态轮转。顺序刻意交错长短，使相邻句的长度差异本身就构成信号——真实文本
     * 正是如此，而「等长句堆叠」会让位置微调（错位一句）几乎不改变总代价。
     */
    private val DEFAULT_SHAPE_CYCLE: List<Shape> = listOf(
        Shape.PLAIN, Shape.SHORT, Shape.LONG_COMPOUND, Shape.PLAIN,
        Shape.QUESTION, Shape.PLAIN, Shape.LONG_COMPOUND, Shape.QUOTED
    )

    /**
     * 默认的**每章段落数**：刻意做成 2～8 段的真实方差。
     *
     * 真实书的章节长度差异很大（《魔戒》英 36 章 / 中 29 章，单章从几百字到上万字）。
     * 若每章都 4 段 12 句等长，**章节层就没有任何可用信号**：章节 DP 的所有路径长度
     * 代价完全相同，margin 恒为 0，实现只能靠先验蒙；那是在考语料而不是考算法。
     *
     * 实测反馈（`MarginDistributionProbe`）：等长章节下章节配对 margin 恒 0.0，章节层
     * 否决级联到全书，coverage 掉到 0.0139。所以主语料必须带真实章节方差。
     *
     * 极差 = 8 − 2 = 6 段（远大于断言的 ≥3），且不是全等。
     */
    private val DEFAULT_CHAPTER_PARAGRAPHS: List<Int> = listOf(2, 6, 3, 8, 4)

    /**
     * 语料规模与形态配置。**全部参数可选**，默认值即 CI 使用的那一套。
     *
     * `shapeCycle` 可调是刻意的：评估「实现是否只是被语料规整度惯坏了」时，把它换成
     * 全 [Shape.PLAIN] 就能复现「近同构语料」，换成更大的轮转可以施加更极端的长度方差。
     *
     * @param chapterParagraphs 每章段落数；**null = 所有章用同一个 [paragraphs]（等长章节，
     *   章节层无区分信号）**。[DEFAULT_OPTIONS] 传入真实的方差模式；对抗变体
     *   [Variant.EQUAL_LENGTH_CHAPTERS] 显式传 null。
     */
    class CorpusOptions(
        val chapters: Int = DEFAULT_CHAPTERS,
        val paragraphs: Int = DEFAULT_PARAGRAPHS,
        val sentences: Int = DEFAULT_SENTENCES,
        private val shapeCycle: List<Shape> = DEFAULT_SHAPE_CYCLE,
        private val chapterParagraphs: List<Int>? = null
    ) {
        init {
            require(chapters > 0 && paragraphs > 0 && sentences > 0) {
                "语料规模必须为正：$chapters×$paragraphs×$sentences"
            }
            require(shapeCycle.isNotEmpty()) { "形态轮转不能为空" }
            require(chapterParagraphs == null || chapterParagraphs.all { it > 0 }) {
                "每章段落数必须为正：$chapterParagraphs"
            }
        }

        /** 第 [index] 句使用的形态（按下标轮转，无随机数）。 */
        fun shapeAt(index: Int): Shape = shapeCycle[index % shapeCycle.size]

        /**
         * 第 [chapter] 章的段落数。
         *
         * 未配置 [chapterParagraphs] 时用恒定 [paragraphs]（等长章节）；配置了则按下标取，
         * 超出的章节回落到 [paragraphs]（避免变体改章节数时越界）。
         */
        fun paragraphCountAt(chapter: Int): Int =
            chapterParagraphs?.getOrNull(chapter) ?: paragraphs

        /** 是否为等长章节（无章节层长度信号）。对抗变体据此自检。 */
        val hasUniformChapterLengths: Boolean
            get() = chapterParagraphs == null ||
                chapterParagraphs.distinct().size == 1

        /** 每章段落数，按 [chapters] 展开（供断言与诊断用）。 */
        fun chapterParagraphCounts(): List<Int> = (0 until chapters).map(::paragraphCountAt)
    }

    /** 默认配置（CI 用）：**带真实章节长度方差**。 */
    val DEFAULT_OPTIONS: CorpusOptions = CorpusOptions(
        chapterParagraphs = DEFAULT_CHAPTER_PARAGRAPHS
    )

    private fun englishSentence(index: Int, shape: Shape): String {
        val s = EN_SUBJECTS[index % EN_SUBJECTS.size]
        val verbIndex = index / EN_SUBJECTS.size % EN_VERBS.size
        val v = EN_VERBS[verbIndex]
        // 问句需要原形（did + 原形），其余形态用过去式。
        val vBase = EN_VERBS_BASE_FORM[verbIndex]
        val o = EN_OBJECTS[index * 5 % EN_OBJECTS.size]
        val tail = EN_TAILS[index / EN_VERBS.size % EN_TAILS.size]
        val marker = englishMarker(index)
        // 第二个分句用偏移取词，保证同一句内的分句不是同一个词换皮。
        val s2 = EN_SUBJECTS[(index + 3) % EN_SUBJECTS.size]
        val v2 = EN_VERBS[(index + 5) % EN_VERBS.size]
        val o2 = EN_OBJECTS[(index + 2) % EN_OBJECTS.size]
        val s3 = EN_SUBJECTS[(index + 6) % EN_SUBJECTS.size]
        val o3 = EN_OBJECTS[(index + 4) % EN_OBJECTS.size]
        // **句首要大写**，这不只是为了像真散文：`SentenceSplitter` 有一条「闭合引号 +
        // 后接小写词 = 说话人引导语、不切分」的规则（`'I am sorry, Frodo!' he cried`）。
        // 若每句都以小写 `the` 开头，任何以 `."` 收尾的引语句都会把**下一句**并进来，
        // 于是「一句 = 一个编码」的语料约定被破坏，真值随之失真。真实文本的句首本来就
        // 是大写的，所以按大写生成既更真实也符合分句契约。
        val body = when (shape) {
            Shape.SHORT -> "$s $v $o «$marker»."
            Shape.PLAIN -> "$s $v $o $tail «$marker»."
            Shape.LONG_COMPOUND ->
                "$s $v $o $tail, and $s2 $v2 $o2 while $s3 watched $o3 «$marker»."
            Shape.QUESTION -> "did $s $vBase $o $tail «$marker»?"
            Shape.QUOTED -> "$s said, \"$o $tail was never the same «$marker».\""
        }
        return body.replaceFirstChar { it.uppercaseChar() }
    }

    private fun chineseSentence(index: Int, shape: Shape): String {
        val s = ZH_SUBJECTS[index % ZH_SUBJECTS.size]
        val v = ZH_VERBS[index / ZH_SUBJECTS.size % ZH_VERBS.size]
        val o = ZH_OBJECTS[index * 5 % ZH_OBJECTS.size]
        val tail = ZH_TAILS[index / ZH_VERBS.size % ZH_TAILS.size]
        val marker = chineseMarker(index)
        val s2 = ZH_SUBJECTS[(index + 3) % ZH_SUBJECTS.size]
        val v2 = ZH_VERBS[(index + 5) % ZH_VERBS.size]
        val o2 = ZH_OBJECTS[(index + 2) % ZH_OBJECTS.size]
        val s3 = ZH_SUBJECTS[(index + 6) % ZH_SUBJECTS.size]
        val o3 = ZH_OBJECTS[(index + 4) % ZH_OBJECTS.size]
        // ⚠️ 所有紧邻中文的插值都必须写成 `${x}` 带大括号：Kotlin 标识符允许 Unicode
        // 字母，`$tail早已不同` 会被解析成标识符 `tail早已不同` 而不是 `$tail` + 文本。
        //
        // 每种形态都含至少一个「，」：句级拆分变体要靠它在构造上稳定地切一刀。
        // 引语形态用「他说：」而非「他说，」，让 `。」` 正常收句（CJK 终止符规则）。
        return when (shape) {
            Shape.SHORT -> "${s}${v}${o}，随即沉默「${marker}」。"
            Shape.PLAIN -> "${s}${v}${o}，${tail}「${marker}」。"
            Shape.LONG_COMPOUND ->
                "${s}${v}${o}，${tail}，而${s2}${v2}${o2}，${s3}则注视着${o3}「${marker}」。"
            Shape.QUESTION -> "${s}可曾${v}${o}，${tail}「${marker}」？"
            Shape.QUOTED -> "${s}说：「${o}${tail}早已不同，此事无可挽回「${marker}」。」"
        }
    }

    /**
     * 铺满 章×段×句 的真值单元格网格。
     *
     * **每章段落数可以不同**（[CorpusOptions.paragraphCountAt]）：这是章节层长度信号的
     * 唯一来源。全等章节会让章节 DP 的所有路径长度代价相同、margin 恒 0 而只能靠蒙。
     * 注意句下标 `index` 是全书连续的，所以章节长短只影响**结构**，不影响编码身份。
     */
    private fun cells(options: CorpusOptions = DEFAULT_OPTIONS): List<List<List<Cell>>> {
        var index = 0
        return (0 until options.chapters).map { chapter ->
            (0 until options.paragraphCountAt(chapter)).map {
                (0 until options.sentences).map {
                    val i = index++
                    Cell(i, englishSentence(i, options.shapeAt(i)), chineseSentence(i, options.shapeAt(i)))
                }
            }
        }
    }

    private fun zhOf(cell: Cell): ZhSentence =
        ZhSentence(cell.zh, listOf(cell.zhMarker), listOf(cell.enKey))

    /** 句级 2:1：去掉前一句句号，用逗号连成**一句**（真实译本常见的合并形态）。 */
    private fun mergeZh(first: ZhSentence, second: ZhSentence): ZhSentence = ZhSentence(
        text = first.text.removeSuffix("。") + "，" + second.text,
        markers = first.markers + second.markers,
        sourceKeys = first.sourceKeys + second.sourceKeys
    )

    /**
     * 句级 1:2：在 `，` 处断开并补句号。
     *
     * 只有后半句含编码，所以真值里这一条英文句对应的**可识别**译文句是后半句；
     * 前半句是一个没有编码的译文句（真值里也无法识别），对齐器指向后半句即算正确。
     */
    private fun splitZh(cell: Cell): List<ZhSentence> {
        val head = cell.zh.substringBefore('，')
        val tail = cell.zh.substringAfter('，')
        require(head != cell.zh && tail.isNotEmpty()) { "拆分点在构造上必须存在：${cell.zh}" }
        return listOf(
            ZhSentence("$head。"),
            ZhSentence(tail, listOf(cell.zhMarker), listOf(cell.enKey))
        )
    }

    /** 把单元格网格转成英文段落文本（1 段 = 若干句用空格拼）。 */
    private fun englishParagraphs(grid: List<List<List<Cell>>>): List<List<String>> =
        grid.map { paragraphs -> paragraphs.map { cells -> cells.joinToString(" ") { it.en } } }

    /**
     * 把中文句网格转成 [AlignmentChapter]。
     *
     * @param titles 章节标题；为 null 时用默认的序号标题。**章节数不匹配变体必须显式传**
     *   与内容自洽的标题（见 [buildChapterCountMismatch]），否则标题会成为误导信号。
     */
    private fun translationChapters(
        layout: List<List<ZhParagraph>>,
        titles: List<String>? = null
    ): List<AlignmentChapter> =
        layout.mapIndexed { i, paragraphs ->
            AlignmentChapter(
                title = titles?.getOrNull(i) ?: "第${i + 1}章",
                paragraphs = paragraphs.map { it.text }
            )
        }

    /** 把中文句网格按阅读顺序摊平成真值单元表。 */
    private fun targetUnits(layout: List<List<ZhParagraph>>): List<TargetUnit> {
        val units = ArrayList<TargetUnit>()
        layout.forEach { paragraphs ->
            paragraphs.forEach { paragraph ->
                paragraph.sentences.forEach { sentence ->
                    units += TargetUnit(
                        id = units.size,
                        markers = sentence.markers,
                        sourceKeys = sentence.sourceKeys,
                        text = sentence.text
                    )
                }
            }
        }
        return units
    }

    /** 统一组装：源段落 + 中文布局 + 可选额外英文段落。 */
    private fun assemble(
        variant: Variant,
        grid: List<List<List<Cell>>>,
        layout: List<List<ZhParagraph>>,
        extraSourceParagraphs: Map<Int, List<String>> = emptyMap(),
        translationTitles: List<String>? = null
    ): FixtureBook {
        val source = grid.indices.map { chapterIndex ->
            AlignmentChapter(
                title = "Chapter ${chapterIndex + 1}",
                paragraphs = englishParagraphs(grid)[chapterIndex] +
                    (extraSourceParagraphs[chapterIndex] ?: emptyList())
            )
        }
        val translation = translationChapters(layout, translationTitles)
        val units = targetUnits(layout)
        val nonUnitSource = extraSourceParagraphs.values.flatten().toSet()
        return FixtureBook(
            variant = variant,
            source = source,
            translation = translation,
            truth = buildTruth(grid, units),
            nonUnitSourceParagraphs = nonUnitSource
        )
    }

    /**
     * 真值表：英文句键按网格顺序；中文单元按译文阅读顺序；编码全集按译文实际文本扫描。
     *
     * 「谁对谁」只来自 [Cell.index]，不来自任何文本比较——这是本套验收台的立身之本。
     *
     * 关键区分：**译者加写的编码进入 [AlignmentTruth.targetMarkerUniverse] 但不进入
     * [AlignmentTruth.markerToUnit]**。前者让它可被识别（从而能把「对齐器指到了译者加写
     * 的句子上」判成 FOREIGN_TARGET，而不是因为认不出编码就当成没有译文），后者保证它
     * 不被误认为任何英文句的合法译文。只带编码、不带编码的拆分前半句则两边都不进。
     */
    private fun buildTruth(
        grid: List<List<List<Cell>>>,
        units: List<TargetUnit>
    ): AlignmentTruth {
        val sourceKeys = grid.flatten().flatten().map { it.enKey }
        val markerToUnit = HashMap<String, Int>()
        val targetMarkers = LinkedHashSet<String>()
        units.forEach { unit ->
            val identifiable = unit.sourceKeys.isNotEmpty()
            unit.markers.forEach { marker ->
                targetMarkers += marker
                if (identifiable) markerToUnit[marker] = unit.id
            }
        }
        return AlignmentTruth(
            sourceKeysInOrder = sourceKeys,
            targetUnits = units,
            markerToUnit = markerToUnit,
            sourceMarkerUniverse = sourceKeys.toSet(),
            targetMarkerUniverse = targetMarkers
        )
    }

    // ------------------------------------------------------------ 各变体实现

    private fun buildBase(options: CorpusOptions = DEFAULT_OPTIONS): FixtureBook {
        val grid = cells(options)
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells -> ZhParagraph(cells.map(::zhOf)) }
        }
        val book = assemble(Variant.BASE, grid, layout)
        // **恒等式断言（语料自检）**：基线不做任何扰动，所以「每句英文都有译文」必须成立。
        // 这条断言把语料 bug 挡在源头——一旦 `cells()`/`buildTruth()` 的张量下标或编码映射
        // 出现错位，这里立刻炸，而不是让下游指标悄悄少算一批句子。
        require(book.truth.untranslatedSourceKeys.isEmpty()) {
            "基线语料不该有未翻译的英文句，实际 ${book.truth.untranslatedSourceKeys.size} 句：" +
                book.truth.untranslatedSourceKeys.take(5)
        }
        require(book.truth.translatedSourceKeys.size == book.truth.sourceKeyCount) {
            "基线每句英文都必须有译文：${book.truth.translatedSourceKeys.size}" +
                "/${book.truth.sourceKeyCount}"
        }
        require(book.truth.extraTargetMarkers == 0) {
            "基线不该有译者加写，实际 ${book.truth.extraTargetMarkers} 条"
        }
        return book
    }

    /** 等长章节的网格（供对抗变体与「章节方差」自检复用）。 */
    private fun equalLengthGrid(): List<List<List<Cell>>> =
        cells(CorpusOptions(chapterParagraphs = null))

    /**
     * 低方差对照组：整本只用 [Shape.PLAIN]，句长同构。
     *
     * 与 [buildBase] 的唯一差别是形态轮转，所以两个变体之间的指标差**只可能**来自
     * 「长度信号的可区分度」，这正是对照实验想要的单变量。
     */
    private fun buildHomogeneous(): FixtureBook =
        buildBase(
            CorpusOptions(
                shapeCycle = listOf(Shape.PLAIN),
                // 低方差对照组与基线**保持同样的章节长度方差**，否则就不是单变量对照
                // （否则句长与章节长度两个变量一起变，差值无法归因）。默认值即带方差。
                chapterParagraphs = DEFAULT_CHAPTER_PARAGRAPHS
            )
        ).withVariant(Variant.HOMOGENEOUS)

    /**
     * **等长章节对抗变体**：所有章节完全同构。
     *
     * 与 [buildBase] 的差别只在 `chapterParagraphs = null`（每章都用同一个段落数）——
     * 章节长度信号被**人为抹掉**，章节 DP 拿不到任何可用于区分「英第 n 章 ↔ 中第 m 章」
     * 的长度证据。这正是现实里等长章节小说/诗集/日记体的形态。
     *
     * 语料本身是**完全正确**的（1:1 句对齐、无漏译、无加写），所以它是干净的对抗样本：
     * 任何对齐损失都只能归因于「章节层信号缺失时降级路径不工作」。
     */
    private fun buildEqualLengthChapters(): FixtureBook {
        val grid = equalLengthGrid()
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells -> ZhParagraph(cells.map(::zhOf)) }
        }
        return assemble(Variant.EQUAL_LENGTH_CHAPTERS, grid, layout)
    }

    /**
     * 章节内容错位配对：英文章节顺序不动，中文侧**按位置**摆放被打乱的章节内容。
     *
     * 关键点（决定这个变体是不是「假绿」）：
     *
     * - 中文**文件位置** p 的标题仍是 `第(p+1)章`——真实损坏文件就是这样，标题跟着文件
     *   顺序走，不会跟着内容走。所以「按标题配对」的实现会自信地全错。
     * - 中文位置 p 的**内容**是英文第 perm(p) 章的译文。perm 是**无不动点**排列（没有任何
     *   p 满足 perm(p)==p），所以不存在偶然对位的章节。
     * - 真值**不跟着打乱**：编码 k 仍对应英文第 k 句。译文客观就在书里，只是换了位置。
     *   这是唯一诚实的口径——若把真值改成「全错」，等于让真值表撒谎，红线失效。
     */
    private fun buildChapterContentPermuted(): FixtureBook {
        val grid = cells()
        val perm = noFixedPointPermutation(grid.size)
        val layout = grid.indices.map { position ->
            // 位置 position 放的是英文第 perm[position] 章的内容。
            grid[perm[position]].map { cells -> ZhParagraph(cells.map(::zhOf)) }
        }
        val book = assemble(Variant.CHAPTER_CONTENT_PERMUTED, grid, layout)
        // 自检：这个变体必须真的有「位置 ≠ 内容来源」的错位，否则它测不到任何东西。
        perm.forEachIndexed { position, source ->
            require(position != source) { "错位排列必须无不动点，实际位置 $position 放到自己" }
        }
        require(book.translation.size == book.source.size) {
            "章节内容错位不改变章节总数：${book.translation.size} vs ${book.source.size}"
        }
        // 自检：译文段落数与英文**不再**逐章对应（存在至少一对长度不同），保证不是等长掩盖。
        val enCounts = book.source.map { it.paragraphs.size }
        val zhCounts = book.translation.map { it.paragraphs.size }
        require(enCounts != zhCounts) {
            "错位后章节段落数序列必须发生改变，否则错位不可观测：en=$enCounts zh=$zhCounts"
        }
        return book
    }

    /**
     * 无不动点排列：把第 0 章的内容挪到别处，其余顺次错开一位。
     *
     * 对 `n` 章返回 `[1, 2, ..., n-1, 0]`（整体左移一位），这是最简单且**必然无不动点**
     * 的排列（`n > 1` 时）。它保持「章节总数不变、每章内容完整」这两条，所以任何错标都
     * 只能来自配对判断本身，而不是语料造假。
     */
    private fun noFixedPointPermutation(n: Int): List<Int> {
        require(n > 1) { "无不动点排列要求章节数 > 1，实际 $n" }
        return (1 until n).toList() + 0
    }

    /** 漏译：中文侧每章删掉第 2 段，被删段里的英文句进入真值「无对应」集合。 */
    private fun buildMissingTranslation(): FixtureBook {
        val grid = cells()
        val layout = grid.map { paragraphs ->
            paragraphs.filterIndexed { i, _ -> i != 1 }.map { cells -> ZhParagraph(cells.map(::zhOf)) }
        }
        return assemble(Variant.MISSING_TRANSLATION, grid, layout)
    }

    /**
     * 多译：中文侧每章末尾追加一段**译者加写**，其编码不属于任何真值英文句。
     *
     * 编码取自中文编码表的尾部区间（`index ≥ 1500`），远离正文用到的 0..99。
     */
    private fun buildExtraTranslation(): FixtureBook {
        val grid = cells()
        val layout = grid.indices.map { chapter ->
            val base = grid[chapter].map { cells -> ZhParagraph(cells.map(::zhOf)) }
            base + ZhParagraph(
                listOf(
                    chineseExtra(chapter * 2),
                    chineseExtra(chapter * 2 + 1)
                )
            )
        }
        return assemble(Variant.EXTRA_TRANSLATION, grid, layout)
    }

    /** 一条「译者加写」中文句：编码不属于真值表，sourceKeys 为空。 */
    private fun chineseExtra(ordinal: Int): ZhSentence {
        val index = EXTRA_INDEX_BASE + ordinal
        val text = chineseSentence(index, Shape.PLAIN)
        return ZhSentence(text, listOf(chineseMarker(index)), emptyList())
    }

    /** 段落 2:1：中文把「段0+段1」「段2+段3」各拼成一段，段数减半、句数与编码不变。 */
    private fun buildParagraphMerge(): FixtureBook {
        val grid = cells(CorpusOptions(paragraphs = 4))
        val layout = grid.map { paragraphs ->
            paragraphs.chunked(2).map { pair ->
                ZhParagraph(pair.flatMap { cells -> cells.map(::zhOf) })
            }
        }
        return assemble(Variant.PARAGRAPH_MERGE, grid, layout)
    }

    /** 段落 1:2：中文每段按句拆成两段（前两句一段、末句一段），段数翻倍。 */
    private fun buildParagraphSplit(): FixtureBook {
        val grid = cells()
        val layout = grid.map { paragraphs ->
            paragraphs.flatMap { cells ->
                listOf(
                    ZhParagraph(cells.take(2).map(::zhOf)),
                    ZhParagraph(cells.drop(2).map(::zhOf))
                )
            }
        }
        return assemble(Variant.PARAGRAPH_SPLIT, grid, layout)
    }

    /** 句级 2:1：中文把段内相邻两句拼成一句（每段 4 句 → 2 句）。 */
    private fun buildSentenceMerge(): FixtureBook {
        val grid = cells(CorpusOptions(sentences = 4))
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells ->
                ZhParagraph(cells.map(::zhOf).chunked(2).map { pair -> mergeZh(pair[0], pair[1]) })
            }
        }
        return assemble(Variant.SENTENCE_MERGE, grid, layout)
    }

    /** 句级 1:2：中文把每个英文长句在「，」处拆成两句。 */
    private fun buildSentenceSplit(): FixtureBook {
        val grid = cells()
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells -> ZhParagraph(cells.flatMap(::splitZh)) }
        }
        return assemble(Variant.SENTENCE_SPLIT, grid, layout)
    }

    /**
     * 繁体：中文侧逐字转繁体。
     *
     * 正文词表里能被 [TRADITIONAL_MAP] 收的字会被转换；编码汉字刻意选在繁简同形的罕用区
     * （不在替换表里），所以编码保持不变——繁体变体检验的是「字形变化不影响句级识别」，
     * 而不是「编码被改掉导致真值丢失」。
     */
    private fun buildTraditional(): FixtureBook {
        val grid = cells()
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells ->
                ZhParagraph(cells.map { cell ->
                    val converted = toTraditional(cell.zh)
                    require(converted.contains(cell.zhMarker)) {
                        "繁简替换不得改动中文编码：${cell.zh} → $converted"
                    }
                    zhOf(cell).let { ZhSentence(converted, it.markers, it.sourceKeys) }
                })
            }
        }
        return assemble(Variant.TRADITIONAL, grid, layout)
    }

    /**
     * 章节数不匹配：中文侧**删掉第一章**并在末尾**追加两章附录**。
     *
     * 模拟真实的「译本卷首少了内容、卷尾多了附录」。第一章的英文句因此进入真值
     * 「无对应」集合，raw coverage 的数学上限降到 48/60 = 0.80（所以该变体单列阈值，
     * 主判据看 coverageT）。
     *
     * ## 标题必须与内容自洽（语料缺陷修正）
     *
     * 初版按**中文下标**生成标题 `第1章..第6章`，于是「en 第2章 ↔ 中第1章」是正确配对
     * 却**标题编号差 1**，而「en 第1章 ↔ 中第1章」是错误配对却**标题完全一致** ——
     * 等于给算法递了反向证据，任何合理实现都会倾向错配。这不是算法缺陷，是语料缺陷。
     *
     * 现在标题**跟内容走**：译文保留原章名（`第2章..第5章` + `附录`），与真实译本一致
     * （中文译本通常保留原章名/章号）。于是标题信号与内容同向，不再构成误导。
     */
    private fun buildChapterCountMismatch(): FixtureBook {
        val grid = cells()
        val retainedChapters = grid.indices.drop(1)
        val layout = retainedChapters.map { chapter ->
            grid[chapter].map { cells -> ZhParagraph(cells.map(::zhOf)) }
        } + listOf(
            // 附录章 1：目录式标题行。
            listOf(ZhParagraph(listOf(ZhSentence("附录　关于文本来源的说明")))),
            // 附录章 2：两条译者补记。
            listOf(
                ZhParagraph(
                    listOf(
                        chineseExtra(40),
                        chineseExtra(41)
                    )
                )
            )
        )
        // 标题跟内容走：被保留的英文第 n 章 → 中文「第n章」；附录章用自己的标题。
        val titles = retainedChapters.map { chapter -> "第${chapter + 1}章" } +
            listOf("附录　关于文本来源的说明", "附录　译后记")
        return assemble(
            Variant.CHAPTER_COUNT_MISMATCH,
            grid,
            layout,
            translationTitles = titles
        )
    }

    /**
     * 硬换行：英文句在**词间**插入 `\n`（模拟 TXT/EPUB 抽取出的硬折行）。
     *
     * 折行点按句子下标与段落下标轮转（3 种位置），保证不是所有句子都在同一处被折。
     * 只折词间、不折词中：词中折行会把编码 token 劈开，真值将无法从文本识别，那时
     * 度量退化为「不可判定」而不是「对齐器错了」，混在同一个指标里没有意义。
     */
    private fun buildHardWrap(): FixtureBook {
        val grid = cells()
        val wrapped = grid.mapIndexed { chapterIndex, paragraphs ->
            paragraphs.mapIndexed { paragraphIndex, cells ->
                cells.joinToString(" ") { cell ->
                    wrapAtWordBoundary(cell.en, (cell.index + paragraphIndex + chapterIndex) % 3)
                }
            }
        }
        val layout = grid.map { paragraphs ->
            paragraphs.map { cells -> ZhParagraph(cells.map(::zhOf)) }
        }
        val source = grid.indices.map { chapterIndex ->
            AlignmentChapter("Chapter ${chapterIndex + 1}", wrapped[chapterIndex])
        }
        return FixtureBook(
            variant = Variant.HARD_WRAP,
            source = source,
            translation = translationChapters(layout),
            truth = buildTruth(grid, targetUnits(layout))
        )
    }

    /** 在第 [slot]（0/1/2，按可折行位置三等分）个词后插入换行。 */
    private fun wrapAtWordBoundary(sentence: String, slot: Int): String {
        val words = sentence.split(' ')
        if (words.size < 5) return sentence
        val at = 2 + slot * ((words.size - 4) / 2)
        return buildString {
            words.forEachIndexed { i, word ->
                if (i == at) append('\n') else if (i > 0) append(' ')
                append(word)
            }
        }
    }

    /**
     * 样板行：英文侧插入纯标点段、全大写标题行、页码行；中文侧插入「目录」标题行与
     * 页码行。真值上这些**不是语言单位**，不该被报 ALIGNED（报了就是错标）。
     */
    private fun buildBoilerplate(): FixtureBook {
        val grid = cells()
        val layout = grid.mapIndexed { chapter, paragraphs ->
            val base = paragraphs.map { cells -> ZhParagraph(cells.map(::zhOf)) }
            when (chapter) {
                0 -> base + ZhParagraph(
                    listOf(
                        ZhSentence("目　录"),
                        ZhSentence("第一部分　那条河"),
                        ZhSentence("— 3 —")
                    )
                )
                grid.size - 1 -> base + ZhParagraph(
                    listOf(ZhSentence("※※※"), ZhSentence("全书完"))
                )
                else -> base
            }
        }
        val extraEn = mapOf(
            0 to listOf("***", "PART ONE: THE RIVER", "— 3 —"),
            grid.size - 1 to listOf("***", "THE END")
        )
        return assemble(Variant.BOILERPLATE, grid, layout, extraSourceParagraphs = extraEn)
    }

    /** 中文转繁体：只转 [TRADITIONAL_MAP] 列出的常用字，编码汉字不在表内。 */
    private fun toTraditional(text: String): String =
        buildString(text.length) { text.forEach { append(TRADITIONAL_MAP[it] ?: it) } }

    /**
     * 简→繁替换表：**只收正文词表里实际出现的字**，逐对硬编码。
     *
     * 刻意避开中文编码汉字（[ZH_SYLLABLES]），保证繁体变体不会把编码也改掉从而让真值
     * 失效；[SyntheticBilingualFixture] 的 `init` 会用 `require` 复核这条约束。
     */
    private val TRADITIONAL_MAP: Map<Char, Char> = buildMap {
        put('长', '長'); put('猎', '獵'); put('说', '說'); put('条', '條')
        put('学', '學'); put('获', '獲'); put('处', '處'); put('见', '見')
        put('铁', '鐵'); put('农', '農'); put('医', '醫'); put('书', '書')
        put('记', '記'); put('时', '時'); put('风', '風'); put('声', '聲')
        put('钟', '鐘'); put('响', '響'); put('旧', '舊'); put('细', '細')
        put('极', '極'); put('为', '為'); put('这', '這'); put('个', '個')
        put('视', '視'); put('随', '隨'); put('发', '發'); put('着', '著')
        put('者', '者'); put('起', '起'); put('暗', '暗'); put('已', '已')
        put('依', '依'); put('暴', '暴'); put('待', '待'); put('注', '注')
        put('收', '收'); put('望', '望'); put('述', '述'); put('描', '描')
        put('跟', '跟'); put('回', '回'); put('答', '答'); put('想', '想')
    }
}
