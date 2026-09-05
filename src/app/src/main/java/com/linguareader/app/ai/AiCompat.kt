package com.linguareader.app.ai

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M2 刀3：ai 包的纯逻辑真相已迁入 com.linguareader.shared.ai.*
// （AiModels / AiTranslator / AiTranslators / AiBookTranslator / 各家 Chat 翻译器
// / ChapterTextExtractor / LocalGlossaryTranslator / SentenceTranslator）。
//
// ModelDiscovery.kt（probeKeyUsable 等 internal 顶层函数）与五个依赖
// Context/SharedPreferences 的仓库（AiSettingsStore / AiTranslationRepository /
// BookContextRepository / BookGlossaryRepository / SpeakerTagRepository）留在 :app。
//
// 这里保留旧包路径的兼容别名，让留守 :app 的消费方（AppViewModel、ReaderScreen、
// BookshelfScreen、GlossaryEditor、AiDrawerSheet、AiProviderSettings 及相关测试）
// 零改动通过编译——与 SharedDataCompat 同一机制。
// 新代码请直接 import com.linguareader.shared.ai.*。
// TODO(M2): 全量替换旧 import 后删除本文件。
// ─────────────────────────────────────────────────────────────────────────────

/** object 不能 typealias，用同名单例 val 兼容旧包路径调用。 */
val AiBookTranslator: com.linguareader.shared.ai.AiBookTranslator =
    com.linguareader.shared.ai.AiBookTranslator

/** 同上。 */
val AiTranslators: com.linguareader.shared.ai.AiTranslators =
    com.linguareader.shared.ai.AiTranslators

/** 同上。 */
val AiProtocol: com.linguareader.shared.ai.AiProtocol =
    com.linguareader.shared.ai.AiProtocol

/** 同上。 */
val SentenceTranslatorFactory: com.linguareader.shared.ai.SentenceTranslatorFactory =
    com.linguareader.shared.ai.SentenceTranslatorFactory

typealias TranslationBatch = com.linguareader.shared.ai.TranslationBatch
typealias AiTranslationAbortedException = com.linguareader.shared.ai.AiTranslationAbortedException
typealias ChapterText = com.linguareader.shared.ai.ChapterText
typealias ContextTerm = com.linguareader.shared.ai.ContextTerm
typealias CharacterProfile = com.linguareader.shared.ai.CharacterProfile
typealias BookContextProfile = com.linguareader.shared.ai.BookContextProfile
typealias AiLookupRequest = com.linguareader.shared.ai.AiLookupRequest
typealias AiLookupResult = com.linguareader.shared.ai.AiLookupResult
typealias AiLookupOutcome = com.linguareader.shared.ai.AiLookupOutcome
typealias AiProviderProfile = com.linguareader.shared.ai.AiProviderProfile
typealias AiSettings = com.linguareader.shared.ai.AiSettings
typealias AiBookStatus = com.linguareader.shared.ai.AiBookStatus
typealias AiRequestException = com.linguareader.shared.ai.AiRequestException
typealias GlossaryEntry = com.linguareader.shared.ai.GlossaryEntry
typealias BookGlossary = com.linguareader.shared.ai.BookGlossary
typealias GlossaryMatch = com.linguareader.shared.ai.GlossaryMatch
typealias ChapterSpeakerTags = com.linguareader.shared.ai.ChapterSpeakerTags
typealias AiChatClient = com.linguareader.shared.ai.AiChatClient
typealias AiTranslationChatClient = com.linguareader.shared.ai.AiTranslationChatClient
typealias AiTranslator = com.linguareader.shared.ai.AiTranslator
typealias SentenceTranslator = com.linguareader.shared.ai.SentenceTranslator
typealias SentenceTranslationResult = com.linguareader.shared.ai.SentenceTranslationResult
typealias AnthropicCompatTranslator = com.linguareader.shared.ai.AnthropicCompatTranslator
typealias ChapterTextExtractor = com.linguareader.shared.ai.ChapterTextExtractor
typealias GeminiCompatTranslator = com.linguareader.shared.ai.GeminiCompatTranslator
typealias JsonChatTranslator = com.linguareader.shared.ai.JsonChatTranslator
typealias LocalGlossaryTranslator = com.linguareader.shared.ai.LocalGlossaryTranslator
typealias OpenAiCompatTranslator = com.linguareader.shared.ai.OpenAiCompatTranslator
