package com.linguareader.shared.ai

import org.json.JSONObject

/**
 * Minimal shared access to the configured chat backend.
 *
 * D1 (PLAN-MULTI-VOICE 4.2): speaker tagging reuses the very same call
 * infrastructure as the AI context profile - network layer, key handling,
 * JSON-mode retry and error type - instead of opening a second HTTP path.
 */
interface AiChatClient {
    /** One JSON-mode chat round trip; throws [AiRequestException] on failure. */
    suspend fun chatJson(system: String, user: String): JSONObject
}

/**
 * AI 整本书翻译的批量出网出口。与 [AiChatClient] 分开定义，因为整本翻译
 * 每批的输出量级远大于语境请求：需要更长的读超时与显式 max_tokens，
 * 且单测需要能注入假客户端（[JsonChatTranslator] 子类是具体类，不好继承）。
 */
interface AiTranslationChatClient {
    /** One long-form JSON-mode chat round trip for a translation batch. */
    suspend fun translateSegments(system: String, user: String): JSONObject
}

/**
 * Abstraction over context-aware translation backends.
 *
 * Implementations:
 *  - [AiTranslators]: remote, protocol-aware dispatch to the configured
 *    model provider (OpenAI-compatible / Anthropic / Gemini).
 *  - [LocalGlossaryTranslator]: fully offline, frequency-based lightweight
 *    profile; works without any API key.
 */
interface AiTranslator {
    val id: String
    val displayName: String
    val offline: Boolean

    /** Builds the per-book context profile from chapter plain text. */
    suspend fun buildBookContext(
        bookTitle: String,
        chapters: List<ChapterText>
    ): BookContextProfile

    /** Returns a contextual meaning for one tap; null when nothing useful. */
    suspend fun translate(
        profile: BookContextProfile,
        request: AiLookupRequest
    ): AiLookupResult?
}
