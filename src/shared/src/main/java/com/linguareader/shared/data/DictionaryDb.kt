package com.linguareader.shared.data

/**
 * 词典驱动抽象（迁移方案 §4「离线词典」行，M2 落地）。
 *
 * Android 用 `SQLiteDatabase`、桌面用 sqlite-jdbc 实现同一接口；SQL 语句收敛到
 * [DictionarySql] 唯一真相，两端实现与 `DictionarySqlParityTest` 对账测试都引用它，
 * 不允许任何一边悄悄改写查询语义。
 */

/** 词典原始条目（queries 的行投影）。 */
data class RawDictionaryEntry(
    val word: String,
    val phonetic: String,
    val translation: String,
    val definition: String
)

/** 两条查询 SQL 的唯一真相：改动任何一边的实现前必须先改这里。 */
object DictionarySql {
    /** 词形 → 原形候选，精确命中优先、短词优先，最多 4 条。 */
    val LEMMA_CANDIDATES = """
        SELECT f.lemma
        FROM forms f
        JOIN entries e ON e.word = f.lemma
        WHERE f.form = ?
        ORDER BY CASE WHEN f.lemma = ? THEN 1 ELSE 0 END, length(f.lemma)
        LIMIT 4
        """.trimIndent()

    /** 精确 headword 查条目。 */
    val ENTRY = "SELECT word, phonetic, translation, definition FROM entries WHERE word = ? LIMIT 1"
}

interface DictionaryDatabase {
    /** [DictionarySql.LEMMA_CANDIDATES] 的语义：有序 lemma 候选。 */
    fun lemmaCandidates(form: String): List<String>

    /** [DictionarySql.ENTRY] 的语义：无命中返回 null。 */
    fun queryEntry(word: String): RawDictionaryEntry?
}
