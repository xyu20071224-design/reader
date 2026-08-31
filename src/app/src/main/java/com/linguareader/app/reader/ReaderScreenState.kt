package com.linguareader.app.reader

/**
 * 阅读页的「位置」状态：章节、页码、滚动比例、待保存快照。
 *
 * 抽出来的理由：这部分原先是 ReaderScreen 主 composable 里十个裸 `var by remember`，
 * 翻页/切章/进滚动模式各自散着赋值，历史上出过「回翻落到上一章开头」「进滑动模式
 * 丢进度」两类事故，而 composable 里的状态单测覆盖不到。改成不可变数据 + 纯迁移
 * 函数后，每条迁移都能在 JVM 单测里断言（见 ReaderScreenStateTest）。
 *
 * 与 [com.linguareader.app.AppViewModel] 的 AppUiState 保持同一风格：字段不可变，
 * 迁移一律返回新实例。
 */
internal data class ReaderPosition(
    /** 当前章节下标。 */
    val chapter: Int,
    /**
     * 注入 WebView 的「还原目标页」。可以是 [ReaderScripts.LAST_PAGE] 哨兵
     * （从下一章回翻进来时用），所以它不等同于 [page]，也绝不参与页码 clamp。
     */
    val restorePage: Int,
    /** 当前渲染页（0 基）。 */
    val page: Int,
    /** 本章总页数，至少 1。 */
    val pageCount: Int,
    /** 是否处于滚动（连续）模式。 */
    val scrollMode: Boolean,
    /** 滚动模式下的章内进度比例 0..1。 */
    val scrollRatio: Float,
    /** 滚动模式下的等效页数，至少 1。 */
    val scrollPageCount: Int,
    /** 待落盘的页码快照（与 [page] 分开：保存走节流，不能读到中途值）。 */
    val savedPage: Int,
    /** 待落盘的页数快照。 */
    val savedCount: Int,
    /** 有未落盘的位置变化。等价于原来的 needsSave。 */
    val dirty: Boolean
) {

    /** WebView 报告本章渲染就绪。 */
    fun onReady(page: Int, count: Int): ReaderPosition = landed(page, count)

    /** 用户翻页 / JS 侧页码变化。 */
    fun onPageChanged(page: Int, count: Int): ReaderPosition = landed(page, count)

    /**
     * 页码落定：还原目标同步推进到实际页，否则重新注入脚本时会把用户翻到的位置
     * 又拉回旧的还原点。
     */
    private fun landed(page: Int, count: Int): ReaderPosition = copy(
        // 页码/页数不在这里 clamp：JS 桥（EpubPage.ReaderBridge）已经做过范围收敛，
        // 这一层再夹一次只会掩盖上游异常，且与重构前的行为不一致。
        restorePage = page,
        page = page,
        pageCount = count,
        savedPage = page,
        savedCount = count,
        dirty = true
    )

    /**
     * 滚动模式进度回报。
     *
     * 注意不碰 [restorePage]：滚动模式的还原走 [scrollRatio]，把比例换算出来的页码
     * 写进还原目标会在切回分页模式时把位置带偏。
     */
    fun onScrollProgress(ratio: Float, page: Int, count: Int): ReaderPosition = copy(
        page = page,
        pageCount = count,
        scrollRatio = ratio,
        scrollPageCount = count,
        savedPage = page,
        savedCount = count,
        dirty = true
    )

    /** JS 侧进入/退出滚动模式。 */
    fun onScrollModeChanged(active: Boolean): ReaderPosition = copy(scrollMode = active)

    /**
     * 切章。越界返回 null（调用方据此跳过保存/通知 TTS 等副作用，与旧代码的
     * `if (index !in indices) return` 等价）。
     *
     * @param fromEnd 从下一章回翻进来：落在新章最后一页，用 [ReaderScripts.LAST_PAGE]
     *   哨兵而不是「某个大页码」——JS 侧会翻译成 restoreTarget = -1 并在每次重排时
     *   重新取末页，普通大页码会被首次（字体未就绪、pageCount 偏小）测量拍成 0。
     * @param keepScrollMode 在滚动模式里跨章：保持滚动模式，回翻时比例落在章末。
     */
    fun selectChapter(
        index: Int,
        chapterCount: Int,
        fromEnd: Boolean = false,
        keepScrollMode: Boolean = false
    ): ReaderPosition? {
        if (index < 0 || index >= chapterCount) return null
        val stayScrolled = keepScrollMode && scrollMode
        return ReaderPosition(
            chapter = index,
            restorePage = if (fromEnd) ReaderScripts.LAST_PAGE else 0,
            page = 0,
            pageCount = 1,
            scrollMode = stayScrolled,
            scrollRatio = if (stayScrolled && fromEnd) 1f else 0f,
            scrollPageCount = 1,
            savedPage = 0,
            savedCount = 1,
            // 新章还没渲染，此刻落盘会把「第 0 页」写成用户进度；等 onReady 再置脏。
            dirty = false
        )
    }

    /** 相邻章切换：往前翻（direction < 0）落在上一章末页。 */
    fun changeChapter(
        direction: Int,
        chapterCount: Int,
        keepScrollMode: Boolean = false
    ): ReaderPosition? = selectChapter(
        index = chapter + direction,
        chapterCount = chapterCount,
        fromEnd = direction < 0,
        keepScrollMode = keepScrollMode
    )

    /** 全书进度 0..1，用于顶栏进度条与书架续读。 */
    fun progress(chapterCount: Int): Float {
        if (chapterCount <= 0) return 0f
        val inChapter = if (pageCount <= 1) 0f else page.toFloat() / (pageCount - 1)
        return (chapter + inChapter) / chapterCount.toFloat()
    }

    /** 有脏数据才给出落盘请求；干净时返回 null，调用方直接跳过一次写盘。 */
    fun saveRequest(chapterCount: Int): ReaderProgressSave? {
        if (!dirty) return null
        val inChapter = if (savedCount <= 1) 0f else savedPage.toFloat() / (savedCount - 1)
        val progress = if (chapterCount <= 0) 0f else (chapter + inChapter) / chapterCount.toFloat()
        return ReaderProgressSave(chapter = chapter, page = savedPage, progress = progress)
    }

    /** 落盘已交出去：清脏标记。取快照与清标记分开，避免节流轮询里重复写同一份。 */
    fun markSaved(): ReaderPosition = if (dirty) copy(dirty = false) else this

    companion object {
        /** 打开一本书时的初始位置（章节/页码来自书库记录）。 */
        fun forBook(chapter: Int, page: Int, chapterCount: Int): ReaderPosition {
            // chapterCount 为 0 时 coerceIn(0, -1) 会抛，兜一层；页码沿用书库记录不夹。
            val safeChapter = chapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
            return ReaderPosition(
                chapter = safeChapter,
                restorePage = page,
                page = page,
                pageCount = 1,
                scrollMode = false,
                scrollRatio = 0f,
                scrollPageCount = 1,
                savedPage = page,
                savedCount = 1,
                dirty = false
            )
        }
    }
}

/** 一次进度落盘请求（章节 + 页码 + 全书进度）。 */
internal data class ReaderProgressSave(
    val chapter: Int,
    val page: Int,
    val progress: Float
)

/** 返回键要做的事。顺序由 [ReaderOverlays.onBack] 决定，不由调用点的 when 决定。 */
internal enum class ReaderBackAction {
    /** 关掉查词弹层。 */
    DismissLookup,

    /** 关掉最上层的设置/目录/跳页弹层。 */
    CloseSheet,

    /** 没有弹层了：退出阅读器（可能先被到期词拦截）。 */
    LeaveReader
}

/** [ReaderOverlays.onBack] 的结果：要做什么 + 处理后的弹层状态。 */
internal data class ReaderBackResult(
    val action: ReaderBackAction,
    val overlays: ReaderOverlays
)

/** 复习提示条被划掉后的结果：是否要接着执行被拦下的关闭。 */
internal data class ReaderPromptDismissal(
    val overlays: ReaderOverlays,
    val leaveReader: Boolean
)

/** 关闭阅读器的决定：要么先弹复习提示，要么真的退出。 */
internal data class ReaderCloseDecision(
    val overlays: ReaderOverlays,
    /** true = 先弹到期词提示，不退出。 */
    val promptReview: Boolean
)

/**
 * 阅读页的弹层与「关闭编排」状态。
 *
 * 抽出来的理由同 [ReaderPosition]：返回键的优先级链、以及「关闭前被到期生词拦一道」
 * 的两段式（先弹 banner 记下 pendingClose，banner 消失才真关）原本散在
 * BackHandler 与三处回调里，改错一处就会出现「返回键直接退出书」或「点了继续阅读
 * 却退出了」这类难复现的问题。
 */
internal data class ReaderOverlays(
    /** 顶栏/底栏是否可见（点击正文中部切换）。 */
    val toolbarVisible: Boolean = true,
    /** 听书「选择起点」态：下一次点句被吃掉当起点。 */
    val choosingStart: Boolean = false,
    val contents: Boolean = false,
    val settings: Boolean = false,
    val listeningSettings: Boolean = false,
    val pageJump: Boolean = false,
    val reviewSettings: Boolean = false,
    /** 到期生词提示条。 */
    val reviewPrompt: Boolean = false,
    /** 用户按了返回但被复习提示拦下：提示消失后要继续执行关闭。 */
    val pendingClose: Boolean = false
) {

    fun toggleToolbar(): ReaderOverlays = copy(toolbarVisible = !toolbarVisible)

    /**
     * 返回键优先级：查词弹层 > 设置 > 目录 > 跳页 > 退出阅读器。
     * 顺序与旧的 BackHandler when 分支一致，改这里等于改全局返回行为。
     */
    fun onBack(lookupOpen: Boolean): ReaderBackResult = when {
        lookupOpen -> ReaderBackResult(ReaderBackAction.DismissLookup, this)
        settings -> ReaderBackResult(ReaderBackAction.CloseSheet, copy(settings = false))
        contents -> ReaderBackResult(ReaderBackAction.CloseSheet, copy(contents = false))
        pageJump -> ReaderBackResult(ReaderBackAction.CloseSheet, copy(pageJump = false))
        else -> ReaderBackResult(ReaderBackAction.LeaveReader, this)
    }

    /**
     * 请求关闭阅读器。到期生词 + 用户开了「暂停时提醒」时先弹提示条，
     * 并记下 pendingClose；提示条已经在显示时不再拦第二次。
     */
    fun requestClose(hasDueWords: Boolean, pausePrompt: Boolean): ReaderCloseDecision =
        if (pausePrompt && hasDueWords && !reviewPrompt) {
            ReaderCloseDecision(copy(reviewPrompt = true, pendingClose = true), promptReview = true)
        } else {
            ReaderCloseDecision(copy(reviewPrompt = false, pendingClose = false), promptReview = false)
        }

    /** 提示条被划掉：如果是关闭流程拦下来的，这次要真的退出。 */
    fun dismissReviewPrompt(): ReaderPromptDismissal = ReaderPromptDismissal(
        overlays = copy(reviewPrompt = false, pendingClose = false),
        leaveReader = pendingClose
    )

    /** 用户从提示条直接进复习：不再退出，pendingClose 作废。 */
    fun startReviewFromPrompt(): ReaderOverlays =
        copy(reviewPrompt = false, pendingClose = false)
}

/** 句级翻译缓存：同一句重复点词不重复出网，超过上限整体丢弃（与旧逻辑一致）。 */
internal class SentenceTranslationCache(private val capacity: Int = 64) {
    private val entries = mutableMapOf<String, com.linguareader.app.ai.SentenceTranslationResult>()

    operator fun get(sentence: String) = entries[sentence.trim()]

    fun put(sentence: String, result: com.linguareader.app.ai.SentenceTranslationResult) {
        val key = sentence.trim()
        if (key.isEmpty()) return
        if (entries.size >= capacity) entries.clear()
        entries[key] = result
    }

    val size: Int get() = entries.size
}

/**
 * 查词弹层的会话状态。
 *
 * 抽出来的理由：原先是 17 个裸 var，`LaunchedEffect(lookup)` 里有一段 12 行的
 * 「重置矩阵」——漏清一个字段，上一次查词的 AI 结果/失败信号/译文就串到下一个词上。
 * 现在 `begin` 一次性定义「开一次新查词到底该归零什么」，这段语义可单测，不再靠
 * 肉眼比对赋值顺序。
 *
 * 注意 [lookup]（WordLookup）仍留在 composable 里用 [androidx.compose.runtime.saveable.Saver]
 * 单独保存：它要跨旋转恢复，而本会话在旋转后整体归零是可接受的（重建查询）。
 */
internal data class ReaderLookupSession(
    val dictionaryResult: com.linguareader.app.data.DictionaryLookupResult? = null,
    val dictionaryLoading: Boolean = false,
    val showingRelatedPhrase: Boolean = false,
    val aiResult: com.linguareader.app.ai.AiLookupResult? = null,
    val aiLoading: Boolean = false,
    val aiFailed: Boolean = false,
    val aiRemoteFailed: Boolean = false,
    val translation: com.linguareader.app.translation.TranslationLookupResult? = null,
    val translationLoading: Boolean = false,
    val sentenceTranslation: com.linguareader.app.ai.SentenceTranslationResult? = null,
    val sentenceTranslationError: String? = null,
    val sentenceTranslationLoading: Boolean = false,
    val retranslateLoading: Boolean = false,
    val retranslateDoneTick: Int = 0,
    val status: com.linguareader.app.SettingsStatus? = null,
    private val sentenceCache: SentenceTranslationCache = SentenceTranslationCache()
) {

    /**
     * 开一次新查词。此处集中定义「什么必须归零」：
     * 上一次的 AI/译文/错误/行内反馈全部清掉，只保留同步词典能立刻给的结果，
     * 句级翻译从缓存取（同句不重复出网）。
     */
    fun begin(
        dictionaryResult: com.linguareader.app.data.DictionaryLookupResult,
        hasTranslation: Boolean,
        sentence: String
    ): ReaderLookupSession = copy(
        dictionaryResult = dictionaryResult,
        dictionaryLoading = false,
        showingRelatedPhrase = false,
        aiResult = null,
        aiLoading = false,
        aiFailed = false,
        aiRemoteFailed = false,
        translation = null,
        translationLoading = hasTranslation,
        sentenceTranslation = sentenceCache[sentence],
        sentenceTranslationError = null,
        sentenceTranslationLoading = false,
        retranslateLoading = false,
        // retranslateDoneTick 故意不归零：它是「重翻完成信号」的单调递增 tick，
        // LookupSheet 用它做 remember 键（连同 lookup.word/sentence），换词本身已经
        // 重置编辑态，多归一次零反而可能在同一句重复重翻时丢失信号。
        status = null
    )

    fun markDictionaryLoading(): ReaderLookupSession = copy(dictionaryLoading = true)

    fun withTranslation(
        translation: com.linguareader.app.translation.TranslationLookupResult?,
        loading: Boolean = false
    ): ReaderLookupSession = copy(translation = translation, translationLoading = loading)

    fun withAiResult(
        result: com.linguareader.app.ai.AiLookupResult?,
        remoteFailed: Boolean = false
    ): ReaderLookupSession = copy(
        aiResult = result,
        aiRemoteFailed = remoteFailed,
        aiLoading = false
    )

    fun markAiFailed(): ReaderLookupSession = copy(aiLoading = false, aiFailed = true)

    fun withSentenceTranslation(
        result: com.linguareader.app.ai.SentenceTranslationResult?
    ): ReaderLookupSession = copy(
        sentenceTranslation = result,
        sentenceTranslationError = null,
        sentenceTranslationLoading = false
    )

    fun withSentenceTranslationError(error: String): ReaderLookupSession = copy(
        sentenceTranslation = null,
        sentenceTranslationError = error,
        sentenceTranslationLoading = false
    )

    fun beginSentenceTranslation(): ReaderLookupSession = copy(
        sentenceTranslationLoading = true,
        sentenceTranslationError = null
    )

    fun startRetranslate(): ReaderLookupSession = copy(retranslateLoading = true)

    fun finishRetranslate(
        status: com.linguareader.app.SettingsStatus,
        translation: com.linguareader.app.translation.TranslationLookupResult? = null
    ): ReaderLookupSession = copy(
        retranslateLoading = false,
        retranslateDoneTick = retranslateDoneTick + 1,
        status = status,
        translation = translation ?: this.translation
    )

    fun setStatus(status: com.linguareader.app.SettingsStatus): ReaderLookupSession =
        copy(status = status)

    fun setShowingRelatedPhrase(show: Boolean): ReaderLookupSession =
        copy(showingRelatedPhrase = show)

    fun clearStatus(): ReaderLookupSession = copy(status = null)

    fun cachedSentenceOrNull(sentence: String): com.linguareader.app.ai.SentenceTranslationResult? =
        sentenceCache[sentence]

    fun cacheSentence(
        sentence: String,
        result: com.linguareader.app.ai.SentenceTranslationResult
    ): ReaderLookupSession {
        sentenceCache.put(sentence, result)
        return copy(sentenceTranslation = result, sentenceTranslationError = null, sentenceTranslationLoading = false)
    }

    companion object {
        /** 未开查词时的初始态。 */
        fun empty(sentenceCache: SentenceTranslationCache = SentenceTranslationCache()) =
            ReaderLookupSession(sentenceCache = sentenceCache)
    }
}
