package com.linguareader.app.translation

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M2 刀3：translation 包的纯逻辑真相已迁入
// com.linguareader.shared.translation.*（TranslationAligner / WordAligner /
// TranslationMemoryIndex / MeaningPhraseParser / TraditionalSimplified /
// TranslationModels + SentenceSplitter 依赖）。
//
// 这里保留旧包路径的兼容别名，让留守 :app 的消费方（AppViewModel、
// ReaderScreen、TranslationMemoryRepository、TranslationBodyDiscardTest 等）
// 零改动通过编译——与 SharedDataCompat 同一机制。
// 新代码请直接 import com.linguareader.shared.translation.*。
// TODO(M2): 全量替换旧 import 后删除本文件。
// ─────────────────────────────────────────────────────────────────────────────

/** object 不能 typealias，用同名单例 val 兼容旧包路径调用。 */
val MeaningPhraseParser: com.linguareader.shared.translation.MeaningPhraseParser =
    com.linguareader.shared.translation.MeaningPhraseParser

/** 同上。 */
val TraditionalSimplified: com.linguareader.shared.translation.TraditionalSimplified =
    com.linguareader.shared.translation.TraditionalSimplified

/** 同上。 */
val TranslationAligner: com.linguareader.shared.translation.TranslationAligner =
    com.linguareader.shared.translation.TranslationAligner

/** 同上。 */
val WordAligner: com.linguareader.shared.translation.WordAligner =
    com.linguareader.shared.translation.WordAligner

/** 同上。 */
val TranslationMemorySearch: com.linguareader.shared.translation.TranslationMemorySearch =
    com.linguareader.shared.translation.TranslationMemorySearch

typealias MeaningIndex = com.linguareader.shared.translation.MeaningIndex
typealias TranslationMemoryIndex = com.linguareader.shared.translation.TranslationMemoryIndex
typealias TranslationMatchLevel = com.linguareader.shared.translation.TranslationMatchLevel
typealias WordAlignmentSource = com.linguareader.shared.translation.WordAlignmentSource
typealias WordAlignment = com.linguareader.shared.translation.WordAlignment
typealias AlignedSentencePair = com.linguareader.shared.translation.AlignedSentencePair
typealias BookTerm = com.linguareader.shared.translation.BookTerm
typealias TranslationMemory = com.linguareader.shared.translation.TranslationMemory
typealias TranslationLookupResult = com.linguareader.shared.translation.TranslationLookupResult
typealias AttachTranslationResult = com.linguareader.shared.translation.AttachTranslationResult
