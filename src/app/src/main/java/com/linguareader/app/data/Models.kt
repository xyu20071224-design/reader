package com.linguareader.app.data

import androidx.annotation.StringRes
import com.linguareader.app.R
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
    val ttsSentenceIndex: Int = 0,

    /**
     * 用户为这本英文书配的中文译本。id 指向导入到 files/translations/ 的书，
     * 它本身不出现在书架上。
     */
    val translationBookId: String = "",
    val translationTitle: String = "",
    val translationAlignedAt: Long = 0L
) {
    val hasTranslation: Boolean get() = translationBookId.isNotBlank()

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
        .put("translationBookId", translationBookId)
        .put("translationTitle", translationTitle)
        .put("translationAlignedAt", translationAlignedAt)

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
                ttsSentenceIndex = json.optInt("ttsSentenceIndex"),
                translationBookId = json.optString("translationBookId"),
                translationTitle = json.optString("translationTitle"),
                translationAlignedAt = json.optLong("translationAlignedAt")
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

/** 显示名走资源（labelRes），颜色仍以 hex 存储并注入 WebView CSS 变量。 */
enum class ReaderTheme(@StringRes val labelRes: Int, val background: String, val foreground: String) {
    PAPER(R.string.reader_theme_paper, "#F7F3EA", "#27231F"),
    WHITE(R.string.reader_theme_white, "#FFFFFF", "#181818"),
    SEPIA(R.string.reader_theme_sepia, "#E9DFC7", "#352F26"),
    DARK(R.string.reader_theme_dark, "#171717", "#E8E3DA")
}

enum class ReaderFont(@StringRes val labelRes: Int, val css: String) {
    SERIF(R.string.reader_font_serif, "Georgia, 'Times New Roman', serif"),
    SANS(R.string.reader_font_sans, "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"),
    MONO(R.string.reader_font_mono, "'Droid Sans Mono', 'Courier New', monospace"),
    CONDENSED(R.string.reader_font_condensed, "'Roboto Condensed', 'sans-serif-condensed', 'Arial Narrow', sans-serif"),
    CURSIVE(R.string.reader_font_cursive, "'Dancing Script', 'Segoe Script', cursive")
}
