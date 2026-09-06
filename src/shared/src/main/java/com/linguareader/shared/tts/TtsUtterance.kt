package com.linguareader.shared.tts

/**
 * 朗读片段——TTS 合成与高亮的**实际单位**。
 *
 * 大多数句子只有一个声部，片段 == 整句（1:1，文本与偏移和旧句级行为逐字节
 * 一致）；引语嵌在句中时一句话拆成「旁白 / 引语 / 旁白」多个片段，各自配音、
 * 各自高亮（`Gandalf said, "Fly, you fools."` → narrator + Gandalf 两段）。
 *
 * [sentenceIndex] 仍是句队列的坐标——进度保存、说话人标签、LLM 对齐全部以句
 * 为单位；片段只是句内的细分，[segmentIndex] / [segmentCount] 描述它在句内的
 * 位置（音频缓存键与 utteranceId 需要）。
 */
data class TtsUtterance(
    val sentenceIndex: Int,
    val segmentIndex: Int,
    val segmentCount: Int,
    /** 片段的声音标签（narrator / dialogue / 角色名）。 */
    val speaker: String,
    /** 朗读文本：块文本的连续子串，两端空白已去除。 */
    val text: String,
    /** 片段所在块；-1 表示块内定位失败（高亮跳过，朗读照常）。 */
    val blockIndex: Int,
    /** 片段在块内的起点（indexOf 契约）；-1 同上。 */
    val offset: Int
) {
    val length: Int get() = text.length

    companion object {
        /** 旁白声部（与 :app SpeakerRuleTagger.NARRATOR 同值；:shared 不依赖 :app）。 */
        const val NARRATOR = "narrator"
    }
}
