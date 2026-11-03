package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter

/**
 * 验收台要测的**对齐引擎**集合，以及「真实现还没接上」时的处理方式。
 *
 * ## 为什么这里的引擎都不是产品实现
 *
 * 本文件里的三台引擎全部是**评估工具的零件**，不是 [SentenceAligner] 的产品实现：
 *
 * | 引擎 | 用途 |
 * | --- | --- |
 * | [TruthOracleAligner] | 用真值表直接产出「完美对齐」。证明**指标本身**不会把满分判成不及格 |
 * | [NaiveOrderAligner] | 「顺序位置对齐」的朴素基线。证明指标**对结构扰动敏感** |
 * | [ShiftedAligner] | 故意错标（整体错位一句）。证明**错标能被抓出来** |
 * | [AllUncertainAligner] | 全弃权。证明 UNCERTAIN 诚实度这一格不是恒真的 |
 *
 * ## 接真实实现：反射式可选加载（**编译期零耦合**）
 *
 * [underTest] 用 `Class.forName` **反射**加载真实实现，而不是直接写类名：
 *
 * ```kotlin
 * Class.forName("com.linguareader.shared.alignment.IndependentAligner")
 *     .getDeclaredConstructor().newInstance() as? SentenceAligner
 * ```
 *
 * 这样做的理由是**评估台不该在编译期绑死被测实现**：
 *
 * - implementer 的类还没落盘、改名、或暂时编译不过时，本测试目录**仍然能编译**
 *   （类名只存在于字符串里，编译器不认识它）；
 * - T3 交付后无需改动本文件——只要类名与无参构造符合约定，它就自动接上；
 * - 换一个候选实现做 A/B，只需改 [REAL_ENGINE_CLASS] 或调 [install]。
 *
 * ⚠️ **诚实说明一处边界**：反射只解耦「对具体类名的编译期依赖」，**不能**让
 * `:shared:compileTestKotlin` 在主源码 (`:shared:compileKotlin`) 编译失败时照常运行——
 * Gradle 里同一模块的测试编译必然依赖主源码编译。主源码在落盘半成品时整条链都会红，
 * 这是 Gradle 的固有行为，不是本文件的缺陷。
 */
internal object AlignmentEngines {

    /**
     * 真实实现的约定类名（全限定名）。
     *
     * T3 的类名若与此处不同，改这一个常量即可（或调用 [install] 显式注入）。
     */
    const val REAL_ENGINE_CLASS = "com.linguareader.shared.alignment.IndependentAligner"

    /**
     * 待验收的真实引擎。默认 `null` = 尝试反射加载 [REAL_ENGINE_CLASS]；加载不到则为
     * `null`，此时回退到 [NaiveOrderAligner] 基线。
     *
     * 显式赋值（[install]）优先于反射，便于测试里注入自造引擎。
     */
    var underTest: SentenceAligner? = null

    /** 反射加载只尝试一次（失败后不必每次重试，类不会凭空出现）。 */
    private var reflectionAttempted = false
    private var reflected: SentenceAligner? = null

    /**
     * 反射解析真实实现：成功返回实例，失败返回 null（**绝不抛异常**）。
     *
     * 失败是**正常状态**（实现尚未交付），所以这里静默降级；调用方通过
     * [isRealImplementation] 判断当前指标属于实现还是基线。
     */
    private fun resolveReal(): SentenceAligner? {
        underTest?.let { return it }
        if (reflectionAttempted) return reflected
        reflectionAttempted = true
        reflected = try {
            val type = Class.forName(REAL_ENGINE_CLASS)
            val instance = type.getDeclaredConstructor().newInstance()
            instance as? SentenceAligner
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            // 类存在但依赖缺失/版本不匹配：当作「尚未交付」，不要让评估台因此崩掉。
            null
        } catch (_: ClassCastException) {
            null
        }
        return reflected
    }

    /** 变体验收实际使用的引擎。 */
    val current: SentenceAligner get() = resolveReal() ?: NaiveOrderAligner

    /** 是否已经接上真实实现（报告里据此说明指标属于实现还是基线）。 */
    val isRealImplementation: Boolean get() = resolveReal() != null

    /** 显式注入被测引擎（测试用）；传 `null` 恢复「反射解析 → 基线」的默认行为。 */
    fun install(engine: SentenceAligner?) {
        underTest = engine
    }
}

/**
 * **完美对齐**（oracle）：直接读真值表产出结果。
 *
 * 它不算法、不猜、不相似度——`marker → 真值单元` 是查表。因此它在任何变体上都应当拿到
 * 精确率 1.0、可对齐覆盖率 1.0、错标 0；**任何一项不达标都说明指标计算或语料真值有
 * 缺陷**，而不是对齐器差。这就是它的价值。
 *
 * @param book 要复现的那本书（每次 `Variant.generate()` 都是新实例，按变体传）
 */
internal class TruthOracleAligner(
    private val book: SyntheticBilingualFixture.FixtureBook
) : SentenceAligner {

    override val version: Int = 1
    override val engineId: String = "oracle-truth"

    private val truth: AlignmentTruth get() = book.truth

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        source.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                SentenceSplitter.split(paragraph).forEachIndexed { sentenceIndex, sentence ->
                    val key = AlignmentProjection
                        .sourceKeysIn(sentence, truth.sourceMarkerUniverse)
                        .firstOrNull()
                    if (key == null) {
                        units += AlignedUnit(
                            status = AlignmentStatus.UNALIGNED,
                            sourceChapter = chapterIndex,
                            sourceParagraph = paragraph,
                            sourceText = sentence,
                            sourceIndexInParagraph = sentenceIndex,
                            skipReason = AlignmentSkipReason.NOT_A_LANGUAGE_UNIT
                        )
                        return@forEachIndexed
                    }
                    val unitId = truth.trueTargetUnitsOf(key).firstOrNull()
                    if (unitId == null) {
                        units += AlignedUnit(
                            status = AlignmentStatus.UNALIGNED,
                            sourceChapter = chapterIndex,
                            sourceParagraph = paragraph,
                            sourceText = sentence,
                            sourceIndexInParagraph = sentenceIndex,
                            skipReason = AlignmentSkipReason.TRANSLATION_MISSING
                        )
                        return@forEachIndexed
                    }
                    val target = truth.targetUnits[unitId]
                    units += AlignedUnit(
                        status = AlignmentStatus.ALIGNED,
                        sourceChapter = chapterIndex,
                        targetChapter = targetChapterOf(unitId),
                        sourceParagraph = paragraph,
                        targetParagraph = target.text,
                        sourceText = sentence,
                        targetText = target.text,
                        sourceIndexInParagraph = sentenceIndex,
                        confidence = 1.0
                    )
                }
            }
        }
        return AlignmentDocument(version, engineId, units = units)
    }

    /** 真值单元 → 它落在译文的第几章（按累计段数反推）。 */
    private fun targetChapterOf(unitId: Int): Int {
        var remaining = unitId
        book.translation.forEachIndexed { chapterIndex, chapter ->
            val count = chapter.paragraphs.sumOf { SentenceSplitter.count(it) }
            if (remaining < count) return chapterIndex
            remaining -= count
        }
        return (book.translation.size - 1).coerceAtLeast(0)
    }
}

/**
 * **朴素顺序基线**：段内按位置 zip（英文第 n 句 ↔ 中文第 n 句），不看任何内容。
 *
 * 代表「实现还没到位」时最可能的样子：结构整齐的书能碰巧对，一旦段/句边界被扰就整段
 * 错位。它是验收台的**敏感度标尺**——如果指标连它的错位都测不出来，指标就是坏的。
 */
internal object NaiveOrderAligner : SentenceAligner {

    override val version: Int = 0
    override val engineId: String = "naive-order-baseline"

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        source.forEachIndexed { chapterIndex, chapter ->
            val zhChapter = translation.getOrNull(chapterIndex)
            val zhParagraphs: List<String> = zhChapter?.paragraphs ?: emptyList()
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                val english = SentenceSplitter.split(paragraph)
                val chinese = zhParagraphs.getOrNull(paragraphIndex)
                    ?.let { SentenceSplitter.split(it) }
                    ?: emptyList()
                english.forEachIndexed { sentenceIndex, sentence ->
                    val target = chinese.getOrNull(sentenceIndex)
                    if (target == null) {
                        units += AlignedUnit(
                            status = AlignmentStatus.UNALIGNED,
                            sourceChapter = chapterIndex,
                            sourceParagraph = paragraph,
                            sourceText = sentence,
                            sourceIndexInParagraph = sentenceIndex,
                            skipReason = AlignmentSkipReason.TRANSLATION_MISSING
                        )
                    } else {
                        units += AlignedUnit(
                            status = AlignmentStatus.ALIGNED,
                            sourceChapter = chapterIndex,
                            targetChapter = chapterIndex,
                            sourceParagraph = paragraph,
                            targetParagraph = zhParagraphs[paragraphIndex],
                            sourceText = sentence,
                            targetText = target,
                            sourceIndexInParagraph = sentenceIndex,
                            targetIndexInParagraph = sentenceIndex,
                            confidence = 0.6
                        )
                    }
                }
            }
        }
        return AlignmentDocument(version, engineId, units = units)
    }
}

/**
 * **故意错标**：每句都报 ALIGNED，且译文整体错位一句（末句回卷到首句）。
 *
 * 用途只有一个：证明 [AlignmentMetrics.falseAligned] 与精确率能把它抓出来。它不该在
 * 变体验收里出现——只被指标自检测试使用。
 */
internal object ShiftedAligner : SentenceAligner {

    override val version: Int = 0
    override val engineId: String = "shifted-deliberately-wrong"

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        val allChinese = translation.flatMap { chapter ->
            chapter.paragraphs.flatMap { paragraph ->
                SentenceSplitter.split(paragraph).map { it to paragraph }
            }
        }
        var cursor = 0
        source.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                SentenceSplitter.split(paragraph).forEachIndexed { sentenceIndex, sentence ->
                    val (target, targetParagraph) = if (allChinese.isEmpty()) {
                        "" to ""
                    } else {
                        // 错位一句：永远指向下一句，末句绕回第一句。
                        allChinese[(cursor + 1) % allChinese.size].also { cursor++ }
                    }
                    units += AlignedUnit(
                        status = AlignmentStatus.ALIGNED,
                        sourceChapter = chapterIndex,
                        targetChapter = chapterIndex,
                        sourceParagraph = paragraph,
                        targetParagraph = targetParagraph,
                        sourceText = sentence,
                        targetText = target,
                        sourceIndexInParagraph = sentenceIndex,
                        confidence = 0.9
                    )
                }
            }
        }
        return AlignmentDocument(version, engineId, units = units)
    }
}

/**
 * **全弃权**：每句都报 UNCERTAIN。用于检验 UNCERTAIN 诚实度这一格不是恒真的——
 * 全弃权时「UNCERTAIN 中真值可对齐比例」应当等于真值的可对齐比例，而覆盖率归零。
 */
internal object AllUncertainAligner : SentenceAligner {

    override val version: Int = 0
    override val engineId: String = "all-uncertain-baseline"

    override fun align(
        source: List<AlignmentChapter>,
        translation: List<AlignmentChapter>,
        meaning: MeaningProvider?
    ): AlignmentDocument {
        val units = ArrayList<AlignedUnit>()
        source.forEachIndexed { chapterIndex, chapter ->
            chapter.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                SentenceSplitter.split(paragraph).forEachIndexed { sentenceIndex, sentence ->
                    units += AlignedUnit(
                        status = AlignmentStatus.UNCERTAIN,
                        sourceChapter = chapterIndex,
                        sourceParagraph = paragraph,
                        sourceText = sentence,
                        sourceIndexInParagraph = sentenceIndex,
                        confidence = 0.3
                    )
                }
            }
        }
        return AlignmentDocument(version, engineId, units = units)
    }
}
