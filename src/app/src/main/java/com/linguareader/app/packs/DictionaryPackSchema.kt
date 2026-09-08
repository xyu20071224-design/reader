package com.linguareader.app.packs

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 词典包的表结构探针。
 *
 * 只校验 SHA-256 是不够的：一个内容完好但 schema 不同的 sqlite 也能通过哈希对账，
 * 装上去以后查词全部落空 —— 而表现是「词典突然没释义了」，用户根本联想不到是包的问题。
 * 所以安装时把 [DictionarySql] 依赖的两张表和列当面点一遍，不合格直接拒装。
 *
 * 与 `DictionarySqlParityTest` 一样，这里认的是「最小可用 schema」，不是逐列全等：
 * 第三方词库多几列不影响查询，少一列才会出事。
 */
object DictionaryPackSchema {

    private val REQUIRED_COLUMNS = mapOf(
        "entries" to setOf("word", "phonetic", "translation", "definition"),
        "forms" to setOf("form", "lemma")
    )

    /** @return null 表示合格；否则是面向用户的拒绝原因。 */
    fun probe(file: File): String? {
        if (!file.isFile || file.length() == 0L) return "词典包词库文件缺失"
        return runCatching {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { db ->
                REQUIRED_COLUMNS.entries.firstNotNullOfOrNull { (table, columns) ->
                    if (!hasTable(db, table)) {
                        "词典包格式不兼容：缺少 $table 表"
                    } else {
                        val present = tableColumns(db, table)
                        val missing = columns - present
                        if (missing.isEmpty()) null
                        else "词典包格式不兼容：$table 表缺少列 ${missing.joinToString()}"
                    }
                }
            }
        }.getOrElse { error ->
            "词典包无法打开：${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) add(cursor.getString(nameIndex))
                }
            }
        }
}
