package com.linguareader.app.data

import android.content.Context
import android.net.Uri
import com.linguareader.app.ai.AiLookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale

object ReviewScheduler {
    // Ebbinghaus forgetting-curve plan (day-granularity). A word is "learned"
    // when it is first reviewed; every successful review then schedules the
    // next point: 12 h → 1 d → 2 d → 4 d → 7 d → 15 d → 30 d. A forgotten
    // word ("再学一次") restarts from the 12-hour point. The base interval
    // is scaled by the selected pace (F-138) with a 30-minute floor.
    private val intervalsMillis = longArrayOf(
        12 * 3_600_000L,
        24 * 3_600_000L,
        2 * 24 * 3_600_000L,
        4 * 24 * 3_600_000L,
        7 * 24 * 3_600_000L,
        15 * 24 * 3_600_000L,
        30 * 24 * 3_600_000L
    )

    /** Number of Ebbinghaus review points (7). */
    val stageCount: Int get() = intervalsMillis.size

    /** reviewLevel at which the full plan is complete ("已掌握"). */
    val masteredLevel: Int get() = stageCount

    fun intervalFor(level: Int, pace: ReviewPace): Long {
        // Level 0 restarts at the first point; level k (>= 1) uses the k-th point.
        val base = intervalsMillis[(level - 1).coerceAtLeast(0)]
        return maxOf((base * pace.intervalMultiplier).toLong(), pace.minIntervalMillis)
    }

    fun intervalFor(level: Int, mode: ReviewMode): Long = intervalFor(level, mode.toPace())

    fun reviewed(
        word: SavedWord,
        remembered: Boolean,
        now: Long,
        pace: ReviewPace
    ): SavedWord {
        val level = if (remembered) {
            (word.reviewLevel + 1).coerceAtMost(masteredLevel)
        } else {
            0
        }
        return word.copy(
            reviewLevel = level,
            nextReviewAt = now + intervalFor(level, pace),
            reviewCount = word.reviewCount + 1
        )
    }

    fun reviewed(
        word: SavedWord,
        remembered: Boolean,
        now: Long,
        mode: ReviewMode = ReviewMode.GENTLE
    ): SavedWord = reviewed(word, remembered, now, mode.toPace())
}

class VocabularyRepository(private val context: Context) {
    private val vocabularyFile = File(context.filesDir, "vocabulary.json")
    private val mutex = Mutex()

    suspend fun load(): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        mode: ReviewMode = ReviewMode.GENTLE,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = save(book, chapterTitle, lookup, entry, mode.toPace(), aiResult)

    suspend fun save(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        pace: ReviewPace,
        aiResult: AiLookupResult? = null
    ): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read()
            val headword = entry.matchedPhrase ?: entry.headword
            val id = headword.lowercase(Locale.ROOT)
            val now = System.currentTimeMillis()
            val existing = current.firstOrNull { it.id == id }
            // 同一个词可能以不同形态被查到（study / studied / studying），逐次累积，
            // 供阅读页做整词高亮；原型本身单独传，不必重复存。
            val surfaceForms = (existing?.surfaceForms.orEmpty() + entry.surfaceWord)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals(headword, ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .takeLast(MAX_SURFACE_FORMS)
            val saved = SavedWord(
                id = id,
                headword = headword,
                phonetic = entry.phonetic,
                meaning = entry.senses.take(3).joinToString("\n") { it.text },
                aiMeaning = aiResult?.contextualMeaning.orEmpty(),
                aiSource = aiResult?.source.orEmpty(),
                aiExplanation = aiResult?.explanation.orEmpty(),
                sentence = lookup.sentence.ifBlank { lookup.paragraph },
                bookId = book.id,
                bookTitle = book.title,
                chapterTitle = chapterTitle,
                addedAt = existing?.addedAt ?: now,
                reviewLevel = existing?.reviewLevel ?: 0,
                nextReviewAt = existing?.nextReviewAt ?: now + pace.firstDelayMillis,
                reviewCount = existing?.reviewCount ?: 0,
                surfaceForms = surfaceForms
            )
            val updated = current.filterNot { it.id == id } + saved
            write(updated)
            sorted(updated)
        }
    }

    suspend fun remove(id: String): List<SavedWord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = read().filterNot { it.id == id }
            write(updated)
            sorted(updated)
        }
    }

    suspend fun review(id: String, remembered: Boolean, pace: ReviewPace): List<SavedWord> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val updated = read().map {
                    if (it.id == id) ReviewScheduler.reviewed(it, remembered, now, pace) else it
                }
                write(updated)
                sorted(updated)
            }
        }

    suspend fun export(uri: Uri, words: List<SavedWord>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
            it.write(csv(words))
        } ?: error("无法打开导出文件")
    }

    private fun read(): List<SavedWord> {
        if (!vocabularyFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(vocabularyFile.readText())
            (0 until array.length()).map { SavedWord.fromJson(array.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    private fun write(words: List<SavedWord>) {
        val temp = File(vocabularyFile.parentFile, "${vocabularyFile.name}.tmp")
        temp.writeText(JSONArray().apply { words.forEach { put(it.toJson()) } }.toString())
        if (!temp.renameTo(vocabularyFile)) {
            vocabularyFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun sorted(words: List<SavedWord>): List<SavedWord> =
        words.sortedWith(compareBy<SavedWord> { it.nextReviewAt }.thenByDescending { it.addedAt })

    companion object {
        /** 每个词最多累积多少个表面形，避免高亮词表无限膨胀。 */
        private const val MAX_SURFACE_FORMS = 8

        fun csv(words: List<SavedWord>): String = buildString {
            appendLine("word,phonetic,meaning,ai_meaning,ai_source,ai_explanation,sentence,book,chapter,review_count,next_review_at")
            words.forEach { word ->
                appendLine(
                    listOf(
                        word.headword,
                        word.phonetic,
                        word.meaning,
                        word.aiMeaning,
                        word.aiSource,
                        word.aiExplanation,
                        word.sentence,
                        word.bookTitle,
                        word.chapterTitle,
                        word.reviewCount.toString(),
                        reviewDate(word.nextReviewAt)
                    ).joinToString(",") { csvCell(it) }
                )
            }
        }

        private fun reviewDate(epochMillis: Long): String =
            if (epochMillis <= 0L) "0"
            else java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(java.util.Date(epochMillis))

        private fun csvCell(value: String): String =
            "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " / ")}\""
    }
}
