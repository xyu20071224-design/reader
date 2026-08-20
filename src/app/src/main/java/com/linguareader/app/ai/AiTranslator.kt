package com.linguareader.app.ai

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
 * Abstraction over context-aware translation backends.
 *
 * Implementations:
 *  - [DeepSeekTranslator]: remote, uses the DeepSeek chat API.
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
