package com.linguareader.shared.ai

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 一批待翻译**译块**：某章内连续的若干个叶级段落（与
 * [com.linguareader.app.tts.TtsTextExtractor] 的 blocks 同源），或某个超长段落
 * 按句边界切出的子块。
 *
 * 正常段落整段成块、绝不会被切断；只有单个段落自身超过每批上限时才按句拆成
 * 多个译块（见 [AiBookTranslator.groupIntoBatches] 与
 * [AiBookTranslator.splitOversizedParagraph]）。译文回写时同段的多个译块会按序
 * 合并回一条，最终仍是「一段原文 = 一段译文」——段落 1:1 映射是译本对照对齐
 * 质量的根基，这条不因拆分而改变。
 */
data class TranslationBatch(
    val chapterIndex: Int,
    val batchIndex: Int,
    /**
     * 这些译文对应章内**译块**的编号，按序排列，也是 prompt / JSON 里 `i` 的取值。
     *
     * 未发生拆分的章里译块与段落 1:1，编号等于段落下标；含超长段的章里，同段拆出
     * 的多个译块依次占号，因此编号是「章内译块号」而非严格的段落下标。
     */
    val paragraphIndices: List<Int>,
    /** 每个译块的源文本，与 [paragraphIndices] 平行。 */
    val paragraphs: List<String>,
    /**
     * 每个译块归属的**原始段落下标**（同段被拆成多块时重复）。默认与
     * [paragraphIndices] 相同 = 译块与段落 1:1（未发生拆分）。译文回写时按它把
     * 同段的多个译块合并回一条，见 [AiBookTranslator.mergeBatchTranslations]。
     */
    val sourceParagraphIndices: List<Int> = paragraphIndices
) {
    val charCount: Int get() = paragraphs.sumOf { it.length }
}

/**
 * AI 整本书翻译的纯逻辑核心：批次分组、prompt 构建（术语注入 + 上文携带）、
 * 响应解析与批后自检。不依赖 Android，全部可 JVM 单测。
 *
 * 质量约定（对应产品定位：给学英文的人做忠实对照，不是出版译本）：
 * 直译为主、语序贴原文、不合并拆分句子；数字原样保留；术语表词条按用户译法，
 * 译法为空的词条「保留原文」不译——这同时保证对齐器的拉丁/数字锚点命中。
 */
object AiBookTranslator {

    /** 每批源文字符上限。按输出端标定：约 3-4k 汉字译文，稳落在输出 token 上限内。 */
    const val MAX_CHARS_PER_BATCH = 6_000

    /**
     * 连续多少批用尽重试仍失败后中止整本翻译（系统性失败的止损）。
     * 与历史上「单批失败中止整本」（c1097ac 修掉）不冲突：那是偶发单点失败
     * （一个数字锚点）被放大成整本必败；这里只拦「重试耗尽 × 连续 3 批」的
     * 系统性失败（坏 Key/欠费/断网）——继续跑只会把剩下的书全烧成英文占位。
     */
    const val MAX_CONSECUTIVE_BATCH_FAILURES = 3

    /**
     * 单段超过此字符数即视为「超长段落」：其译文可能顶到模型的输出 token 上限
     * （8192 token ≈ 2-3.5 万英文字符的译文量）。估算里显式提示用户。
     *
     * 真命中时不再整段独占一批（那会让单批远超 [MAX_CHARS_PER_BATCH]，模型上下文
     * 超限 → 重试耗尽 → 连败断路器中止整本，正是「长文本整本翻译总是失败」的
     * 根因），而是按句边界切成多个译块（[splitOversizedParagraph]），回写时由
     * [mergeBatchTranslations] 合并回一段。
     */
    const val OVERSIZED_PARAGRAPH_CHARS = 15_000

    /** 术语表注入 prompt 的条数上限（与语境点词的 take(80) 同量级）。 */
    private const val MAX_GLOSSARY_LINES = 80

    const val TRANSLATION_SYSTEM_PROMPT =
        "你是一位专业的英译中译者，正在为一款英语学习阅读器翻译整本英文书，" +
            "译文将供中文读者与原文逐段对照使用。风格要求：直译为主、忠实原文；" +
            "语序尽量贴近原文；不要合并或拆分句子；不添加任何解释、注释或译者按语；" +
            "数字、年份、编号必须原样保留；用户提供的术语表中词条必须按给定译法处理，" +
            "译法为「保留原文」的词条保持英文不译。" +
            "输出要求：用户消息里每个段落带编号（如 [0]）。逐段翻译后只输出一个 JSON 对象：" +
            "{\"segments\":[{\"i\":段落编号,\"t\":\"该段中文译文\"}]}，" +
            "编号必须与输入一一对应，不得遗漏、不得新增。"

    /**
     * 把一章的段落按 [maxCharsPerBatch] 贪心分组，**每一批都不超过上限**。
     *
     * 普通段落整段成块，绝不切断。单个段落自身超过上限时按句边界切成多个译块
     * （[splitOversizedParagraph]），再与其他译块一起贪心装批——所以同一段落的译块
     * 可能分布到相邻多批。批次的 [TranslationBatch.paragraphIndices] 是章内**译块号**，
     * [TranslationBatch.sourceParagraphIndices] 记录每块归属的原段，回写时用
     * [mergeBatchTranslations] 按原段合并。
     */
    fun groupIntoBatches(
        chapterIndex: Int,
        paragraphs: List<String>,
        maxCharsPerBatch: Int = MAX_CHARS_PER_BATCH
    ): List<TranslationBatch> {
        require(maxCharsPerBatch >= 1) { "maxCharsPerBatch must be positive, got $maxCharsPerBatch" }
        // 展开成译块：每块记录归属的原始段落下标。普通段 1 块，超长段多块。
        val sourceIndices = mutableListOf<Int>()
        val texts = mutableListOf<String>()
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.length <= maxCharsPerBatch) {
                sourceIndices += index
                texts += paragraph
            } else {
                splitOversizedParagraph(paragraph, maxCharsPerBatch).forEach { chunk ->
                    sourceIndices += index
                    texts += chunk
                }
            }
        }
        // 按译块贪心装批：每块都 ≤ 上限，所以每批必然 ≤ 上限。
        val batches = mutableListOf<TranslationBatch>()
        var positions = mutableListOf<Int>()
        var chars = 0
        fun flush() {
            if (positions.isEmpty()) return
            batches += TranslationBatch(
                chapterIndex = chapterIndex,
                batchIndex = batches.size,
                // 章内译块号：未发生拆分时与原始段落下标逐位相同（旧检查点、手动 IO 零变化）。
                paragraphIndices = positions.toList(),
                paragraphs = positions.map { texts[it] },
                sourceParagraphIndices = positions.map { sourceIndices[it] }
            )
            positions = mutableListOf()
            chars = 0
        }
        texts.forEachIndexed { position, text ->
            if (positions.isNotEmpty() && chars + text.length > maxCharsPerBatch) flush()
            positions += position
            chars += text.length
        }
        flush()
        return batches
    }

    /**
     * 把一个超长段落切成 ≤ [maxChars] 的译块：先用 [SentenceSplitter]（与朗读/对齐
     * 同源的断句规则：缩写保护、省略号、中英引号）分出句子，再按句贪心合并；单个
     * 句子本身超限时由 SentenceSplitter 在词边界（整段无空格时退到字符边界）硬切
     * 兜底。译块覆盖整段、无重叠，且每块都 ≤ [maxChars]。
     *
     * 入参段落来自 [com.linguareader.app.tts.TtsTextExtractor]，已被归一化成单空格、
     * 无首尾空白的文本，因此断句前的空白归一化对它是恒等变换；各译块用单个空格拼回
     * 即得原段（失败批的整段英文占位依赖这一点）。
     */
    private fun splitOversizedParagraph(paragraph: String, maxChars: Int): List<String> {
        val sentences = SentenceSplitter.split(paragraph, maxSentenceLength = maxChars)
        if (sentences.isEmpty()) return listOf(paragraph)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            // +1 是合并时的分隔空格，保证合并后的块仍 ≤ 上限。
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChars) {
                chunks += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * 把逐批译文合并回「每原始段落一条」的章级译文列表，顺序与章内段落下标一致。
     *
     * 同一原始段落被 [splitOversizedParagraph] 拆成多个译块、可能跨批，这里按译块
     * 顺序用 [separator] 拼接（默认空串：中文不需要空格，断句标点已在译文里）。
     *
     * [translationsByBatch] 与 [batches] 等长；某批为 null 表示该批翻译失败。此时
     * 它覆盖到的每个段落**整段**回退为 [englishParagraphs] 的原文——与「失败批以英文
     * 原文占位、段落数与英文侧严格 1:1」的既有语义一致，也避免英文子块的边界空格被
     * 空串拼接吞掉后粘成一团。
     *
     * [englishParagraphs] 是该章原始叶级段落，与 [TranslationBatch.sourceParagraphIndices]
     * 同一下标空间。
     */
    fun mergeBatchTranslations(
        batches: List<TranslationBatch>,
        translationsByBatch: List<List<String>?>,
        englishParagraphs: List<String>,
        separator: String = ""
    ): List<String> {
        require(batches.size == translationsByBatch.size) {
            "batches(" + batches.size + ") 与 translationsByBatch(" + translationsByBatch.size + ") 必须一一对应"
        }
        val order = LinkedHashSet<Int>()
        val merged = HashMap<Int, StringBuilder>()
        val failed = HashSet<Int>()
        batches.forEachIndexed { batchPosition, batch ->
            val translations = translationsByBatch[batchPosition]
            batch.sourceParagraphIndices.forEachIndexed { position, sourceIndex ->
                order += sourceIndex
                if (translations == null) {
                    failed += sourceIndex
                    return@forEachIndexed
                }
                val piece = translations.getOrNull(position)
                    ?: batch.paragraphs.getOrNull(position)
                    ?: return@forEachIndexed
                val builder = merged.getOrPut(sourceIndex) { StringBuilder() }
                if (builder.isNotEmpty()) builder.append(separator)
                builder.append(piece)
            }
        }
        return order.sorted().map { index ->
            if (index in failed) {
                englishParagraphs.getOrNull(index) ?: merged[index]?.toString().orEmpty()
            } else {
                merged[index]?.toString().orEmpty()
            }
        }
    }

    fun buildUserPrompt(
        bookTitle: String,
        chapterTitle: String,
        glossary: List<GlossaryEntry>,
        previousTail: String?,
        batch: TranslationBatch,
        retryError: String? = null,
        styleNotes: String? = null
    ): String = buildString {
        appendLine("书名：$bookTitle")
        appendLine("本章标题：${chapterTitle.ifBlank { "第 ${batch.chapterIndex + 1} 章" }}")
        styleLine(styleNotes)?.let { appendLine(it) }
        val lines = glossaryLines(glossary)
        if (lines.isNotEmpty()) {
            appendLine("本书术语表（词条 | 译法 | 说明；译法为「保留原文」的保持英文不译）：")
            lines.forEach { appendLine(it) }
            appendLine()
        }
        if (!previousTail.isNullOrBlank()) {
            appendLine("前情提要（上一段已完成的译文，仅供衔接语气与指代，不要翻译或输出它）：")
            appendLine(previousTail)
            appendLine()
        }
        appendLine("请把下面这些带编号的英文段落逐段翻译成简体中文：")
        batch.paragraphs.forEachIndexed { position, paragraph ->
            appendLine("[${batch.paragraphIndices[position]}] $paragraph")
        }
        appendLine()
        if (retryError != null) {
            appendLine("上一次输出未通过校验（$retryError）。请重新逐段完整翻译，确保每个编号都有对应译文、不合并不拆分段落。")
            appendLine()
        }
        appendLine("只输出 JSON：{\"segments\":[{\"i\":编号,\"t\":\"中文译文\"}]}")
    }

    /**
     * 解析并自检一批译文。
     *
     * 校验分两档：
     * - **硬校验**（任何时候都拒绝）：缺 segments 数组、编号没覆盖全批次、译文空白。
     *   这类响应结构上就没法用，留着只会污染对照。
     * - **软校验**（仅 [strict] 时拒绝）：数字锚点、「保留原文」术语、长度比。
     *   这些是质量偏好而非结构错误。过去它们也是硬失败，于是模型把 1,000 译成
     *   「一千」就判整批失败，而一批失败会中止整本书 —— 文本越长批数越多，
     *   命中概率越接近 1，正是「长文本整本翻译总是失败」的成因。
     *   现在首轮仍然拒绝（失败原因进重试 prompt 提醒模型），重试轮放行并保留译文。
     */
    fun extractValidated(
        json: JSONObject,
        batch: TranslationBatch,
        keepOriginalTerms: List<String>,
        strict: Boolean = true
    ): List<String> {
        val array = json.optJSONArray("segments")
            ?: throw AiRequestException("AI 译文缺少 segments 数组")
        val byIndex = HashMap<Int, String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val index = item.optInt("i", Int.MIN_VALUE)
            val text = item.optString("t").trim()
            if (index == Int.MIN_VALUE) continue
            byIndex[index] = text
        }
        val missing = batch.paragraphIndices.filter { byIndex[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            // 错误消息会原样进重试 prompt，别用 [N] 方括号格式——那和待翻译
            // 段落的编号格式撞车，可能把模型（或解析器）带偏。
            throw AiRequestException(
                "AI 译文缺少编号 ${missing.joinToString("、")} 的段落（共 ${batch.paragraphIndices.size} 段）"
            )
        }
        val translations = batch.paragraphIndices.map { byIndex.getValue(it) }

        // 软校验到此为止：重试轮只要结构完整就收下，别让质量偏好毁掉整本书。
        if (!strict) return translations

        batch.paragraphs.forEachIndexed { position, source ->
            val translated = translations[position]
            Regex("\\d+").findAll(source).forEach { match ->
                if (match.value !in translated) {
                    throw AiRequestException("AI 译文丢失了数字锚点「${match.value}」")
                }
            }
            // 「保留原文」词条按段校验：本段源文出现的词条必须在本段译文存活。
            // 过去是批级检查（同批任意译文命中即过），一段漏翻术语、另一段碰巧
            // 含同词就会漏检。错误消息会原样进重试 prompt，别用 [N] 方括号格式。
            keepOriginalTerms.forEach { term ->
                val trimmed = term.trim()
                if (trimmed.length >= 2 && source.contains(trimmed, ignoreCase = true) &&
                    !translated.contains(trimmed, ignoreCase = true)
                ) {
                    throw AiRequestException(
                        "AI 译文未按术语表保留原文「$trimmed」（编号 ${batch.paragraphIndices[position]} 的段落）"
                    )
                }
            }
        }
        val sourceWords = batch.paragraphs.sumOf { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        val translatedChars = translations.sumOf { it.length }
        if (sourceWords > 0 && (translatedChars < sourceWords * 0.2 || translatedChars > sourceWords * 8)) {
            throw AiRequestException("AI 译文长度异常（原文 $sourceWords 词，译文 $translatedChars 字）")
        }
        return translations
    }

    /**
     * 句级定点重翻的 system prompt：只翻一个句子，输出单字段 JSON。
     */
    const val RETRANSLATE_SYSTEM_PROMPT =
        "你是一位专业的英译中译者，正在为一款英语学习阅读器修订整本书翻译中" +
            "用户不满意的一个句子。风格要求：直译为主、忠实原文；语序尽量贴近原文；" +
            "不添加解释或注释；数字、年份、编号必须原样保留；术语表词条按给定译法处理，" +
            "「保留原文」的词条保持英文不译。只输出一个 JSON 对象：" +
            "{\"translation\":\"重译后的中文句子\"}。"

    /**
     * 精修一遍（两阶段润色的第二遍）：把「原文 + 初稿」交给模型修订。
     * 输出格式与初翻一致，自检复用 [extractValidated]。
     */
    const val POLISH_SYSTEM_PROMPT =
        "你是一位严谨的中文译审。用户会给你一段英文原文和一份中文初稿，" +
            "请逐段对照原文修订初稿：补上漏译的内容，改掉死译/硬译的句子，" +
            "修正指代错误和时态错误，术语表词条必须按给定译法，「保留原文」的词条保持英文不译，" +
            "数字、年份、编号必须原样保留。" +
            "只修订确有问题的地方，初稿里已经通顺准确的段落保持原样，不要为了改而改。" +
            "输出要求：初稿每段带编号（如 [0]）。修订后只输出一个 JSON 对象：" +
            "{\"segments\":[{\"i\":段落编号,\"t\":\"修订后的中文段落\"}]}，" +
            "编号必须与初稿一一对应，不得遗漏、不得新增。"

    fun buildPolishUserPrompt(
        bookTitle: String,
        chapterTitle: String,
        glossary: List<GlossaryEntry>,
        batch: TranslationBatch,
        draftTranslations: List<String>,
        styleNotes: String? = null,
        retryError: String? = null
    ): String = buildString {
        appendLine("书名：$bookTitle")
        appendLine("本章标题：${chapterTitle.ifBlank { "第 ${batch.chapterIndex + 1} 章" }}")
        styleLine(styleNotes)?.let { appendLine(it) }
        val lines = glossaryLines(glossary)
        if (lines.isNotEmpty()) {
            appendLine("本书术语表（词条 | 译法 | 说明；译法为「保留原文」的保持英文不译）：")
            lines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine("英文原文与中文初稿对照如下，请逐段修订：")
        batch.paragraphs.forEachIndexed { position, source ->
            appendLine("[${batch.paragraphIndices[position]}] 原文：$source")
            appendLine("[${batch.paragraphIndices[position]}] 初稿：${draftTranslations[position]}")
        }
        appendLine()
        if (retryError != null) {
            appendLine("上一次输出未通过校验（$retryError）。请重新逐段完整输出，确保每个编号都有对应修订稿。")
            appendLine()
        }
        appendLine("只输出 JSON：{\"segments\":[{\"i\":编号,\"t\":\"修订后的中文段落\"}]}")
    }

    /**
     * 句级定点重翻的 prompt：带所在段落上下文、当前译文、术语表、风格说明和
     * 用户反馈（可空 = 原样重试换一次结果）。
     */
    fun buildRetranslateUserPrompt(
        enSentence: String,
        enParagraph: String,
        currentZh: String,
        glossary: List<GlossaryEntry>,
        styleNotes: String? = null,
        feedback: String?
    ): String = buildString {
        appendLine("书名上下文中的一句英文需要重新翻译。所在段落（仅供理解上下文，不要翻译）：")
        appendLine(enParagraph)
        appendLine()
        styleLine(styleNotes)?.let { appendLine(it) }
        val lines = glossaryLines(glossary)
        if (lines.isNotEmpty()) {
            appendLine("本书术语表（词条 | 译法 | 说明；译法为「保留原文」的保持英文不译）：")
            lines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine("待重译的英文句子：$enSentence")
        appendLine("现有译文（用户不满意）：$currentZh")
        if (!feedback.isNullOrBlank()) {
            appendLine("用户对现有译文的反馈：$feedback")
        } else {
            appendLine("用户未给出具体反馈，请在保持忠实直译的前提下换一种更通顺自然的译法。")
        }
        appendLine()
        appendLine("只输出 JSON：{\"translation\":\"重译后的中文句子\"}")
    }

    /**
     * 句级重翻结果自检：非空 + 数字锚点保留 + 该句中出现的「保留原文」术语存活 +
     * 长度比合理。失败抛 [AiRequestException]。
     */
    fun validateRetranslation(
        enSentence: String,
        newZh: String,
        keepOriginalTerms: List<String>
    ) {
        val translated = newZh.trim()
        if (translated.isEmpty()) {
            throw AiRequestException("AI 未返回重译结果")
        }
        Regex("\\d+").findAll(enSentence).forEach { match ->
            if (match.value !in translated) {
                throw AiRequestException("重译译文丢失了数字锚点「${match.value}」")
            }
        }
        keepOriginalTerms.forEach { term ->
            val trimmed = term.trim()
            if (trimmed.length >= 2 && enSentence.contains(trimmed, ignoreCase = true) &&
                !translated.contains(trimmed, ignoreCase = true)
            ) {
                throw AiRequestException("重译译文未按术语表保留原文「$trimmed」")
            }
        }
        val sourceWords = enSentence.split(Regex("\\s+")).count { it.isNotBlank() }
        if (sourceWords > 0 && (translated.length < sourceWords * 0.2 || translated.length > sourceWords * 8)) {
            throw AiRequestException("重译译文长度异常（原文 $sourceWords 词，译文 ${translated.length} 字）")
        }
    }

    /**
     * 重试前的退避时长（毫秒）。过去是「立刻原样重发」，命中限流时等于二次撞墙。
     * 429 退避最久，5xx 次之，解析/自检类失败最短（重发本身就可能换来好结果）。
     */
    fun retryDelayMillis(reason: String?): Long {
        val status = reason
            ?.let { Regex("HTTP (\\d{3})").find(it) }
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
        return when {
            status == 429 -> 8_000L
            status != null && status >= 500 -> 4_000L
            else -> 800L
        }
    }

    /** 批次源文本指纹：检查点复用时的有效性校验（书变了旧检查点自动失效）。 */
    fun sourceHash(paragraphs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paragraphs.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(byteArrayOf(0)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun styleLine(styleNotes: String?): String? =
        styleNotes?.trim()?.takeIf { it.isNotEmpty() }?.let { "风格说明（全书统一，必须遵守）：$it" }

    /**
     * 实际会注入 prompt 的术语条（手动条目 origin == "manual" 永远排在最前——
     * 超过 [MAX_GLOSSARY_LINES] 截断时被挤出去的只能是自动条目，与「手动条目
     * 在任何合并中都不可被覆盖」的产品契约同一个优先级方向）。公开给手动翻译
     * 任务导出复用，保证任务文件与在线 prompt 的术语口径严格一致。
     */
    fun injectedGlossary(glossary: List<GlossaryEntry>): List<GlossaryEntry> =
        glossary.asSequence()
            .filter { it.enabled && it.term.isNotBlank() }
            .distinctBy { it.term.lowercase() }
            .sortedByDescending { it.origin == "manual" }
            .take(MAX_GLOSSARY_LINES)
            .toList()

    private fun glossaryLines(glossary: List<GlossaryEntry>): List<String> =
        injectedGlossary(glossary).map {
            "${it.term.trim()} | ${it.translation.ifBlank { "保留原文" }} | ${it.note}"
        }

    /** 实际会注入 prompt 的术语条数（与 [glossaryLines] 严格同口径，估算展示用）。 */
    fun injectedGlossaryCount(glossary: List<GlossaryEntry>): Int = injectedGlossary(glossary).size
}

/**
 * 连续多批失败触发的整本中止信号（阈值见
 * [AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES]）。检查点全部保留，
 * 排除故障后重新生成即续跑。刻意不继承 [AiRequestException]：它不出现在
 * 单批重试链路里，线路层与重试逻辑都不该把它当成可重试的请求错误。
 */
class AiTranslationAbortedException(message: String) : RuntimeException(message)
