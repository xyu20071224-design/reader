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
import com.linguareader.app.ai.TranslationCoordinator
import com.linguareader.app.ai.TranslationHost
import com.linguareader.app.ai.BookGlossary
import com.linguareader.app.ai.BookGlossaryRepository
import com.linguareader.app.ai.BookContextProfile
import com.linguareader.app.ai.BookContextRepository
import com.linguareader.app.ai.GlossaryEntry
import com.linguareader.app.ai.SpeakerTagRepository
import com.linguareader.app.ai.SentenceTranslationResult
import com.linguareader.app.translation.TranslationLookupResult
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
import com.linguareader.app.packs.LoadWarning
import com.linguareader.app.packs.PackRepository
import com.linguareader.app.packs.PackUiItem
import com.linguareader.app.packs.PackUiState
import com.linguareader.app.packs.RemotePacksState
import com.linguareader.shared.update.GitHubReleaseParser
import com.linguareader.app.packs.toUiItem
import com.linguareader.app.tts.MultiVoiceSupport
import com.linguareader.app.tts.TtsAudioCache
import com.linguareader.app.update.AppUpdateRepository
import com.linguareader.app.update.UpdateCheckOutcome
import com.linguareader.shared.update.AppUpdatePhase
import com.linguareader.shared.update.AppUpdateUiState
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.sync.CloudBook
import com.linguareader.app.sync.AppSyncCoordinator
import com.linguareader.app.sync.SyncHost
import com.linguareader.shared.sync.SyncDetail
import com.linguareader.shared.sync.SyncSettings
import com.linguareader.shared.packs.PackValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /**
     * 对齐档案已旧于当前对齐器版本的书 id（阶段 2 的版本闸门）。
     * 书架据此提示「对照待更新」并在译本菜单里显示「重新对齐」。
     */
    val outdatedTranslations: Set<String> = emptySet(),
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
    val storageScanning: Boolean = false,
    /** 资源包（词典/预生成音频/音色）列表与安装状态。 */
    val packs: PackUiState = PackUiState(),
    /** 云同步（自托管服务端）：设置、最近一次状态文案、是否正在跑。 */
    val syncSettings: SyncSettings = SyncSettings(),
    val syncStatus: String? = null,
    val syncBusy: Boolean = false,
    /** 云端书单：远端有完整正文、本机没有的书（书籍正文 blob）。 */
    val cloudBooks: List<CloudBook> = emptyList(),
    /** 是否已成功拉取过一次云端书单（区分「没拉过」与「拉过但为空」）。 */
    val cloudBooksLoaded: Boolean = false,
    val cloudBooksLoading: Boolean = false,
    /**
     * 同步明细快照：本地全部书/生词逐条三态 + 只在云端的书 + 汇总计数。
     * null = 尚未构建（冷启动 init 结尾会构建一次；构建函数 [AppViewModel.refreshSyncDetail] 纯本地、零网络）。
     */
    val syncDetail: SyncDetail? = null,
    /** 明细构建中的标记（纯本地读水位文件，通常一闪而过）。 */
    val syncDetailLoading: Boolean = false,
    /**
     * 云端正文 blob 的 bookId → size；null = 尚未成功拉到云端正文列表。
     * 与 [cloudBooks] 语义不同：这里含**全部**云端完整 blob（含本机也有的书），
     * cloudBooks 只含「远端有、本机没有」的书。
     */
    val cloudBlobSizes: Map<String, Long>? = null,
    /** 上次同步动作成功的时间（毫秒）；0 = 从未成功。仅表示动作成功，不代表业务数据都已同步。 */
    val lastSyncAt: Long = 0L
) {
    /** The effective pace used by scheduling and reminders. */
    val reviewPace: ReviewPace get() = reviewPreset?.toPace() ?: customReview
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryRepository(application)
    /** 资源包（词典/音频/音色）：安装、登记表、当前词典包。 */
    private val packs = PackRepository(application)
    private val dictionary = DictionaryRepository(application) { packs.dictionarySource() }
    private val vocabulary = VocabularyRepository(application)
    private val reviewPrefs = application.getSharedPreferences("review_settings", android.content.Context.MODE_PRIVATE)
    private val launchPrefs = application.getSharedPreferences("launch_promo", android.content.Context.MODE_PRIVATE)
    private val aiSettingsStore = AiSettingsStore(application)
    private val aiRepository = BookContextRepository(application, aiSettingsStore)
    private val glossaryRepository = BookGlossaryRepository(application)
    private val translationRepository =
        TranslationMemoryRepository(application) { packs.dictionarySource() }
    /** AI 整本翻译（生成译本对照）。 */
    private val aiTranslationRepository =
        AiTranslationRepository(application, aiSettingsStore, glossaryRepository)
    /** Multi-voice M2: per-chapter speaker tag cache (invalidated with the profile). */
    private val speakerTagRepository =
        SpeakerTagRepository(application, aiSettingsStore, glossaryRepository)
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    /**
     * 云同步协调器：同步动作 / 自动同步调度 / 同步明细 / 正文 blob 四块逻辑的归属地。
     * 构造在 mutableState 之后——它要通过 [SyncHost] 读写同一份状态。
     */
    private val syncCoordinator = AppSyncCoordinator(
        context = application,
        library = library,
        host = object : SyncHost {
            override fun snapshot(): AppUiState = mutableState.value

            override fun updateState(transform: (AppUiState) -> AppUiState) {
                mutableState.value = transform(mutableState.value)
            }

            override val scope: CoroutineScope get() = this@AppViewModel.viewModelScope

            /** 同步会改写书库/生词本落盘内容；refresh() 同时重播种 AI 状态、排复习提醒。 */
            override fun refreshLibrary() {
                this@AppViewModel.refresh()
            }
        }
    )

    /**
     * AI 翻译协调器：AI 生成译本 / 手动全书翻译 / 句级重翻 / 整句翻译四块的归属地。
     *
     * 注入的仓库与拆分前是**同一批实例**（尤其 aiTranslationRepository 仍在
     * bookDataStores 里登记着 —— 删书级联不能因为搬家而漏掉它）。
     */
    private val translationCoordinator = TranslationCoordinator(
        context = application,
        library = library,
        aiRepository = aiRepository,
        glossaryRepository = glossaryRepository,
        aiTranslationRepository = aiTranslationRepository,
        translationRepository = translationRepository,
        host = object : TranslationHost {
            override fun snapshot(): AppUiState = mutableState.value

            override fun updateState(transform: (AppUiState) -> AppUiState) {
                mutableState.value = transform(mutableState.value)
            }

            override val scope: CoroutineScope get() = this@AppViewModel.viewModelScope

            /** 译本生成/导入完成后重读书架（refresh 顺带重播种 AI 状态、排复习提醒）。 */
            override fun refreshLibrary() {
                this@AppViewModel.refresh()
            }
        }
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
            update = AppUpdateUiState(autoCheckEnabled = updateRepository.loadSettings().autoCheckEnabled),
            syncSettings = syncCoordinator.settings()
        )
        if (updateRepository.loadSettings().autoCheckEnabled) {
            // 出厂默认关；用户开了开关才在启动时静默查一次，失败不打扰。
            checkForUpdate(silent = true)
        }
        refresh()
        // 冷启动自动同步一次（默认关闭：enabled=false 或未登录时 runAutoSync 直接返回，零出网）。
        runAutoSync()
        // 资源包列表要出现在存储页与资源包页，启动时读一次登记表（小文件，无扫盘）。
        refreshPacks()
        // 同步明细：冷启动构建一次（纯本地，未登录也不出网）。
        refreshSyncDetail()
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
            // 版本闸门：已配译本的书里，档案由更旧版对齐器写出的挑出来（尾部快读，
            // 不解析整份 JSON）。没有这一步，对齐器的改进永远触达不到既有档案。
            val outdated = books.filter { it.hasTranslation }
                .filter { translationRepository.isMemoryOutdated(it.id) }
                .map { it.id }
                .toSet()
            mutableState.value = mutableState.value.copy(
                books = books,
                savedWords = savedWords,
                loading = false,
                aiStatuses = mutableState.value.aiStatuses + seeded,
                outdatedTranslations = outdated
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
                    // 云同步开启时后台自动上传源文件；不阻塞导入，失败只发 Snackbar。
                    uploadBookSourceInBackground(book)
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
        // 阅读进度写盘成功 → 排一次防抖同步（未启用/未登录时内部直接清空排队）。
        scheduleAutoSync()
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
        } + StoreUsage(PackRepository.DIR_NAME, packs.totalBytes())
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

    // ─── 资源包（方案-资源包系统.md §5 M1） ─────────────────────────────────────

    /** 重读登记表并刷新资源包列表（打开页面、安装/卸载/切换后调用）。 */
    fun refreshPacks() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val books = runCatching { library.loadBooks() }.getOrDefault(emptyList()).associateBy { it.id }
                val loaded = packs.reloadWithError()
                val registry = loaded.registry
                val items = registry.packs.map { pack ->
                    pack.toUiItem(
                        active = registry.activeDictionary == pack.packId,
                        bookTitle = pack.manifest.audioPayload?.bookId?.let { books[it]?.title }
                    )
                }
                // 降级优先级：登记表自身损坏 > 活动词典包文件缺失（两者都会让「已启用」名不副实）。
                val warning = when {
                    loaded.error != null -> LoadWarning.REGISTRY_DAMAGED
                    packs.activeDictionaryFileMissing() -> LoadWarning.ACTIVE_DICTIONARY_MISSING
                    else -> null
                }
                // 6-14/7-13：总占用与存储页同源 —— 都用扫盘实测，避免两个页面数字不一致。
                PackUiState(
                    items = items,
                    totalBytes = packs.totalBytes(),
                    // 7-9：把未登记目录与 .tmp 残留一并带出去，供包列表页显式列出 + 清理。
                    residuals = packs.residuals(),
                    loadWarning = warning
                )
            }
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(
                    items = snapshot.items,
                    totalBytes = snapshot.totalBytes,
                    residuals = snapshot.residuals,
                    loadWarning = snapshot.loadWarning
                )
            )
        }
    }

    /**
     * 安装一个 `.lrpack`（SAF 选中的 Uri）。
     *
     * 失败走**对话框**（message）而不是 Snackbar：装包失败的原因（哈希不符、版本过高、
     * 路径穿越）用户需要看清并据此换文件，一次性提示会被忽略。
     */
    fun installPack(uri: Uri) {
        if (mutableState.value.packs.installing) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(installing = true)
            )
            runCatching { withContext(Dispatchers.IO) { packs.install(uri) } }
                .onSuccess { installed ->
                    // 词典包首次安装会自动生效 → 必须让查词侧换源，否则旧库句柄还开着。
                    if (installed.type == PackType.DICTIONARY) dictionary.invalidate()
                    mutableState.value = mutableState.value.copy(
                        packs = mutableState.value.packs.copy(installing = false),
                        notice = string(
                            R.string.packs_installed,
                            installed.nameZh.ifBlank { installed.nameEn }
                        ),
                        noticeTone = StatusTone.SUCCESS
                    )
                    refreshPacks()
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        packs = mutableState.value.packs.copy(installing = false),
                        message = error.message ?: string(R.string.packs_install_failed),
                        messageTitle = string(R.string.packs_install_failed_title)
                    )
                }
        }
    }

    /** 切换当前词典包；[packId] 为 null = 恢复内置词典。 */
    fun setActiveDictionary(packId: String?) {
        viewModelScope.launch {
            val name = packId?.let { id ->
                mutableState.value.packs.items.firstOrNull { it.packId == id }?.let { packName(it) }
            } ?: string(R.string.packs_dictionary_builtin_name)
            runCatching { withContext(Dispatchers.IO) { packs.setActiveDictionary(packId) } }
                .onSuccess {
                    // 关键：不清句柄与 LRU 就是「切了等于没切」（旧词典的释义还在缓存里）。
                    dictionary.invalidate()
                    mutableState.value = mutableState.value.copy(
                        notice = if (packId == null) string(R.string.packs_dictionary_restored)
                        else string(R.string.packs_dictionary_activated, name),
                        noticeTone = StatusTone.SUCCESS
                    )
                    refreshPacks()
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        message = error.message ?: string(R.string.packs_switch_failed),
                        messageTitle = string(R.string.packs_switch_failed_title)
                    )
                }
        }
    }

    /** 卸载资源包；若是当前词典包则自动回退内置。 */
    /**
     * Q2-c03：从 GitHub Releases 拉取可用资源包列表。
     *
     * 只在用户点了「获取在线资源包」时联网；失败原因留在 `remote.error` 上，
     * 不弹对话框（可重试，且不打断正在看的包列表）。
     */
    fun loadRemotePacks() {
        if (mutableState.value.packs.remote.loading) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(
                    remote = mutableState.value.packs.remote.copy(loading = true, error = null)
                )
            )
            val result = updateRepository.fetchPackAssets()
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(
                    remote = result.fold(
                        onSuccess = { RemotePacksState(assets = it, fetched = true) },
                        onFailure = { RemotePacksState(error = it.message ?: "获取失败") }
                    )
                )
            )
        }
    }

    /**
     * Q2-c03：下载选中的远端资源包并交给同一条安装管线。
     *
     * 复用 [installPack]：安装中的禁用、成功提示、失败对话框、词典包换源都在那里，
     * 这里只负责「下载到本地文件」这一步。
     */
    fun installRemotePack(asset: GitHubReleaseParser.PackAsset) {
        if (mutableState.value.packs.remote.downloading != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(
                    remote = mutableState.value.packs.remote.copy(
                        downloading = asset.name,
                        error = null
                    )
                )
            )
            val outcome = updateRepository.downloadPack(asset) { _, _ -> }
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(
                    remote = mutableState.value.packs.remote.copy(
                        downloading = null,
                        error = outcome.exceptionOrNull()?.message
                    )
                )
            )
            outcome.getOrNull()?.let { file -> installPack(Uri.fromFile(file)) }
        }
    }

    fun uninstallPack(packId: String) {
        viewModelScope.launch {
            val item = mutableState.value.packs.items.firstOrNull { it.packId == packId }
            val wasActive = item?.active == true
            runCatching { withContext(Dispatchers.IO) { packs.uninstall(packId) } }
                .onSuccess {
                    if (wasActive) dictionary.invalidate()
                    mutableState.value = mutableState.value.copy(
                        notice = string(R.string.packs_uninstalled, item?.let { packName(it) } ?: packId),
                        noticeTone = StatusTone.DANGER
                    )
                    refreshPacks()
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        message = error.message ?: string(R.string.packs_uninstall_failed),
                        messageTitle = string(R.string.packs_uninstall_failed_title)
                    )
                }
        }
    }

    /** 手动重哈希校验一个包（安装时已对过账；这是用户主动复查的入口）。 */
    fun verifyPack(packId: String) {
        if (mutableState.value.packs.verifying != null) return
        viewModelScope.launch {
            val name = mutableState.value.packs.items.firstOrNull { it.packId == packId }?.let { packName(it) } ?: packId
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(verifying = packId)
            )
            val result = withContext(Dispatchers.IO) { packs.verify(packId) }
            mutableState.value = mutableState.value.copy(
                packs = mutableState.value.packs.copy(verifying = null)
            )
            if (result.ok) {
                mutableState.value = mutableState.value.copy(
                    notice = string(R.string.packs_verify_ok, name),
                    noticeTone = StatusTone.SUCCESS
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    message = (result as PackValidationResult.Rejected).reason,
                    messageTitle = string(R.string.packs_verify_failed_title)
                )
            }
        }
    }

    private fun packName(item: PackUiItem): String =
        item.nameZh.ifBlank { item.nameEn }

    /**
     * 清理未登记目录 / `.tmp` 残留（第四轮审查 7-9）。
     *
     * 与 `deleteOrphans` 同一纪律：删的是**报告里那些路径**，删除结果按实际释放量统计，
     * 完成后刷新包列表（占用数字随之下降）。
     */
    fun cleanPackResiduals() {
        if (mutableState.value.packs.residuals.isEmpty()) return
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { packs.cleanResiduals() }
            mutableState.value = mutableState.value.copy(
                notice = string(R.string.packs_residuals_cleared, formatStorageBytes(freed)),
                noticeTone = StatusTone.SUCCESS
            )
            refreshPacks()
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            // ⚠ 删书**必须**先取消该书的 AI 翻译 job，否则后台会继续跑一个已经删掉的书：
            // 它会往刚清掉的检查点目录里接着写（把检查点、句界侧车重新造出来，
            // 让这次删除变成「删了又回来」），并且跑完后还会写一条「译本已生成」的
            // Snackbar 指向一本不存在的书。
            //
            // job 表（aiTranslationJobs）已随实现搬进 [TranslationCoordinator]，
            // 本类只剩这一个取消入口——**不要**因为这里看着像多余的清理就删掉它。
            // 新增任何「按书挂后台任务」的机制时，也要在这里补上对应的取消。
            translationCoordinator.cancelForBookDeletion(book.id)
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
            // 删书连带删了该书的生词 → 防抖同步（同步的墓碑记录来自本地快照）。
            scheduleAutoSync()
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

    // --- 云同步（自托管服务端，F-160）-----------------------------------------
    //
    // 实现已抽到 [AppSyncCoordinator]（同步动作 / 自动同步 / 明细 / 正文 blob 四块）。
    // 这里**只保留同名转发**：签名、参数、返回类型、副作用与拆分前逐字一致，
    // 调用点（MainActivity / BookshelfScreen）零改动。

    /** 设置页改动后落盘；令牌不放这里（走 Android Keystore）。 */
    fun saveSyncSettings(settings: SyncSettings) = syncCoordinator.saveSyncSettings(settings)

    /**
     * 登录用界面传来的**当前输入**设置（不是已保存的旧值）：改了地址没点保存也能登录。
     * 落库由 AndroidSyncController.login 在成功后完成（失败不写设置）。
     */
    fun syncLogin(settings: SyncSettings, password: String) =
        syncCoordinator.syncLogin(settings, password)

    fun syncNow() = syncCoordinator.syncNow()

    /**
     * 本地数据变更（阅读进度/生词增删改/删书）**写盘成功后**调用，排一次 30 秒防抖同步。
     * 调度决策全在 :shared 的 AutoSyncScheduler；这里转发给 [AppSyncCoordinator]。
     */
    private fun scheduleAutoSync() = syncCoordinator.scheduleAutoSync()

    /**
     * 自动同步：冷启动一次 + 防抖到点各走这里（失败安静、与手动同步互斥）。
     * 实现见 [AppSyncCoordinator.runAutoSync]。
     */
    private fun runAutoSync() = syncCoordinator.runAutoSync()

    fun syncLogout() = syncCoordinator.syncLogout()

    /**
     * 重建同步明细：**纯本地、零网络**——只读书库/生词本快照与本地水位文件
     * （`filesDir/sync/{synced,outbox}.json`），未登录时也能算出进度/生词的三态。
     */
    fun refreshSyncDetail() = syncCoordinator.refreshSyncDetail()

    /**
     * 拉取云端书单：远端有完整正文、本机书库没有的书。
     * 顺带把云端正文清单（全部完整 blob 的 bookId → size）写进 [AppUiState.cloudBlobSizes]。
     */
    fun refreshCloudBooks() = syncCoordinator.refreshCloudBooks()

    /** 下载云端书籍正文 → 临时文件 → 重跑导入器 → 刷新书库/书单。 */
    fun downloadCloudBook(cloud: CloudBook) = syncCoordinator.downloadCloudBook(cloud)

    /**
     * 导入成功后自动上传源文件。未启用/未登录/老书没有留存源文件都静默跳过；
     * 只有真正的网络/服务端失败才发 Snackbar（规格：失败必须可见）。
     */
    private fun uploadBookSourceInBackground(book: Book) =
        syncCoordinator.uploadBookSourceInBackground(book)

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

    /**
     * 用当前对齐器重跑该书档案的对齐（**不重新翻译、不重新导入译本**）。
     *
     * 进度与反馈沿用 [attachTranslation] 那一套，不新造机制：
     * [AppUiState.attachingTranslation] 让书卡显示「对齐中…」并在期间禁用按钮
     * （整本重对齐是 CPU 密集的数十秒级操作，全程在 IO 上跑，界面不卡）；
     * 结果无论成败都出一条全局 Snackbar —— 「保存/长任务无反馈」是这个项目
     * 吃过的亏（AI 保存无反馈）。
     */
    fun realignTranslation(book: Book) {
        if (book.id in mutableState.value.attachingTranslation) return
        // 整本翻译在跑时档案正要被它重建，重对齐只会白跑一遍：让翻译那条路先收尾。
        if (book.id in mutableState.value.aiTranslationProgress) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                attachingTranslation = mutableState.value.attachingTranslation + book.id,
                notice = string(R.string.notice_translation_realigning, book.title)
            )
            val startedAt = System.currentTimeMillis()
            runCatching { translationRepository.realign(book) }
                .onSuccess { memory ->
                    if (memory == null) {
                        // 档案不存在/没有可还原的中文段落：一个字节都没写，如实报失败。
                        mutableState.value = mutableState.value.copy(
                            attachingTranslation = mutableState.value.attachingTranslation - book.id,
                            notice = string(R.string.notice_translation_realign_failed),
                            noticeTone = StatusTone.DANGER
                        )
                    } else {
                        library.saveTranslation(
                            book,
                            memory.translationBookId,
                            memory.translationTitle,
                            memory.alignedAt
                        )
                        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                        mutableState.value = mutableState.value.copy(
                            attachingTranslation = mutableState.value.attachingTranslation - book.id,
                            notice = string(
                                R.string.notice_translation_realigned,
                                memory.pairs.size,
                                seconds
                            ),
                            noticeTone = StatusTone.SUCCESS
                        )
                        refresh()
                    }
                }
                .onFailure {
                    mutableState.value = mutableState.value.copy(
                        attachingTranslation = mutableState.value.attachingTranslation - book.id,
                        notice = string(R.string.notice_translation_realign_failed),
                        noticeTone = StatusTone.DANGER
                    )
                }
        }
    }

    // --- AI 生成译本对照 / 手动 AI 全书翻译 / 句级重翻 ----------------------------
    //
    // 实现已抽到 [TranslationCoordinator]（含句级定点重翻）。这里只保留同名转发：
    // 签名、参数、返回类型、副作用与拆分前逐字一致，调用点零改动。
    //
    // 注意边界：**中文译本对照**（attach/detach/realign）与下面的 translationLookup
    // 仍在本类——它们是纯离线对齐/只读查询，与 AI 无关。

    /**
     * 「AI 生成译本」第一步：备齐术语表、估算规模，然后停在确认框等用户点头。
     * 已有 AI 译本的书走这里是重新生成（检查点复用，只补失败批）。
     */
    fun prepareAiTranslation(book: Book) = translationCoordinator.prepareAiTranslation(book)

    fun dismissAiTranslationPrepare() = translationCoordinator.dismissAiTranslationPrepare()

    /** 确认框点「开始生成」：逐章逐批翻译（检查点续跑）→ 对齐 → 落盘。 */
    fun startAiTranslation(book: Book, mode: String, styleNotes: String) =
        translationCoordinator.startAiTranslation(book, mode, styleNotes)

    /** 取消在跑的整本翻译；已完成批次的检查点保留，下次直接续跑。 */
    fun cancelAiTranslation(book: Book) = translationCoordinator.cancelAiTranslation(book)

    /** 「手动 AI 翻译」对话框数据：覆盖进度 + 风格说明，全部本地读取，不出网。 */
    fun prepareManualTranslation(book: Book) = translationCoordinator.prepareManualTranslation(book)

    fun dismissManualTranslationPrepare() =
        translationCoordinator.dismissManualTranslationPrepare()

    fun clearManualImportRejected() = translationCoordinator.clearManualImportRejected()

    /** 构建任务文件文本；就绪后置 [AppUiState.pendingManualExport] 让界面弹 SAF 面板。 */
    fun buildManualExport(book: Book, styleNotes: String) =
        translationCoordinator.buildManualExport(book, styleNotes)

    /** 界面弹保存面板前取走任务文件（内容暂存在协调器，供写入回调使用）。 */
    fun takeManualExport(): PendingManualExport? = translationCoordinator.takeManualExport()

    /** SAF 保存面板的回调：把构建好的任务文件写进用户选的位置。 */
    fun writeManualExport(uri: Uri) = translationCoordinator.writeManualExport(uri)

    /** 用户在 SAF 面板取消了保存：丢弃已构建的任务文件。 */
    fun cancelManualExport() = translationCoordinator.cancelManualExport()

    /** 导入外部 agent 的结果文件（可多选）；覆盖满 100% 则自动离线生成译本对照。 */
    fun importManualResults(book: Book, uris: List<Uri>) =
        translationCoordinator.importManualResults(book, uris)

    /**
     * 句级定点重翻：一次小请求替换档案里 [TranslationLookupResult.pairIndex] 那条句对的译文。
     * 只对句级命中开放；成功/失败经 [onDone] 回调，旧译文由界面保持不动。
     */
    fun retranslateTranslation(
        book: Book,
        result: TranslationLookupResult,
        feedback: String?,
        onDone: (Boolean) -> Unit
    ) = translationCoordinator.retranslateTranslation(book, result, feedback, onDone)

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
    suspend fun translateSentence(book: Book, sentence: String): SentenceTranslationResult =
        translationCoordinator.translateSentence(book, sentence)

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
                notice = string(R.string.notice_word_saved, entry.matchedPhrase ?: entry.headword),
                noticeTone = StatusTone.SUCCESS
            )
            rescheduleReviewReminders()
            scheduleAutoSync()
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
            scheduleAutoSync()
        }
    }

    fun reviewWord(id: String, remembered: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            runCatching { vocabulary.review(id, remembered, mutableState.value.reviewPace) }
                .onSuccess { words ->
                    mutableState.value = mutableState.value.copy(savedWords = words)
                    rescheduleReviewReminders()
                    // 复习等级/下次复习时间已落盘 → 防抖同步。
                    scheduleAutoSync()
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
