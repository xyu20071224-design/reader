package com.linguareader.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 一批待翻译段落：某章内连续的若干个叶级段落（与 [com.linguareader.app.tts.TtsTextExtractor]
 * 的 blocks 同源）。批次以完整段落为界，绝不切断段落——段落 1:1 映射是译本对照
 * 对齐质量的根基。
 */
data class TranslationBatch(
    val chapterIndex: Int,
    val batchIndex: Int,
    /** 这些译文对应章内 blocks 的下标，按序排列。 */
    val paragraphIndices: List<Int>,
    val paragraphs: List<String>
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
     * 把一章的段落按 [maxCharsPerBatch] 贪心分组；单个超长段落独立成批
     * （不切断段落）。批次覆盖全部段落下标且按序。
     */
    fun groupIntoBatches(
        chapterIndex: Int,
        paragraphs: List<String>,
        maxCharsPerBatch: Int = MAX_CHARS_PER_BATCH
    ): List<TranslationBatch> {
        val batches = mutableListOf<TranslationBatch>()
        var indices = mutableListOf<Int>()
        var chars = 0
        paragraphs.forEachIndexed { index, paragraph ->
            if (indices.isNotEmpty() && chars + paragraph.length > maxCharsPerBatch) {
                batches += TranslationBatch(chapterIndex, batches.size, indices.toList(), indices.map { paragraphs[it] })
                indices = mutableListOf()
                chars = 0
            }
            indices += index
            chars += paragraph.length
        }
        if (indices.isNotEmpty()) {
            batches += TranslationBatch(chapterIndex, batches.size, indices.toList(), indices.map { paragraphs[it] })
        }
        return batches
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
        }
        keepOriginalTerms.forEach { term ->
            val trimmed = term.trim()
            if (trimmed.length < 2) return@forEach
            val sourceHit = batch.paragraphs.any { it.contains(trimmed, ignoreCase = true) }
            if (!sourceHit) return@forEach
            val translatedHit = batch.paragraphIndices
                .map { byIndex.getValue(it) }
                .any { it.contains(trimmed, ignoreCase = true) }
            if (!translatedHit) {
                throw AiRequestException("AI 译文未按术语表保留原文「$trimmed」")
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

    private fun glossaryLines(glossary: List<GlossaryEntry>): List<String> =
        glossary.asSequence()
            .filter { it.enabled && it.term.isNotBlank() }
            .distinctBy { it.term.lowercase() }
            .take(MAX_GLOSSARY_LINES)
            .map { "${it.term.trim()} | ${it.translation.ifBlank { "保留原文" }} | ${it.note}" }
            .toList()
}
