package com.linguareader.app.data

/** A single result row; columns are positional and coerced to String. */
class SqlRow(private val values: List<Any?>) {
    fun string(index: Int): String = values.getOrNull(index) as? String ?: ""
}

/** Read-only SQLite access abstraction (Android framework DB / desktop JDBC). */
expect class SqliteDatabase(path: String) : AutoCloseable {
    fun query(sql: String, args: Array<String>): List<SqlRow>
}
