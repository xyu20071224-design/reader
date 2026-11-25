package com.linguareader.app.uirender

import android.graphics.Bitmap
import java.io.File

/**
 * **视觉回归基线比对**（P8：把「出图」升级成「可断言的视觉回归」）。
 *
 * ## 解决什么问题
 *
 * 上一轮之前，「视觉验收」的做法是：跑测试出 PNG、人眼看一眼。这有三个问题：
 * 1. **不可回归** —— 改完当时看着对，两周后某次重构把它改坏了，没人会发现；
 * 2. **不可自动化** —— CI 跑不了「人眼」；
 * 3. **判据模糊** —— 「看着差不多」不是判据。
 *
 * 本类把「图」变成「可断言的东西」：**基线 PNG 入库，实际渲染与基线做像素差，
 * 差异超过阈值就让测试红。**
 *
 * ## 设计取舍（重要）
 *
 * **不要求逐像素完全相同**。原因：
 * - Robolectric 的字体光栅化在不同 JDK / 平台上可能有 1px 级抖动；
 * - 抗锯齿、圆角、阴影边缘天然有微小差异。
 *
 * 判据是**两个可调阈值**：
 * - [DIFF_RATIO_THRESHOLD]：差异像素占比上限（默认 0.5%）；
 * - [DIFF_DELTA_THRESHOLD]：单像素通道差值超过多少才算「不同」（默认 12/255）。
 *
 * 先按通道阈值统计不同像素，再按占比判红；最大通道差仅用于诊断，
 * 避免「大面积 1/255 抖动」和「少数点大变化」互相误伤。
 *
 * ## 基线怎么建
 *
 * ```
 * $env:UPDATE_VISUAL_BASELINE='1'   # 或 -DupdateVisualBaseline=true
 * ./gradlew :app:testDebugUnitTest --tests '...'
 * ```
 * 
 * 基线落在 `src/app/src/test/resources/visual-baseline/<name>.png`（**要提交入库**）。
 * [compare] 始终只读；缺图或解码失败必须判红。经人工审阅后，测试夹具才能在显式
 * 更新模式调用 [generate]。生成不是验收；必须退出更新模式后重新执行普通比对。
 *
 * ## 覆盖不到什么
 *
 * 它只比「画出来的像素」。**滚动、手势、动画、WebView、真实字体渲染** 全测不到。
 * 那些仍然必须真机。
 */
object VisualBaseline {

    /** 差异像素占比上限。0.005 = 0.5%。 */
    private const val DIFF_RATIO_THRESHOLD = 0.005

    /** 单通道差值超过该值才计入「不同像素」。12/255 ≈ 5%。 */
    private const val DIFF_DELTA_THRESHOLD = 12

    private const val BASELINE_DIR = "src/test/resources/visual-baseline"

    /** 是否处于「更新基线」模式。 */
    fun isUpdateMode(): Boolean =
        System.getenv("UPDATE_VISUAL_BASELINE") == "1" ||
            System.getProperty("updateVisualBaseline") == "true"

    /** 比对结果。 */
    data class Result(
        val baselineMissing: Boolean,
        val diffPixels: Int,
        val totalPixels: Int,
        val worstDelta: Int,
        val baselineError: String? = null
    ) {
        val diffRatio: Double get() = if (totalPixels == 0) 0.0 else diffPixels.toDouble() / totalPixels
        val passed: Boolean get() = !baselineMissing && baselineError == null &&
            diffRatio <= DIFF_RATIO_THRESHOLD
    }

    /**
     * 只读比对。更新环境变量也不会让本方法创建或覆盖参考图。
     *
     * @return 比对结果；[Result.passed] 为 false 时调用方应让测试失败并打印差异摘要
     */
    fun compare(name: String, actual: Bitmap): Result {
        val file = File(BASELINE_DIR, "$name.png")

        if (!file.isFile) {
            return Result(baselineMissing = true, diffPixels = 0, totalPixels = 0, worstDelta = 0,
                baselineError = "参考图不存在：${file.absolutePath}")
        }

        val baseline = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: return Result(baselineMissing = false, diffPixels = 0, totalPixels = 0, worstDelta = 0,
                baselineError = "参考图无法解码：${file.absolutePath}")

        // 尺寸不一致 = 布局塌了，直接判红（比逐像素比对更早发现问题）
        require(baseline.width == actual.width && baseline.height == actual.height) {
            "视觉基线尺寸不符：" + name + " 基线 " + baseline.width + "x" + baseline.height +
                "，实际 " + actual.width + "x" + actual.height + " —— 布局尺寸变了"
        }

        var diff = 0
        var worst = 0
        var total = 0
        for (x in 0 until actual.width) {
            for (y in 0 until actual.height) {
                total++
                val a = actual.getPixel(x, y)
                val b = baseline.getPixel(x, y)
                val d = maxOf(
                    Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
                    Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
                    Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                )
                if (d > DIFF_DELTA_THRESHOLD) diff++
                if (d > worst) worst = d
            }
        }

        return Result(
            baselineMissing = false,
            diffPixels = diff,
            totalPixels = total,
            worstDelta = worst
        )
    }

    /** 仅显式调用才写图；不返回 passed，也不把旧图损坏/尺寸差异冒充普通验收成功。 */
    fun generate(name: String, actual: Bitmap): File {
        check(isUpdateMode()) { "生成参考图必须显式设置 UPDATE_VISUAL_BASELINE=1 或 updateVisualBaseline=true" }
        val file = File(BASELINE_DIR, "$name.png")
        val directory = checkNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建参考图目录" }
        val temporary = File.createTempFile("visual-", ".png.tmp", directory)
        try {
            temporary.outputStream().use {
                check(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) { "参考图 PNG 编码失败" }
                it.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        println("VISUAL_BASELINE_GENERATED $name -> ${file.absolutePath} (generation only; not compared)")
        return file
    }

    /** 人能读的差异摘要（测试失败时打印）。 */
    fun describe(name: String, r: Result): String = buildString {
        appendLine("[" + name + "] 视觉回归")
        if (r.baselineError != null) {
            appendLine("  验收失败：${r.baselineError}；普通比对不会自动生成或覆盖参考图")
            return@buildString
        }
        appendLine("  差异像素：" + r.diffPixels + " / " + r.totalPixels +
            "（" + String.format(java.util.Locale.ROOT, "%.3f%%", r.diffRatio * 100) + "，阈值 0.500%）")
        appendLine("  最大通道差：" + r.worstDelta + "（像素计差阈值 " + DIFF_DELTA_THRESHOLD + "，非独立失败条件）")
        appendLine("  若变化是有意的：设 UPDATE_VISUAL_BASELINE=1 重跑以更新基线，并在提交信息里说明")
    }
}
