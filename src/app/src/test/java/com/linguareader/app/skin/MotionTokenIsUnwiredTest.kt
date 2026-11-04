package com.linguareader.app.skin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **`motion` token 零消费方的事实登记**（P22）。
 *
 * ## 现状
 *
 * `SkinSpec.motion` → `TokenMotion`（`fastMillis` / `normalMillis` / `slowMillis` / `easing`）
 * + 便捷属性 `fastSpec` / `normalSpec`，转换链路完整。但 2026-11-04 核查：
 *
 * | 检查 | 结果 |
 * | --- | --- |
 * | `t.motion.` 在整个 `src/main` 的出现 | **0 次** |
 * | `fastSpec` / `normalSpec` 的调用点 | **只有它们自己的 getter** |
 * | `components/` 层动画 | **0 处**（`Switch` 用 Material 默认时长）|
 *
 * 这是本项目第六例「建好但零调用」（前五：P9 换肤管线、P13 导航层、P14 组件原语、
 * P15 状态切片、P16 `shared.alignment`）。
 *
 * ## 为什么不硬接
 *
 * 唯一「看起来该接」的地方是阅读页的 3 处 `AnimatedVisibility`（顶栏 / 听书条 / 复习横幅）。
 * 但它们的 `visible` 直接绑定阅读交互状态（`overlays.toolbarVisible` / `ttsForThisBook` /
 * `overlays.reviewPrompt`），**改动画曲线等于改这些状态的观感节奏** ——
 * 而阅读页是本项目历史事故最多的地方（章末死循环、TTS 高亮错位），且**当前无真机可验**。
 *
 * `components/` 层没有任何动画，所以也没有「安全的第一个消费方」。
 *
 * ## 所以这一笔做的是
 *
 * 把事实登记成断言：**如果哪天有地方用上了 `t.motion.`，这条会红**，提醒同步更新
 * 本文件的 KDoc 与 `VALIDATION.md`（它们现在写的是「零消费方」）。
 *
 * 这不是「测试」，是「把已知债务钉在明面上」—— 与 `AlignmentModuleIsUnwiredTest` 同一模式。
 */
class MotionTokenIsUnwiredTest {

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
                // 字符串整段跳过：否则 ".../*..." 里的 /* 会被当块注释开头（P19 踩过的坑）。
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src[i]).append(src[i + 1])
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') { i++; break }
                        i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun mainSources(): List<Pair<String, String>> =
        File("src/main/java/com/linguareader/app")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to stripComments(it.readText()) }
            .toList()

    /**
     * 当前事实：`t.motion.` 没有任何消费方。
     *
     * 如果这条红了，说明有人接上了 —— **那是好事，请把本文件的 KDoc 与
     * `VALIDATION.md` 里「零消费方」的记录一起更新**，不要让文档继续误导人。
     */
    @Test
    fun motionTokensHaveNoConsumersYet() {
        val sources = mainSources()
        val consumers = sources.filter { (name, text) ->
            // 排除 token 定义与转换器本身：它们是「提供方」，不是「消费方」。
            name !in setOf("DesignTokens.kt", "SkinRegistry.kt") &&
                (text.contains("t.motion.") || text.contains("fastSpec") || text.contains("normalSpec"))
        }.map { it.first }

        assertTrue(
            buildString {
                appendLine("原本 `motion` token 零消费方（t.motion. 在 src/main 出现 0 次，")
                appendLine("components/ 层也没有任何动画）。现在发现这些文件在用：")
                consumers.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("如果这是有意接上的：很好，请更新 MotionTokenIsUnwiredTest 的 KDoc")
                appendLine("与 VALIDATION.md，让「零消费方」这条记录停止误导人。")
            },
            consumers.isEmpty()
        )
    }
}