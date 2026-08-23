package com.linguareader.app.data

import androidx.annotation.StringRes
import com.linguareader.app.R

enum class PartOfSpeech(@StringRes val labelRes: Int) {
    NOUN(R.string.pos_noun),
    VERB(R.string.pos_verb),
    ADJECTIVE(R.string.pos_adjective),
    ADVERB(R.string.pos_adverb),
    UNKNOWN(R.string.pos_unknown)
}

data class ContextToken(
    val text: String,
    val start: Int,
    val endExclusive: Int
)

data class PhraseWindow(
    val tokens: List<ContextToken>
) {
    val text: String = tokens.joinToString(" ") { it.text }
}

data class DictionarySense(
    val text: String,
    val partOfSpeech: PartOfSpeech,
    val contextPreferred: Boolean
)

data class ContextualDictionaryEntry(
    val surfaceWord: String,
    val headword: String,
    val phonetic: String,
    val senses: List<DictionarySense>,
    val definitions: List<String>,
    val matchedPhrase: String?,
    val inferredPartOfSpeech: PartOfSpeech
)
data class DictionaryLookupResult(
    val entry: ContextualDictionaryEntry?,
    val relatedPhrase: ContextualDictionaryEntry?
)

object ContextAnalyzer {
    private val tokenPattern = Regex(
        """[A-Za-zÀ-ÖØ-öø-ÿ]+(?:[.'’\-][A-Za-zÀ-ÖØ-öø-ÿ]+)*(?:\.)?"""
    )
    private val determiners = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his",
        "her", "its", "our", "their", "some", "any", "each", "every", "no"
    )
    private val modalsAndAuxiliaries = setOf(
        "can", "could", "may", "might", "must", "shall", "should", "will", "would",
        "do", "does", "did", "have", "has", "had"
    )
    private val beForms = setOf("am", "is", "are", "was", "were", "be", "been", "being")
    private val lyAdjectiveExceptions = setOf(
        "friendly", "lovely", "lively", "ugly", "lonely", "silly", "likely",
        "elderly", "deadly", "costly", "monthly", "yearly", "daily", "weekly",
        "hourly", "early", "only", "holy", "saintly", "unlikely"
    )
    // Words that should not trigger phrase-priority by themselves. Verb
    // particles (off/up/out/forward/...) are intentionally absent so phrasal
    // verbs like "take off" and "look forward to" keep their phrase trigger.
    private val phraseFunctionWords = setOf(
        "a", "an", "the", "this", "that", "these", "those",
        "my", "your", "his", "her", "its", "our", "their", "some", "any", "each", "every", "no",
        "to", "of", "for", "with", "by", "at", "from", "into", "onto", "upon",
        "about", "between", "among", "during", "after", "before", "under",
        "without", "against", "within", "across", "behind", "beside", "beyond",
        "despite", "except", "toward", "towards", "until", "since", "near",
        "past", "per", "via", "and", "or", "but", "so", "yet", "nor",
        "can", "could", "may", "might", "must", "shall", "should", "will",
        "would", "do", "does", "did", "have", "has", "had", "am", "is", "are",
        "was", "were", "be", "been", "being",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her",
        "us", "them", "mine", "yours", "his", "hers", "ours", "theirs",
        "who", "whom", "whose", "which", "what", "there"
    )
    // Verb particles that may carry phrase-priority when tapped right after
    // the phrase head (off/up/out/forward/...). Content words deeper inside a
    // phrase (e.g. "day" in "good day", "time" in "run out of time") are not
    // particles and must fall back to the word lookup so a nearby phrase
    // cannot replace the tapped word's own meaning.
    private val verbParticles = setOf(
        "off", "up", "out", "on", "down", "away", "back", "forward", "in", "over",
        "through", "along", "around", "ahead", "aside", "apart", "together", "round"
    )
    // in/on double as prepositions and verb particles: leading a phrase they
    // are function words ("in order to" -> core is "order"), while after a
    // verb they are particles ("give in" -> tapping "in" triggers the phrase).
    private val phraseHeadFunctionWords = phraseFunctionWords + "in" + "on"

    /**
     * Whether the clicked lemma may take phrase-priority for [phraseHeadword].
     *
     * The semantic core is the phrase's first content word (the head of
     * "take off", "a lot of", "in order to", "to go"); the verb particle
     * directly after it ("off", "forward") is also a core trigger.
     * Determiners, prepositions, auxiliaries and content words deeper in the
     * phrase ("day" in "good day", "time" in "out of time") fall back to the
     * word lookup so a nearby phrase cannot replace the tapped word's meaning.
     *
     * [phraseLemmas] optionally carries the lemmatized form of every phrase
     * token so inflected dictionary entries ("have got to", "is going to") can
     * be matched against the clicked lemma ("get", "go").
     */
    fun isCorePhraseToken(
        clickedLemma: String,
        phraseHeadword: String,
        phraseLemmas: List<String> = emptyList()
    ): Boolean {
        val phraseTokens = phraseHeadword
            .lowercase()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        if (phraseTokens.isEmpty()) return false
        val lemmas = if (phraseLemmas.size == phraseTokens.size) phraseLemmas else phraseTokens
        val clicked = clickedLemma.lowercase()
        val index = lemmas.indexOf(clicked)
        if (index < 0) return false
        // The phrase's semantic core is its first content word; the verb
        // particle directly after it also triggers phrase-priority. Any other
        // position (function words, objects/complements) falls back to the
        // word lookup so a nearby phrase cannot replace the tapped word.
        val firstContentIndex = phraseTokens.indexOfFirst { it !in phraseHeadFunctionWords }
        if (firstContentIndex < 0) return false
        return index == firstContentIndex || (index == firstContentIndex + 1 && clicked in verbParticles)
    }

    fun tokenize(sentence: String): List<ContextToken> =
        tokenPattern.findAll(sentence).map {
            val text = it.value.let { value ->
                if (value.endsWith('.') && value.count { character -> character == '.' } < 2) {
                    value.dropLast(1)
                } else {
                    value
                }
            }
            ContextToken(text, it.range.first, it.range.last + 1)
        }.toList()

    fun targetIndex(tokens: List<ContextToken>, lookup: WordLookup): Int {
        if (tokens.isEmpty()) return -1
        val wanted = normalize(lookup.word)
        val byOffset = tokens.indexOfFirst {
            lookup.sentenceOffset in it.start until it.endExclusive
        }
        // Only trust the caret offset when the token under it is the word the
        // reader actually tapped. If the JS offset drifts (whitespace or
        // segmentation normalization), fall back to the nearest occurrence of
        // the tapped word so a nearby phrase is never matched for a word that
        // is not inside it.
        if (byOffset >= 0 && normalize(tokens[byOffset].text) == wanted) return byOffset
        return tokens.indices
            .filter { normalize(tokens[it].text) == wanted }
            .minByOrNull { kotlin.math.abs(tokens[it].start - lookup.sentenceOffset) }
            ?: -1
    }

    fun phraseWindows(
        tokens: List<ContextToken>,
        targetIndex: Int,
        maximumWords: Int = 5
    ): List<PhraseWindow> {
        if (targetIndex !in tokens.indices) return emptyList()
        return buildList {
            for (size in minOf(maximumWords, tokens.size) downTo 2) {
                val firstStart = maxOf(0, targetIndex - size + 1)
                val lastStart = minOf(targetIndex, tokens.size - size)
                for (start in firstStart..lastStart) {
                    add(PhraseWindow(tokens.subList(start, start + size)))
                }
            }
        }
    }

    fun inferPartOfSpeech(tokens: List<ContextToken>, targetIndex: Int): PartOfSpeech {
        if (targetIndex !in tokens.indices) return PartOfSpeech.UNKNOWN
        val word = normalize(tokens[targetIndex].text)
        val previous = tokens.getOrNull(targetIndex - 1)?.text?.let(::normalize).orEmpty()
        val next = tokens.getOrNull(targetIndex + 1)?.text?.let(::normalize).orEmpty()

        return when {
            word in lyAdjectiveExceptions -> PartOfSpeech.ADJECTIVE
            word.endsWith("ly") -> PartOfSpeech.ADVERB
            previous == "to" || previous in modalsAndAuxiliaries -> PartOfSpeech.VERB
            previous in beForms && adjectiveLike(word) -> PartOfSpeech.ADJECTIVE
            previous in determiners && adjectiveLike(word) && next.isNotBlank() ->
                PartOfSpeech.ADJECTIVE
            previous in determiners -> PartOfSpeech.NOUN
            word.endsWith("ing") || word.endsWith("ed") -> PartOfSpeech.VERB
            nounLike(word) -> PartOfSpeech.NOUN
            adjectiveLike(word) -> PartOfSpeech.ADJECTIVE
            else -> PartOfSpeech.UNKNOWN
        }
    }

    fun senses(translation: String, inferred: PartOfSpeech): List<DictionarySense> {
        val lines = translation
            .replace("\\n", "\n")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { line ->
                val partOfSpeech = sensePartOfSpeech(line)
                DictionarySense(
                    text = line,
                    partOfSpeech = partOfSpeech,
                    contextPreferred = inferred != PartOfSpeech.UNKNOWN && partOfSpeech == inferred
                )
            }
            .toList()
        return lines.sortedByDescending { it.contextPreferred }
    }

    fun definitions(definition: String): List<String> =
        definition.replace("\\n", "\n")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    fun normalize(value: String): String = value.lowercase()
        .trim('’', '\'', '"', '“', '”', '.', ',', ';', ':', '!', '?')
        .removeSuffix("'s")
        .removeSuffix("’s")

    private fun sensePartOfSpeech(line: String): PartOfSpeech {
        val marker = line.lowercase().trimStart()
        return when {
            marker.startsWith("n.") || marker.startsWith("n ") -> PartOfSpeech.NOUN
            marker.startsWith("vt.") || marker.startsWith("vi.") ||
                marker.startsWith("v.") || marker.startsWith("v ") -> PartOfSpeech.VERB
            marker.startsWith("a.") || marker.startsWith("adj.") -> PartOfSpeech.ADJECTIVE
            marker.startsWith("ad.") || marker.startsWith("adv.") -> PartOfSpeech.ADVERB
            else -> PartOfSpeech.UNKNOWN
        }
    }

    private fun nounLike(word: String): Boolean =
        listOf("tion", "sion", "ment", "ness", "ity", "ship", "ance", "ence")
            .any(word::endsWith)

    private fun adjectiveLike(word: String): Boolean =
        listOf("ous", "ive", "ful", "less", "able", "ible", "al", "ic", "ary")
            .any(word::endsWith)
}
