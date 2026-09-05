package com.linguareader.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.DictionarySql
import com.linguareader.shared.data.RawDictionaryEntry
import java.io.File

/**
 * 词典仓库的 Android 壳（桌面迁移 M2 刀2）。
 *
 * 查词逻辑的真相已迁入 `com.linguareader.shared.data.DictionaryRepository`；
 * 本类只保留平台侧两件事：assets 里 58 MB 词典的落盘初始化，以及
 * `SQLiteDatabase` 对 [DictionaryDatabase] 接口的实现（SQL 引用
 * [DictionarySql] 常量，与桌面 sqlite-jdbc 实现及 DictionarySqlParityTest 共用唯一真相）。
 */
class DictionaryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val shared = com.linguareader.shared.data.DictionaryRepository(AndroidDictionaryDatabase())
    private var database: SQLiteDatabase? = null

    suspend fun lookup(lookup: WordLookup): DictionaryLookupResult =
        shared.lookup(lookup)

    private fun openDatabase(): SQLiteDatabase {
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
        return SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
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
