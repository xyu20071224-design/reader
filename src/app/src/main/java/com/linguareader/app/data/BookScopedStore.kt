package com.linguareader.app.data

import java.io.File

/**
 * 一处「按书存放」的数据 —— 它的**所有者**由这个接口认领。
 *
 * 为什么要有它：删一本书要清 9 处落点，而这张级联表以前是手写的、住在
 * `AppViewModel.deleteBook` 里，其中两步还是绕过 Repository 的裸路径字符串。
 * 没有编译期保证、没有对账，新增一处存储时也没有任何机制提醒你回来加一行 ——
 * 它已经漏了一处（生词本）。现在把「一本书的数据由什么构成」变成可枚举的清单：
 * 谁拥有那份数据，谁来实现这个接口；UI 层只负责遍历。
 *
 * 见「重构方案-数据所有权与生命周期.md」第 3 节。
 */
interface BookScopedStore {

    /** 诊断名（如 "tts_cache"）。出现在残留报告与孤儿对账里，出事时能一眼看出是哪处。 */
    val storeId: String

    /**
     * 这处存储在磁盘上的根（目录，或像生词本那样的单个文件）。供孤儿对账
     * **不要**拿它去拼单本书的路径 —— 那是各实现自己的事。
     */
    fun storageRoots(): List<File>

    /**
     * 删除这本书在此处的全部数据。
     *
     * 实现必须**幂等**：数据本来就不存在不算失败，也不要抛。调用方会 catch 住
     * 单处失败继续清理其余，但那是兜底，不是许可。
     */
    suspend fun deleteBookData(book: Book)

    /**
     * 磁盘上属于「书库里已经没有的书」的数据 —— 孤儿。**只报，不删**。
     *
     * 孤儿的来源不止「删书漏清」：重新导入同一本书的不同版本会换 id
     * （id = 源文件内容哈希），旧 id 名下的数据当场就没人认领了。
     *
     * 默认实现假设「每个根的直接子项按 bookId 命名」（`<bookId>/` 或 `<bookId>.json`），
     * 这对绝大多数存储成立；命名规则不同的（如译本正文按译本 id）自行覆盖。
     */
    fun orphans(books: List<Book>): List<File> {
        val known = books.map { it.id }.toSet()
        return storageRoots().flatMap { root ->
            root.listFiles().orEmpty().filter { it.name.removeSuffix(".json") !in known }
        }
    }
}

/** 一处存储的实际占用。存储页面显示的是它，不是配额那个魔法数字。 */
data class StoreUsage(
    val storeId: String,
    val bytes: Long
)

/**
 * 一次存储体检的结果：各处占用 + 孤儿清单。
 *
 * 孤儿 = 磁盘上有、书库里没有的数据。**只报不删** —— 清理由用户按下去。
 */
data class StorageReport(
    val usages: List<StoreUsage>,
    val orphans: List<BookDataOrphan>
) {
    val totalBytes: Long get() = usages.sumOf { it.bytes }
    val orphanBytes: Long get() = orphans.sumOf { it.bytes }
}

/** 一处孤儿数据（[storeId] 来自 [BookScopedStore.storeId]）。 */
data class BookDataOrphan(
    val storeId: String,
    val path: File,
    val bytes: Long
)
/** 人类可读的字节数。占用要给人看，不是给机器看。 */
fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
