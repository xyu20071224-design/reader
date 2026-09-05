package com.linguareader.desktop

import java.io.File
import java.sql.DriverManager

/**
 * 桌面侧词典只读访问的冒烟探针（迁移方案 M1 验收项）。
 *
 * 目的不是做产品功能，而是把「Android `SQLiteDatabase` → 桌面 `sqlite-jdbc`」
 * 这一平台替换的风险提前打掉：同一份 58 MB `ecdict.sqlite`，用 `DictionaryRepository`
 * 里原样的两条 SQL 去查，确认驱动能开、结果能出。
 *
 * 与 Android 侧的**结果对账**在 `:app` 的 `DictionarySqlParityTest`（Robolectric 真开
 * SQLiteDatabase 与 JDBC 逐行比对），这里只跑桌面一侧。
 *
 * TODO(M4): :desktopApp 正式化后，本文件与探针式路径引用一并删除或改造成设置页诊断。
 */
private const val ASSET_DB = "../app/src/main/assets/dictionary/ecdict.sqlite"

/** `DictionaryRepository.lemmaCandidates` 的同款 SQL，逐字照抄。 */
private const val SQL_LEMMA = """
    SELECT f.lemma
    FROM forms f
    JOIN entries e ON e.word = f.lemma
    WHERE f.form = ?
    ORDER BY CASE WHEN f.lemma = ? THEN 1 ELSE 0 END, length(f.lemma)
    LIMIT 4
"""

/** `DictionaryRepository.queryEntry` 的同款 SQL。 */
private const val SQL_ENTRY =
    "SELECT word, phonetic, translation, definition FROM entries WHERE word = ? LIMIT 1"

private fun resolveDb(): File {
    val fromEnv = System.getProperty("lr.dict")?.let(::File)
    if (fromEnv != null && fromEnv.exists()) return fromEnv
    val candidate = File(File(System.getProperty("user.dir")), ASSET_DB).canonicalFile
    check(candidate.exists()) { "找不到词典：$candidate（可用 -Dlr.dict=<路径> 指定）" }
    return candidate
}

private fun open(path: String) =
    // 只读打开，等价于 Android 侧的 OPEN_READONLY。
    // 注意：sqlite-jdbc 不解析 `jdbc:sqlite:<盘符路径>?mode=ro` 这种查询串（整串被当
    // 文件名，Windows 直接报错）；只读要用驱动的 open_mode 属性（1 = SQLITE_OPEN_READONLY）。
    // 该结论由 :app 的 DictionarySqlParityTest 实测钉死（2026-09-05）。
    DriverManager.getConnection("jdbc:sqlite:$path", java.util.Properties().apply {
        setProperty("open_mode", "1")
    })

fun main(args: Array<String>) {
    val db = resolveDb()
    println("词典：${db.absolutePath}  (${db.length() / 1024 / 1024} MB)")
    open(db.absolutePath).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM entries").use { rs ->
                check(rs.next())
                println("entries 行数：${rs.getLong(1)}")
            }
        }
        val probes = if (args.isNotEmpty()) args.toList() else listOf("running", "houses", "child", "in spite of")
        for (word in probes) {
            conn.prepareStatement(SQL_LEMMA).use { ps ->
                ps.setString(1, word); ps.setString(2, word)
                ps.executeQuery().use { rs ->
                    val lemmas = buildList { while (rs.next()) add(rs.getString(1)) }
                    println("[$word] lemma -> $lemmas")
                }
            }
            conn.prepareStatement(SQL_ENTRY).use { ps ->
                ps.setString(1, word)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        println("       entry -> ${rs.getString(1)} / ${rs.getString(2)} / " +
                            rs.getString(3).take(60).replace("\n", "\\n"))
                    } else {
                        println("       entry -> (无命中，走词形还原结果)")
                    }
                }
            }
        }
    }
    println("OK：sqlite-jdbc 能以只读方式驱动 Android 用的同一份词典。")
}
