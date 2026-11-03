package com.linguareader.app.ai

import android.content.Context
import android.net.Uri
import com.linguareader.app.AiTranslationPrepare
import com.linguareader.app.AiTranslationProgress
import com.linguareader.app.AppUiState
import com.linguareader.app.ManualTranslationPrepare
import com.linguareader.app.PendingManualExport
import com.linguareader.app.R
import com.linguareader.app.StatusTone
import com.linguareader.app.data.Book
import com.linguareader.app.data.LibraryRepository
import com.linguareader.app.translation.TranslationLookupResult
import com.linguareader.app.translation.TranslationMatchLevel
import com.linguareader.app.translation.TranslationMemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 协调器与宿主之间的最小契约。与 `SyncHost` 同一形态。
 *
 * ## 为什么是接口，而不是直接传 `AppViewModel`
 *
 * 同 `SyncHost`：**为了让协调器能在不构造整个 ViewModel 的前提下单测**。
 * 手动翻译这条链有一堆纯状态分支——「抽不出段落时要说清楚」「覆盖满 100% 才自动
 * 生成」「拒绝条目要弹详情」「finally 里必须摘 job 否则防重闸会卡死下一轮」——
 * 它们全都可以用假宿主 + 临时目录在几毫秒内验掉，不必起 Robolectric。
 *
 * 附带好处：协调器不知道 `viewModelScope`、`mutableState`、`getApplication()` 的存在，
 * 这三个才是真正的 Android/ViewModel 耦合点，现在只剩宿主那一处实现。
 */
interface TranslationHost {
    /** 当前 UI 状态快照（只读）。 */
    fun snapshot(): AppUiState

    /** 以「当前状态 → 新状态」的方式写回（等价于 `MutableStateFlow.value = ...`）。 */
    fun updateState(transform: (AppUiState) -> AppUiState)

    /** 宿主生命周期内的协程作用域（ViewModel 里就是 viewModelScope）。 */
    val scope: CoroutineScope

    /** 译本生成/导入完成后重读书架（宿主侧顺带刷新 AI 状态与复习提醒）。 */
    fun refreshLibrary()
}

/**
 * AI 翻译协调器：**AI 生成译本对照 / 手动全书翻译 / 句级定点重翻 / 整句翻译**
 * 四块从 `AppViewModel` 抽出来后的归属地（原 L1407~L1920，约 500 行）。
 *
 * 边界（刻意留在宿主 AppViewModel）：
 * - **中文译本对照**的导入 / 移除 / 重对齐（`attachTranslation` / `detachTranslation` /
 *   `realignTranslation`）是**纯离线**的对齐动作，与 AI 无关，用的是
 *   [translationRepository] 而非 [aiTranslationRepository]，留在原分区。
 * - `translationLookup`（点词查对照）只读、无状态，同样留在宿主。
 * - 本类**不构造**任何仓库：全部从宿主注入，与拆分前的实例数、共享关系逐一对齐
 *   （`aiTranslationRepository` 仍是 BookScopedStore 里那**同一个**实例）。
 *
 * 拆分原则与 `AppSyncCoordinator` 一致：**提取类 + 保留转发**。公开方法的签名、
 * 参数、返回类型、副作用一字不改，调用点（MainActivity / BookshelfScreen /
 * ReaderScreen）零改动。
 */
class TranslationCoordinator(
    context: Context,
    /** 书库的 Android 壳：生成/导入完成后要把译本元数据写回书库。 */
    private val library: LibraryRepository,
    private val aiRepository: BookContextRepository,
    private val glossaryRepository: BookGlossaryRepository,
    private val aiTranslationRepository: AiTranslationRepository,
    private val translationRepository: TranslationMemoryRepository,
    private val host: TranslationHost
) {
    private val appContext = context.applicationContext

    /** 整本翻译的在跑任务，按书 id 取消用。抽出来之后这里是它的唯一知情者。 */
    private val aiTranslationJobs = mutableMapOf<String, Job>()

    /** 已取走、等 SAF 写入回调的手动任务文件（takeManualExport 与 writeManualExport 之间）。 */
    private var manualExportInFlight: PendingManualExport? = null

    /** 整本翻译的「记住上次选择」：翻译模式（标准/精译）。 */
    private val aiTranslationPrefs =
        appContext.getSharedPreferences("ai_translation", Context.MODE_PRIVATE)

    /** 句级定点重翻的在跑书（每本同时最多一次，防双击双扣费）。 */
    private val retranslateInFlight = mutableSetOf<String>()

    /** 取应用资源文案（协调器里没有 Composable 上下文）。 */
    private fun string(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    /**
     * 取消某本书在跑的任何翻译任务。
     *
     * **删书必须调它**：不取消的话任务会继续往刚清掉的检查点目录里写（原实现里
     * 这一步是 `AppViewModel.deleteBook` 直接 `aiTranslationJobs.remove(id)?.cancel()`，
     * 抽出来后 job 表只有这里知道，所以对外开了这个入口）。
     */
    fun cancelForBookDeletion(bookId: String) {
        aiTranslationJobs.remove(bookId)?.cancel()
    }

    private fun setAiTranslationProgress(bookId: String, progress: AiTranslationProgress) {
        host.updateState {
            it.copy(aiTranslationProgress = it.aiTranslationProgress + (bookId to progress))
        }
    }

    private fun clearAiTranslationProgress(bookId: String) {
        host.updateState { it.copy(aiTranslationProgress = it.aiTranslationProgress - bookId) }
    }

    // --- AI 生成译本对照 -------------------------------------------------------

    /**
     * 「AI 生成译本」第一步：备齐术语表（缺失时先生成语境档案并导入）、估算
     * 规模，然后停在确认框等用户点头。全程除语境档案生成本身外不出网。
     * 已有 AI 译本的书走这里是重新生成（检查点复用，只补失败批）；
     * 出版译本不提供重生成，但书架入口对它本来只出删除确认框，这里只是兜底。
     */
    fun prepareAiTranslation(book: Book) {
        if (book.id in host.snapshot().aiTranslationProgress) return
        if (book.hasTranslation && !book.isAiTranslation) return
        val settings = host.snapshot().aiSettings
        if (!settings.powerEnabled || !settings.remoteReady) {
            host.updateState { it.copy(notice = string(R.string.notice_translation_ai_need_key)) }
            return
        }
        host.scope.launch {
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
                host.updateState {
                    it.copy(
                        aiTranslationProgress = it.aiTranslationProgress - book.id,
                        aiTranslationPrepare = prepare
                    )
                }
            }.onFailure {
                host.updateState {
                    it.copy(
                        aiTranslationProgress = it.aiTranslationProgress - book.id,
                        notice = string(
                            R.string.notice_translation_ai_failed,
                            it.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            }
        }
    }

    fun dismissAiTranslationPrepare() {
        if (host.snapshot().aiTranslationPrepare != null) {
            host.updateState { it.copy(aiTranslationPrepare = null) }
        }
    }

    /** 确认框点「开始生成」：逐章逐批翻译（检查点续跑）→ 对齐 → 落盘。 */
    fun startAiTranslation(book: Book, mode: String, styleNotes: String) {
        dismissAiTranslationPrepare()
        if (book.id in host.snapshot().aiTranslationProgress) return
        val settings = host.snapshot().aiSettings
        if (!settings.powerEnabled || !settings.remoteReady) {
            host.updateState { it.copy(notice = string(R.string.notice_translation_ai_need_key)) }
            return
        }
        val safeMode = if (mode == AiTranslationRepository.MODE_POLISH) {
            AiTranslationRepository.MODE_POLISH
        } else {
            AiTranslationRepository.MODE_STANDARD
        }
        val polish = safeMode == AiTranslationRepository.MODE_POLISH
        aiTranslationPrefs.edit().putString(KEY_TRANSLATION_MODE, safeMode).apply()
        aiTranslationJobs[book.id] = host.scope.launch {
            val startedAt = System.currentTimeMillis()
            runCatching { aiTranslationRepository.saveStyle(book.id, styleNotes) }
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, polish = polish))
            host.updateState {
                it.copy(notice = string(R.string.notice_translation_ai_started, book.title))
            }
            // 单批用尽重试后不再中止整本书：该批整段保留英文原文继续跑，末尾汇总告知用户。
            // 一个段落可能被拆成多个译块、跨多批（超长段），所以按「章 + 原段」去重计数；
            // 不能拿 batch.paragraphs.size（那是译块数）累加。
            val untranslatedParagraphs = mutableSetOf<Pair<Int, Int>>()
            try {
                val translationBook = aiTranslationRepository.translateBook(
                    book,
                    mode = safeMode,
                    styleNotes = styleNotes.trim().ifBlank { null },
                    onBatchFailed = { batch, _ ->
                        batch.sourceParagraphIndices.forEach { index ->
                            untranslatedParagraphs += batch.chapterIndex to index
                        }
                    }
                ) { percent ->
                    setAiTranslationProgress(
                        book.id,
                        AiTranslationProgress(percent = percent, polish = polish)
                    )
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
                host.updateState {
                    it.copy(
                        notice = if (untranslatedParagraphs.isNotEmpty()) {
                            string(
                                R.string.notice_translation_ready_partial,
                                result.memory.pairs.size,
                                seconds,
                                untranslatedParagraphs.size
                            )
                        } else {
                            string(R.string.notice_translation_ready, result.memory.pairs.size, seconds)
                        },
                        noticeTone = if (untranslatedParagraphs.isNotEmpty()) StatusTone.NEUTRAL
                        else StatusTone.SUCCESS
                    )
                }
                host.refreshLibrary()
            } catch (cancelled: CancellationException) {
                host.updateState {
                    it.copy(notice = string(R.string.notice_translation_ai_cancelled))
                }
            } catch (aborted: AiTranslationAbortedException) {
                // 连败断路器（AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES）：
                // 系统性失败提前止损。检查点都在，排除故障后重新生成即续跑。
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_ai_aborted,
                            book.title,
                            AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } catch (failed: Throwable) {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_ai_failed,
                            failed.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } finally {
                aiTranslationJobs.remove(book.id)
                clearAiTranslationProgress(book.id)
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
        if (book.id in host.snapshot().aiTranslationProgress) return
        if (book.hasTranslation && !book.isAiTranslation) return
        aiTranslationJobs[book.id] = host.scope.launch {
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                val coverage = aiTranslationRepository.manualCoverage(book)
                if (coverage.totalBatches == 0) {
                    // 抽不出任何段落的书（如无文字层的扫描 PDF）没有可翻译的东西，
                    // 直接说明，别让用户困在「已收 0/0 批」里永远无法完成。
                    host.updateState {
                        it.copy(notice = string(R.string.notice_translation_manual_empty))
                    }
                    return@launch
                }
                // 风格说明要读盘（suspend），必须先取出来再进 updateState 的纯 lambda。
                val styleNotes = aiTranslationRepository.loadStyle(book.id).orEmpty()
                host.updateState {
                    it.copy(
                        manualTranslationPrepare = ManualTranslationPrepare(
                            book = book,
                            batches = coverage.totalBatches,
                            coveredBatches = coverage.coveredBatches,
                            styleNotes = styleNotes
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_ai_failed,
                            failed.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } finally {
                aiTranslationJobs.remove(book.id)
                clearAiTranslationProgress(book.id)
            }
        }
    }

    fun dismissManualTranslationPrepare() {
        if (host.snapshot().manualTranslationPrepare != null) {
            host.updateState { it.copy(manualTranslationPrepare = null) }
        }
    }

    fun clearManualImportRejected() {
        if (host.snapshot().manualImportRejected != null) {
            host.updateState { it.copy(manualImportRejected = null) }
        }
    }

    /**
     * 构建任务文件文本（全书抽取，大书要几秒），就绪后置 [PendingManualExport]
     * 让界面弹 SAF 保存面板。风格说明顺手保存，与在线路径同一存储位。
     * 构建阶段登记进 [aiTranslationJobs]，书卡上的「取消生成」真有效。
     */
    fun buildManualExport(book: Book, styleNotes: String) {
        dismissManualTranslationPrepare()
        if (book.id in host.snapshot().aiTranslationProgress) return
        aiTranslationJobs[book.id] = host.scope.launch {
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                aiTranslationRepository.saveStyle(book.id, styleNotes)
                val task = aiTranslationRepository.buildManualTask(book, styleNotes.trim().ifBlank { null })
                host.updateState {
                    it.copy(
                        pendingManualExport = PendingManualExport(
                            bookId = book.id,
                            fileName = "${book.title}-manual-translation.json"
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_"),
                            content = task.toString(),
                            batches = task.optJSONArray("batches")?.length() ?: 0
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_manual_export_failed,
                            failed.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } finally {
                aiTranslationJobs.remove(book.id)
                clearAiTranslationProgress(book.id)
            }
        }
    }

    /**
     * 界面弹保存面板前取走任务文件：状态即刻置空，旋转屏幕重建组合后
     * [AppUiState.pendingManualExport] 已不在，LaunchedEffect 不会重放出
     * 第二个面板。内容暂存 [manualExportInFlight] 供写入回调使用。
     */
    fun takeManualExport(): PendingManualExport? {
        val pending = host.snapshot().pendingManualExport ?: return null
        host.updateState { it.copy(pendingManualExport = null) }
        manualExportInFlight = pending
        return pending
    }

    /** SAF 保存面板的回调：把构建好的任务文件写进用户选的位置。 */
    fun writeManualExport(uri: Uri) {
        val pending = manualExportInFlight ?: return
        manualExportInFlight = null
        host.scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri, "w")
                        ?.bufferedWriter()
                        ?.use { it.write(pending.content) }
                        ?: error("无法打开导出文件")
                }
            }.onSuccess {
                host.updateState {
                    it.copy(notice = string(R.string.notice_translation_manual_exported, pending.batches))
                }
            }.onFailure {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_manual_export_failed,
                            it.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            }
        }
    }

    /** 用户在 SAF 面板取消了保存：丢弃已构建的任务文件。 */
    fun cancelManualExport() {
        manualExportInFlight = null
        host.updateState { it.copy(pendingManualExport = null) }
    }

    /**
     * 导入外部 agent 的结果文件（可多选）：读文本 → 校验逐批落检查点 →
     * 覆盖满 100% 则自动离线生成译本对照（一个网络请求都不发）。
     * 导入阶段登记进 [aiTranslationJobs]，书卡上的「取消生成」真有效。
     */
    fun importManualResults(book: Book, uris: List<Uri>) {
        dismissManualTranslationPrepare()
        if (book.id in host.snapshot().aiTranslationProgress) return
        aiTranslationJobs[book.id] = host.scope.launch {
            var readyToComplete = false
            setAiTranslationProgress(book.id, AiTranslationProgress(percent = 0, preparing = true))
            try {
                if (aiTranslationRepository.manualCoverage(book).totalBatches == 0) {
                    host.updateState {
                        it.copy(notice = string(R.string.notice_translation_manual_empty))
                    }
                    return@launch
                }
                val texts = uris.map { uri ->
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("无法读取所选文件")
                    }
                }
                val report = aiTranslationRepository.importManualResults(book, texts)
                if (report.coverage.complete) {
                    readyToComplete = true
                    host.updateState {
                        it.copy(
                            notice = string(
                                R.string.notice_translation_manual_imported_done,
                                report.coverage.totalBatches
                            )
                        )
                    }
                } else {
                    host.updateState {
                        it.copy(
                            notice = string(
                                R.string.notice_translation_manual_imported_partial,
                                report.coverage.coveredBatches,
                                report.coverage.totalBatches,
                                report.rejected.size
                            ),
                            noticeTone = if (report.acceptedBatches == 0) StatusTone.DANGER
                            else StatusTone.NEUTRAL
                        )
                    }
                }
                if (report.rejected.isNotEmpty()) {
                    // 拒绝详情立刻弹对话框逐条展示——「拒绝 N 条」的条数不告诉
                    // 用户该让 agent 改什么（典型错误：段落编号被重新编号）。
                    host.updateState { it.copy(manualImportRejected = report.rejected) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_manual_import_failed,
                            failed.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } finally {
                aiTranslationJobs.remove(book.id)
                if (!readyToComplete) clearAiTranslationProgress(book.id)
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
        aiTranslationJobs[book.id] = host.scope.launch {
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
                host.updateState {
                    it.copy(
                        notice = string(R.string.notice_translation_ready, result.memory.pairs.size, seconds),
                        noticeTone = StatusTone.SUCCESS
                    )
                }
                host.refreshLibrary()
            } catch (cancelled: CancellationException) {
                host.updateState {
                    it.copy(notice = string(R.string.notice_translation_ai_cancelled))
                }
            } catch (failed: Throwable) {
                host.updateState {
                    it.copy(
                        notice = string(
                            R.string.notice_translation_ai_failed,
                            failed.message ?: string(R.string.message_ai_context_failed)
                        ),
                        noticeTone = StatusTone.DANGER
                    )
                }
            } finally {
                aiTranslationJobs.remove(book.id)
                clearAiTranslationProgress(book.id)
            }
        }
    }

    // --- 句级定点重翻 -----------------------------------------------------------

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
        val settings = host.snapshot().aiSettings
        if (!settings.powerEnabled || !settings.remoteReady ||
            !book.isAiTranslation ||
            result.matchLevel != TranslationMatchLevel.SENTENCE || result.pairIndex < 0 ||
            book.id in retranslateInFlight || book.id in host.snapshot().aiTranslationProgress
        ) {
            onDone(false)
            return
        }
        retranslateInFlight.add(book.id)
        host.scope.launch {
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

    // --- 整句翻译（sentence translation） --------------------------------------

    /**
     * Translates a sentence with the configured model provider, applying the
     * book's enabled glossary terms. The returned [SentenceTranslationResult]
     * carries the provider name so the UI can label the real source.
     */
    suspend fun translateSentence(book: Book, sentence: String): SentenceTranslationResult {
        val settings = host.snapshot().aiSettings
        val translator = SentenceTranslatorFactory.from(settings)
            ?: error("未启用整句翻译（先在 AI 中心配置并保存一个服务商）")
        val glossary = glossaryRepository.load(book.id)
        return SentenceTranslationResult(
            text = translator.translateSentence(sentence, glossary),
            provider = translator.displayName
        )
    }

    private companion object {
        /** 「记住上次选择」的翻译模式（标准/精译）在 SharedPreferences 里的键。 */
        const val KEY_TRANSLATION_MODE = "mode"
    }
}
