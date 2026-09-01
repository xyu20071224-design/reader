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
}
