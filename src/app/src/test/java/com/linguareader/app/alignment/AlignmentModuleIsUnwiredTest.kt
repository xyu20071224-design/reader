package com.linguareader.app.alignment

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **`shared.alignment` 零生产调用的事实登记**（P16）。
 *
 * ## 这不是「测试」，是「把已知债务钉在明面上」
 *
 * 2026-11-04 核查发现：`src/shared/.../alignment/` 有 **18 个文件、约 9000 行**，
 * 配了 18 个测试文件、5206 行，**但生产代码零调用**——`:shared` 内部没人用，
 * `:app` 也没人用。
 *
 * 生产实际走的是另一条线：`shared.translation`（`TranslationAligner` / `WordAligner`）
 * + `:app/translation/TranslationMemoryRepository`（546 行）。
 *
 * 这是本轮发现的**第五例**「建好但零调用」（前四例：P9 换肤管线、P13 导航层、
 * P14 组件原语、P15 状态切片）。
 *
 * ## 为什么这一例不删
 *
 * 前四例都在前端重构范围内，删了不影响业务。这一例不同：
 * - 它有 16 个测试、其中两个各跑 22 秒（`LotrIndependentAlignmentVerificationTest` /
 *   `LotrSpanDistributionTest`），说明它是**实验性/研究性代码**（LotR 语料验证、
 *   召回率上限实验、繁简差异诊断），可能是**刻意保留的算法探索**；
 * - 删它超出了「前端重构」的范围，应由了解那段研究背景的人决定。
 *
 * ## 所以这里做的是
 *
 * 把事实写成断言：**如果哪天它被接进生产，这条测试会红，提醒更新文档**。
 * 如果确实要删，删掉这个文件即可（它本身也是记录）。
 */
class AlignmentModuleIsUnwiredTest {

    /** 生产源（剥注释）。 */
    private fun productionSources(): List<Pair<String, String>> {
        val appSources = File("src/main/java/com/linguareader/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to stripComments(it.readText()) }
            .toList()
        // :app 模块根往上两级是仓库根，:shared 在 ../shared/src/main/java
        val sharedSources = File("../shared/src/main/java/com/linguareader/shared")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile.name != "alignment" }
            .map { it.name to stripComments(it.readText()) }
            .toList()
        return appSources + sharedSources
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * 当前事实：`shared.alignment` 零生产调用。
     *
     * 如果这条红了，说明有人把它接进生产了 —— **那是好事，请把本文件的 KDoc
     * 与 VALIDATION.md 里的记录一起更新**，不要让文档继续误导人。
     */
    @Test
    fun alignmentPackageHasNoProductionCallers() {
        val sources = productionSources()
        val importers = sources.filter { (_, text) ->
            text.contains("com.linguareader.shared.alignment")
        }.map { it.first }

        assertTrue(
            "原本 `shared.alignment` 在生产代码里零调用（18 文件 9000 行、16 个测试、" +
                "两个各跑 22 秒）。现在发现这些文件引用了它：\n" +
                importers.joinToString("\n") { "  - $it" } +
                "\n\n如果这是有意接线：很好，请更新本测试的 KDoc 与 VALIDATION.md，" +
                "让「零调用」这条记录停止误导人。",
            importers.isEmpty()
        )
    }

    /**
     * 反向：如果 `shared.alignment` 被删了，这个测试文件也该跟着删。
     *
     * 留这条是为了避免「目录没了但守卫还在，每次跑都绿得毫无意义」。
     */
    @Test
    fun alignmentPackageStillExistsOrThisGuardShouldBeDeleted() {
        val dir = File("../shared/src/main/java/com/linguareader/shared/alignment")
        assertTrue(
            "`shared.alignment` 目录不存在了。如果它被删掉，请**同时删掉本测试文件**" +
                "（AlignmentModuleIsUnwiredTest.kt），否则这条守卫每次跑都会绿得毫无意义。",
            dir.isDirectory
        )
    }
}