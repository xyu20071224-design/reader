package com.linguareader.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.DictionarySql
import com.linguareader.shared.data.RawDictionaryEntry
import java.io.File

/**
 * 词典仓库的 Android 壳（桌面迁移 M2 刀2 + 资源包 M1）。
 *
 * 查词逻辑的真相在 `com.linguareader.shared.data.DictionaryRepository`；本类只保留
 * 平台侧两件事：**决定用哪个 sqlite 文件**，以及 `SQLiteDatabase` 对 [DictionaryDatabase]
 * 接口的实现（SQL 引用 [DictionarySql] 常量）。
 *
 * 文件解析链（方案-资源包系统 §3）：
 * 1. 活动词典包（`filesDir/packs/dictionary/<packId>/<version>/…`，由 [packSource] 提供）；
 * 2. 内置 assets 的落盘副本 `filesDir/dictionary/ecdict-v2.sqlite`；
 * 3. assets 本体（首次使用时拷出）。
 *
 * **活动包存在时不创建第 2 步的副本** —— 否则白占 58 MB。包文件丢失时 [packSource]
 * 返回 null，自动回退内置，查词不会因包损坏而哑掉（离线优先）。
 *
 * **线程模型**：`database` 单例缓存与 `:shared` 的 256 项词条 LRU 都非线程安全，
 * 依赖上层串行调用（既有假设，未改变）。换源必须调用 [invalidate]。
 */
class DictionaryRepository(
    context: Context,
    /** 当前活动词典包的文件；null = 用内置。由 `PackRepository.dictionarySource()` 提供。 */
    private val packSource: () -> File? = { null }
) {
    private val appContext = context.applicationContext
    private val shared = com.linguareader.shared.data.DictionaryRepository(AndroidDictionaryDatabase())
    private var database: SQLiteDatabase? = null

    /**
     * 换源后必须调用：关掉旧句柄、丢掉旧库的词条缓存。
     *
     * 不调用就会「切了等于没切」：旧 `SQLiteDatabase` 仍然指着旧文件，而 LRU 里
     * 缓存的是旧词典的释义。调用方（`AppViewModel`）在切包/卸载/恢复内置后立刻调。
     */
    fun invalidate() {
        runCatching { database?.close() }
        database = null
        shared.clearCache()
    }

    suspend fun lookup(lookup: WordLookup): DictionaryLookupResult =
        shared.lookup(lookup)

    private fun openDatabase(): SQLiteDatabase {
        val target = packSource() ?: ensureBuiltInCopy()
        return SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }

    /** 内置词典的落盘副本（首次使用时从 assets 拷出）。 */
    private fun ensureBuiltInCopy(): File {
        val databaseDir = File(appContext.filesDir, "dictionary").apply { mkdirs() }
        val target = File(databaseDir, "ecdict-v2.sqlite")
        if (!target.exists() || target.length() == 0L) {
            val temp = File(databaseDir, "ecdict-v2.sqlite.tmp")
            appContext.assets.open("dictionary/ecdict.sqlite").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temp.renameTo(target)) {
                target.outputStream().use { output ->
                    temp.inputStream().use { it.copyTo(output) }
                }
                temp.delete()
            }
        }
        return target
    }

    private inner class AndroidDictionaryDatabase : DictionaryDatabase {
        private fun db(): SQLiteDatabase = database ?: openDatabase().also { database = it }

        override fun lemmaCandidates(form: String): List<String> =
            db().rawQuery(
                DictionarySql.LEMMA_CANDIDATES,
                arrayOf(form, form)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }

        override fun queryEntry(word: String): RawDictionaryEntry? =
            db().rawQuery(DictionarySql.ENTRY, arrayOf(word)).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                RawDictionaryEntry(
                    word = cursor.getString(0),
                    phonetic = cursor.getString(1).orEmpty(),
                    translation = cursor.getString(2).orEmpty(),
                    definition = cursor.getString(3).orEmpty()
                )
            }
    }
}
