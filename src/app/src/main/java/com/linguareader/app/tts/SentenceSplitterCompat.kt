package com.linguareader.app.tts

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M2 刀3：SentenceSplitter 纯逻辑已迁入
// com.linguareader.shared.tts.SentenceSplitter（TranslationAligner 也消费它）。
// 本包的 TtsTextExtractor / SpeakerRuleTagger / SpeakerLlmTagger 经此同名 val
// 零改动继续工作。新代码请直接 import com.linguareader.shared.tts.SentenceSplitter。
// TODO(M2): 全量替换旧引用后删除本文件。
// ─────────────────────────────────────────────────────────────────────────────

/** object 不能 typealias，用同名单例 val 兼容旧包路径调用。 */
val SentenceSplitter: com.linguareader.shared.tts.SentenceSplitter =
    com.linguareader.shared.tts.SentenceSplitter
