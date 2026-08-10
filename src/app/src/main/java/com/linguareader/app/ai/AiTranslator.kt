package com.linguareader.app.ai

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
