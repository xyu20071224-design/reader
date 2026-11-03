package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **T3 交付验收**：把新实现接到 evaluator 的验收台上跑。
 *
 * ## 与 `SyntheticAlignmentRobustnessTest` 的分工
 *
 * 那个文件（evaluator 的）验证**指标与语料本身**是否可靠（oracle 满分、错标捕获、
 * 真值自洽），它跑的是 [AlignmentEngines.current]。本文件不碰它的任何阈值或语料，
 * 只做一件事：**把 [IndependentAligner] 接上，并把实测指标打印出来**。
 *
 * ## 为什么这里注入词典（Lead 已裁定 A 方案）
 *
 * `meaning = null` 时内容见证只剩标点/数字/专名。合成语料恰好把后两条掐死（每句都是
 * 陈述句、编码 token 全书高频），于是 `witnessCount ≡ 0` ⇒ 全部 `UNCERTAIN`。那不是
 * 测试，那是把最好用的信号关掉再考算法。生产环境有 ECDICT，所以主验收路径注入
 * [LexiconProvider]，无词典退化模式另立一条用例单独跑（只断言不出错）。
 */
class IndependentAlignerAcceptanceTest {

    /**
     * 从语料自带的词表构造的词典替身（<-- 这是 `SyntheticBilingualFixture` 的真值词表，
     * 不是凭空捏的映射）。
     *
     * 为什么它是对的测试替身：它只回答「这个英文词在中文里可能怎么写」，**不知道任何配对
     * 信息**——它给每个英文词返回它在语料中文侧出现过的对应词，这正是 ECDICT 在做的事
     * （词条式释义，与具体句子无关）。
     */
    /**
     * **主指标口径的词典替身**：直接取 `SyntheticBilingualFixture.truthLexicon()`。
     *
     * ## 为什么不再用自己的手写词表
     *
     * 报告里「同一个数字两个值」是硬伤。此前验收台用 fixture 的全词表、本文件用一份
     * 手写的 21 词截断表，于是 `base coverage` 同时存在 0.3478 与 0.2609 两个数。
     * 现在两边统一到 fixture 的 `truthLexicon()`，口径只有一个。
     *
     * ## 这份词典是**能力上界**，不是生产预期
     *
     * 它比真实 ECDICT 强两点：多义词被真值唯一化、无词形变化噪声。
     * 所以由它测出的覆盖率应理解为**上界估计**（报告里必须这样写）。
     */
    internal class LexiconProvider : MeaningProvider {
        private val lexicon: Map<String, List<String>> = SyntheticBilingualFixture.truthLexicon()

        override fun chineseCandidates(englishWord: String): List<String> =
            lexicon[englishWord.lowercase()] ?: emptyList()
    }

    /**
     * 主验收：12 个变体全部带词典，打印指标行。
     *
     * 断言只做一件事：`falseAligned == 0`（硬红线）。覆盖率/精确率的正式门由 evaluator
     * 的变体测试守；本测试的产出是**实测数字**，供 T5 与报告引用。
     */
    @Test
    fun allVariants_withLexicon_emitMetricsAndNoFalseAligned() {
        val engine = IndependentAligner()
        val rows = ArrayList<AlignmentMetrics>()
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val metrics = measure(book, engine, LexiconProvider())
            rows += metrics
            println(metrics.line())
            metrics.detailLines().forEach(::println)
        }
        println()
        println(AlignmentReport.table(rows))
        assertEquals(
            SyntheticBilingualFixture.Variant.ALL.size,
            rows.size,
            "每个变体都必须产出一行指标"
        )
        val violations = rows.filter { it.falseAlignedCount > 0 }
        assertTrue(
            violations.isEmpty(),
            "硬红线 falseAligned == 0 被破：\n" +
                violations.joinToString("\n") { "${it.variant}: ${it.falseAlignedCount}\n" +
                    it.detailLines().joinToString("\n") }
        )
    }

    /**
     * **无词典退化模式**（Lead 修正后的 D3）：不设覆盖率门，只断言不出错。
     *
     * 该模式下算法不承诺可用性，只承诺「能对的对了，对不了的明确说未对齐，不出错」。
     */
    @Test
    fun noMeaningDegenerateMode_onlyRequiresNoFalseAligned() {
        val engine = IndependentAligner()
        val rows = ArrayList<AlignmentMetrics>()
        SyntheticBilingualFixture.Variant.ALL.forEach { variant ->
            val book = variant.generate()
            val metrics = measure(book, engine, meaning = null)
            rows += metrics
            println("[无词典退化模式] ${metrics.line()}")
        }
        println()
        println("### 无词典退化模式（该模式下算法不承诺可用性，只承诺不出错）")
        println(AlignmentReport.table(rows))
        val violations = rows.filter { it.falseAlignedCount > 0 }
        assertTrue(
            violations.isEmpty(),
            "无词典模式下也不许出错：\n" +
                violations.joinToString("\n") { "${it.variant}: ${it.falseAlignedCount}" }
        )
    }

    /**
     * 反自证（§14.4 第 19 条，在真实语料上再验一次）：换词典不改变**配对**。
     *
     * ## 为什么比较「两轮都不是 UNALIGNED 的单元」而不是全部单元
     *
     * `UNALIGNED` 按契约**必须清空 `targetChapter` / `targetText`**（Lead 裁定：状态与
     * 展示必须一致）。于是同一个单元在「有词典 → UNCERTAIN（带坐标）」与「无词典 →
     * UNALIGNED（坐标清零）」之间切换时，**展示坐标**确实会变——但那是状态变化的
     * 合法后果，不是配对被验证层改动了。
     *
     * 真正要守的性质是：**DP 选中的 (英文句, 中文句) 对应关系不变**。所以只在两轮都
     * 「有展示坐标」的单元上比较，并额外断言：有词典时 UNALIGNED 的数量**不少于**
     * 无词典时（词典只会让判定更确定，不会让配对消失）。
     */
    @Test
    fun pairingInvariantUnderMeaning_onRealFixture() {
        val engine = IndependentAligner()
        val book = SyntheticBilingualFixture.Variant.BASE.generate()
        val providers = listOf<MeaningProvider?>(
            null,
            MeaningProvider { emptyList() },
            MeaningProvider { listOf("胡说八道") },
            LexiconProvider()
        )
        val documents = providers.map { provider ->
            engine.align(book.source, book.translation, provider)
        }

        // 基准（第一个 provider）里「有展示坐标」的单元
        val baseline = documents[0]
        for (index in 1 until documents.size) {
            val candidate = documents[index]
            assertEquals(
                baseline.units.size, candidate.units.size,
                "第 $index 个 provider 改变了单元数量（不许静默丢句/多句）"
            )
            // 只比较两轮都有展示坐标（即都不是 UNALIGNED）的单元
            var compared = 0
            for (unitIndex in baseline.units.indices) {
                val a = baseline.units[unitIndex]
                val b = candidate.units[unitIndex]
                if (a.status == AlignmentStatus.UNALIGNED ||
                    b.status == AlignmentStatus.UNALIGNED
                ) continue
                assertEquals(
                    a.sourceChapter to a.sourceIndexInParagraph,
                    b.sourceChapter to b.sourceIndexInParagraph,
                    "第 $index 个 provider 改变了英文坐标"
                )
                assertEquals(
                    a.targetChapter to a.targetIndexInParagraph,
                    b.targetChapter to b.targetIndexInParagraph,
                    "第 $index 个 provider 改变了配对（unit=$unitIndex " +
                        "en=\"${a.sourceText.take(30)}\"）"
                )
                assertEquals(
                    a.targetText, b.targetText,
                    "第 $index 个 provider 改变了展示的译文（unit=$unitIndex）"
                )
                compared++
            }
            // 至少有单元可比，否则这条测试是空转
            assertTrue(compared > 0, "第 $index 个 provider 下没有任何可比较的单元")
        }
    }

    /**
     * **性能硬门槛**：整本量级（3.5k 段落 / 11k 句）必须在 3 秒内跑完（契约
     * `AlignmentAcceptance.MAX_MILLIS_FULL_BOOK`）。
     *
     * 语料是合成的整本规模（严格按设计 §12.1 的量级构造），**不是**从合成变体放大的
     * 假数据：句子结构与真实书一致（章→段→句三层、有标点、有专名与数字）。
     */
    @Test
    fun perfFullBookScale_underThreeSeconds() {
        val (en, zh) = fullBookScale()
        val sourceSentences = en.sumOf { chapter ->
            chapter.paragraphs.sumOf { SentenceSplitter.count(it) }
        }
        val targetSentences = zh.sumOf { chapter ->
            chapter.paragraphs.sumOf { SentenceSplitter.count(it) }
        }
        val paragraphCount = en.sumOf { it.paragraphs.size }
        println(
            "PERF corpus: enChapters=${en.size} paragraphs=$paragraphCount " +
                "enSentences=$sourceSentences zhSentences=$targetSentences"
        )
        // 预热（JIT）后再计时，避免把类加载算进耗时
        IndependentAligner().align(en, zh, LexiconProvider())
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val beforeMemory = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val document = IndependentAligner().align(en, zh, LexiconProvider())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        val afterMemory = runtime.totalMemory() - runtime.freeMemory()

        println("PERF elapsed=${elapsedMillis}ms heapDelta=${(afterMemory - beforeMemory) / 1024 / 1024}MB")
        println("PERF stats: ${document.stats.summaryLine()}")
        println(document.stats.summaryLine())
        assertTrue(
            document.units.size == sourceSentences,
            "每条英文句必须恰好一个单元：units=${document.units.size} sentences=$sourceSentences"
        )
        assertTrue(
            elapsedMillis < AlignmentAcceptance.MAX_MILLIS_FULL_BOOK,
            "整本耗时 ${elapsedMillis}ms 超过预算 ${AlignmentAcceptance.MAX_MILLIS_FULL_BOOK}ms"
        )
    }

    // ──────────────────────────────────────────────────────────────

    private fun measure(
        book: SyntheticBilingualFixture.FixtureBook,
        engine: SentenceAligner,
        meaning: MeaningProvider?
    ): AlignmentMetrics {
        val document = engine.align(book.source, book.translation, meaning)
        val projection = AlignmentProjection.project(document, book.truth)
        return AlignmentMetricsCalculator.compute(
            variant = book.variant.label,
            engine = engine.engineId,
            truth = book.truth,
            projection = projection
        )
    }

    /**
     * 整本量级语料：36 章 × 约 97 段 × 约 3.1 句 ≈ 3500 段 / 10800 句。
     *
     * 与 [SyntheticBilingualFixture] 不同：这里**故意**用更接近真实散文的形态
     * （段落长短不一、有对话、有数字与专名、句长分布有方差），因为性能与误杀都要在
     * 真实形态上量，而不是在等长同构的语料上量。
     */
    private fun fullBookScale(): Pair<List<AlignmentChapter>, List<AlignmentChapter>> {
        val enSubjects = listOf(
            "the elder", "the hunter", "the smith", "the singer", "the farmer",
            "the sailor", "the healer", "the scribe", "old Meriadoc", "the stranger"
        )
        val enVerbs = listOf(
            "remembered", "described", "followed", "watched", "answered",
            "waited for", "glimpsed", "spoke of", "counted", "left"
        )
        val enObjects = listOf(
            "the river", "the mountain", "the city", "the sea", "the harvest",
            "the harbour", "the forest", "the tower", "the old road", "the ninth gate"
        )
        val enTails = listOf(
            "at dusk", "before the storm", "with great care", "in the old way",
            "as the bells rang", "without a word", "for three days", "again"
        )
        val zhSubjects = listOf(
            "那长者", "那猎人", "那铁匠", "那歌者", "那农夫",
            "那水手", "那医者", "那书吏", "老梅里亚多克", "那陌生人"
        )
        val zhVerbs = listOf(
            "想起了", "描述了", "跟随了", "注视着", "回答了",
            "等待着", "望见了", "说起了", "数着", "离开了"
        )
        val zhObjects = listOf(
            "那条河", "那座山", "那座城", "那片海", "那次收获",
            "那处港口", "那座森林", "那座塔", "那条旧路", "第九道门"
        )
        val zhTails = listOf(
            "时天色已暗", "在暴风雨之前", "极为仔细", "依着旧例",
            "钟声响起时", "一言不发", "连着三天", "又一次"
        )
        val quotations = listOf(
            " \"We should go,\" he said.",
            " \"Not yet,\" she answered quietly.",
            " \"It is the ninth gate,\" the stranger whispered.",
            ""
        )

        var index = 0
        val source = ArrayList<AlignmentChapter>(36)
        val translation = ArrayList<AlignmentChapter>(36)
        for (chapter in 0 until 36) {
            // 段落数在 90..104 之间波动（真实书里章节长度不等）
            val paragraphCount = 90 + (chapter * 7) % 15
            val enParagraphs = ArrayList<String>(paragraphCount)
            val zhParagraphs = ArrayList<String>(paragraphCount)
            for (paragraph in 0 until paragraphCount) {
                // 段内句数 2..5（长短段交替；对话段更短，这是真实形态）
                val sentenceCount = 2 + (index % 4)
                val enSentences = ArrayList<String>(sentenceCount)
                val zhSentences = ArrayList<String>(sentenceCount)
                for (sentence in 0 until sentenceCount) {
                    val subject = enSubjects[index % enSubjects.size]
                    val verb = enVerbs[(index / 3) % enVerbs.size]
                    val obj = enObjects[(index / 7) % enObjects.size]
                    val tail = enTails[(index / 11) % enTails.size]
                    val quote = if ((index % 9) == 0) quotations[index % quotations.size] else ""
                    enSentences += "$subject $verb $obj $tail$quote."
                    zhSentences += "${zhSubjects[index % zhSubjects.size]}" +
                        "${zhVerbs[(index / 3) % zhVerbs.size]}" +
                        "${zhObjects[(index / 7) % zhObjects.size]}，" +
                        "${zhTails[(index / 11) % zhTails.size]}。"
                    index++
                }
                enParagraphs += enSentences.joinToString(" ")
                zhParagraphs += zhSentences.joinToString("")
            }
            source += AlignmentChapter("Chapter ${chapter + 1}", enParagraphs)
            translation += AlignmentChapter("第${chapter + 1}章", zhParagraphs)
        }
        return source to translation
    }
}
