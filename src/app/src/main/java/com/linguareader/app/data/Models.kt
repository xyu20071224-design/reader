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

/**
 * 阅读主题。除正文底/字色外，还携带「标记类」颜色的主题变体：生词下划线、链接
 * 装饰、选区与 TTS 高亮的浅色值在夜间(#171717)底上对比不足（#8D5535 约 2.97:1，
 * 卡在 3:1 门槛下，真机人工判「费劲」辨认），深色主题换提亮变体（#C98A5E 约
 * 6.2:1，与外壳 DarkLinguaPalette.accent 同源，保持色相只提亮度）。这些值经
 * ReaderScripts.preferenceScript 注入为 CSS 变量。
 */
enum class ReaderTheme(
    val label: String,
    val background: String,
    val foreground: String,
    /** 生词点状下划线（.lr-saved-word 的 text-decoration-color）。 */
    val markColor: String,
    /** 链接下划线装饰色（正文链接文字仍是 foreground）。 */
    val linkColor: String,
    /** 文本选区背景（半透明棕）。 */
    val selectionWash: String,
    /** TTS 整句高亮底（半透明棕）。 */
    val highlightWash: String
) {
    PAPER("纸张", "#F7F3EA", "#27231F", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    WHITE("明亮", "#FFFFFF", "#181818", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    SEPIA("护眼", "#E9DFC7", "#352F26", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    DARK("夜间", "#171717", "#E8E3DA", "#C98A5E", "#D7A072", "rgba(201,138,94,.30)", "rgba(201,138,94,.38)")
}

enum class ReaderFont(val label: String, val css: String) {
    SERIF("衬线", "Georgia, 'Times New Roman', serif"),
    SANS("无衬线", "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"),
    MONO("等宽", "'Droid Sans Mono', 'Courier New', monospace"),
    CONDENSED("窄体", "'Roboto Condensed', 'sans-serif-condensed', 'Arial Narrow', sans-serif"),
    CURSIVE("手写", "'Dancing Script', 'Segoe Script', cursive")
}
