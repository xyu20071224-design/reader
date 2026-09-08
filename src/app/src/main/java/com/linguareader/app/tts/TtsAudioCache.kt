package com.linguareader.app.tts

import android.content.Context
import com.linguareader.app.data.Book
import com.linguareader.app.data.BookScopedStore
import com.linguareader.shared.tts.TtsCacheKey
import java.io.File

/**
 * 云 TTS 音频缓存的所有者：`filesDir/tts_cache/<bookId>/<chapter>/<voice>/<sentence>.mp3`。
 *
 * 单独成类的理由：这条路径以前被写了两遍 —— `CloudTtsSynthesizer` 有 `cacheRoot`，
 * `AppViewModel.deleteBook` 又手写了一份 `"tts_cache/<id>"`。两份可以静默漂移，而漂移的
 * 表现是「书删了、音频还在」这种没人会发现的残留。现在路径只有这里知道。
 *
 * **注意**：这里还没有容量上限与淘汰策略（方案 D2.3）。缓存在 `filesDir` 而非 `cacheDir`，
 * 系统的低存储回收够不着它，长期听书会无界增长。
 */
class TtsAudioCache(context: Context) : BookScopedStore {

    private val root = File(context.applicationContext.filesDir, DIR_NAME)

    override val storeId: String = DIR_NAME

    override fun storageRoots(): List<File> = listOf(root)

    override suspend fun deleteBookData(book: Book) {
        File(root, book.id).deleteRecursively()
    }

    /**
     * 一个淘汰单元：`<bookId>/<chapter>/<voice>` 目录 —— 一次章节预生成为一个音色
     * 填的就是它。按句淘汰太碎，按书淘汰又太狠（听到一半整本没了）。
     */
    data class Entry(
        val dir: File,
        val bookId: String,
        val chapterIndex: Int,
        val bytes: Long,
        /**
         * 目录内最新的文件修改时间。
         *
         * **这是「最近写入」不是「最近使用」**：Android 上 atime 不可靠，而播放只读
         * 文件、不碰时间戳。所以一本很久以前缓存、天天在听的书，可能比昨天缓存却
         * 从没播过的书更早被淘汰。缓解办法是保护「当前书当前章」（见 [trimTo]），
         * 真要做成真 LRU 得在播放时主动 touch，那是另一件事。
         */
        val lastModified: Long
    )

    /** 枚举全部淘汰单元。目录名不合规（章号不是数字）的直接跳过，不猜。 */
    fun entries(): List<Entry> = root.listFiles().orEmpty().filter { it.isDirectory }
        .flatMap { bookDir ->
            bookDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { chapterDir ->
                val chapter = chapterDir.name.toIntOrNull()
                if (chapter == null) emptyList()
                else chapterDir.listFiles().orEmpty().filter { it.isDirectory }.map { voiceDir ->
                    val files = voiceDir.walkBottomUp().filter { it.isFile }.toList()
                    Entry(
                        dir = voiceDir,
                        bookId = bookDir.name,
                        chapterIndex = chapter,
                        bytes = files.sumOf { it.length() },
                        lastModified = files.maxOfOrNull { it.lastModified() }
                            ?: voiceDir.lastModified()
                    )
                }
            }
        }

    /** 当前占用（字节）。存储占用页面与配额判断都用它。 */
    fun totalBytes(): Long = entries().sumOf { it.bytes }

    /**
     * 清空整个音频缓存，返回释放的字节数。
     *
     * 用户在设置里主动按的，所以不做任何保护 —— 包括正在听的那本。下次播放会
     * 重新合成（云 TTS 会重新计费，文案里要说清楚）。
     */
    fun clearAll(): Long {
        val freed = totalBytes()
        root.listFiles().orEmpty().forEach { it.deleteRecursively() }
        return freed
    }

    /**
     * 淘汰到 [limitBytes] 以下，返回释放的字节数。
     *
     * - [limitBytes] <= 0 表示**不限**（用户可选），直接不动；
     * - [protectBookId] / [protectChapterIndex] 指定的单元永不淘汰 —— 正在听的东西
     *   被删掉会当场触发重新合成，云 TTS 那是要花钱的；
     * - 淘汰顺序按 [Entry.lastModified] 从旧到新（注意那是「最近写入」，见该字段注释）。
     */
    fun trimTo(
        limitBytes: Long,
        protectBookId: String? = null,
        protectChapterIndex: Int? = null
    ): Long {
        if (limitBytes <= 0) return 0L
        val all = entries()
        var total = all.sumOf { it.bytes }
        if (total <= limitBytes) return 0L
        var freed = 0L
        for (entry in all.sortedBy { it.lastModified }) {
            if (total <= limitBytes) break
            val protectedEntry = protectBookId != null && entry.bookId == protectBookId &&
                (protectChapterIndex == null || entry.chapterIndex == protectChapterIndex)
            if (protectedEntry) continue
            if (entry.dir.deleteRecursively()) {
                total -= entry.bytes
                freed += entry.bytes
            }
        }
        return freed
    }

    /**
     * 某句话的缓存文件（不保证存在）。
     *
     * [segmentIndex] 是句内朗读片段序号（发言/旁白分离）：一句话拆成旁白段+
     * 引语段后逐段缓存，键必须含片段，否则同句两段互相覆盖。2026-09-06 加片段
     * 维度时键从 `5.mp3` 改为 `s5-0.mp3`，存量缓存一次性作废（重新合成）。
     *
     * [engineTag] 是**引擎身份**（见 VoiceLibraryLoader.engineKey）：同一个音色 id 在
     * 两台不同的自建服务器上是**不同的声音**，不把它算进键，换服务器后会直接播出
     * 上一台的音频。键里带上它之后，旧引擎的目录自然不再命中，交给配额淘汰或
     * 「清空音频缓存」回收。
     *
     * **键里还含朗读管线版本**（`~v1~`，见 [TtsCacheKey] 与 `TtsPipelineContract`）：
     * 断句/块选择器/片段拆分任一改动都会让存量缓存静默对不上文本，版本进键是唯一的
     * 闸门。代价是版本 bump 时存量缓存一次性作废（方案 D1 已接受）。
     */
    fun fileFor(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        segmentIndex: Int,
        voice: String,
        engineTag: String
    ): File = File(
        root,
        // 键的唯一实现在 :shared（音频包生成工具共用），见 TtsCacheKey。
        // 键里含管线版本：切分规则一变，旧目录自然不命中（方案 D1，存量缓存一次性作废）。
        "$bookId/" + TtsCacheKey.relativePath(
            chapterIndex = chapterIndex,
            sentenceIndex = sentenceIndex,
            segmentIndex = segmentIndex,
            engineTag = engineTag,
            voice = voice
        )
    )

    companion object {
        const val DIR_NAME = "tts_cache"

        /** 淘汰单元的目录名；实现在 [TtsCacheKey.segmentDir]（含管线版本）。 */
        fun segmentFor(engineTag: String, voice: String): String =
            TtsCacheKey.segmentDir(engineTag, voice)

        /** 音色 id → 目录名段；实现在 [TtsCacheKey.voiceSegment]。 */
        fun voiceSegment(voice: String): String = TtsCacheKey.voiceSegment(voice)
    }
}
