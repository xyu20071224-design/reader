package com.linguareader.app.data

import java.sql.DriverManager

actual class SqliteDatabase actual constructor(path: String) : AutoCloseable {
    private val connection = DriverManager.getConnection("jdbc:sqlite:$path")

    actual fun query(sql: String, args: Array<String>): List<SqlRow> {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { result ->
                val columnCount = result.metaData.columnCount
                return buildList {
                    while (result.next()) {
                        add(SqlRow((1..columnCount).map { index -> result.getString(index) }))
                    }
                }
            }
        }
    }

    override fun close() {
        connection.close()
    }
}
