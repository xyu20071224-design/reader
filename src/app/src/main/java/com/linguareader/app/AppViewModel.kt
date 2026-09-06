package com.linguareader.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linguareader.app.ai.AiBookStatus
import com.linguareader.app.ai.AiBookTranslator
import com.linguareader.app.ai.AiLookupOutcome
import com.linguareader.app.ai.AiLookupRequest
import com.linguareader.app.ai.AiLookupResult
import com.linguareader.app.ai.AiSettings
import com.linguareader.app.ai.AiSettingsStore
import com.linguareader.app.ai.AiTranslationAbortedException
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
import com.linguareader.app.data.BookDataOrphan
import com.linguareader.app.data.BookScopedStore
import com.linguareader.app.data.StorageReport
import com.linguareader.app.data.formatStorageBytes
import com.linguareader.app.data.StoreUsage
import com.linguareader.app.data.ContextualDictionaryEntry
import com.linguareader.app.data.DictionaryLookupResult
import com.linguareader.app.data.DictionaryRepository
import com.linguareader.app.data.Greeting
import com.linguareader.app.data.LaunchPromptPolicy
import com.linguareader.app.data.asPreferencesStore
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
import com.linguareader.app.tts.MultiVoiceSupport
import com.linguareader.app.tts.TtsAudioCache
import com.linguareader.app.update.AppUpdateRepository
import com.linguareader.app.update.UpdateCheckOutcome
import com.linguareader.shared.update.AppUpdatePhase
import com.linguareader.shared.update.AppUpdateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 实际注入 prompt 的术语条数（手动优先；与 glossaryTerms 的差 = 超上限截断）。 */
    val glossaryInjected: Int,
    /** 超长段落个数（译文可能顶到模型输出上限，确认框显式提示）。 */
    val oversizedParagraphs: Int,
    val mode: String,
    val styleNotes: String
)

/**
 * 「手动 AI 翻译」对话框数据：全书批次覆盖进度 + 风格说明。与在线确认框
 * 不同的是不要求配置 API Key——手动路径的全部出网都发生在应用之外。
 */
data class ManualTranslationPrepare(
    val book: Book,
    val batches: Int,
    /** 已有有效译文（历史在线批次 + 已导入的手动批次）的批次数。 */
    val coveredBatches: Int,
    val styleNotes: String
)

/** 任务文件已构建完毕、等用户在 SAF 面板里选保存位置。 */
data class PendingManualExport(
    val bookId: String,
    /** SAF 面板的建议文件名。 */
    val fileName: String,
    /** 任务文件 JSON 全文。 */
    val content: String,
    /** 导出完成通知里报批次数用。 */
    val batches: Int
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
    val aiTranslationPrepare: AiTranslationPrepare? = null,
    /** 非空时显示「手动 AI 翻译」对话框（覆盖进度与风格说明已加载）。 */
    val manualTranslationPrepare: ManualTranslationPrepare? = null,
    /** 手动任务文件已构建、等用户选保存位置；写入由导出面板回调接手。 */
    val pendingManualExport: PendingManualExport? = null,
    /** 最近一次手动导入的逐条拒绝原因；非空时弹详情对话框。 */
    val manualImportRejected: List<String>? = null,
    /** 自动更新（GitHub Release 检查/下载）的流程状态。 */
    val update: AppUpdateUiState = AppUpdateUiState(),
    /** 存储体检结果；null = 还没扫过（扫盘只在用户打开存储页面时跑）。 */
    val storage: StorageReport? = null,
    val storageScanning: Boolean = false
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
    /** 已取走、等 SAF 写入回调的手动任务文件（takeManualExport 与 writeManualExport 之间）。 */
    private var manualExportInFlight: PendingManualExport? = null
    /** 整本翻译的「记住上次选择」：翻译模式（标准/精译）。 */
    private val aiTranslationPrefs = getApplication<Application>().getSharedPreferences(
        "ai_translation", android.content.Context.MODE_PRIVATE
    )
    /** 云 TTS 音频缓存：路径的唯一知情者，删书时按这份清单清理。 */
    private val ttsAudioCache = TtsAudioCache(application)
    /** 角色→音色映射：以前删书是在这里手写 File 路径，绕过了它自带的 delete。 */
    private val voiceMaps = MultiVoiceSupport.voiceMapRepository(application)

    /**
     * **「一本书的数据由什么构成」的权威清单。**
     *
     * 以前这份清单是 deleteBook 里一串手写调用 + 两行裸路径字符串，没有编译期
     * 保证、没有对账，新增一处存储时也没有任何机制提醒你回来加一行 —— 它已经
     * 漏过一处（生词本）。现在删书只是遍历它；新增 per-book 存储时，
     * **实现 BookScopedStore 并登记到这里**是唯一要做的事。
     */
    // internal 而非 private：漂移守卫测试要读它（BookDeletionCascadeTest）。
    internal val bookDataStores: List<BookScopedStore> = listOf(
        library,
        vocabulary,
        aiRepository,
        glossaryRepository,
        speakerTagRepository,
        translationRepository,
        aiTranslationRepository,
        ttsAudioCache,
        voiceMaps
    )

    /** 自动更新：检查 + 下载的编排层（网络细节在 update/ 包内）。 */
    private val updateRepository = AppUpdateRepository(application)
    private var updateJob: Job? = null

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
            reviewPrefs.asPreferencesStore(),
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
            aiSettings = aiSettingsStore.load(),
            update = AppUpdateUiState(autoCheckEnabled = updateRepository.loadSettings().autoCheckEnabled)
        )
        if (updateRepository.loadSettings().autoCheckEnabled) {
            // 出厂默认关；用户开了开关才在启动时静默查一次，失败不打扰。
            checkForUpdate(silent = true)
        }
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

    suspend fun saveProgress(
        book: Book,
        chapterIndex: Int,
        pageIndex: Int,
        progress: Float,
        locusBlockIndex: Int = Book.NO_LOCUS,
        locusCharOffset: Int = 0,
        locusAnchor: String = Book.ANCHOR_EXACT
    ) {
        library.saveProgress(
            book, chapterIndex, pageIndex, progress,
            locusBlockIndex, locusCharOffset, locusAnchor
        )
        val hasNewLocus = locusBlockIndex >= 0 || locusAnchor != Book.ANCHOR_EXACT
        val chapterChanged = mutableState.value.currentBook?.chapterIndex != chapterIndex
        mutableState.value = mutableState.value.copy(
            currentBook = mutableState.value.currentBook?.let { current ->
                current.copy(
                    chapterIndex = chapterIndex,
                    pageIndex = pageIndex,
                    progress = progress,
                    // 与仓库层同一规则：没取到锚点时不要用「没有」覆盖已有锚点
                    locusBlockIndex = if (hasNewLocus || chapterChanged) locusBlockIndex else current.locusBlockIndex,
                    locusCharOffset = if (hasNewLocus || chapterChanged) locusCharOffset else current.locusCharOffset,
                    locusAnchor = if (hasNewLocus || chapterChanged) locusAnchor else current.locusAnchor
                )
            }
        )
    }

    /**
     * 孤儿对账（方案 D1.7）：**只报不删**。
     *
     * 自动删是不可逆的，而对账逻辑自己可能有 bug（比如把正在导入的中间态误判成
     * 孤儿）。所以这里只产出报告，清理入口留给 D2.4 的「存储占用」页面，由用户
     * 按下去。启动时也不跑 —— 扫盘要 IO，会拖慢冷启动。
     */
    internal suspend fun scanStorage(): StorageReport = withContext(Dispatchers.IO) {
        val books = library.loadBooks()
        val usages = bookDataStores.map { store ->
            StoreUsage(store.storeId, store.storageRoots().sumOf { sizeOf(it) })
        }
        val orphans = bookDataStores.flatMap { store ->
            runCatching { store.orphans(books) }.getOrDefault(emptyList()).map { file ->
                BookDataOrphan(storeId = store.storeId, path = file, bytes = sizeOf(file))
            }
        }
        StorageReport(usages = usages, orphans = orphans)
    }

    /** 体检一次并写进 UI 状态；扫盘要 IO，只在用户打开存储页面时跑。 */
    fun refreshStorageReport() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(storageScanning = true)
            val report = runCatching { scanStorage() }.getOrNull()
            mutableState.value = mutableState.value.copy(
                storageScanning = false,
                storage = report
            )
        }
    }

    /**
     * 清理孤儿数据。**只删对账报出来的那些路径**，不重新推断 ——
     * 推断与删除之间隔着用户的一次点击，中间可能已经导入了新书。
     */
    fun deleteOrphans() {
        val targets = mutableState.value.storage?.orphans.orEmpty()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                targets.sumOf { orphan ->
                    val size = orphan.bytes
                    if (runCatching { orphan.path.deleteRecursively() }.getOrDefault(false)) size else 0L
                }
            }
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.storage_orphans_cleared, formatStorageBytes(freed)),
                noticeTone = StatusTone.SUCCESS
            )
            refreshStorageReport()
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else file.length()

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            // 在途的整本翻译任务先停，否则它会往刚清掉的检查点目录里继续写。
            aiTranslationJobs.remove(book.id)?.cancel()
            // 单处清理失败（文件被占用、目录权限异常）不该中断其余清理 ——
            // 半清理的残留比一次报错更难查。失败的项留给孤儿对账兜底（方案 D1.7）。
            val failed = bookDataStores.mapNotNull { store ->
                runCatching { store.deleteBookData(book) }.exceptionOrNull()?.let { store.storeId }
            }
            if (failed.isNotEmpty()) {
                android.util.Log.w("AppViewModel", "删书残留：${failed.joinToString()}（书 ${book.id}）")
            }
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
        ReviewReminders.write(reviewPrefs.asPreferencesStore(), reminders)
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

    // --- 自动更新（GitHub Release）-------------------------------------------

    private fun setUpdate(transform: (AppUpdateUiState) -> AppUpdateUiState) {
        mutableState.value = mutableState.value.copy(update = transform(mutableState.value.update))
    }

    fun setAutoCheck(enabled: Boolean) {
        updateRepository.setAutoCheck(enabled)
        setUpdate { it.copy(autoCheckEnabled = enabled) }
    }

    /**
     * 检查更新。[silent] 为 true 表示启动自动检查：只在新版时 Snackbar 提示，
     * 无更新/失败都静默归位，避免打扰；手动入口走 false，结果都在 UpdateSheet 里展示。
     */
    fun checkForUpdate(silent: Boolean = false) {
        if (mutableState.value.update.phase == AppUpdatePhase.CHECKING) return
        setUpdate { it.copy(phase = AppUpdatePhase.CHECKING, error = null) }
        viewModelScope.launch {
            when (val outcome = updateRepository.check()) {
                is UpdateCheckOutcome.Available -> {
                    setUpdate {
                        it.copy(phase = AppUpdatePhase.AVAILABLE, info = outcome.info, error = null)
                    }
                    if (silent) {
                        mutableState.value = mutableState.value.copy(
                            notice = string(R.string.update_found_notice, outcome.info.versionName)
                        )
                    }
                }
                UpdateCheckOutcome.UpToDate -> setUpdate {
                    it.copy(
                        phase = if (silent) AppUpdatePhase.IDLE else AppUpdatePhase.UP_TO_DATE,
                        error = null
                    )
                }
                is UpdateCheckOutcome.Failure ->
                    if (silent) setUpdate { it.copy(phase = AppUpdatePhase.IDLE) }
                    else setUpdate { it.copy(phase = AppUpdatePhase.ERROR, error = outcome.message) }
            }
        }
    }

    fun downloadUpdate() {
        val info = mutableState.value.update.info ?: return
        if (mutableState.value.update.phase == AppUpdatePhase.DOWNLOADING) return
        updateJob?.cancel()
        setUpdate { it.copy(phase = AppUpdatePhase.DOWNLOADING, progress = 0, error = null) }
        updateJob = viewModelScope.launch {
            updateRepository.download(info) { downloaded, total ->
                if (total > 0) {
                    val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                    setUpdate { it.copy(progress = percent) }
                }
            }.onSuccess { apk ->
                setUpdate { it.copy(phase = AppUpdatePhase.DOWNLOADED, downloadedApk = apk) }
            }.onFailure { throwable ->
                setUpdate { it.copy(phase = AppUpdatePhase.ERROR, error = throwable.message) }
            }
        }
    }

    fun cancelUpdateDownload() {
        updateJob?.cancel()
        updateJob = null
        if (mutableState.value.update.phase == AppUpdatePhase.DOWNLOADING) {
            setUpdate { it.copy(phase = AppUpdatePhase.AVAILABLE, progress = 0) }
        }
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
     * 已有 AI 译本的书走这里是重新生成（检查点复用，只补失败批）；
     * 出版译本不提供重生成，但书架入口对它本来只出删除确认框，这里只是兜底。
     */
    fun prepareAiTranslation(book: Book) {
        if (book.id in mutableState.value.aiTranslationProgress) return
        if (book.hasTranslation && !book.isAiTranslation) return
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
                    book = book,
                    chapters = estimate.chapters,
                    batches = estimate.batches,
                    glossaryTerms = estimate.glossaryTerms,
                    glossaryInjected = estimate.glossaryInjected,
                    oversizedParagraphs = estimate.oversizedParagraphs,
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
            // 单批用尽重试后不再中止整本书：该批保留英文原文继续跑，末尾汇总告知用户。
            var untranslatedParagraphs = 0
            try {
                val translationBook = aiTranslationRepository.translateBook(
                    book,
                    mode = safeMode,
                    styleNotes = styleNotes.trim().ifBlank { null },
                    onBatchFailed = { batch, _ -> untranslatedParagraphs += batch.paragraphs.size }
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
                    notice = if (untranslatedParagraphs > 0) {
                        string(
                            R.string.notice_translation_ready_partial,
                            result.memory.pairs.size,
                            seconds,
                            untranslatedParagraphs
                        )
                    } else {
                        string(R.string.notice_translation_ready, result.memory.pairs.size, seconds)
                    },
                    noticeTone = if (untranslatedParagraphs > 0) StatusTone.NEUTRAL else StatusTone.SUCCESS
                )
                refresh()
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(
                    notice = string(R.string.notice_translation_ai_cancelled)
                )
            } catch (aborted: AiTranslationAbortedException) {
                // 连败断路器（AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES）：
                // 系统性失败提前止损。检查点都在，排除故障后重新生成即续跑。
                mutableState.value = mutableState.value.copy(
                    notice = string(
                        R.string.notice_translation_ai_aborted,
                        book.title,
                        AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES
                    ),
                    noticeTone = StatusTone.DANGER
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

    // --- 手动 AI 全书翻译（导出任务文件 / 导入外部 agent 结果） -----------------

    /**
     * 「手动 AI 翻译」对话框数据：覆盖进度 + 风格说明，全部本地读取，不出网。
     * 与 [prepareAiTranslation] 的关键差异：不检查 API Key、不自动生成语境
     * 档案——手动路径的用户可能根本没配 Key，术语表用已有的即可。
     * 准备阶段登记进 [aiTranslationJobs]，书卡上的「取消生成」真有效。
     */
    fun prepareManualTranslation(book: Book) {
        if (book.id in mutableState.value.aiTranslationProgress) return
        if (book.hasTranslation && !book.isAiTranslation) return
        aiTranslationJobs[book.id] = viewModelScope.launch {
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                val coverage = aiTranslationRepository.manualCoverage(book)
                if (coverage.totalBatches == 0) {
                    // 抽不出任何段落的书（如无文字层的扫描 PDF）没有可翻译的东西，
                    // 直接说明，别让用户困在「已收 0/0 批」里永远无法完成。
                    mutableState.value = mutableState.value.copy(
                        notice = string(R.string.notice_translation_manual_empty)
                    )
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    manualTranslationPrepare = ManualTranslationPrepare(
                        book = book,
                        batches = coverage.totalBatches,
                        coveredBatches = coverage.coveredBatches,
                        styleNotes = aiTranslationRepository.loadStyle(book.id).orEmpty()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
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

    fun dismissManualTranslationPrepare() {
        if (mutableState.value.manualTranslationPrepare != null) {
            mutableState.value = mutableState.value.copy(manualTranslationPrepare = null)
        }
    }

    fun clearManualImportRejected() {
        if (mutableState.value.manualImportRejected != null) {
            mutableState.value = mutableState.value.copy(manualImportRejected = null)
        }
    }

    /**
     * 构建任务文件文本（全书抽取，大书要几秒），就绪后置 [PendingManualExport]
     * 让界面弹 SAF 保存面板。风格说明顺手保存，与在线路径同一存储位。
     * 构建阶段登记进 [aiTranslationJobs]，书卡上的「取消生成」真有效。
     */
    fun buildManualExport(book: Book, styleNotes: String) {
        dismissManualTranslationPrepare()
        if (book.id in mutableState.value.aiTranslationProgress) return
        aiTranslationJobs[book.id] = viewModelScope.launch {
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                aiTranslationRepository.saveStyle(book.id, styleNotes)
                val task = aiTranslationRepository.buildManualTask(book, styleNotes.trim().ifBlank { null })
                mutableState.value = mutableState.value.copy(
                    pendingManualExport = PendingManualExport(
                        bookId = book.id,
                        fileName = "${book.title}-manual-translation.json"
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_"),
                        content = task.toString(),
                        batches = task.optJSONArray("batches")?.length() ?: 0
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutableState.value = mutableState.value.copy(
                    notice = string(
                        R.string.notice_translation_manual_export_failed,
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

    /**
     * 界面弹保存面板前取走任务文件：状态即刻置空，旋转屏幕重建组合后
     * [AppUiState.pendingManualExport] 已不在，LaunchedEffect 不会重放出
     * 第二个面板。内容暂存 [manualExportInFlight] 供写入回调使用。
     */
    fun takeManualExport(): PendingManualExport? {
        val pending = mutableState.value.pendingManualExport ?: return null
        mutableState.value = mutableState.value.copy(pendingManualExport = null)
        manualExportInFlight = pending
        return pending
    }

    /** SAF 保存面板的回调：把构建好的任务文件写进用户选的位置。 */
    fun writeManualExport(uri: Uri) {
        val pending = manualExportInFlight ?: return
        manualExportInFlight = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                        ?.bufferedWriter()
                        ?.use { it.write(pending.content) }
                        ?: error("无法打开导出文件")
                }
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    notice = string(R.string.notice_translation_manual_exported, pending.batches)
                )
            }.onFailure {
                mutableState.value = mutableState.value.copy(
                    notice = string(
                        R.string.notice_translation_manual_export_failed,
                        it.message ?: string(R.string.message_ai_context_failed)
                    ),
                    noticeTone = StatusTone.DANGER
                )
            }
        }
    }

    /** 用户在 SAF 面板取消了保存：丢弃已构建的任务文件。 */
    fun cancelManualExport() {
        manualExportInFlight = null
        mutableState.value = mutableState.value.copy(pendingManualExport = null)
    }

    /**
     * 导入外部 agent 的结果文件（可多选）：读文本 → 校验逐批落检查点 →
     * 覆盖满 100% 则自动离线生成译本对照（一个网络请求都不发）。
     * 导入阶段登记进 [aiTranslationJobs]，书卡上的「取消生成」真有效。
     */
    fun importManualResults(book: Book, uris: List<Uri>) {
        dismissManualTranslationPrepare()
        if (book.id in mutableState.value.aiTranslationProgress) return
        aiTranslationJobs[book.id] = viewModelScope.launch {
            var readyToComplete = false
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                if (aiTranslationRepository.manualCoverage(book).totalBatches == 0) {
                    mutableState.value = mutableState.value.copy(
                        notice = string(R.string.notice_translation_manual_empty)
                    )
                    return@launch
                }
                val texts = uris.map { uri ->
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("无法读取所选文件")
                    }
                }
                val report = aiTranslationRepository.importManualResults(book, texts)
                if (report.coverage.complete) {
                    readyToComplete = true
                    mutableState.value = mutableState.value.copy(
                        notice = string(R.string.notice_translation_manual_imported_done, report.coverage.totalBatches)
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        notice = string(
                            R.string.notice_translation_manual_imported_partial,
                            report.coverage.coveredBatches,
                            report.coverage.totalBatches,
                            report.rejected.size
                        ),
                        noticeTone = if (report.acceptedBatches == 0) StatusTone.DANGER else StatusTone.NEUTRAL
                    )
                }
                if (report.rejected.isNotEmpty()) {
                    // 拒绝详情立刻弹对话框逐条展示——「拒绝 N 条」的条数不告诉
                    // 用户该让 agent 改什么（典型错误：段落编号被重新编号）。
                    mutableState.value = mutableState.value.copy(
                        manualImportRejected = report.rejected
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                mutableState.value = mutableState.value.copy(
                    notice = string(
                        R.string.notice_translation_manual_import_failed,
                        failed.message ?: string(R.string.message_ai_context_failed)
                    ),
                    noticeTone = StatusTone.DANGER
                )
            } finally {
                aiTranslationJobs.remove(book.id)
                if (!readyToComplete) {
                    mutableState.value = mutableState.value.copy(
                        aiTranslationProgress = mutableState.value.aiTranslationProgress - book.id
                    )
                }
            }
            // finally 已摘掉本 job，这里才能过 completeManualTranslation 的防重闸。
            if (readyToComplete) completeManualTranslation(book)
        }
    }

    /**
     * 手动译文齐批后的离线生成：completeFromCheckpoints（零出网）→ 对齐落盘。
     * 进度条与取消复用在线翻译的 [aiTranslationProgress] 机制。
     */
    private fun completeManualTranslation(book: Book) {
        if (book.id in aiTranslationJobs) return
        aiTranslationJobs[book.id] = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 100, aligning = true))
            try {
                val translationBook = aiTranslationRepository.completeFromCheckpoints(
                    book,
                    providerName = string(R.string.translation_manual_provider)
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
