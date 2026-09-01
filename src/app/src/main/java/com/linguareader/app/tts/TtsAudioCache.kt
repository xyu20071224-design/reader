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
