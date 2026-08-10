package com.linguareader.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Chapter(
    val title: String,
    val relativePath: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("relativePath", relativePath)

    companion object {
        fun fromJson(json: JSONObject) = Chapter(
            title = json.getString("title"),
            relativePath = json.getString("relativePath")
        )
    }
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val extractedDir: String,
    val coverRelativePath: String?,
    val chapters: List<Chapter>,
    val addedAt: Long,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val progress: Float = 0f,
    val sourceFormat: String = "epub",
    /** Last listened position: chapter and sentence index inside that chapter. */
    val ttsChapterIndex: Int = 0,
    val ttsSentenceIndex: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("extractedDir", extractedDir)
        .put("coverRelativePath", coverRelativePath)
        .put("chapters", JSONArray().apply { chapters.forEach { put(it.toJson()) } })
        .put("addedAt", addedAt)
        .put("chapterIndex", chapterIndex)
        .put("pageIndex", pageIndex)
        .put("progress", progress.toDouble())
        .put("sourceFormat", sourceFormat)
        .put("ttsChapterIndex", ttsChapterIndex)
        .put("ttsSentenceIndex", ttsSentenceIndex)

    companion object {
        fun fromJson(json: JSONObject): Book {
            val chapterArray = json.getJSONArray("chapters")
            return Book(
                id = json.getString("id"),
                title = json.getString("title"),
                author = json.optString("author"),
                extractedDir = json.getString("extractedDir"),
                coverRelativePath = json.optString("coverRelativePath").takeIf { it.isNotBlank() },
                chapters = (0 until chapterArray.length()).map {
                    Chapter.fromJson(chapterArray.getJSONObject(it))
                },
                addedAt = json.optLong("addedAt"),
                chapterIndex = json.optInt("chapterIndex"),
                pageIndex = json.optInt("pageIndex"),
                progress = json.optDouble("progress").toFloat(),
                sourceFormat = json.optString("sourceFormat", "epub").ifBlank { "epub" },
                ttsChapterIndex = json.optInt("ttsChapterIndex"),
                ttsSentenceIndex = json.optInt("ttsSentenceIndex")
            )
        }
    }
}

data class WordLookup(
    val word: String,
    val sentence: String,
    val paragraph: String,
    val sentenceOffset: Int,
    val x: Float,
    val y: Float
)

data class SavedWord(
    val id: String,
    val headword: String,
    val phonetic: String,
    val meaning: String,
    /** Book-specific AI meaning saved alongside the local dictionary sense. */
    val aiMeaning: String = "",
    val aiSource: String = "",
    val aiExplanation: String = "",
    val sentence: String,
    val bookId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val addedAt: Long,
    val reviewLevel: Int = 0,
    val nextReviewAt: Long = 0L,
    val reviewCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("headword", headword)
        .put("phonetic", phonetic)
        .put("meaning", meaning)
        .put("aiMeaning", aiMeaning)
        .put("aiSource", aiSource)
        .put("aiExplanation", aiExplanation)
        .put("sentence", sentence)
        .put("bookId", bookId)
        .put("bookTitle", bookTitle)
        .put("chapterTitle", chapterTitle)
        .put("addedAt", addedAt)
        .put("reviewLevel", reviewLevel)
        .put("nextReviewAt", nextReviewAt)
        .put("reviewCount", reviewCount)

    companion object {
        fun fromJson(json: JSONObject) = SavedWord(
            id = json.getString("id"),
            headword = json.getString("headword"),
            phonetic = json.optString("phonetic"),
            meaning = json.optString("meaning"),
            aiMeaning = json.optString("aiMeaning"),
            aiSource = json.optString("aiSource"),
            aiExplanation = json.optString("aiExplanation"),
            sentence = json.optString("sentence"),
            bookId = json.optString("bookId"),
            bookTitle = json.optString("bookTitle"),
            chapterTitle = json.optString("chapterTitle"),
            addedAt = json.optLong("addedAt"),
            reviewLevel = json.optInt("reviewLevel"),
            nextReviewAt = json.optLong("nextReviewAt"),
            reviewCount = json.optInt("reviewCount")
        )
    }
}

data class ReaderPreferences(
    val fontPercent: Int = 100,
    val lineHeight: Float = 1.65f,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val fontFamily: ReaderFont = ReaderFont.SERIF
)

enum class ReaderTheme(val label: String, val background: String, val foreground: String) {
    PAPER("纸张", "#F7F3EA", "#27231F"),
    WHITE("明亮", "#FFFFFF", "#181818"),
    SEPIA("护眼", "#E9DFC7", "#352F26"),
    DARK("夜间", "#171717", "#E8E3DA")
}

enum class ReaderFont(val label: String, val css: String) {
    SERIF("衬线", "Georgia, 'Times New Roman', serif"),
    SANS("无衬线", "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"),
    MONO("等宽", "'Droid Sans Mono', 'Courier New', monospace"),
    CONDENSED("窄体", "'Roboto Condensed', 'sans-serif-condensed', 'Arial Narrow', sans-serif"),
    CURSIVE("手写", "'Dancing Script', 'Segoe Script', cursive")
}
