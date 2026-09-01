package com.linguareader.app.tts

import android.content.Context
import com.linguareader.app.data.Book
import com.linguareader.app.data.BookScopedStore
import java.io.File
import java.security.MessageDigest

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

    /** 某句话的缓存文件（不保证存在）。 */
    fun fileFor(bookId: String, chapterIndex: Int, sentenceIndex: Int, voice: String): File =
        File(root, "$bookId/$chapterIndex/${voiceSegment(voice)}/$sentenceIndex.mp3")

    companion object {
        const val DIR_NAME = "tts_cache"

        /**
         * 音色 id → 缓存目录名。**必须是单射**，否则缓存会串音。
         *
         * 旧实现是 voice.replace(Regex("[^A-Za-z0-9._-]"), "_") —— 有损映射：两个不同音色
         * 可以消毒成同一个目录名（自建服务器把参考音频命名成「男声.wav」「女声.wav」，
         * 两者都变成同一串下划线），而命中判据只有「文件存在且非空」，于是**放出另一个
         * 音色的音频**，且永远发现不了。
         *
         * 现在只在「这个 id 当目录名会出事」时才改写，其余原样保留：
         * - 原样保留 ⇒ 单射显然成立，且**存量缓存全部继续命中**（MiMo 的
         *   mimo-clone:<ascii-slug>、系统音色名、常见服务器音色名都落在这一档）；
         * - 含路径分隔符 / 空串 / 单点 / 双点 的才换成哈希 —— 这几种当目录名会穿越或
         *   指错地方，哈希同时保证单射。
         *
         * 这里**没有**引擎维度：同一个音色 id 在两台不同服务器上可能是不同的声音，
         * 那条要等缓存清理入口就绪后一起改（改键会作废存量缓存，见方案 D2.2）。
         */
        fun voiceSegment(voice: String): String {
            val unusable = voice.isEmpty() ||
                voice == "." ||
                voice == ".." ||
                voice.any { it == '/' || it == '\\' || it == '\u0000' }
            if (!unusable) return voice
            val digest = MessageDigest.getInstance("SHA-256").digest(voice.toByteArray())
            return "h-" + digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
