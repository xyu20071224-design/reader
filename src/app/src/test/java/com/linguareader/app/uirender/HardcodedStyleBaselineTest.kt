package com.linguareader.app.uirender

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 硬编码样式基线：把「不得裸写样式」这条约束变成可断言的数字。
 *
 * 迁移期间**记录基线**，每迁一屏只允许下降。这里不断言"零"，因为还没迁完；
 * 断言的是"总量不超过基线"——防止重构过程中又新塞进硬编码。
 */
class HardcodedStyleBaselineTest {

    private val uiDir = File("src/main/java/com/linguareader/app")

    @Test
    fun hardcodedStyleTokensDoNotGrow() {
        assertTrue("UI 源码目录不存在：" + uiDir.absolutePath, uiDir.isDirectory)
        val stats = HardcodedStyleScanner.scanDir(uiDir)
        val report = HardcodedStyleScanner.report(stats)
        println(report)

        val total = stats.sumOf { it.total }
        // 基线：2026-11-04 重构开工时实测。迁移过程中只许降，不许升。
        val baseline = BASELINE_TOTAL
        assertTrue(
            "硬编码样式总数从基线 " + baseline + " 涨到 " + total + "；新增代码请走 design token。\n" + report,
            total <= baseline
        )
    }

    @Test
    fun reportIsGenerated() {
        val stats = HardcodedStyleScanner.scanDir(uiDir)
        val out = File("build/ui-render-probe/hardcoded-style-baseline.txt")
        out.parentFile?.mkdirs()
        out.writeText(HardcodedStyleScanner.report(stats))
        println("BASELINE_REPORT=" + out.absolutePath)
    }

    private companion object {
        /** 2026-11-04 重构开工基线（见 build/ui-render-probe/hardcoded-style-baseline.txt）。 */
        const val BASELINE_TOTAL = 4000
    }
}
