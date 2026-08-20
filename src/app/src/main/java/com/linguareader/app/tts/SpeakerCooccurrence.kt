package com.linguareader.app.tts

/**
 * Adjacent-speaker statistics across chapters (PLAN-MULTI-VOICE §5.1
 * `adjacentSpeakers`).
 *
 * Narration and unattributed dialogue are dropped first, so a conversation
 * that alternates "A - narration - B" still counts A next to B: characters who
 * speak in the same scene are exactly the ones whose voices must be pulled
 * apart by the assigner.
 */
object SpeakerCooccurrence {

    /** Counts adjacent character pairs over every chapter tag list. */
    fun from(chapters: List<List<String>>): Map<Pair<String, String>, Int> {
        val counts = mutableMapOf<Pair<String, String>, Int>()
        for (chapter in chapters) {
            val speakers = chapter.mapNotNull { tag ->
                val name = tag.trim()
                when {
                    name.isEmpty() -> null
                    name.equals(SpeakerRuleTagger.NARRATOR, ignoreCase = true) -> null
                    name.equals(SpeakerRuleTagger.DIALOGUE, ignoreCase = true) -> null
                    else -> name.lowercase()
                }
            }
            // Collapse runs of the same speaker: one long speech is not a
            // co-occurrence with itself.
            val turns = mutableListOf<String>()
            for (speaker in speakers) {
                if (turns.lastOrNull() != speaker) turns += speaker
            }
            for (index in 0 until turns.size - 1) {
                val key = pairOf(turns[index], turns[index + 1]) ?: continue
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }

    /** Symmetric lookup of how strongly two characters share scenes. */
    fun count(
        counts: Map<Pair<String, String>, Int>,
        first: String,
        second: String
    ): Int {
        val key = pairOf(first.trim().lowercase(), second.trim().lowercase()) ?: return 0
        return counts[key] ?: 0
    }

    /** Total adjacency weight of one character (assignment order tie-break). */
    fun degree(counts: Map<Pair<String, String>, Int>, name: String): Int {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return 0
        return counts.entries.sumOf { (pair, value) ->
            if (pair.first == key || pair.second == key) value else 0
        }
    }

    /** Normalised (sorted) pair key; null when both sides are the same speaker. */
    private fun pairOf(first: String, second: String): Pair<String, String>? {
        if (first.isEmpty() || second.isEmpty() || first == second) return null
        return if (first <= second) first to second else second to first
    }
}
