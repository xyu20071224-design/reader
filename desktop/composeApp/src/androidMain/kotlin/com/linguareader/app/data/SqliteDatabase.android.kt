package com.linguareader.app.data

import android.database.sqlite.SQLiteDatabase

actual class SqliteDatabase actual constructor(path: String) : AutoCloseable {
    private val database = SQLiteDatabase.openDatabase(
        path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    )

    actual fun query(sql: String, args: Array<String>): List<SqlRow> {
        return database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(SqlRow((0 until cursor.columnCount).map { index -> cursor.getString(index) }))
                }
            }
        }
    }

    override fun close() {
        database.close()
    }
}
