package com.linguareader.app.ai

/**
 * Sentence-level translation with book-glossary overrides.
 *
 * Implementations:
 *  - [AzureSentenceTranslator]: Azure AI Translator with dynamic dictionary
 *    markup, the primary sentence translator.
 *  - [DeepSeekTranslator]: falls back to the DeepSeek chat API when Azure is
 *    not configured; glossary entries are supplied in the prompt.
 */
interface SentenceTranslator {
    val id: String
    val displayName: String
    val offline: Boolean

    /** Translates one English sentence to Simplified Chinese. */
    suspend fun translateSentence(sentence: String, glossary: BookGlossary): String
}

/**
 * One finished sentence translation plus the backend that produced it, so the
 * UI can name the real provider instead of assuming Azure.
 */
data class SentenceTranslationResult(val text: String, val provider: String)

/** Picks the active sentence translator from the user's settings. */
object SentenceTranslatorFactory {
    fun from(settings: AiSettings): SentenceTranslator? = when {
        // Master power switch: networked AI disabled keeps the app fully offline.
        !settings.powerEnabled -> null
        settings.azureReady -> AzureSentenceTranslator(settings)
        settings.remoteReady -> DeepSeekTranslator(settings)
        else -> null
    }

    /**
     * True when sentence translation can actually run. The reader uses this to
     * disable the button up front instead of failing silently on tap.
     */
    fun isConfigured(settings: AiSettings): Boolean = from(settings) != null
}
