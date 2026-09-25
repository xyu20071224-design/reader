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
         * 目录内最新的文件修改时间 = **最近写入**（保留给诊断与旧语义参考）。
         */
        val lastModified: Long,
        /**
         * **最近访问时间**（第四轮审查 6-13）：淘汰排序用它。
         *
         * Android 上文件 atime 不可靠，所以由播放路径显式写下 [ACCESS_MARKER]（见
         * [markAccessed]）；没有标记时退化为 [lastModified]，与旧行为一致。
         */
        val lastAccessed: Long
    )

    /** 枚举全部淘汰单元。目录名不合规（章号不是数字）的直接跳过，不猜。 */
    fun entries(): List<Entry> = root.listFiles().orEmpty().filter { it.isDirectory }
        .flatMap { bookDir ->
            bookDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { chapterDir ->
                val chapter = chapterDir.name.toIntOrNull()
                if (chapter == null) emptyList()
                else chapterDir.listFiles().orEmpty().filter { it.isDirectory }.map { voiceDir ->
                    // 访问标记是 0 字节的记账文件，不计入占用、也不参与「最近写入」——
                    // 它不是音频内容。（否则每次 touch 都会把 lastModified 顶到最新。）
                    val files = voiceDir.walkBottomUp()
                        .filter { it.isFile && it.name != ACCESS_MARKER }
                        .toList()
                    val lastModified = files.maxOfOrNull { it.lastModified() }
                        ?: voiceDir.lastModified()
                    Entry(
                        dir = voiceDir,
                        bookId = bookDir.name,
                        chapterIndex = chapter,
                        bytes = files.sumOf { it.length() },
                        lastModified = lastModified,
                        // 标记是空文件，其 lastModified 即我们写入的时刻；缺标记则退化为 lastModified。
                        lastAccessed = File(voiceDir, ACCESS_MARKER)
                            .takeIf { it.isFile }?.lastModified() ?: lastModified
                    )
                }
            }
        }

    /** 当前占用（字节）。存储占用页面与配额判断都用它。 */
    fun totalBytes(): Long = entries().sumOf { it.bytes }

    /**
     * 记下「这组缓存刚被用来播放」（第四轮审查 6-13）。
     *
     * 播放路径只读文件、系统 atime 不可靠，所以由这里显式写一个空标记文件，
     * [entries] 用它的 mtime 作为 [Entry.lastAccessed]，[trimTo] 据此做**真 LRU**。
     * 只为平衡「写放大 vs 精度」：同一单元在 [ACCESS_TOUCH_INTERVAL_MS] 内重复播放
     * 不重复写（读多写少，避免每次朗读都产生一次 IO）。
     *
     * 传进来的 [file] 是缓存里的某个文件（可能不在缓存目录下，例如音频包命中）——
     * 不在缓存根下时直接忽略，不制造空目录。
     */
    fun markAccessed(file: File) {
        val voiceDir = file.parentFile ?: return
        if (!voiceDir.isDirectory) return
        if (!isUnderRoot(voiceDir)) return
        val marker = File(voiceDir, ACCESS_MARKER)
        val now = System.currentTimeMillis()
        if (marker.isFile && now - marker.lastModified() < ACCESS_TOUCH_INTERVAL_MS) return
        runCatching {
            marker.writeBytes(ByteArray(0))
            marker.setLastModified(now)
        }
    }

    /** [dir] 是否位于本缓存根之下（避免把音频包目录也打上标记）。 */
    private fun isUnderRoot(dir: File): Boolean {
        var cursor: File? = dir
        while (cursor != null) {
            if (cursor.absolutePath == root.absolutePath) return true
            cursor = cursor.parentFile
        }
        return false
    }

    /**
     * 清空整个音频缓存，返回**实际**释放的字节数。
     *
     * 用户在设置里主动按的，所以不做任何保护 —— 包括正在听的那本。下次播放会
     * 重新合成（云 TTS 会重新计费，文案里要说清楚）。
     *
     * 第四轮审查 6-13：freed 改为**按删除结果统计**（删成功才计入），不再用删除前的
     * `totalBytes()` 估算 —— 删除失败时不该谎报释放量。
     */
    fun clearAll(): Long {
        var freed = 0L
        root.listFiles().orEmpty().forEach { entry ->
            val bytes = entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (entry.deleteRecursively()) freed += bytes
        }
        return freed
    }

    /**
     * 淘汰到 [limitBytes] 以下，返回释放的字节数。
     *
     * - [limitBytes] <= 0 表示**不限**（用户可选），直接不动；
     * - [protectBookId] / [protectChapterIndex] 指定的单元永不淘汰 —— 正在听的东西
     *   被删掉会当场触发重新合成，云 TTS 那是要花钱的；
     * - [protectNewestChapterOnly] 为真且 [protectChapterIndex] 为 null 时，**只**保护该
     *   书最近写入的那一章（第四轮审查 M6：整书缓存路径此前保护整本书，单本超上限就永不
     *   淘汰，配额形同失效）。非空 [protectChapterIndex] 优先，语义不变。
     * - 淘汰顺序按 [Entry.lastAccessed] 从旧到新（第四轮审查 6-13：改为**真 LRU**；
     *   无访问标记的旧单元退化为「最近写入」，见该字段注释）。
     */
    fun trimTo(
        limitBytes: Long,
        protectBookId: String? = null,
        protectChapterIndex: Int? = null,
        protectNewestChapterOnly: Boolean = false
    ): Long {
        if (limitBytes <= 0) return 0L
        val all = entries()
        var total = all.sumOf { it.bytes }
        if (total <= limitBytes) return 0L
        // M6：整书缓存路径只保该书的「最近写入的那一章」，而不是整本。
        val effectiveProtectChapter = when {
            protectChapterIndex != null -> protectChapterIndex
            protectNewestChapterOnly && protectBookId != null ->
                all.filter { it.bookId == protectBookId }.maxByOrNull { it.lastModified }?.chapterIndex
            else -> null
        }
        var freed = 0L
        for (entry in all.sortedBy { it.lastAccessed }) {
            if (total <= limitBytes) break
            val protectedEntry = protectBookId != null && entry.bookId == protectBookId &&
                (effectiveProtectChapter == null || entry.chapterIndex == effectiveProtectChapter)
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
     * **键里还含朗读管线版本与引擎身份指纹**（`e<sha256(引擎身份)[:8]>~v3~<voice>`，
     * 当前 v3；见 [TtsCacheKey] 与 `TtsPipelineContract`）：
     * 断句/块选择器/片段拆分任一改动都会让存量缓存静默对不上文本，版本进键是唯一的
     * 闸门；引擎身份自 2026-09-12 起含 `serverModel` / MiMo 风格指令，换模型即换键。
     * 代价是版本 bump 或换模型时存量缓存一次性作废（方案 D1 已接受）。
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

        /** 记录「最近访问」的 0 字节标记文件名（第四轮审查 6-13）。 */
        const val ACCESS_MARKER = ".accessed"

        /**
         * 同一单元在此时长内重复播放不重写访问标记（写放大护栏）：
         * 播放一次就写一次盘太浪费，而「一小时粒度」对天级的淘汰决策绰绰有余。
         */
        const val ACCESS_TOUCH_INTERVAL_MS = 60L * 60L * 1000L

        /** 淘汰单元的目录名；实现在 [TtsCacheKey.segmentDir]（含管线版本）。 */
        fun segmentFor(engineTag: String, voice: String): String =
            TtsCacheKey.segmentDir(engineTag, voice)

        /** 音色 id → 目录名段；实现在 [TtsCacheKey.voiceSegment]。 */
        fun voiceSegment(voice: String): String = TtsCacheKey.voiceSegment(voice)
    }
}
