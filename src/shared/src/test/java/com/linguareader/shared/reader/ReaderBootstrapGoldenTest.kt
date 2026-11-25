package com.linguareader.shared.reader

import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.data.ReaderFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * `bootstrap()` 的**金标准快照**（P4 拆分阅读页注入脚本的安全网）。
 *
 * 为什么需要它：`bootstrap()` 产出的是一段 69KB 的 JS 字符串，WebView 在 Robolectric
 * 里不执行 JS，所以"拆完之后还能不能跑"单测抓不到。唯一可靠的做法是**锁住字符级输出**：
 * 拆分前后 `bootstrap()` 必须逐字节相同。
 *
 * 用法：
 * 1. 拆分**之前**跑一次，产物落到 `build/reader-bootstrap-golden/`；
 * 2. 拆分**之后**再跑，与旧快照逐字符比对；
 * 3. 全部通过后，把快照提交到 `src/shared/src/test/resources/reader/` 作为长期基线。
 *
 * 一旦基线入库，任何无意的脚本改动（少一个反引号、多一个分号）都会让这条测试红。
 */
class ReaderBootstrapGoldenTest {

    /** 覆盖所有会影响输出的参数组合，每个组合一个快照文件。 */
    private val cases: List<Triple<String, ReaderPreferences, Array<Any>>> = listOf(
        Triple(
            "default",
            ReaderPreferences(),
            arrayOf(0)
        ),
        Triple(
            "night-scroll",
            ReaderPreferences(theme = ReaderTheme.DARK, fontPercent = 120, lineHeight = 1.8f, fontFamily = ReaderFont.SANS),
            arrayOf(1)
        )
    )

    private fun snapshotOf(prefs: ReaderPreferences, page: Int): String =
        ReaderScripts.bootstrap(
            initialPage = page,
            preferences = prefs,
            initialScrollMode = false,
            initialScrollRatio = 0f,
            initialScrollPageCount = 1
        )

    @Test
    fun bootstrapOutputIsStable() {
        val writeMode = System.getenv("LR_WRITE_GOLDEN") == "1"
        val outDir = File("build/reader-bootstrap-golden").apply { check(isDirectory || mkdirs()) }
        val environment = captureEnvironment(outDir)
        val failures = mutableListOf<String>()
        for ((name, prefs, pages) in cases) {
            val page = pages[0] as Int
            val actual = snapshotOf(prefs, page)
            val resource = "reader/$name.js"
            val expected = javaClass.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() }
            val checked = checkSnapshot(name, expected, actual.toByteArray(Charsets.UTF_8), writeMode, outDir)
            val sourcePath = "src/shared/src/test/resources/$resource"
            val head = git(outDir, "$name.git-head.js", "show", "HEAD:$sourcePath")
            val root = environment.optString("gitRoot")
            val worktree = if (root.isNotEmpty()) File(root, sourcePath).takeIf { it.isFile }?.readBytes() else null
            if (worktree != null) File(outDir, "$name.worktree.js").writeBytes(worktree)
            checked.put("resourceUrl", javaClass.classLoader?.getResource(resource)?.toString() ?: JSONObject.NULL)
                .put("parameters", prefs.toString()).put("page", page)
                .put("gitHead", head.second).put("gitHeadBytes", metrics(head.first))
                .put("worktreeBytes", metrics(worktree))
            File(outDir, "$name.metadata.json").writeText(checked.toString(2), Charsets.UTF_8)
            for (failure in checked.getJSONArray("failures")) failures += failure.toString()
            if (actual.length !in 40_000..120_000) {
                failures += "[$name] bootstrap length outside 40000..120000: ${actual.length}"
            }
            println("BOOTSTRAP_SNAPSHOT=$name ${checked.getJSONObject("actual")} errors=${checked.getJSONArray("failures")} diagnostics=${outDir.absolutePath}")
        }
        // Compare every case before failing, so Windows also provides the second snapshot's raw bytes.
        assertEquals("Exact UTF-8 snapshot verification failed; inspect ${outDir.absolutePath}", emptyList<String>(), failures)
    }

    /** No trim, newline/locale conversion, or missing-reference generation is permitted in verification. */
    private fun checkSnapshot(name: String, expected: ByteArray?, actual: ByteArray, writeMode: Boolean, outDir: File): JSONObject {
        val failures = mutableListOf<String>()
        File(outDir, "$name.actual.js").writeBytes(actual)
        val reference = File(outDir, "$name.expected.js")
        if (expected != null) reference.writeBytes(expected) else reference.delete()
        if (expected == null) failures += "[$name] Missing committed reference reader/$name.js"
        else if (!expected.contentEquals(actual)) failures += "[$name] Raw UTF-8 bytes differ: expected=${expected.size}, actual=${actual.size}"
        val candidate = File(outDir, "$name.js")
        if (writeMode) {
            candidate.writeBytes(actual)
            failures += "[$name] LR_WRITE_GOLDEN generated a candidate for review; this is not a passing verification"
        } else candidate.delete()
        val differences = JSONArray()
        if (expected != null) for (i in 0 until maxOf(expected.size, actual.size)) {
            if (expected.getOrNull(i) != actual.getOrNull(i)) {
                differences.put(JSONObject().put("offset", i)
                    .put("expected", expected.getOrNull(i)?.toInt()?.and(255) ?: JSONObject.NULL)
                    .put("actual", actual.getOrNull(i)?.toInt()?.and(255) ?: JSONObject.NULL))
                if (differences.length() == 16) break
            }
        }
        return JSONObject().put("name", name).put("writeMode", writeMode).put("comparison", "raw UTF-8 bytes; no normalization")
            .put("expected", metrics(expected)).put("actual", metrics(actual)).put("firstDifferences", differences)
            .put("failures", JSONArray(failures))
    }

    private fun metrics(bytes: ByteArray?): Any {
        if (bytes == null) return JSONObject.NULL
        var crlf = 0; var lf = 0; var cr = 0
        bytes.forEachIndexed { i, byte ->
            if (byte == 10.toByte()) { if (i > 0 && bytes[i - 1] == 13.toByte()) crlf++ else lf++ }
            if (byte == 13.toByte() && bytes.getOrNull(i + 1) != 10.toByte()) cr++
        }
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).length }.getOrNull()
        return JSONObject().put("bytes", bytes.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("crlf", crlf).put("loneLf", lf).put("loneCr", cr).put("utf8Valid", utf8 != null)
            .put("utf16Length", utf8 ?: JSONObject.NULL)
            .put("utf8Bom", bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte())
    }

    private fun captureEnvironment(outDir: File): JSONObject {
        val data = JSONObject().put("defaultCharset", Charset.defaultCharset().name()).put("locale", Locale.getDefault().toLanguageTag())
            .put("lineSeparatorBytes", JSONArray(System.lineSeparator().toByteArray(Charsets.UTF_8).map { it.toInt() and 255 }))
        val properties = JSONObject()
        for (key in listOf("os.name", "os.version", "os.arch", "java.version", "java.runtime.version", "java.vendor", "file.encoding", "native.encoding", "sun.jnu.encoding", "user.language", "user.country", "user.dir"))
            properties.put(key, System.getProperty(key) ?: JSONObject.NULL)
        data.put("properties", properties)
        val root = git(outDir, "git-root.txt", "rev-parse", "--show-toplevel")
        data.put("gitRootCommand", root.second).put("gitRoot", root.first?.toString(Charsets.UTF_8)?.trimEnd('\r', '\n') ?: "")
        val commands = JSONObject()
        for (key in listOf("core.autocrlf", "core.eol")) commands.put(key, git(outDir, "$key.txt", "config", "--show-origin", "--get", key).second)
        val paths = cases.map { "src/shared/src/test/resources/reader/${it.first}.js" }.toTypedArray()
        commands.put("eol", git(outDir, "git-eol.txt", "ls-files", "--eol", "--", *paths).second)
        commands.put("attributes", git(outDir, "git-attributes.txt", "check-attr", "--all", "--", *paths).second)
        commands.put("head", git(outDir, "git-head.txt", "rev-parse", "HEAD").second)
        data.put("git", commands)
        File(outDir, "environment.json").writeText(data.toString(2), Charsets.UTF_8)
        return data
    }

    /** Read-only diagnostics only; missing Git is recorded and never changes snapshot comparison. */
    private fun git(outDir: File, name: String, vararg args: String): Pair<ByteArray?, JSONObject> {
        val output = File(outDir, name); val error = File(outDir, "$name.stderr.txt")
        val metadata = JSONObject().put("args", JSONArray(args.toList()))
        return try {
            val process = ProcessBuilder(listOf("git") + args).redirectOutput(output).redirectError(error).start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            val exit = if (process.isAlive) -1 else process.exitValue()
            metadata.put("exit", exit).put("timeout", !finished)
                .put("stderr", error.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.take(4096) ?: "")
            val bytes = output.takeIf { exit == 0 && it.isFile }?.readBytes()
            if (bytes == null) output.delete()
            if (!name.endsWith(".js")) metadata.put("stdout", bytes?.toString(Charsets.UTF_8) ?: "")
            bytes to metadata
        } catch (failure: Exception) { metadata.put("error", failure.toString()); null to metadata }
    }

    @Test
    fun missingReferenceFailsInsteadOfGeneratingAPassingBaseline() = diagnosticDirectory { directory ->
        val actual = "missing reference\n".toByteArray(Charsets.UTF_8)
        val report = checkSnapshot("missing", null, actual, false, directory)
        assertEquals(1, report.getJSONArray("failures").length())
        assertTrue(report.getJSONArray("failures").getString(0).contains("Missing committed reference"))
        assertFalse(File(directory, "missing.js").exists())
        assertFalse(File(directory, "missing.expected.js").exists())
        assertArrayEquals(actual, File(directory, "missing.actual.js").readBytes())
    }

    @Test
    fun explicitGenerationProducesAReviewCandidateAndNeverAPassingVerification() = diagnosticDirectory { directory ->
        val bytes = "same bytes\n".toByteArray(Charsets.UTF_8)
        val report = checkSnapshot("candidate", bytes, bytes, true, directory)
        assertEquals(1, report.getJSONArray("failures").length())
        assertTrue(report.getJSONArray("failures").getString(0).contains("not a passing verification"))
        assertArrayEquals(bytes, File(directory, "candidate.js").readBytes())
        assertArrayEquals(bytes, File(directory, "candidate.expected.js").readBytes())
    }

    @Test
    fun rawNewlinesBomAndTrailingWhitespaceAreNotNormalized() = diagnosticDirectory { directory ->
        val actual = "line\n".toByteArray(Charsets.UTF_8)
        val references = listOf("line\r\n".toByteArray(Charsets.UTF_8),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + actual,
            "line \n".toByteArray(Charsets.UTF_8))
        val reports = references.mapIndexed { i, expected -> checkSnapshot("raw-$i", expected, actual, false, directory) }
        for ((index, report) in reports.withIndex()) {
            assertEquals(1, report.getJSONArray("failures").length())
            assertTrue(report.getJSONArray("firstDifferences").length() > 0)
            assertArrayEquals(references[index], File(directory, "raw-$index.expected.js").readBytes())
            assertArrayEquals(actual, File(directory, "raw-$index.actual.js").readBytes())
        }
        assertEquals(1, reports[0].getJSONObject("expected").getInt("crlf"))
        assertEquals(0, reports[0].getJSONObject("actual").getInt("crlf"))
        assertTrue(reports[1].getJSONObject("expected").getBoolean("utf8Bom"))
        assertFalse(reports[1].getJSONObject("actual").getBoolean("utf8Bom"))
    }

    private fun diagnosticDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("reader-bootstrap-contract-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test
    fun goldenCoversAllSixteenApis() {
        // 金标准不是随便一段字符串：必须真的含 16 个 API（防止快照拍到了残缺版本）
        val js = snapshotOf(ReaderPreferences(), 0)
        val apis = listOf(
            "lrTurn", "lrSetPage", "lrGetPage", "lrSyncPage",
            "lrEnterScrollMode", "lrExitScrollMode",
            "lrLookupAt", "lrHighlightBlock", "lrHighlightSentence", "lrClearHighlight",
            "lrRefreshSavedWords", "lrSetChromeInsets", "lrSetChoosingStart",
            "lrLocusHere", "lrScrollToLocus", "lrBackToSpeaking"
        )
        val missing = apis.filterNot { js.contains("window." + it) }
        assertEquals("金标准快照里缺 API：" + missing, emptyList<String>(), missing)
    }
}
