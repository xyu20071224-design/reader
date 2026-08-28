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
import com.linguareader.app.ai.AiTranslationRepository
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.BookGlossaryRepository
import com.linguareader.app.ai.BookContextProfile
import com.linguareader.app.ai.BookContextRepository
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.ai.SpeakerTagRepository
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.ai.SentenceTranslatorFactory
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMatchLevel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

sealed interface LaunchPromptUi {
    data class GreetingPrompt(val greeting: Greeting) : LaunchPromptUi
    data class UpdatePrompt(val note: UpdateNote) : LaunchPromptUi
}

/** 一本书的 AI 整本翻译进度（批次百分比；aligning = 翻译完成正在对齐）。 */
data class AiTranslationProgress(
    val percent: Int,
    val aligning: Boolean = false,
    /** 术语表/规模估算准备中（确认框弹出前）。 */
    val preparing: Boolean = false,
    /** 精译模式（每批初翻+精修两遍）：书卡文案显示「AI 精译中」。 */
    val polish: Boolean = false
)

/**
 * AI 生成译本的确认框数据：术语表已备齐、规模已估算，等用户确认才开翻。
 * [mode] 取上次使用的翻译模式；[styleNotes] 是已保存的风格说明（可空），
 * 确认框里可再编辑。
 */
data class AiTranslationPrepare(
    val book: Book,
    val chapters: Int,
    val batches: Int,
    val glossaryTerms: Int,
    val mode: String,
    val styleNotes: String
)

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
    val attachingTranslation: Set<String> = emptySet(),
    /** AI 整本翻译进行中的书 → 进度（含准备中），界面据此显示进度/取消并防重复触发。 */
    val aiTranslationProgress: Map<String, AiTranslationProgress> = emptyMap(),
    /** 非空时显示「AI 生成译本」确认框（术语已备齐、规模已估算）。 */
    val aiTranslationPrepare: AiTranslationPrepare? = null
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
    /** AI 整本翻译（生成译本对照）。 */
    private val aiTranslationRepository =
        AiTranslationRepository(application, aiSettingsStore, glossaryRepository)
    /** Multi-voice M2: per-chapter speaker tag cache (invalidated with the profile). */
    private val speakerTagRepository =
        SpeakerTagRepository(application, aiSettingsStore, glossaryRepository)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    /** AI 整本翻译的在跑任务，按书 id 取消用。 */
    private val aiTranslationJobs = mutableMapOf<String, Job>()
    /** 整本翻译的「记住上次选择」：翻译模式（标准/精译）。 */
    private val aiTranslationPrefs = getApplication<Application>().getSharedPreferences(
        "ai_translation", android.content.Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_TRANSLATION_MODE = "mode"
    }

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
            // 除了存在性，还要看档案来源：配了 Key 但档案是 local（远程失败降级），
            // 播种为 degraded 而非「就绪」，否则坏 Key 在全链路里始终被当成可用。
            val remoteConfigured = mutableState.value.aiSettings.remoteReady
            val seeded = books
                .filter { it.id !in mutableState.value.aiStatuses }
                .mapNotNull { book ->
                    val source = aiRepository.sourceOf(book.id) ?: return@mapNotNull null
                    book.id to (
                        if (remoteConfigured && source != "deepseek") AiBookStatus(degraded = true)
                        else AiBookStatus(ready = true)
                        )
                }.toMap()
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
            // AI 整本翻译的检查点（含在途任务）也随书清理。
            aiTranslationJobs.remove(book.id)?.cancel()
            aiTranslationRepository.delete(book.id)
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
        // 已降级的书（配了 Key 但生成时连接失败、落到本地档案）：下次打开时用
        // force 重新调 DeepSeek，让换了有效 Key 的用户自动升级到远程语境，而不是
        // 永远卡在本地档案；仍是坏 Key 则本次再次降级，下次打开再试（每次仅一次，
        // 不会在单次打开里死循环）。
        val retryRemote = current?.degraded == true && settings.remoteReady
        viewModelScope.launch {
            setAiStatus(book.id, AiBookStatus(generating = true))
            runCatching { aiRepository.generate(book, force = retryRemote) }
                .onSuccess { profile ->
                    runCatching { glossaryRepository.importFromProfile(book.id, profile) }
                    // 强制重生成意味着角色名单可能变化：清理说话人缓存，按需重标。
                    if (retryRemote) speakerTagRepository.delete(book.id)
                    setAiStatus(book.id, statusFor(profile))
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
                    setAiStatus(book.id, statusFor(profile))
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

    /**
     * Maps a freshly generated profile to a shelf status. A profile that came
     * back as `local` while a DeepSeek key is configured means the remote
     * attempt actually failed and degraded offline — that is NOT "就绪", it is
     * a degraded run (the offline fallback still works, so reading is fine, but
     * the user must fix the key/endpoint for real AI context).
     */
    private fun statusFor(profile: BookContextProfile): AiBookStatus {
        val remoteConfigured = mutableState.value.aiSettings.remoteReady
        return if (remoteConfigured && profile.source != "deepseek") AiBookStatus(degraded = true)
        else AiBookStatus(ready = true)
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

    // --- AI 生成译本对照 -------------------------------------------------------

    /**
     * 「AI 生成译本」第一步：备齐术语表（缺失时先生成语境档案并导入）、估算
     * 规模，然后停在确认框等用户点头。全程除语境档案生成本身外不出网。
     */
    fun prepareAiTranslation(book: Book) {
        if (book.hasTranslation || book.id in mutableState.value.aiTranslationProgress) return
        val settings = mutableState.value.aiSettings
        if (!settings.powerEnabled || !settings.remoteReady) {
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.notice_translation_ai_need_key)
            )
            return
        }
        viewModelScope.launch {
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            runCatching {
                var glossary = glossaryRepository.load(book.id)
                if (glossary.entries.isEmpty()) {
                    // 术语先行：整本翻译的译法一致性靠它；档案缺失或降级时这里
                    // 会触发一次远程生成（generate 自带降级，失败返回 local）。
                    val profile = aiRepository.generate(book)
                    runCatching { glossaryRepository.importFromProfile(book.id, profile) }
                    glossary = glossaryRepository.load(book.id)
                }
                val estimate = aiTranslationRepository.estimate(book)
                AiTranslationPrepare(
                    book,
                    estimate.chapters,
                    estimate.batches,
                    glossary.entries.count { it.enabled },
                    mode = aiTranslationPrefs.getString(
                        KEY_TRANSLATION_MODE, AiTranslationRepository.MODE_STANDARD
                    ) ?: AiTranslationRepository.MODE_STANDARD,
                    styleNotes = aiTranslationRepository.loadStyle(book.id).orEmpty()
                )
            }.onSuccess { prepare ->
                mutableState.value = mutableState.value.copy(
                    aiTranslationProgress = mutableState.value.aiTranslationProgress - book.id,
                    aiTranslationPrepare = prepare
                )
            }.onFailure {
                mutableState.value = mutableState.value.copy(
                    aiTranslationProgress = mutableState.value.aiTranslationProgress - book.id,
                    notice = string(
                        R.string.notice_translation_ai_failed,
                        it.message ?: string(R.string.message_ai_context_failed)
                    ),
                    noticeTone = StatusTone.DANGER
                )
            }
        }
    }

    fun dismissAiTranslationPrepare() {
        if (mutableState.value.aiTranslationPrepare != null) {
            mutableState.value = mutableState.value.copy(aiTranslationPrepare = null)
        }
    }

    /** 确认框点「开始生成」：逐章逐批翻译（检查点续跑）→ 对齐 → 落盘。 */
    fun startAiTranslation(book: Book, mode: String, styleNotes: String) {
        dismissAiTranslationPrepare()
        if (book.id in mutableState.value.aiTranslationProgress) return
        val settings = mutableState.value.aiSettings
        if (!settings.powerEnabled || !settings.remoteReady) {
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.notice_translation_ai_need_key)
            )
            return
        }
        val safeMode = if (mode == AiTranslationRepository.MODE_POLISH) {
            AiTranslationRepository.MODE_POLISH
        } else {
            AiTranslationRepository.MODE_STANDARD
        }
        val polish = safeMode == AiTranslationRepository.MODE_POLISH
        aiTranslationPrefs.edit().putString(KEY_TRANSLATION_MODE, safeMode).apply()
        aiTranslationJobs[book.id] = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching { aiTranslationRepository.saveStyle(book.id, styleNotes) }
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, polish = polish))
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.notice_translation_ai_started, book.title)
            )
            try {
                val translationBook = aiTranslationRepository.translateBook(
                    book,
                    mode = safeMode,
                    styleNotes = styleNotes.trim().ifBlank { null }
                ) { percent ->
                    setAiTranslationProgress(book.id, AiTranslationProgress(percent = percent, polish = polish))
                }
                setAiTranslationProgress(
                    book.id,
                    AiTranslationProgress(percent = 100, aligning = true, polish = polish)
                )
                val result = translationRepository.attachGenerated(book, translationBook)
                library.saveTranslation(
                    book,
                    result.translationBook.id,
                    result.translationBook.title,
                    result.memory.alignedAt
                )
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                mutableState.value = mutableState.value.copy(
                    notice = string(R.string.notice_translation_ready, result.memory.pairs.size, seconds),
                    noticeTone = StatusTone.SUCCESS
                )
                refresh()
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(
                    notice = string(R.string.notice_translation_ai_cancelled)
                )
            } catch (failed: Throwable) {
                mutableState.value = mutableState.value.copy(
                    notice = string(
                        R.string.notice_translation_ai_failed,
                        failed.message ?: string(R.string.message_ai_context_failed)
                    ),
                    noticeTone = StatusTone.DANGER
                )
            } finally {
                aiTranslationJobs.remove(book.id)
                mutableState.value = mutableState.value.copy(
                    aiTranslationProgress = mutableState.value.aiTranslationProgress - book.id
                )
            }
        }
    }

    /** 取消在跑的整本翻译；已完成批次的检查点保留，下次直接续跑。 */
    fun cancelAiTranslation(book: Book) {
        aiTranslationJobs.remove(book.id)?.cancel()
    }

    /** 句级定点重翻的在跑书（每本同时最多一次，防双击双扣费）。 */
    private val retranslateInFlight = mutableSetOf<String>()

    /**
     * 句级定点重翻：一次小请求替换档案里 [result.pairIndex] 那条句对的译文。
     * 只对句级命中开放（段落级兜底命中没有确定的句对条目）；成功/失败经
     * [onDone] 回调，旧译文由界面保持不动。
     */
    fun retranslateTranslation(
        book: Book,
        result: TranslationLookupResult,
        feedback: String?,
        onDone: (Boolean) -> Unit
    ) {
        val settings = mutableState.value.aiSettings
        if (!settings.powerEnabled || !settings.remoteReady ||
            !book.isAiTranslation ||
            result.matchLevel != TranslationMatchLevel.SENTENCE || result.pairIndex < 0 ||
            book.id in retranslateInFlight || book.id in mutableState.value.aiTranslationProgress
        ) {
            onDone(false)
            return
        }
        retranslateInFlight.add(book.id)
        viewModelScope.launch {
            val ok = runCatching {
                val newZh = aiTranslationRepository.retranslateText(
                    book,
                    enSentence = result.english,
                    enParagraph = result.englishParagraph,
                    currentZh = result.chinese,
                    feedback = feedback
                )
                translationRepository.replaceSentenceTranslation(book.id, result.pairIndex, newZh) != null
            }.isSuccess
            retranslateInFlight.remove(book.id)
            onDone(ok)
        }
    }

    private fun setAiTranslationProgress(bookId: String, progress: AiTranslationProgress) {
        mutableState.value = mutableState.value.copy(
            aiTranslationProgress = mutableState.value.aiTranslationProgress + (bookId to progress)
        )
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
     * Translates a sentence with the configured model provider, applying the
     * book's enabled glossary terms. The returned [SentenceTranslationResult]
     * carries the provider name so the UI can label the real source.
     */
    suspend fun translateSentence(book: Book, sentence: String): SentenceTranslationResult {
        val settings = mutableState.value.aiSettings
        val translator = SentenceTranslatorFactory.from(settings)
            ?: error("未启用整句翻译（先在 AI 中心配置并保存一个服务商）")
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
