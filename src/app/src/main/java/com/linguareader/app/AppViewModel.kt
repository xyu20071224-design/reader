package com.linguareader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewReminderScheduler
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.UpdateNote
import com.linguareader.app.data.WordLookup
import com.linguareader.app.facade.AiFacade
import com.linguareader.app.facade.LibraryFacade
import com.linguareader.app.facade.ReviewSettingsFacade
import com.linguareader.app.facade.VocabularyFacade
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LaunchPromptUi {
    data class GreetingPrompt(val greeting: Greeting) : LaunchPromptUi
    data class UpdatePrompt(val note: UpdateNote) : LaunchPromptUi
}

data class AppUiState(
    val books: List<Book> = emptyList(),
    val currentBook: Book? = null,
    val savedWords: List<SavedWord> = emptyList(),
    val loading: Boolean = true,
    /** Non-null when a built-in preset is selected; null means custom (F-138). */
    val reviewPreset: ReviewMode? = ReviewMode.DEFAULT,
    val customReview: ReviewPace = ReviewPace.defaultCustom(),
    val reminders: ReviewReminders = ReviewReminders.DEFAULT,
    val launchPrompt: LaunchPromptUi? = null,
    val message: String? = null,
    val messageTitle: String = "提示",
    val aiSettings: AiSettings = AiSettings(),
    val aiStatuses: Map<String, AiBookStatus> = emptyMap(),
    val translationStatuses: Map<String, TranslationStatus> = emptyMap()
) {
    /** The effective pace used by scheduling and reminders. */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview
}

data class TranslationStatus(
    val generating: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null
)

/**
 * 顶层 ViewModel。现状是 thin facade：库/生词/AI/复习偏好分别委托给
 * [LibraryFacade] / [VocabularyFacade] / [AiFacade] / [ReviewSettingsFacade]。
 * 本类只负责 AppUiState 的读改写、协程编排与复习提醒重调度聚合。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val libraryFacade = LibraryFacade(application, viewModelScope)
    private val vocabularyFacade = VocabularyFacade(application)
    private val aiFacade = AiFacade(application)
    private val reviewSettingsFacade = ReviewSettingsFacade(application)
    private val dictionary = DictionaryRepository(application)
    private val translationRepository = TranslationMemoryRepository(application)

    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        // Delete-book cleanup: drop AI context profile + per-book glossary.
        libraryFacade.onBeforeDelete = { book ->
            aiFacade.delete(book.id)
        }

        val reviewState = reviewSettingsFacade.loadState()
        mutableState.value = mutableState.value.copy(
            reviewPreset = reviewState.preset,
            customReview = reviewState.custom,
            reminders = reviewState.reminders,
            launchPrompt = reviewSettingsFacade.resolveLaunchPrompt(),
            aiSettings = aiFacade.loadSettings()
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            val books = libraryFacade.loadBooks()
            val savedWords = vocabularyFacade.load()
            mutableState.value = mutableState.value.copy(
                books = books,
                savedWords = savedWords,
                loading = false
            )
            rescheduleReviewReminders()
        }
    }

    private fun rescheduleReviewReminders() {
        ReviewReminderScheduler.schedule(
            getApplication(),
            mutableState.value.savedWords,
            mutableState.value.reviewPace,
            mutableState.value.reminders.notifications
        )
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            runCatching { libraryFacade.importBook(uri) }
                .onSuccess { book ->
                    val books = libraryFacade.loadBooks()
                    mutableState.value = mutableState.value.copy(
                        books = books,
                        currentBook = book,
                        savedWords = vocabularyFacade.load(),
                        loading = false
                    )
                    rescheduleReviewReminders()
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        message = it.message ?: "导入失败",
                        messageTitle = "无法导入"
                    )
                }
        }
    }

    fun openBook(book: Book) {
        mutableState.value = mutableState.value.copy(currentBook = book)
        ensureBookContext(book)
    }

    fun closeBook() {
        mutableState.value = mutableState.value.copy(currentBook = null)
        refresh()
    }

    suspend fun saveProgress(book: Book, chapterIndex: Int, pageIndex: Int, progress: Float) {
        libraryFacade.saveProgress(book, chapterIndex, pageIndex, progress)
        mutableState.value = mutableState.value.copy(
            currentBook = mutableState.value.currentBook?.copy(
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                progress = progress
            )
        )
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            libraryFacade.deleteBook(book)
            translationRepository.remove(book)
            refresh()
        }
    }

    private fun setTranslationStatus(bookId: String, status: TranslationStatus) {
        mutableState.value = mutableState.value.copy(
            translationStatuses = mutableState.value.translationStatuses + (bookId to status)
        )
    }

    fun attachTranslation(book: Book, uri: Uri) {
        viewModelScope.launch {
            setTranslationStatus(book.id, TranslationStatus(generating = true))
            runCatching { translationRepository.attach(book, uri) }
                .onSuccess { attached ->
                    libraryFacade.attachTranslation(
                        book = book,
                        translationBookId = attached.translationBook.id,
                        translationTitle = attached.translationBook.title,
                        alignedAt = attached.memory.alignedAt
                    )
                    val books = libraryFacade.loadBooks()
                    mutableState.value = mutableState.value.copy(books = books)
                    setTranslationStatus(book.id, TranslationStatus(ready = true))
                    mutableState.value = mutableState.value.copy(
                        message = "中文译本已对齐：共 ${attached.memory.pairs.size} 个句子对照。",
                        messageTitle = "译本已添加"
                    )
                }
                .onFailure {
                    setTranslationStatus(
                        book.id,
                        TranslationStatus(error = it.message ?: "中文译本对齐失败")
                    )
                    mutableState.value = mutableState.value.copy(
                        message = it.message ?: "中文译本对齐失败",
                        messageTitle = "无法添加译本"
                    )
                }
        }
    }

    fun removeTranslation(book: Book) {
        viewModelScope.launch {
            translationRepository.remove(book)
            libraryFacade.removeTranslation(book)
            val books = libraryFacade.loadBooks()
            mutableState.value = mutableState.value.copy(books = books)
            mutableState.value = mutableState.value.copy(
                message = "已移除中文译本对照。",
                messageTitle = "译本已移除"
            )
        }
    }

    /** Local lookup into the user-provided Chinese translation memory. */
    suspend fun translationLookup(
        book: Book,
        chapterIndex: Int,
        lookup: WordLookup
    ): TranslationLookupResult? = translationRepository.lookup(book, chapterIndex, lookup)

    fun setAiSettings(settings: AiSettings) {
        aiFacade.saveSettings(settings)
        mutableState.value = mutableState.value.copy(aiSettings = settings)
        if (settings.enabled) {
            mutableState.value.currentBook?.let { ensureBookContext(it) }
        }
    }

    /** Generates the per-book context profile once when AI is enabled. */
    fun ensureBookContext(book: Book) {
        val settings = mutableState.value.aiSettings
        if (!settings.enabled) return
        val current = mutableState.value.aiStatuses[book.id]
        if (current?.generating == true || current?.ready == true) return
        viewModelScope.launch {
            setAiStatus(book.id, AiBookStatus(generating = true))
            runCatching { aiFacade.generateContext(book) }
                .onSuccess { profile ->
                    runCatching { aiFacade.importGlossaryFromProfile(book.id, profile) }
                    setAiStatus(book.id, AiBookStatus(ready = true))
                }
                .onFailure {
                    setAiStatus(
                        book.id,
                        AiBookStatus(error = it.message ?: "语境档案生成失败")
                    )
                }
        }
    }

    /** Regenerates a book profile from scratch (used by settings/debug UI). */
    fun regenerateBookContext(book: Book) {
        if (!mutableState.value.aiSettings.enabled) return
        viewModelScope.launch {
            setAiStatus(book.id, AiBookStatus(generating = true))
            runCatching { aiFacade.generateContext(book, force = true) }
                .onSuccess { profile ->
                    runCatching { aiFacade.importGlossaryFromProfile(book.id, profile) }
                    setAiStatus(book.id, AiBookStatus(ready = true))
                }
                .onFailure {
                    setAiStatus(
                        book.id,
                        AiBookStatus(error = it.message ?: "语境档案生成失败")
                    )
                }
        }
    }

    private fun setAiStatus(bookId: String, status: AiBookStatus) {
        mutableState.value = mutableState.value.copy(
            aiStatuses = mutableState.value.aiStatuses + (bookId to status)
        )
    }

    fun setReviewMode(mode: ReviewMode) {
        reviewSettingsFacade.setReviewMode(mode)
        mutableState.value = mutableState.value.copy(reviewPreset = mode)
        rescheduleReviewReminders()
    }

    fun setCustomReview(pace: ReviewPace) {
        reviewSettingsFacade.setCustomReview(pace)
        mutableState.value = mutableState.value.copy(reviewPreset = null, customReview = pace)
        rescheduleReviewReminders()
    }

    fun setReminders(reminders: ReviewReminders) {
        reviewSettingsFacade.setReminders(reminders)
        mutableState.value = mutableState.value.copy(reminders = reminders)
        rescheduleReviewReminders()
    }

    fun dismissLaunchPrompt() {
        mutableState.value = mutableState.value.copy(launchPrompt = null)
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    suspend fun lookup(lookup: WordLookup): DictionaryLookupResult = dictionary.lookup(lookup)

    /**
     * Contextual AI enhancement on top of the local lookup result.
     * Returns null quickly when AI is disabled or no profile exists yet.
     */
    suspend fun aiLookup(
        book: Book,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry?
    ): AiLookupResult? {
        if (!mutableState.value.aiSettings.enabled) return null
        return aiFacade.aiLookup(book, lookup, entry)
    }

    // --- per-book glossary ---------------------------------------------------

    suspend fun glossary(bookId: String): BookGlossary = aiFacade.glossary(bookId)

    suspend fun addGlossaryEntry(bookId: String, term: String, translation: String): BookGlossary =
        aiFacade.addGlossaryEntry(bookId, term, translation)

    suspend fun updateGlossaryEntry(bookId: String, entry: GlossaryEntry): BookGlossary =
        aiFacade.updateGlossaryEntry(bookId, entry)

    suspend fun removeGlossaryEntry(bookId: String, term: String): BookGlossary =
        aiFacade.removeGlossaryEntry(bookId, term)

    // --- Azure sentence translation -------------------------------------------

    /**
     * Translates a sentence with Azure AI Translator, applying the book's
     * enabled glossary terms as dynamic dictionary markup.
     */
    suspend fun translateSentence(book: Book, sentence: String): String {
        val settings = mutableState.value.aiSettings
        return aiFacade.translateSentence(book, sentence, settings)
    }

    fun saveWord(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        aiResult: AiLookupResult? = null
    ) {
        viewModelScope.launch {
            val words = vocabularyFacade.save(
                book, chapterTitle, lookup, entry,
                pace = mutableState.value.reviewPace,
                aiResult = aiResult
            )
            mutableState.value = mutableState.value.copy(savedWords = words)
            rescheduleReviewReminders()
        }
    }

    fun removeSavedWord(id: String) {
        viewModelScope.launch {
            val words = vocabularyFacade.remove(id)
            mutableState.value = mutableState.value.copy(savedWords = words)
            rescheduleReviewReminders()
        }
    }

    fun reviewWord(id: String, remembered: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatching { vocabularyFacade.review(id, remembered, mutableState.value.reviewPace) }
                .onSuccess { words ->
                    mutableState.value = mutableState.value.copy(savedWords = words)
                    rescheduleReviewReminders()
                    onDone(true)
                }
                .onFailure {
                    // Roll the list back to what is actually on disk so the UI
                    // never claims a review that was not persisted.
                    val actual = vocabularyFacade.load()
                    mutableState.value = mutableState.value.copy(
                        savedWords = actual,
                        message = "保存复习结果失败，已恢复本地记录。",
                        messageTitle = "保存失败"
                    )
                    onDone(false)
                }
        }
    }

    fun exportVocabulary(uri: Uri) {
        viewModelScope.launch {
            runCatching { vocabularyFacade.export(uri, mutableState.value.savedWords) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        message = "生词表已导出为 CSV。",
                        messageTitle = "导出完成"
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        message = it.message ?: "导出失败",
                        messageTitle = "无法导出"
                    )
                }
        }
    }
}
