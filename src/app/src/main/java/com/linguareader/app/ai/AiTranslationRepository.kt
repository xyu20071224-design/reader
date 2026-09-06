package com.linguareader.app.ai

import com.linguareader.app.data.BookScopedStore
import android.content.Context
import com.linguareader.app.R
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.shared.importer.escapeHtml
import com.linguareader.app.tts.TtsTextExtractor
import com.linguareader.shared.ai.ManualTranslationIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 整本书翻译（生成译本对照）的编排层。
 *
 * 流程：用 [TtsTextExtractor] 抽全书叶级段落（与对齐引擎完全同源）→
 * [AiBookTranslator.groupIntoBatches] 按段落分批 → 逐批调 DeepSeek（术语表注入 +
 * 上一批译文衔接）→ 批后自检 → **每批成功即原子写检查点**（仿
 * [SpeakerTagRepository]：tmp+rename+Mutex，一章多批各一个小文件）→
 * 全部完成后把译文写成 XHTML 章节文件，构造一个不上书架的合成 [Book]，
 * 由调用方交给 `TranslationMemoryRepository.attachGenerated` 对齐落盘。
 *
 * 中断恢复是硬需求：整本书几十次串行请求在手机上是小时级，检查点让
 * 取消、进程被杀、断网失败都只损失当前一批。
 */
class AiTranslationRepository(
    context: Context,
    private val settingsStore: AiSettingsStore,
    private val glossaryRepository: BookGlossaryRepository,
    /**
     * 重试退避（测试注入空实现，避免单测真的睡过去）。
     * 必须排在 chatClientFactory 之前：后者要留在末位，才能继续用尾随 lambda 构造。
     */
    private val retryDelay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** 翻译批量出口工厂（测试注入假客户端用）。 */
    private val chatClientFactory: (AiSettings) -> AiTranslationChatClient =
        { AiTranslators.forSettings(it) }
) : BookScopedStore {
    private val appContext = context.applicationContext
    private val checkpointDir = File(appContext.filesDir, "ai/ai-translations")
    private val translationsDir = File(appContext.filesDir, "translations")
    private val mutex = Mutex()

    /** 确认框展示用的规模估算（全部是本地抽取，无出网）。 */
    data class Estimate(
        val chapters: Int,
        val batches: Int,
        /** 已启用的术语条数（用户视角的术语表规模）。 */
        val glossaryTerms: Int,
        /** 实际会注入 prompt 的术语条数（手动优先，超出上限的不生效）。 */
        val glossaryInjected: Int,
        /** 超长段落个数（见 [AiBookTranslator.OVERSIZED_PARAGRAPH_CHARS]）。 */
        val oversizedParagraphs: Int
    )

    suspend fun estimate(book: Book): Estimate = withContext(Dispatchers.IO) {
        val extractor = TtsTextExtractor()
        var batches = 0
        var oversized = 0
        book.chapters.indices.forEach { chapterIndex ->
            val blocks = extractor.chapter(book, chapterIndex).blocks
            batches += AiBookTranslator.groupIntoBatches(chapterIndex, blocks).size
            oversized += blocks.count { it.length > AiBookTranslator.OVERSIZED_PARAGRAPH_CHARS }
        }
        val glossary = glossaryRepository.load(book.id)
        Estimate(
            chapters = book.chapters.size,
            batches = batches,
            glossaryTerms = glossary.entries.count { it.enabled },
            glossaryInjected = AiBookTranslator.injectedGlossaryCount(glossary.entries),
            oversizedParagraphs = oversized
        )
    }

    // --- 手动 AI 全书翻译（导出任务文件 / 导入外部 agent 结果） -----------------
    //
    // 与在线翻译共用同一条批次规划、同一套校验、同一批检查点文件，只是
    // 请求/响应不再走 HTTP，而是用户手动把任务文件交给外部 AI agent、把结果
    // 文件搬回来。全程本地计算，不出网。

    /**
     * 导出手动翻译任务文件：全书批次 + 术语表（与 prompt 注入同口径）+ 风格
     * 说明 + 给 agent 的输出契约。已有有效检查点的批次打 hasCheckpoint 标，
     * agent 按说明跳过——在线翻译中断后可以只手动补翻剩余批次。
     */
    suspend fun buildManualTask(book: Book, styleNotes: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val glossary = glossaryRepository.load(book.id).entries.filter { it.enabled }
            val extractor = TtsTextExtractor()
            val checkpoints = File(checkpointDir, book.id)
            val batches = book.chapters.indices.flatMap { chapterIndex ->
                val blocks = extractor.chapter(book, chapterIndex).blocks
                AiBookTranslator.groupIntoBatches(chapterIndex, blocks).map { batch ->
                    val hash = AiBookTranslator.sourceHash(batch.paragraphs)
                    ManualTranslationIo.ManualTaskBatch(
                        chapterTitle = book.chapters[chapterIndex].title,
                        sourceHash = hash,
                        batch = batch,
                        hasCheckpoint = readCheckpoint(checkpoints, batch, hash) != null
                    )
                }
            }
            ManualTranslationIo.buildTask(
                bookId = book.id,
                bookTitle = book.title,
                glossary = glossary,
                styleNotes = styleNotes,
                batches = batches
            )
        }

    /** 手动导入的进度报告：批次数按全书当前批次规划口径统计。 */
    data class ManualCoverage(
        /** 已有有效检查点（含历史在线批次 + 已导入的手动批次）的批次数。 */
        val coveredBatches: Int,
        val totalBatches: Int
    ) {
        val complete: Boolean get() = totalBatches > 0 && coveredBatches >= totalBatches
    }

    /** 全书检查点覆盖进度（手动翻译对话框展示「已收 X/Y 批」用）。 */
    suspend fun manualCoverage(book: Book): ManualCoverage = withContext(Dispatchers.IO) {
        val checkpoints = File(checkpointDir, book.id)
        val extractor = TtsTextExtractor()
        val batches = book.chapters.indices.flatMap { chapterIndex ->
            AiBookTranslator.groupIntoBatches(chapterIndex, extractor.chapter(book, chapterIndex).blocks)
        }
        ManualCoverage(
            coveredBatches = batches.count { batch ->
                readCheckpoint(checkpoints, batch, AiBookTranslator.sourceHash(batch.paragraphs)) != null
            },
            totalBatches = batches.size
        )
    }

    /**
     * 导入外部 agent 产出的结果文件（可多文件、可多次导入合并——落点是检查点）。
     * 结构性错误按文件整体拒绝，单批错误（批次对不上、指纹不符、硬校验不过）
     * 逐条进 [ManualImportReport.rejected]，不影响其余批次。校验只保硬校验
     * （结构完整、段数、非空白），软校验不拦——外部 agent 没有「带原因重试」
     * 的机会，把数字锚点这类质量偏好拦在门外只会让用户对着拒绝清单干瞪眼。
     */
    data class ManualImportReport(
        val acceptedBatches: Int,
        /** 逐条拒绝原因（面向用户）。 */
        val rejected: List<String>,
        val coverage: ManualCoverage
    )

    suspend fun importManualResults(book: Book, texts: List<String>): ManualImportReport =
        withContext(Dispatchers.IO) {
            val glossary = glossaryRepository.load(book.id).entries.filter { it.enabled }
            val keepOriginalTerms = glossary.filter { it.translation.isBlank() }.map { it.term }
            val extractor = TtsTextExtractor()
            val plan = book.chapters.indices.flatMap { chapterIndex ->
                AiBookTranslator.groupIntoBatches(chapterIndex, extractor.chapter(book, chapterIndex).blocks)
            }
            val byKey = plan.associateBy { it.chapterIndex to it.batchIndex }
            val checkpoints = File(checkpointDir, book.id)
            val rejected = mutableListOf<String>()
            var accepted = 0

            texts.forEachIndexed { fileIndex, text ->
                val entries = try {
                    ManualTranslationIo.parseResults(text)
                } catch (error: IllegalArgumentException) {
                    rejected += "文件 ${fileIndex + 1}：${error.message}"
                    return@forEachIndexed
                }
                for (entry in entries) {
                    val label = "批次 ${entry.chapterIndex}-${entry.batchIndex}"
                    val batch = byKey[entry.chapterIndex to entry.batchIndex]
                    if (batch == null) {
                        rejected += "$label：不属于这本书（检查 bookId 或全书批次规划是否已变化）"
                        continue
                    }
                    val hash = AiBookTranslator.sourceHash(batch.paragraphs)
                    if (entry.sourceHash != null && entry.sourceHash != hash) {
                        rejected += "$label：原文指纹不符（书重新导入过，或结果文件是旧任务产的）"
                        continue
                    }
                    val translations = try {
                        AiBookTranslator.extractValidated(
                            entry.segments, batch, keepOriginalTerms, strict = false
                        )
                    } catch (error: AiRequestException) {
                        rejected += "$label：${error.message}"
                        continue
                    }
                    storeCheckpoint(checkpoints, batch, hash, translations, ManualTranslationIo.MODE_MANUAL)
                    accepted++
                }
            }
            ManualImportReport(accepted, rejected, manualCoverage(book))
        }

    /**
     * 只凭检查点离线组装整本译作：一个网络请求都不发，批次规划或指纹对不上
     * 即抛 [ManualTranslationIncompleteException]（调用方只在覆盖满 100% 时
     * 走到这里，这是最后一道防御）。
     */
    suspend fun completeFromCheckpoints(book: Book, providerName: String): Book =
        withContext(Dispatchers.IO) {
            val checkpoints = File(checkpointDir, book.id)
            val extractor = TtsTextExtractor()
            val translatedChapters = book.chapters.indices.map { chapterIndex ->
                val blocks = extractor.chapter(book, chapterIndex).blocks
                AiBookTranslator.groupIntoBatches(chapterIndex, blocks).flatMap { batch ->
                    val hash = AiBookTranslator.sourceHash(batch.paragraphs)
                    readCheckpoint(checkpoints, batch, hash)
                        ?: throw ManualTranslationIncompleteException(
                            "批次 ${batch.chapterIndex}-${batch.batchIndex} 没有可用译文，" +
                                "请先在「手动 AI 翻译」里补齐并导入"
                        )
                }
            }
            writeTranslationBook(
                book,
                translatedChapters,
                providerName = providerName,
                titleOverride = appContext.getString(R.string.translation_manual_title)
            )
        }

    /**
     * 逐章逐批翻译整本书。译文章节文件写好后返回合成译本 [Book]（id 带
     * `ai-` 前缀，不进 `files/books/`、不上书架）；对齐由调用方接续完成。
     *
     * [mode] 见 [MODE_POLISH]；[styleNotes] 为本书风格说明（可空），随每批
     * 初翻/精修 prompt 注入。[onProgress] 在每批完成后回调已完成批次的百分比
     * （0-100）。
     */
    suspend fun translateBook(
        book: Book,
        mode: String = MODE_STANDARD,
        styleNotes: String? = null,
        /**
         * 某一批用尽重试后仍然失败时回调（每批至多一次）。整本书不再因此中止，
         * 该批以英文原文占位继续往下跑，调用方可据此提示「N 段未翻译」。
         */
        onBatchFailed: (suspend (batch: TranslationBatch, error: Throwable) -> Unit)? = null,
        onProgress: suspend (percent: Int) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        val client = chatClientFactory(settings)
        val glossary = glossaryRepository.load(book.id).entries.filter { it.enabled }
        val keepOriginalTerms = glossary.filter { it.translation.isBlank() }.map { it.term }
        val extractor = TtsTextExtractor()
        val checkpoints = File(checkpointDir, book.id)

        // 先做全书的批次规划（纯本地），进度才有确定的总数。
        val chapterBatches = book.chapters.indices.map { chapterIndex ->
            val blocks = extractor.chapter(book, chapterIndex).blocks
            AiBookTranslator.groupIntoBatches(chapterIndex, blocks)
        }
        val totalBatches = chapterBatches.sumOf { it.size }.coerceAtLeast(1)
        var finishedBatches = 0
        var tail: String? = null
        // 连败断路器：单批失败占位继续（不拖垮整本），但连续多批重试耗尽说明
        // 是系统性失败（坏 Key/欠费/断网），再跑下去只会把剩余章节全烧成占位。
        var consecutiveFailures = 0

        val translatedChapters = chapterBatches.mapIndexed { chapterIndex, batches ->
            val chapter = book.chapters[chapterIndex]
            val paragraphs = batches.flatMap { batch ->
                val translations = try {
                    val done = restoreOrTranslate(
                        client, checkpoints, book, chapter, glossary, keepOriginalTerms,
                        tail, batch, mode, styleNotes
                    )
                    // 只有成功的批次才更新衔接上文，免得把英文原文当「前情提要」喂回去。
                    consecutiveFailures = 0
                    tail = done.lastOrNull() ?: tail
                    done
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    consecutiveFailures++
                    onBatchFailed?.invoke(batch, error)
                    if (consecutiveFailures >= AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES) {
                        throw AiTranslationAbortedException(
                            "连续 ${AiBookTranslator.MAX_CONSECUTIVE_BATCH_FAILURES} 批翻译失败" +
                                "（最后错误：${error.message}），已中止整本；已完成进度已保留"
                        )
                    }
                    // 用英文原文占位：段落数必须与英文侧严格 1:1，否则整章对照错位；
                    // 且 xhtmlFor 会滤掉空串，空占位会直接改变段数。
                    batch.paragraphs
                }
                finishedBatches++
                onProgress(finishedBatches * 100 / totalBatches)
                translations
            }
            paragraphs
        }
        writeTranslationBook(book, translatedChapters, settings.providerDisplayName)
    }

    /** 删除一本书的全部翻译检查点与风格说明（删书时随书清理）。 */
    override val storeId: String = "ai/ai-translations"

    override fun storageRoots(): List<File> = listOf(checkpointDir)

    override suspend fun deleteBookData(book: Book) { delete(book.id) }

    fun delete(bookId: String) {
        if (bookId.isBlank()) return
        File(checkpointDir, bookId).deleteRecursively()
    }

    // --- 书级风格说明 ---------------------------------------------------------

    /** 本书风格说明（确认框里用户输入的可选文本），随每次请求注入 prompt。 */
    suspend fun loadStyle(bookId: String): String? = withContext(Dispatchers.IO) {
        val file = styleFile(bookId)
        if (!file.isFile) return@withContext null
        runCatching {
            JSONObject(file.readText()).optString("notes").trim().ifBlank { null }
        }.getOrNull()
    }

    suspend fun saveStyle(bookId: String, notes: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = styleFile(bookId)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("notes", notes.trim())
                .put("updatedAt", System.currentTimeMillis())
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun styleFile(bookId: String): File = File(checkpointDir, "$bookId/style.json")

    // --- 句级定点重翻 ---------------------------------------------------------

    /**
     * 重译一个句子并返回通过自检的新译文。落盘由调用方接
     * `TranslationMemoryRepository.replaceSentenceTranslation`（本类不持有档案）；
     * 反馈可空 = 原样重试换一次结果。失败抛 [AiRequestException]，调用方保持
     * 旧译文不动。
     */
    suspend fun retranslateText(
        book: Book,
        enSentence: String,
        enParagraph: String,
        currentZh: String,
        feedback: String?
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        val client = chatClientFactory(settings)
        val glossary = glossaryRepository.load(book.id).entries.filter { it.enabled }
        val keepOriginalTerms = glossary.filter { it.translation.isBlank() }.map { it.term }
        val user = AiBookTranslator.buildRetranslateUserPrompt(
            enSentence = enSentence,
            enParagraph = enParagraph,
            currentZh = currentZh,
            glossary = glossary,
            styleNotes = loadStyle(book.id),
            feedback = feedback?.trim()?.ifBlank { null }
        )
        val json = client.translateSegments(AiBookTranslator.RETRANSLATE_SYSTEM_PROMPT, user)
        val newZh = json.optString("translation")
        AiBookTranslator.validateRetranslation(enSentence, newZh, keepOriginalTerms)
        newZh.trim()
    }

    /** 命中有效检查点直接复用；否则调 DeepSeek 翻译（自检失败带原因重试一次）。 */
    private suspend fun restoreOrTranslate(
        client: AiTranslationChatClient,
        checkpoints: File,
        book: Book,
        chapter: Chapter,
        glossary: List<GlossaryEntry>,
        keepOriginalTerms: List<String>,
        tail: String?,
        batch: TranslationBatch,
        mode: String,
        styleNotes: String?
    ): List<String> {
        val hash = AiBookTranslator.sourceHash(batch.paragraphs)
        readCheckpoint(checkpoints, batch, hash)?.let { return it }
        return translateBatch(
            client, checkpoints, hash, book, chapter, glossary, keepOriginalTerms,
            tail, batch, mode, styleNotes, retryError = null
        )
    }

    /** 一批的完整产出：初翻（失败带原因重试一次）→ polish 模式再精修一遍。 */
    private suspend fun translateBatch(
        client: AiTranslationChatClient,
        checkpoints: File,
        hash: String,
        book: Book,
        chapter: Chapter,
        glossary: List<GlossaryEntry>,
        keepOriginalTerms: List<String>,
        tail: String?,
        batch: TranslationBatch,
        mode: String,
        styleNotes: String?,
        retryError: String?
    ): List<String> {
        val draft = requestSegments(
            client, checkpoints, hash, book, chapter, glossary, keepOriginalTerms,
            tail, batch, mode, styleNotes, retryError,
            isPolish = false, draft = null
        )
        if (mode != MODE_POLISH) return draft
        return requestSegments(
            client, checkpoints, hash, book, chapter, glossary, keepOriginalTerms,
            tail, batch, mode, styleNotes, retryError,
            isPolish = true, draft = draft
        )
    }

    /**
     * 一遍请求（初翻或精修）+ 自检 + 带原因重试一次 + 检查点落盘。
     * [isPolish] 切换 system prompt 与 user prompt 构造；[draft] 仅精修用。
     */
    private suspend fun requestSegments(
        client: AiTranslationChatClient,
        checkpoints: File,
        hash: String,
        book: Book,
        chapter: Chapter,
        glossary: List<GlossaryEntry>,
        keepOriginalTerms: List<String>,
        tail: String?,
        batch: TranslationBatch,
        mode: String,
        styleNotes: String?,
        retryError: String?,
        isPolish: Boolean,
        draft: List<String>?
    ): List<String> {
        val systemPrompt: String
        val user: String
        if (isPolish) {
            systemPrompt = AiBookTranslator.POLISH_SYSTEM_PROMPT
            user = AiBookTranslator.buildPolishUserPrompt(
                bookTitle = book.title,
                chapterTitle = chapter.title,
                glossary = glossary,
                batch = batch,
                draftTranslations = requireNotNull(draft),
                styleNotes = styleNotes,
                retryError = retryError
            )
        } else {
            systemPrompt = AiBookTranslator.TRANSLATION_SYSTEM_PROMPT
            user = AiBookTranslator.buildUserPrompt(
                bookTitle = book.title,
                chapterTitle = chapter.title,
                glossary = glossary,
                previousTail = tail,
                batch = batch,
                retryError = retryError,
                styleNotes = styleNotes
            )
        }
        val json = try {
            client.translateSegments(systemPrompt, user)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (retryError == null) {
                // 立刻原样重发在 429/5xx 上等于二次撞墙，先退避再试。
                retryDelay(AiBookTranslator.retryDelayMillis(error.message))
                return requestSegments(
                    client, checkpoints, hash, book, chapter, glossary, keepOriginalTerms,
                    tail, batch, mode, styleNotes, retryError = "请求失败：${error.message}",
                    isPolish, draft
                )
            }
            throw error
        }
        return try {
            // 首轮严格自检；重试轮只保硬校验（结构完整即收下），
            // 免得数字锚点这类质量偏好把整本书拖垮。
            val translations = AiBookTranslator.extractValidated(
                json, batch, keepOriginalTerms, strict = retryError == null
            )
            storeCheckpoint(checkpoints, batch, hash, translations, mode)
            translations
        } catch (error: AiRequestException) {
            if (retryError == null) {
                retryDelay(AiBookTranslator.retryDelayMillis(error.message))
                requestSegments(
                    client, checkpoints, hash, book, chapter, glossary, keepOriginalTerms,
                    tail, batch, mode, styleNotes, retryError = error.message,
                    isPolish, draft
                )
            } else {
                throw error
            }
        }
    }

    // --- 检查点 --------------------------------------------------------------

    private fun checkpointFile(dir: File, batch: TranslationBatch): File =
        File(dir, "${batch.chapterIndex}-${batch.batchIndex}.json")

    private fun readCheckpoint(dir: File, batch: TranslationBatch, hash: String): List<String>? {
        val file = checkpointFile(dir, batch)
        if (!file.isFile) return null
        val stored = runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("sourceHash") != hash) return null
            val array = json.optJSONArray("paragraphs") ?: return null
            (0 until array.length()).map { array.optString(it) }
        }.getOrNull() ?: return null
        // 段数对不上说明检查点属于旧版批次规划，作废重翻。
        return stored.takeIf { it.size == batch.paragraphs.size && it.all(String::isNotBlank) }
    }

    private suspend fun storeCheckpoint(
        dir: File,
        batch: TranslationBatch,
        hash: String,
        translations: List<String>,
        mode: String
    ) = withContext(Dispatchers.IO + NonCancellable) {
        // NonCancellable：校验通过的译文已经花了钱，取消只能拦在它到手之前，
        // 不能把「已到手未落盘」的结果丢掉——否则恢复时该批重复计费。
        // 真正的取消响应点在批次循环的 onProgress 等挂起点。
        mutex.withLock {
            val file = checkpointFile(dir, batch)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("sourceHash", hash)
                .put("paragraphs", JSONArray(translations))
                .put("mode", mode)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    // --- 译文落盘 ------------------------------------------------------------

    /**
     * 把逐章译文写成 XHTML（每段一个 `<p>`，与英文侧叶级块 1:1，标题不进正文
     * 以免多出原文没有的段落），并构造不上书架的合成译本 [Book]。
     * [providerName] 是发起本次翻译的生效服务商展示名，进标题与作者——
     * 标题会经 `saveTranslation` 落到书架卡片上，别再硬编码服务商名。
     */
    private fun writeTranslationBook(
        source: Book,
        translatedChapters: List<List<String>>,
        providerName: String,
        titleOverride: String? = null
    ): Book {
        val dir = File(translationsDir, translationId(source.id))
        dir.deleteRecursively()
        dir.mkdirs()
        val chapters = translatedChapters.mapIndexed { index, paragraphs ->
            val title = source.chapters[index].title.ifBlank { "第 ${index + 1} 章" }
            val file = File(dir, "chapter_%03d.xhtml".format(index))
            file.writeText(xhtmlFor(title, paragraphs), Charsets.UTF_8)
            Chapter(
                title = title,
                relativePath = file.relativeTo(dir).invariantSeparatorsPath
            )
        }
        return Book(
            id = translationId(source.id),
            title = titleOverride ?: appContext.getString(R.string.translation_ai_title, providerName),
            author = providerName,
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = System.currentTimeMillis(),
            sourceFormat = "epub"
        )
    }

    private fun xhtmlFor(title: String, paragraphs: List<String>): String {
        val body = paragraphs.filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<title>${escapeHtml(title)}</title>
</head><body>
${body.ifBlank { "<p></p>" }}
</body></html>"""
    }

    companion object {
        /**
         * 合成译本的 book id / 目录名前缀。`remove()` 靠它整目录删除。
         * 前缀常量本体在 `Book.AI_TRANSLATION_ID_PREFIX`（data 层判 isAiTranslation 用）。
         */
        fun translationId(sourceBookId: String): String =
            Book.AI_TRANSLATION_ID_PREFIX + sourceBookId

        /**
         * 整本翻译模式：standard = 单遍直译；polish = 每批初翻后再让模型对照
         * 原文精修一遍（耗时与费用 ×2）。值随检查点落盘，便于事后区分产出方式。
         */
        const val MODE_STANDARD = "standard"
        const val MODE_POLISH = "polish"
    }
}

/**
 * 手动导入结果后离线组装译本时的防御性失败：仍有批次缺有效检查点。
 * 正常流程只在覆盖满 100% 时才调 [AiTranslationRepository.completeFromCheckpoints]，
 * 这是最后一道闸。
 */
class ManualTranslationIncompleteException(message: String) : RuntimeException(message)
