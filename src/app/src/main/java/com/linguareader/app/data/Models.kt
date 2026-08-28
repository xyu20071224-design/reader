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

    /**
     * 译本是否为 AI 生成（[AI_TRANSLATION_ID_PREFIX] 前缀）。导入的出版译本
     * 不得被机器改写——句级重翻等编辑入口只对 AI 译本开放。导入书的 id 是
     * SHA-256 十六进制，不可能以 "ai-" 开头（'i' 非十六进制字符），天然无歧义。
     */
    val isAiTranslation: Boolean get() = translationBookId.startsWith(AI_TRANSLATION_ID_PREFIX)

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
        /** AI 生成译本的 id / 目录名前缀（实现在 ai/AiTranslationRepository）。 */
        const val AI_TRANSLATION_ID_PREFIX = "ai-"

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
 * 阅读主题。显示名走资源（labelRes）；颜色仍以 hex 存储并经 ReaderScripts.preferenceScript
 * 注入为 CSS 变量。除正文底/字色外还携带「标记类」变体：生词下划线、链接装饰、选区与
 * TTS 高亮的浅色值在夜间(#171717)底上对比不足（#8D5535 约 2.97:1，卡在 3:1 门槛下，
 * 真机人工判「费劲」辨认），深色主题换提亮变体（#C98A5E 约 6.2:1，与外壳
 * DarkLinguaPalette.accent 同源，保持色相只提亮度）。
 */
enum class ReaderTheme(
    @StringRes val labelRes: Int,
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
    PAPER(R.string.reader_theme_paper, "#F7F3EA", "#27231F", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    WHITE(R.string.reader_theme_white, "#FFFFFF", "#181818", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    SEPIA(R.string.reader_theme_sepia, "#E9DFC7", "#352F26", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    GREEN(R.string.reader_theme_green, "#CCE8CF", "#243329", "#8D5535", "#9b6b43", "rgba(184,132,83,.26)", "rgba(184,132,83,.30)"),
    MORANDI(R.string.reader_theme_morandi, "#E2D8D2", "#3A3330", "#8D5535", "#9b6b43", "rgba(184,132,83,.28)", "rgba(184,132,83,.32)"),
    DARK(R.string.reader_theme_dark, "#171717", "#E8E3DA", "#C98A5E", "#D7A072", "rgba(201,138,94,.30)", "rgba(201,138,94,.38)"),
    AMOLED(R.string.reader_theme_amoled, "#000000", "#E8E3DA", "#C98A5E", "#D7A072", "rgba(201,138,94,.30)", "rgba(201,138,94,.38)")
}

enum class ReaderFont(@StringRes val labelRes: Int, val css: String) {
    SERIF(R.string.reader_font_serif, "Georgia, 'Times New Roman', serif"),
    SANS(R.string.reader_font_sans, "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"),
    MONO(R.string.reader_font_mono, "'Droid Sans Mono', 'Courier New', monospace"),
    CONDENSED(R.string.reader_font_condensed, "'Roboto Condensed', 'sans-serif-condensed', 'Arial Narrow', sans-serif"),
    CURSIVE(R.string.reader_font_cursive, "'Dancing Script', 'Segoe Script', cursive")
}
