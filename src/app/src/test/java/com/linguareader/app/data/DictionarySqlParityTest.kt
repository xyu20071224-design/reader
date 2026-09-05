package com.linguareader.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 桌面迁移 M1 的 SQL 语义对账（迁移方案 §4「离线词典」行的证据，不是断言）。
 *
 * `DictionaryRepository` 用 `SQLiteDatabase`（READONLY | NO_LOCALIZED_COLLATORS）
 * 打开 ecdict.sqlite；桌面计划换成 sqlite-jdbc。本测试把**同一份文件**同时用两个
 * 引擎打开，逐字照抄 `DictionaryRepository` 的两条 SQL，对同一批探测词比对
 * 有序结果集——大小写、排序规则（ORDER BY CASE/length）、`\n` 字面量
 * （known-pitfalls 的转义陷阱）、词组主键，任何一个不等价都会在这里现形。
 *
 * **对账修正（2026-09-05）**：本测试初稿曾断言「裸 BINARY、大写查询不命中」，
 * 第一次运行即被证伪——`src/scripts/build_dictionary.py` 在**列上**声明了
 * `COLLATE NOCASE`（word/form/lemma 三处），大小写不敏感是 schema 自带行为；
 * Android 的 NO_LOCALIZED_COLLATORS 只禁用 ICU 本地化排序，不覆盖列级 COLLATE。
 * 两端等价的根据从「都是 BINARY」改成「两引擎同样尊重 schema 声明的 NOCASE」，
 * 方向相反、结论更强：等价性由本测试机械验证，不靠推测。
 *
 * **驱动坑（M1 实测）**：sqlite-jdbc 对 `jdbc:sqlite:<盘符路径>?mode=ro` 的查询串
 * **不解析**——整串被当文件名，Windows 上报「文件名、目录名或卷标语法不正确」。
 * 只读要用驱动的 `open_mode` 连接属性（1 = SQLITE_OPEN_READONLY），不能用 URI 后缀。
 */
@RunWith(RobolectricTestRunner::class)
class DictionarySqlParityTest {

    private lateinit var androidDb: SQLiteDatabase
    private lateinit var dbPath: String

    private val readOnlyProps = Properties().apply { setProperty("open_mode", "1") }

    private fun connection(): Connection = DriverManager.getConnection(dbPath, readOnlyProps)

    @Before
    fun openBothEnginesOnSameFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 58 MB 整本复制每个测试方法都要付一次，测试类收敛到最小集合是有意为之；
        // 若嫌慢，可改 @BeforeClass 共享，先保持与其它 Robolectric 测试同构。
        val target = File(context.filesDir, "parity-ecdict.sqlite")
        if (!target.exists() || target.length() == 0L) {
            context.assets.open("dictionary/ecdict.sqlite").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        androidDb = SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        dbPath = "jdbc:sqlite:${target.absolutePath}"
    }

    // ——— 与 DictionaryRepository 逐字一致的两条 SQL ———

    private fun androidLemmas(form: String): List<String> =
        androidDb.rawQuery(
            """
            SELECT f.lemma
            FROM forms f
            JOIN entries e ON e.word = f.lemma
            WHERE f.form = ?
            ORDER BY CASE WHEN f.lemma = ? THEN 1 ELSE 0 END, length(f.lemma)
            LIMIT 4
            """.trimIndent(),
            arrayOf(form, form)
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun jdbcLemmas(form: String): List<String> =
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT f.lemma
                FROM forms f
                JOIN entries e ON e.word = f.lemma
                WHERE f.form = ?
                ORDER BY CASE WHEN f.lemma = ? THEN 1 ELSE 0 END, length(f.lemma)
                LIMIT 4
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, form); ps.setString(2, form)
                ps.executeQuery().use { c -> buildList { while (c.next()) add(c.getString(1)) } }
            }
        }

    private fun androidEntry(word: String): List<String?>? =
        androidDb.rawQuery(
            "SELECT word, phonetic, translation, definition FROM entries WHERE word = ? LIMIT 1",
            arrayOf(word)
        ).use { c -> if (!c.moveToNext()) null else (0 until c.columnCount).map { c.getString(it) } }

    private fun jdbcEntry(word: String): List<String?>? =
        connection().use { conn ->
            conn.prepareStatement(
                "SELECT word, phonetic, translation, definition FROM entries WHERE word = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, word)
                ps.executeQuery().use { c ->
                    if (!c.next()) null else (1..4).map { c.getString(it) }
                }
            }
        }

    private fun both(sql: String, args: List<String> = emptyList()): Pair<List<List<String?>>, List<List<String?>>> {
        val a = androidDb.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { i -> c.getString(i) }) }
        }
        val j = connection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                ps.executeQuery().use { c ->
                    buildList { while (c.next()) add((1..c.metaData.columnCount).map { i -> c.getString(i) }) }
                }
            }
        }
        return a to j
    }

    @Test
    fun lemmaCandidates_identicalOrderedRows_onEveryProbe() {
        val probes = listOf(
            "running", "houses", "children", "went", "better", "studies",
            "in spite of", "have got to", "got", "be", "I", "you",
            "RUNNING", "Running", // schema 是 NOCASE：应命中且有序一致
            "don’t", "’", // 非 ASCII 撇号（词典里的 ’ 键）
            "", "no-such-word-xyzzy"
        )
        for (p in probes) {
            assertEquals("lemma 有序结果不一致: [$p]", androidLemmas(p), jdbcLemmas(p))
        }
    }

    @Test
    fun entry_identicalRows_andNewlineLiteralSurvivesBoth() {
        val probes = listOf("running", "houses", "child", "in spite of", "be", "no-such-word-xyzzy")
        for (p in probes) {
            assertEquals("entry 行不一致: [$p]", androidEntry(p), jdbcEntry(p))
        }
        // known-pitfalls 的 `\n` 字面量陷阱：translation 里存的是字面反斜杠+n，
        // 两个引擎都必须原样返回（不被任何驱动偷偷反转义）。
        val running = androidEntry("running")!!
        assertTrue("词典 translation 应含字面 \\n（若此断言失败先查测试基线）", running[2]?.contains("\\n") == true)
        assertEquals(running, jdbcEntry("running"))
    }

    @Test
    fun wholeTable_semanticsProbes() {
        // count 一致性 + 词典没用但译本记忆可能用到的面：LIKE、GROUP BY、|| 拼接。
        // 断言的是「两引擎结果逐行相等」；下面的地板值只做「文件确实打开了、有数据」的
        // 兜底（entries 实测 770611 行，:desktopApp 探针打印过），不承诺具体量级。
        val (a1, j1) = both("SELECT count(*) FROM forms")
        assertEquals(a1, j1)
        val (a2, j2) = both("SELECT lemma, count(*) AS c FROM forms GROUP BY lemma HAVING c > 20 ORDER BY c DESC, lemma LIMIT 25")
        assertEquals(a2, j2)
        val (a3, j3) = both("SELECT word FROM entries WHERE word LIKE 'in %' ORDER BY word LIMIT 50")
        assertEquals(a3, j3)
        val (a4, j4) = both("SELECT 'a' || word FROM entries ORDER BY word LIMIT 50")
        assertEquals(a4, j4)
        assertTrue("forms 表非空", (a1.first().first() as String).toLong() > 0)
        assertTrue("LIKE 探测应有命中", a3.isNotEmpty())
    }

    @Test
    fun caseInsensitivity_isSchemaLevel_nocase_onBoth() {
        // 锁死等价前提本身（方向已由 build_dictionary.py 的 COLLATE NOCASE 修正）：
        // 大写查询命中同一条目，且两引擎逐字段一致。桌面 actual 若哪天查不到大写词，
        // 一定是实现偏离了 schema 语义，这里会红。
        val lower = androidEntry("running")
        val upper = androidEntry("RUNNING")
        assertTrue("Android 侧 NOCASE 前提破了（大写应命中）", upper != null)
        assertEquals("Android 侧大写与小写应命中同一行", lower, upper)
        assertEquals(lower, jdbcEntry("running"))
        assertEquals(upper, jdbcEntry("RUNNING"))
    }
}
