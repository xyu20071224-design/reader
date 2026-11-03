package com.linguareader.shared.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * **真正的 JS 可执行性验证**（P4 拆分阅读页脚本的关键护栏）。
 *
 * 为什么需要它：WebView 在 Robolectric 里不执行 JS，所以「拆完之后脚本还能不能跑」
 * 一直只能靠真机。但脚本开头就会把 16 个 `window.lr*` 挂到 `window` 上——这一步
 * **不依赖真实布局**。用 Node + 最小 DOM 桩真正执行一遍，就能证明：
 * 语法完整、IIFE 结构没被破坏、16 个 API 全部安装。
 *
 * 对照实验（本测试的价值所在）：
 * - 删掉一个函数的闭合花括号 → `EXEC_FAIL: Unexpected token 'return'`
 * - 把 `window.lrSetPage` 改名 → `MISSING_APIS: lrSetPage`
 * 两种破坏都能被抓到，而不是"看着像对的"。
 *
 * **覆盖不到的（仍然必须真机）**：布局测量、分页计算、触摸/滚动手势、真实字体渲染。
 * 这个测试只证明"脚本能加载并注册 API"，不证明"读者看到的是对的"。
 *
 * 环境无 node 时跳过（假设 Node 是开发机工具，不是构建必需依赖）。
 */
class ReaderJsExecutabilityTest {

    private fun nodeAvailable(): Boolean = try {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    private fun runReaderJs(bootstrapJs: File): Pair<Int, String> {
        // 执行器放在 test resources，避免把一大段 JS 逻辑塞进 Kotlin 字符串
        val runner = File("src/test/resources/reader/run-reader-js.js")
        assertTrue("缺少执行器 " + runner.absolutePath, runner.isFile)
        val process = ProcessBuilder("node", runner.absolutePath, bootstrapJs.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out: String = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return -1 to "TIMEOUT after 60s"
        }
        return process.exitValue() to out.trim()
    }

    /** 用金标准快照作输入：它正是 `bootstrap()` 的实际产物。 */
    private fun goldenFile(name: String): File {
        // 金标准在 test resources；运行时能直接拿到路径（file: 协议）
        val url = javaClass.classLoader!!.getResource("reader/" + name + ".js")
            ?: error("缺少金标准 reader/" + name + ".js")
        val uri = url.toURI()
        return if (uri.scheme == "file") File(uri) else {
            // jar 里时先落到临时文件（避免依赖 File 路径）
            val tmp = File.createTempFile(name, ".js")
            javaClass.classLoader!!.getResourceAsStream("reader/" + name + ".js")!!.use { input: InputStream ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp
        }
    }

    @Test
    fun bootstrapScriptExecutesAndInstallsAllApis() {
        assumeTrue("环境无 node，跳过 JS 可执行性验证", nodeAvailable())
        for (name in listOf("default", "night-scroll")) {
            val (code, out) = runReaderJs(goldenFile(name))
            assertEquals(
                "[" + name + "] 阅读页脚本必须能被执行且 16 个 API 全部安装，实际输出：" + out,
                0,
                code
            )
            assertTrue("[" + name + "] 期望 EXEC_OK，实际：" + out, out.contains("EXEC_OK"))
            assertTrue("[" + name + "] 必须报告 16/16，实际：" + out, out.contains("16/16"))
        }
    }

    @Test
    fun executorActuallyCatchesBrokenScripts() {
        assumeTrue("环境无 node，跳过 JS 可执行性验证", nodeAvailable())

        // 负向：删掉一个闭合花括号（语法破坏）
        val good = goldenFile("default")
        val src = good.readText()
        val marker = "function clamp(value, min, max) {"
        val idx = src.indexOf(marker)
        assertTrue("夹具失效：找不到 clamp 函数", idx > 0)
        val broken = src.substring(0, idx) +
            "function clamp(value, min, max) " +
            src.substring(idx + marker.length)
        val brokenFile = File.createTempFile("broken-reader", ".js").apply { writeText(broken) }
        val (brokenCode, brokenOut) = runReaderJs(brokenFile)
        assertTrue(
            "执行器必须能抓到语法破坏，实际输出：" + brokenOut,
            brokenCode != 0 && brokenOut.contains("EXEC_FAIL")
        )

        // 负向：改掉一个 API 名（语义破坏，仍然能解析）
        val renamed = src.replace("window.lrSetPage =", "window.lrSetPageRenamed =")
        val renamedFile = File.createTempFile("renamed-reader", ".js").apply { writeText(renamed) }
        val (renamedCode, renamedOut) = runReaderJs(renamedFile)
        assertTrue(
            "执行器必须能抓到 API 缺失，实际输出：" + renamedOut,
            renamedCode != 0 && renamedOut.contains("MISSING_APIS") && renamedOut.contains("lrSetPage")
        )
    }
}
