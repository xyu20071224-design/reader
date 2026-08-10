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

/** Picks the active sentence translator from the user's settings. */
object SentenceTranslatorFactory {
    fun from(settings: AiSettings): SentenceTranslator? = when {
        settings.azureReady -> AzureSentenceTranslator(settings)
        settings.remoteReady -> DeepSeekTranslator(settings)
        else -> null
    }
}
