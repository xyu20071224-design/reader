package com.linguareader.app

import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.ImportSupport
import com.linguareader.app.data.LaunchPromptPolicy
import com.linguareader.app.data.LibraryRepository
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewNotifier
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.UpdateNote
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup
import com.linguareader.app.data.updateNoteFor
import com.linguareader.app.platform.KeyValueStore
import com.linguareader.app.platform.OutputTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

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
    val messageTitle: String = "提示"
) {
    /** The effective pace used by scheduling and reminders. */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview
}

class AppViewModel(
    dataDir: File,
    private val reviewPrefs: KeyValueStore,
    private val launchPrefs: KeyValueStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    private val library = LibraryRepository(dataDir)
    private val dictionary = DictionaryRepository()
    private val vocabulary = VocabularyRepository(dataDir)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    init {
        val storedName = reviewPrefs.getString(ReviewMode.PREFERENCE_KEY, null)
        val preset = if (storedName == ReviewPace.CUSTOM_NAME) null
        else runCatching { ReviewMode.valueOf(storedName ?: "") }.getOrDefault(ReviewMode.DEFAULT)
        val custom = ReviewPace.fromJson(reviewPrefs.getString(ReviewPace.STORAGE_KEY, null))
            ?: ReviewPace.defaultCustom()
        val reminders = ReviewReminders.fromPreferences(
            reviewPrefs,
            fallback = preset?.defaultReminders() ?: ReviewReminders.DEFAULT
        )
        val versionCode = AppVersion.VERSION_CODE
        val versionName = AppVersion.VERSION_NAME
        val lastSeenVersion = launchPrefs.getInt("last_seen_version", 0)
        val prompt = if (LaunchPromptPolicy.shouldShowUpdateNote(versionCode, lastSeenVersion)) {
            // Mark as seen immediately so the note appears exactly once.
            launchPrefs.putInt("last_seen_version", versionCode)
            LaunchPromptUi.UpdatePrompt(updateNoteFor(versionCode, versionName))
        } else {
            LaunchPromptUi.GreetingPrompt(
                LaunchPromptPolicy.greetingForHour(
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                )
            )
        }
        mutableState.value = mutableState.value.copy(
            reviewPreset = preset,
            customReview = custom,
            reminders = reminders,
            launchPrompt = prompt
        )
        refresh()
    }

    fun refresh() {
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            val books = library.loadBooks()
            val savedWords = vocabulary.load()
            mutableState.value = mutableState.value.copy(
                books = books,
                savedWords = savedWords,
                loading = false
            )
            rescheduleReviewReminders()
        }
    }

    private fun rescheduleReviewReminders() {
        ReviewNotifier.schedule(
            mutableState.value.savedWords,
            mutableState.value.reviewPace,
            mutableState.value.reminders.notifications
        )
    }

    fun importBook(file: File, displayName: String? = null) {
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            runCatching { library.importBook(ImportSupport.prepare(file, displayName)) }
                .onSuccess { book ->
                    val books = library.loadBooks()
                    mutableState.value = mutableState.value.copy(
                        books = books,
                        currentBook = book,
                        savedWords = vocabulary.load(),
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
    }

    fun closeBook() {
        mutableState.value = mutableState.value.copy(currentBook = null)
        refresh()
    }

    suspend fun saveProgress(book: Book, chapterIndex: Int, pageIndex: Int, progress: Float) {
        library.saveProgress(book, chapterIndex, pageIndex, progress)
        mutableState.value = mutableState.value.copy(
            currentBook = mutableState.value.currentBook?.copy(
                chapterIndex = chapterIndex,
                pageIndex = pageIndex,
                progress = progress
            )
        )
    }

    fun deleteBook(book: Book) {
        scope.launch {
            library.deleteBook(book)
            refresh()
        }
    }

    fun setReviewMode(mode: ReviewMode) {
        reviewPrefs.putString(ReviewMode.PREFERENCE_KEY, mode.name)
        mutableState.value = mutableState.value.copy(reviewPreset = mode)
        rescheduleReviewReminders()
    }

    fun setCustomReview(pace: ReviewPace) {
        reviewPrefs.putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
        reviewPrefs.putString(ReviewPace.STORAGE_KEY, pace.toJson())
        mutableState.value = mutableState.value.copy(reviewPreset = null, customReview = pace)
        rescheduleReviewReminders()
    }

    fun setReminders(reminders: ReviewReminders) {
        ReviewReminders.write(reviewPrefs, reminders)
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

    fun saveWord(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry
    ) {
        scope.launch {
            val words = vocabulary.save(
                book, chapterTitle, lookup, entry,
                pace = mutableState.value.reviewPace
            )
            mutableState.value = mutableState.value.copy(savedWords = words)
            rescheduleReviewReminders()
        }
    }

    fun removeSavedWord(id: String) {
        scope.launch {
            val words = vocabulary.remove(id)
            mutableState.value = mutableState.value.copy(savedWords = words)
            rescheduleReviewReminders()
        }
    }

    fun reviewWord(id: String, remembered: Boolean, onDone: (Boolean) -> Unit) {
        scope.launch {
            runCatching { vocabulary.review(id, remembered, mutableState.value.reviewPace) }
                .onSuccess { words ->
                    mutableState.value = mutableState.value.copy(savedWords = words)
                    rescheduleReviewReminders()
                    onDone(true)
                }
                .onFailure {
                    // Roll the list back to what is actually on disk so the UI
                    // never claims a review that was not persisted.
                    val actual = vocabulary.load()
                    mutableState.value = mutableState.value.copy(
                        savedWords = actual,
                        message = "保存复习结果失败，已恢复本地记录。",
                        messageTitle = "保存失败"
                    )
                    onDone(false)
                }
        }
    }

    fun exportVocabulary(target: OutputTarget) {
        scope.launch {
            runCatching { vocabulary.export(target, mutableState.value.savedWords) }
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
