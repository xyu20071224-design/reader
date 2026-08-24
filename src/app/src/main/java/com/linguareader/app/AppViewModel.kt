package com.linguareader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiLookupOutcome
import com.linguareader.app.ai.AiLookupRequest
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.AiSettingsStore
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.BookGlossaryRepository
import com.linguareader.app.ai.BookContextRepository
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.ai.SpeakerTagRepository
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.ai.SentenceTranslatorFactory
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMemoryRepository
import com.linguareader.app.data.Book
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.LaunchPromptPolicy
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewReminderScheduler
import com.linguareader.app.data.UpdateNote
import com.linguareader.app.data.updateNoteFor
import java.util.Calendar
import com.linguareader.app.data.LibraryRepository
import com.linguareader.app.data.SavedWord
import com.linguareader.app.data.VocabularyRepository
import com.linguareader.app.data.WordLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    val messageTitle: String = "",
    /** 一次性轻提示（Snackbar）：显示后由界面调用 [AppViewModel.clearNotice] 清空。 */
    val notice: String? = null,
    /** 提示的语义（成功/失败/中性），决定 Snackbar 容器配色；与文案解耦。 */
    val noticeTone: StatusTone = StatusTone.NEUTRAL,
    val aiSettings: AiSettings = AiSettings(),
    val aiStatuses: Map<String, AiBookStatus> = emptyMap(),
    /** 正在对齐中文译本的书 id（对齐是数十秒级操作，界面据此显示进度并防重复触发）。 */
    val attachingTranslation: Set<String> = emptySet()
) {
    /** The effective pace used by scheduling and reminders. */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryRepository(application)
    private val dictionary = DictionaryRepository(application)
    private val vocabulary = VocabularyRepository(application)
    private val reviewPrefs = application.getSharedPreferences("review_settings", android.content.Context.MODE_PRIVATE)
    private val launchPrefs = application.getSharedPreferences("launch_promo", android.content.Context.MODE_PRIVATE)
    private val aiSettingsStore = AiSettingsStore(application)
    private val aiRepository = BookContextRepository(application, aiSettingsStore)
    private val glossaryRepository = BookGlossaryRepository(application)
    private val translationRepository = TranslationMemoryRepository(application)
    /** Multi-voice M2: per-chapter speaker tag cache (invalidated with the profile). */
    private val speakerTagRepository =
        SpeakerTagRepository(application, aiSettingsStore, glossaryRepository)
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
        val versionCode = BuildConfig.VERSION_CODE
        val versionName = BuildConfig.VERSION_NAME
        val lastSeenVersion = launchPrefs.getInt("last_seen_version", 0)
        val prompt = if (LaunchPromptPolicy.shouldShowUpdateNote(versionCode, lastSeenVersion)) {
            // Mark as seen immediately so the note appears exactly once.
            launchPrefs.edit().putInt("last_seen_version", versionCode).apply()
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
            launchPrompt = prompt,
            aiSettings = aiSettingsStore.load()
        )
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true)
            val books = library.loadBooks()
            val savedWords = vocabulary.load()
            // 书架「AI 语境」标签此前只看内存 aiStatuses：进程被系统回收后重建，
            // 状态清零，磁盘上明明有档案的书也显示「待生成」；打开书时
            // generate() 命中磁盘档案又秒变「就绪」——造成同一天内
            // 待生成↔就绪 的往返漂移。以磁盘档案存在性播种就绪态，
            // 已有的 generating/error 状态不被覆盖。
            val seeded = books
                .filter { it.id !in mutableState.value.aiStatuses && aiRepository.hasProfile(it.id) }
                .associate { it.id to AiBookStatus(ready = true) }
            mutableState.value = mutableState.value.copy(
                books = books,
                savedWords = savedWords,
                loading = false,
                aiStatuses = mutableState.value.aiStatuses + seeded
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
            runCatching { library.importBook(uri) }
                .onSuccess { book ->
                    val books = library.loadBooks()
                    mutableState.value = mutableState.value.copy(
                        books = books,
                        currentBook = book,
                        savedWords = vocabulary.load(),
                        loading = false,
                        notice = string(R.string.notice_book_imported, book.title),
                        noticeTone = StatusTone.SUCCESS
                    )
                    rescheduleReviewReminders()
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        message = it.message ?: string(R.string.message_import_failed),
                        messageTitle = string(R.string.message_import_failed_title)
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
        viewModelScope.launch {
            library.deleteBook(book)
            aiRepository.delete(book.id)
            glossaryRepository.delete(book.id)
            speakerTagRepository.delete(book.id)
            // 译本与对齐档案跟着英文书一起删。
            translationRepository.remove(book)
            // Cloud TTS chapter audio cache is per book; remove it with the book.
            File(getApplication<Application>().filesDir, "tts_cache/${book.id}")
                .deleteRecursively()
            // Multi-voice M3: the per-book character → voice mapping goes too.
            File(getApplication<Application>().filesDir, "voice_maps/${book.id}.json").delete()
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.notice_book_deleted, book.title),
                noticeTone = StatusTone.DANGER
            )
            refresh()
        }
    }

    fun setAiSettings(settings: AiSettings) {
        aiSettingsStore.save(settings)
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
            runCatching { aiRepository.generate(book) }
                .onSuccess { profile ->
                    runCatching { glossaryRepository.importFromProfile(book.id, profile) }
                    setAiStatus(book.id, AiBookStatus(ready = true))
                }
                .onFailure {
                    setAiStatus(
                        book.id,
                        AiBookStatus(error = it.message ?: string(R.string.message_ai_context_failed))
                    )
                }
        }
    }

    /** Regenerates a book profile from scratch (used by settings/debug UI). */
    fun regenerateBookContext(book: Book) {
        if (!mutableState.value.aiSettings.enabled) return
        viewModelScope.launch {
            setAiStatus(book.id, AiBookStatus(generating = true))
            runCatching { aiRepository.generate(book, force = true) }
                .onSuccess { profile ->
                    runCatching { glossaryRepository.importFromProfile(book.id, profile) }
                    // A fresh profile means a fresh character roster, so cached
                    // chapter speaker tags are re-requested on demand (M2).
                    speakerTagRepository.delete(book.id)
                    setAiStatus(book.id, AiBookStatus(ready = true))
                }
                .onFailure {
                    setAiStatus(
                        book.id,
                        AiBookStatus(error = it.message ?: string(R.string.message_ai_context_failed))
                    )
                }
        }
    }

    /** internal：单测需要预置某本书的状态来验证播种不覆盖既有状态。 */
    internal fun setAiStatus(bookId: String, status: AiBookStatus) {
        mutableState.value = mutableState.value.copy(
            aiStatuses = mutableState.value.aiStatuses + (bookId to status)
        )
    }

    fun setReviewMode(mode: ReviewMode) {
        reviewPrefs.edit().putString(ReviewMode.PREFERENCE_KEY, mode.name).apply()
        mutableState.value = mutableState.value.copy(reviewPreset = mode)
        rescheduleReviewReminders()
    }

    fun setCustomReview(pace: ReviewPace) {
        reviewPrefs.edit()
            .putString(ReviewMode.PREFERENCE_KEY, ReviewPace.CUSTOM_NAME)
            .putString(ReviewPace.STORAGE_KEY, pace.toJson())
            .apply()
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

    /** 取应用资源文案（ViewModel 里没有 Composable 上下文）。 */
    private fun string(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** 清掉已经弹过的一次性提示，避免重组时重复弹。 */
    fun clearNotice() {
        if (mutableState.value.notice != null) {
            mutableState.value = mutableState.value.copy(notice = null, noticeTone = StatusTone.NEUTRAL)
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    suspend fun lookup(lookup: WordLookup): DictionaryLookupResult = dictionary.lookup(lookup)

    /**
     * Contextual AI enhancement on top of the local lookup result.
     * Returns an outcome with a null result quickly when AI is disabled or no
     * profile exists yet; a remote failure is flagged so the UI can say so.
     */
    suspend fun aiLookup(
        book: Book,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry?
    ): AiLookupOutcome {
        if (!mutableState.value.aiSettings.enabled) return AiLookupOutcome(result = null)
        val request = AiLookupRequest(
            bookId = book.id,
            bookTitle = book.title,
            surfaceWord = lookup.word,
            headword = entry?.headword ?: lookup.word,
            sentence = lookup.sentence,
            paragraph = lookup.paragraph,
            localSenses = entry?.senses?.map { it.text }.orEmpty(),
            localDefinitions = entry?.definitions.orEmpty(),
            matchedPhrase = entry?.matchedPhrase,
            glossary = glossaryRepository.load(book.id).entries
        )
        return aiRepository.translate(book, request)
    }

    // --- per-book glossary ---------------------------------------------------

    suspend fun glossary(bookId: String): BookGlossary = glossaryRepository.load(bookId)

    suspend fun addGlossaryEntry(bookId: String, term: String, translation: String): BookGlossary =
        glossaryRepository.addOrUpdate(bookId, term, translation)

    suspend fun updateGlossaryEntry(bookId: String, entry: GlossaryEntry): BookGlossary =
        glossaryRepository.update(bookId, entry)

    suspend fun removeGlossaryEntry(bookId: String, term: String): BookGlossary =
        glossaryRepository.remove(bookId, term)

    // --- 中文译本对照 ---------------------------------------------------------

    /**
     * 导入中文译本并与英文原书对齐。整本书是数十秒级操作（本机实测魔戒首部曲
     * 29s），全程在 IO 上跑；界面靠 [AppUiState.attachingTranslation] 显示进度
     * 并防止重复触发。
     */
    fun attachTranslation(book: Book, uri: Uri) {
        if (book.id in mutableState.value.attachingTranslation) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                attachingTranslation = mutableState.value.attachingTranslation + book.id,
                notice = string(R.string.notice_translation_aligning, book.title)
            )
            val startedAt = System.currentTimeMillis()
            runCatching { translationRepository.attach(book, uri) }
                .onSuccess { result ->
                    library.saveTranslation(
                        book,
                        result.translationBook.id,
                        result.translationBook.title,
                        result.memory.alignedAt
                    )
                    val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    mutableState.value = mutableState.value.copy(
                        attachingTranslation = mutableState.value.attachingTranslation - book.id,
                        notice = string(
                            R.string.notice_translation_ready,
                            result.memory.pairs.size,
                            seconds
                        ),
                        noticeTone = StatusTone.SUCCESS
                    )
                    refresh()
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        attachingTranslation = mutableState.value.attachingTranslation - book.id,
                        notice = string(R.string.notice_translation_failed),
                        noticeTone = StatusTone.DANGER
                    )
                }
        }
    }

    fun detachTranslation(book: Book) {
        viewModelScope.launch {
            translationRepository.remove(book)
            library.saveTranslation(book, "", "", 0L)
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.notice_translation_removed, book.title)
            )
            refresh()
        }
    }

    /** 点词时查译本对照；未配译本或档案损坏时返回 null，界面不显示该区块。 */
    suspend fun translationLookup(
        book: Book,
        chapterIndex: Int,
        lookup: WordLookup
    ): TranslationLookupResult? {
        if (!book.hasTranslation) return null
        return runCatching { translationRepository.lookup(book, chapterIndex, lookup) }.getOrNull()
    }

    // --- sentence translation -------------------------------------------------

    /**
     * Translates a sentence with the configured backend (Azure first, DeepSeek
     * as fallback), applying the book's enabled glossary terms. The returned
     * [SentenceTranslationResult] carries the provider name so the UI can label
     * the real source.
     */
    suspend fun translateSentence(book: Book, sentence: String): SentenceTranslationResult {
        val settings = mutableState.value.aiSettings
        val translator = SentenceTranslatorFactory.from(settings)
            ?: error("未启用整句翻译（需要 Azure Translator Key 或 DeepSeek API Key）")
        val glossary = glossaryRepository.load(book.id)
        return SentenceTranslationResult(
            text = translator.translateSentence(sentence, glossary),
            provider = translator.displayName
        )
    }

    fun saveWord(
        book: Book,
        chapterTitle: String,
        lookup: WordLookup,
        entry: ContextualDictionaryEntry,
        aiResult: AiLookupResult? = null
    ) {
        viewModelScope.launch {
            val words = vocabulary.save(
                book, chapterTitle, lookup, entry,
                pace = mutableState.value.reviewPace,
                aiResult = aiResult
            )
            mutableState.value = mutableState.value.copy(
                savedWords = words,
                notice = string(R.string.notice_word_saved, lookup.word),
                noticeTone = StatusTone.SUCCESS
            )
            rescheduleReviewReminders()
        }
    }

    fun removeSavedWord(id: String) {
        viewModelScope.launch {
            val removedWord = mutableState.value.savedWords.firstOrNull { it.id == id }?.headword
            val words = vocabulary.remove(id)
            mutableState.value = mutableState.value.copy(
                savedWords = words,
                notice = removedWord?.let { string(R.string.notice_word_removed, it) }
            )
            rescheduleReviewReminders()
        }
    }

    fun reviewWord(id: String, remembered: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
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
                        message = string(R.string.message_review_save_failed),
                        messageTitle = string(R.string.message_review_save_failed_title)
                    )
                    onDone(false)
                }
        }
    }

    fun exportVocabulary(uri: Uri) {
        viewModelScope.launch {
            runCatching { vocabulary.export(uri, mutableState.value.savedWords) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        message = string(R.string.message_export_done),
                        messageTitle = string(R.string.message_export_done_title)
                    )
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        message = it.message ?: string(R.string.message_export_failed),
                        messageTitle = string(R.string.message_export_failed_title)
                    )
                }
        }
    }
}
