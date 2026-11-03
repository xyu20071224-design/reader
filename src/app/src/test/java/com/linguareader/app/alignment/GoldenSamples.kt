package com.linguareader.app.alignment

import com.linguareader.shared.alignment.AlignmentDocument
import com.linguareader.shared.alignment.AlignmentStatus
import com.linguareader.shared.alignment.AlignedUnit
import org.json.JSONObject
import java.io.File

/**
 * 100 条人工金标准的读取、定位与「英文句 → 新算法单元」的对应。
 *
 * **这里不做任何判定**，只产出「每个样本在新算法里落在哪个单元、那个单元说了什么」。
 * 四格表与错标清单由测试主流程算，口径写在那里，便于 report 里逐项引用行号。
 */
internal object GoldenSamples {

    /** 一条人工判定样本。字段名与 `golden-samples.json` 完全一致，不做语义改写。 */
    internal class Sample(
        val id: String,
        val stratum: String,
        val chapter: Int,
        val en: String,
        /** 人工判定：ok=对 / ok2=勉强对（粒度跳或残缺） / bad=错。 */
        val verdict: String,
        /** 纯标点切片等无法在应用内发起查询的样本（第 5 轮已定性，待剔除）。 */
        val garbage: Boolean,
        /** 当年批准展示的对照（`level` = SENTENCE / PARAGRAPH / MISS，`zh` = 中文）。 */
        val approvedLevel: String,
        val approvedZh: String,
        val note: String
    ) {
        /** 人工认为「这条展示是对的」。注意 ok/ok2 的判定对象是**当时那条展示**。 */
        val approvedByHuman: Boolean get() = verdict == "ok" || verdict == "ok2"

        /**
         * 人工是否认为「这一句本应有对照可展示」。
         *
         * 与 [approvedByHuman] 的唯一区别在 `U-非正文无对照` 分层：那里人工批准的
         * 展示是 `MISS`（= 不展示），`verdict=ok` 的意思是「**没有对照才是对的**」。
         * 把它当成「应有对照」会把正确的弃权算成漏配，四格表就会失真。
         */
        val shouldShowSomething: Boolean
            get() = approvedByHuman && approvedLevel != "MISS"
    }

    fun parse(fixture: File): List<Sample> {
        val root = JSONObject(fixture.readText())
        val array = root.getJSONArray("samples")
        return (0 until array.length()).map { i ->
            val json = array.getJSONObject(i)
            val approved = json.optJSONObject("approved")
            Sample(
                id = json.getString("id"),
                stratum = json.optString("stratum"),
                chapter = json.optInt("chapter", -1),
                en = json.optString("en"),
                verdict = json.optString("verdict"),
                garbage = json.optBoolean("garbage", false),
                approvedLevel = approved?.optString("level").orEmpty(),
                approvedZh = approved?.optString("zh").orEmpty(),
                note = json.optString("note")
            )
        }
    }

    /** 样本英文句在英文书里的落点。 */
    internal class Located(val chapter: Int, val paragraphIndex: Int, val paragraph: String)

    /**
     * 定位：优先样本记录的章，否则全书扫描（与 `TranslationGoldenReplayTest.locate` 同法）。
     *
     * 用「段落实际所在章」而不是无条件信 `chapter` 字段——历史评估工具用错过桶
     * （s50/s66 的伪 MISS 就是这么来的）。
     */
    fun locate(sample: Sample, enChapters: List<List<String>>): Located? {
        val target = LotrCorpus.lightNormalize(sample.en)
        if (target.isBlank()) return null
        val order = buildList {
            if (sample.chapter in enChapters.indices) add(sample.chapter)
            for (i in enChapters.indices) if (i != sample.chapter) add(i)
        }
        for (chapterIndex in order) {
            val blocks = enChapters[chapterIndex]
            for (paragraphIndex in blocks.indices) {
                if (LotrCorpus.lightNormalize(blocks[paragraphIndex]).contains(target)) {
                    return Located(chapterIndex, paragraphIndex, blocks[paragraphIndex])
                }
            }
        }
        return null
    }

    /** 新算法单元索引：`(章, 段落原文)` → 该段的单元序列。 */
    class UnitIndex(document: AlignmentDocument) {
        private val byParagraph: Map<Pair<Int, String>, List<AlignedUnit>> =
            document.units.groupBy { it.sourceChapter to it.sourceParagraph }

        /**
         * 找出该样本英文句对应的**一个**单元。
         *
         * 优先级：归一化后完全相等 → 互相包含且英文句最短（最specific）→ 都没有则 null。
         * 一个段落里同一句不会重复出现，所以这里不需要更复杂的消歧。
         */
        fun find(located: Located, sampleEnglish: String): AlignedUnit? {
            val units = byParagraph[located.chapter to located.paragraph] ?: return null
            val target = LotrCorpus.lightNormalize(sampleEnglish)
            units.firstOrNull { LotrCorpus.lightNormalize(it.sourceText) == target }?.let { return it }
            return units
                .filter {
                    val text = LotrCorpus.lightNormalize(it.sourceText)
                    text.contains(target) || target.contains(text)
                }
                .minByOrNull { it.sourceText.length }
        }
    }

    /** 一条样本的对照记录，直接序列化成报告 JSON 的一行。 */
    class Row(
        val sample: Sample,
        val located: Located?,
        val unit: AlignedUnit?
    ) {
        val status: AlignmentStatus? get() = unit?.status
        val targetText: String get() = unit?.targetText.orEmpty()
        val confidence: Double get() = unit?.confidence ?: 0.0
        val evidenceSummary: String get() = unit?.evidence?.summary().orEmpty()
        val reasons: List<String> get() = unit?.evidence?.reasons.orEmpty()
        val verifyMethod: String get() = unit?.evidence?.verifyMethod.orEmpty()
        val skipReason: String get() = unit?.skipReason?.name.orEmpty()
        val sourceText: String get() = unit?.sourceText.orEmpty()

        fun toJson(): JSONObject = JSONObject()
            .put("id", sample.id)
            .put("stratum", sample.stratum)
            .put("chapter", sample.chapter)
            .put("en", sample.en)
            .put("verdict", sample.verdict)
            .put("garbage", sample.garbage)
            .put("approvedLevel", sample.approvedLevel)
            .put("approvedZh", sample.approvedZh)
            .put("located", located != null)
            .put("locatedChapter", located?.chapter ?: -1)
            .put("foundUnit", unit != null)
            .put("status", status?.name ?: "NOT_LOCATED")
            .put("sourceText", sourceText)
            .put("targetText", targetText)
            .put("confidence", confidence)
            .put("evidence", evidenceSummary)
            .put("verifyMethod", verifyMethod)
            .put("reasons", reasons.joinToString(" | "))
            .put("skipReason", skipReason)
    }
}
