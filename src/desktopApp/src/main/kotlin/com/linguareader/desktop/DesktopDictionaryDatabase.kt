package com.linguareader.desktop

import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.DictionarySql
import com.linguareader.shared.data.RawDictionaryEntry
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * 桌面侧词典引擎（迁移方案 §4「离线词典」行，M2 刀8）：
 * sqlite-jdbc 实现 :shared 的 [DictionaryDatabase]，SQL 引用 [DictionarySql]
 * 唯一真相（与 Android `SQLiteDatabase` 实现及 DictionarySqlParityTest 同源，
 * M1 已双引擎对账）。只读：连接属性 open_mode=1（sqlite-jdbc 不解析 URI 后缀，
 * known-pitfalls §26）。
 */
class DesktopDictionaryDatabase(private val connection: Connection) : DictionaryDatabase {
    override fun lemmaCandidates(form: String): List<String> =
        connection.prepareStatement(DictionarySql.LEMMA_CANDIDATES).use { ps ->
            ps.setString(1, form)
            ps.setString(2, form)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    override fun queryEntry(word: String): RawDictionaryEntry? =
        connection.prepareStatement(DictionarySql.ENTRY).use { ps ->
            ps.setString(1, word)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else RawDictionaryEntry(
                    word = rs.getString(1),
                    phonetic = rs.getString(2) ?: "",
                    translation = rs.getString(3) ?: "",
                    definition = rs.getString(4) ?: ""
                )
            }
        }

    fun close() = connection.close()

    companion object {
        /**
         * 词典文件解析顺序：`-Dlr.dict=<路径>` → `<home>/dictionary/ecdict.sqlite`
         * （可从手机侧拷贝）→ 开发仓库的 assets（`../app/src/main/assets/...`，桌面 UI 阶段的便利路径）。
         */
        fun resolve(home: File): File? {
            System.getProperty("lr.dict")?.let { p ->
                val f = File(p)
                if (f.isFile) return f
            }
            File(File(home, "dictionary"), "ecdict.sqlite").takeIf { it.isFile }?.let { return it }
            // 开发便利：直接用仓库 assets 里的同一份词典（桌面 UI 阶段）。
            val candidates = listOf(
                File(System.getProperty("user.dir"), "../app/src/main/assets/dictionary/ecdict.sqlite"),
                File(System.getProperty("user.dir"), "app/src/main/assets/dictionary/ecdict.sqlite")
            )
            return candidates.map { it.canonicalFile }.firstOrNull { it.isFile }
        }
        fun open(dictionary: File): DesktopDictionaryDatabase {
            val props = Properties().apply { setProperty("open_mode", "1") }
            val connection = DriverManager.getConnection("jdbc:sqlite:${dictionary.absolutePath}", props)
            return DesktopDictionaryDatabase(connection)
        }
    }
}
