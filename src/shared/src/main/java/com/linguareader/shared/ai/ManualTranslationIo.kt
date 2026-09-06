package com.linguareader.shared.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手动 AI 全书翻译的文件协议（纯逻辑，JVM 可单测）。
 *
 * 与在线翻译同一条批次规划（[AiBookTranslator.groupIntoBatches]）与同一套
 * 校验（[AiBookTranslator.extractValidated]），只是请求/响应不再走 HTTP，而是
 * 走用户手动搬运的两个文件：
 * - **任务文件**（[buildTask]）：全书批次 + 术语表 + 风格说明 + 给外部 AI agent
 *   的输出契约说明。agent 读完直接产出结果文件。
 * - **结果文件**（[parseResults]）：逐批的 segments JSON（与在线响应同一格式），
 *   导入侧校验后写成翻译检查点。
 *
 * 编号约定与在线链路完全一致：segments 里的 `i` 是**章内段落号**
 * （[TranslationBatch.paragraphIndices]），不是批内序号——对齐质量依赖这条 1:1 契约。
 */
object ManualTranslationIo {

    const val FORMAT_VERSION = 1
    const val TASK_KIND = "linguareader-manual-translation-task"
    const val RESULT_KIND = "linguareader-manual-translation-result"

    /**
     * 手动导入产出的检查点的 mode 值。检查点读取侧不校验 mode（纯溯源字段），
     * 用它区分「这段译文来自外部 agent」而不是在线请求。
     */
    const val MODE_MANUAL = "manual"

    /** 任务文件里的一个批次：批次本体 + 供 agent 精确定位与跳过的元数据。 */
    data class ManualTaskBatch(
        val chapterTitle: String,
        /** 批次源文本指纹（[AiBookTranslator.sourceHash]），导入侧防错位。 */
        val sourceHash: String,
        /** 章号、批号、段落编号与段落文本都在 [TranslationBatch] 里。 */
        val batch: TranslationBatch,
        /** 该批已有有效检查点：agent 应跳过，导入侧也不期待它出现。 */
        val hasCheckpoint: Boolean
    )

    /** 结果文件里的一批译文。 */
    data class ManualResultEntry(
        val chapterIndex: Int,
        val batchIndex: Int,
        /** 可空：agent 忘了带回时导入侧退化为按 (章, 批) 定位 + 段数校验。 */
        val sourceHash: String?,
        val segments: JSONObject
    )

    /**
     * 组装任务文件 JSON。[glossary] 由调用方传已启用的词条；这里用
     * [AiBookTranslator.injectedGlossary] 截断，保证与在线 prompt 注入口径一致。
     */
    fun buildTask(
        bookId: String,
        bookTitle: String,
        glossary: List<GlossaryEntry>,
        styleNotes: String?,
        batches: List<ManualTaskBatch>
    ): JSONObject {
        val glossaryArray = JSONArray()
        AiBookTranslator.injectedGlossary(glossary).forEach { entry ->
            glossaryArray.put(
                JSONObject()
                    .put("term", entry.term.trim())
                    .put("translation", entry.translation.trim())
                    .put("note", entry.note.trim())
            )
        }
        val batchesArray = JSONArray()
        batches.forEach { taskBatch ->
            val paragraphs = JSONArray()
            taskBatch.batch.paragraphs.forEach { paragraphs.put(it) }
            val indices = JSONArray()
            taskBatch.batch.paragraphIndices.forEach { indices.put(it) }
            batchesArray.put(
                JSONObject()
                    .put("chapterIndex", taskBatch.batch.chapterIndex)
                    .put("batchIndex", taskBatch.batch.batchIndex)
                    .put("chapterTitle", taskBatch.chapterTitle)
                    .put("sourceHash", taskBatch.sourceHash)
                    .put("hasCheckpoint", taskBatch.hasCheckpoint)
                    .put("paragraphIndices", indices)
                    .put("paragraphs", paragraphs)
            )
        }
        return JSONObject()
            .put("kind", TASK_KIND)
            .put("version", FORMAT_VERSION)
            .put("bookId", bookId)
            .put("bookTitle", bookTitle)
            .put("styleNotes", styleNotes?.trim().orEmpty())
            .put("glossary", glossaryArray)
            .put("instructions", INSTRUCTIONS)
            .put("batches", batchesArray)
    }

    /**
     * 解析结果文件。结构性错误（不是 JSON、kind 不对、版本过新、批次缺定位或
     * 缺 segments）抛 [IllegalArgumentException]，消息面向用户——最常见的是
     * 「选错了文件」（把任务文件或其他 JSON 当成了结果文件）。
     */
    fun parseResults(text: String): List<ManualResultEntry> {
        val json = lenientParse(text)
            ?: throw IllegalArgumentException("文件不是有效的 JSON，请确认选的是 AI 返回的结果文件")
        if (json.optString("kind") != RESULT_KIND) {
            throw IllegalArgumentException(
                "这不是翻译结果文件（kind 不符）。请把任务文件交给 AI agent 并让它按文件内说明产出结果文件"
            )
        }
        if (json.optInt("version", FORMAT_VERSION) > FORMAT_VERSION) {
            throw IllegalArgumentException("结果文件版本过新，请先升级应用再导入")
        }
        val array = json.optJSONArray("translations")
            ?: throw IllegalArgumentException("结果文件缺少 translations 数组")
        val entries = mutableListOf<ManualResultEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw IllegalArgumentException("translations 第 ${i + 1} 项不是对象")
            val chapterIndex = item.optInt("chapterIndex", Int.MIN_VALUE)
            val batchIndex = item.optInt("batchIndex", Int.MIN_VALUE)
            if (chapterIndex == Int.MIN_VALUE || batchIndex == Int.MIN_VALUE) {
                throw IllegalArgumentException("translations 第 ${i + 1} 项缺少 chapterIndex/batchIndex")
            }
            val segments = when (val raw = item.opt("segments")) {
                // 说明约定的形状：segments 直接是 [{i,t},…] 数组 → 包一层给
                // extractValidated（它吃在线响应的 {"segments":[…]} 对象）。
                is JSONArray -> JSONObject().put("segments", raw)
                // agent 直接把在线响应对象 {"segments":[…]} 整个贴进来也收下。
                is JSONObject -> raw
                else -> throw IllegalArgumentException(
                    "批次 $chapterIndex-$batchIndex 的 segments 不是 JSON 数组或对象"
                )
            }
            entries += ManualResultEntry(
                chapterIndex = chapterIndex,
                batchIndex = batchIndex,
                sourceHash = item.optString("sourceHash").trim().ifBlank { null },
                segments = segments
            )
        }
        return entries
    }

    /** 给外部 AI agent 的翻译要求与输出契约说明（进任务文件的 instructions 字段）。 */
    const val INSTRUCTIONS =
        "你是一个负责英译中的翻译 agent。请把这个任务文件里的英文段落翻译成简体中文，" +
            "并产出一个「结果文件」。翻译要求：\n" +
            "1. 直译为主、忠实原文；语序尽量贴近原文；不合并、不拆分段落；" +
            "不添加任何解释、注释或译者按语。\n" +
            "2. 数字、年份、编号必须原样保留（如 1,000 不能写成「一千」）。\n" +
            "3. glossary 里的词条必须按给定 translation 翻译；translation 为「保留原文」的" +
            "词条在译文中保持英文不译。\n" +
            "4. 只翻译 hasCheckpoint 为 false 的批次；hasCheckpoint 为 true 的批次已有译文，" +
            "直接跳过，不要输出。\n" +
            "5. 每个批次 paragraphs 里的每个段落都要翻译；segments 里的编号 i 必须使用该批次 " +
            "paragraphIndices 里对应的原始编号（章内段落号），一一对应，不得遗漏、不得新增。\n" +
            "6. 结果文件是一个 JSON 文件，结构如下（bookId、chapterIndex、batchIndex、" +
            "sourceHash 都从任务文件原样带回）：\n" +
            "{\"kind\":\"$RESULT_KIND\",\"version\":$FORMAT_VERSION,\"bookId\":\"…\"," +
            "\"translations\":[{\"chapterIndex\":0,\"batchIndex\":0,\"sourceHash\":\"…\"," +
            "\"segments\":[{\"i\":0,\"t\":\"该段中文译文\"}]}]}\n" +
            "7. 全部批次可以写进一个结果文件，也可以按章或按量拆成多个文件；导入端支持" +
            "多选与多次导入合并。"

    /**
     * 宽容解析：agent 常把 JSON 包进 markdown 代码围栏或前后带说明文字，
     * 剥掉围栏、截取首尾大括号之间再试一次。
     */
    private fun lenientParse(text: String): JSONObject? {
        val stripped = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { return JSONObject(stripped) }
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(stripped.substring(start, end + 1)) }.getOrNull()
    }
}
