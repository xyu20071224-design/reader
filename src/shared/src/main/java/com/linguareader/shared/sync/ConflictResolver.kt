package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.SavedWord
import java.util.Locale

/** 版本戳相等时的取舍。默认保守保留本地：幂等、可重复执行，不会因同步把本机状态改掉。 */
enum class TieBreak { KEEP_LOCAL, KEEP_REMOTE }

/**
 * 同步冲突裁决（阶段 1 §3 的 D-03 决策）。**纯函数、无平台依赖、无第三方依赖**，
 * 因此 Android / Windows / Linux / macOS 四端共用同一份语义，并可直接单测。
 *
 * 三类数据的策略（不要在这里改语义而不改阶段 1 文档）：
 * - 阅读/听书进度：整个「位置向量」按 Book.progressUpdatedAt 做 LWW；
 * - 生词卡：文本/出处字段 LWW，复习状态单调取大，例句形态取并集；
 * - 自由文本（术语表备注、译本风格备注）：LWW + 版本检查（检查在调用方）。
 *
 * **已知取舍（有意，勿当 bug）**：复习状态取大意味着「答错导致等级下降」这类
 * 逆向变更在并发合并时会丢失。当前数据模型没有操作日志，做不到无损合并；
 * 若日后确有需要，再为该字段单独立项（不改其余字段语义）。
 */
object ConflictResolver {

    /** 通用 last-write-wins：严格更新的一方胜；相等时按 tieBreak。 */
    fun <T> lastWriteWins(
        local: T,
        localAt: Long,
        remote: T,
        remoteAt: Long,
        tieBreak: TieBreak = TieBreak.KEEP_LOCAL
    ): T = when {
        remoteAt > localAt -> remote
        localAt > remoteAt -> local
        else -> if (tieBreak == TieBreak.KEEP_REMOTE) remote else local
    }

    /**
     * 合并同一本书的阅读/听书进度。
     *
     * **关键约束**：只搬「位置向量」，绝不把远端的设备相关字段
     * （Book.extractedDir、Book.coverRelativePath、Book.chapters）覆盖到本机 ——
     * 那些是本机解压路径与本地章节表，换成远端的值会让这本书在本机读不出来。
     */
    fun mergeProgress(
        local: Book,
        remote: Book,
        tieBreak: TieBreak = TieBreak.KEEP_LOCAL
    ): Book {
        val remoteWins = when {
            remote.progressUpdatedAt > local.progressUpdatedAt -> true
            local.progressUpdatedAt > remote.progressUpdatedAt -> false
            else -> tieBreak == TieBreak.KEEP_REMOTE
        }
        if (!remoteWins) return local
        return local.copy(
            chapterIndex = remote.chapterIndex,
            pageIndex = remote.pageIndex,
            progress = remote.progress,
            locusBlockIndex = remote.locusBlockIndex,
            locusCharOffset = remote.locusCharOffset,
            locusAnchor = remote.locusAnchor,
            ttsChapterIndex = remote.ttsChapterIndex,
            ttsSentenceIndex = remote.ttsSentenceIndex,
            progressUpdatedAt = remote.progressUpdatedAt
        )
    }

    /** 自由文本合并：LWW。 */
    fun mergeText(
        local: String,
        localAt: Long,
        remote: String,
        remoteAt: Long,
        tieBreak: TieBreak = TieBreak.KEEP_LOCAL
    ): String = lastWriteWins(local, localAt, remote, remoteAt, tieBreak)

    /**
     * 合并同一词头的生词卡（键 = SavedWord.id）。
     *
     * - 词头/音标/释义/AI 解释/例句/书名章节等**文本与出处字段**：取 SavedWord.updatedAt 较晚的一方；
     * - reviewLevel / reviewCount / nextReviewAt：取较大值（复习进度单调）；
     * - addedAt：取较早的非零值（首次收藏时间不因同步后移）；
     * - surfaceForms：并集（先本地后远端顺序稳定、按小写去重）。
     */
    fun mergeSavedWord(
        local: SavedWord,
        remote: SavedWord,
        tieBreak: TieBreak = TieBreak.KEEP_LOCAL
    ): SavedWord {
        val remoteWins = when {
            remote.updatedAt > local.updatedAt -> true
            local.updatedAt > remote.updatedAt -> false
            else -> tieBreak == TieBreak.KEEP_REMOTE
        }
        val textSource = if (remoteWins) remote else local
        val forms = (local.surfaceForms + remote.surfaceForms)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        return textSource.copy(
            addedAt = earliestNonZero(local.addedAt, remote.addedAt),
            reviewLevel = maxOf(local.reviewLevel, remote.reviewLevel),
            reviewCount = maxOf(local.reviewCount, remote.reviewCount),
            nextReviewAt = maxOf(local.nextReviewAt, remote.nextReviewAt),
            surfaceForms = forms,
            updatedAt = maxOf(local.updatedAt, remote.updatedAt)
        )
    }

    private fun earliestNonZero(a: Long, b: Long): Long = when {
        a <= 0L -> b
        b <= 0L -> a
        else -> minOf(a, b)
    }
}
