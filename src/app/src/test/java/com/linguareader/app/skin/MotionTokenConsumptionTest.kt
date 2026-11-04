package com.linguareader.app.skin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **`motion` token 的消费方守卫**（P23）。
 *
 * ## 历史
 *
 * P22 时这个文件叫 `MotionTokenIsUnwiredTest`，断言的是「`motion` token 零消费方」：
 * `SkinSpec.motion` → `TokenMotion`（`fastSpec` / `normalSpec`）转换链完整，
 * 但 `t.motion.` 在整个 `src/main` 出现 **0 次**，`components/` 层也**零动画**。
 *
 * 那时不接的理由是：唯一看起来该接的地方是阅读页的 3 处 `AnimatedVisibility`，
 * 但它们直接绑阅读交互状态（`overlays.toolbarVisible` / `ttsForThisBook` /
 * `overlays.reviewPrompt`），改动画曲线等于改观感节奏，而阅读页无真机可验。
 *
 * ## 现在
 *
 * P23 给 `SettingRow` 的副标题加了 fade 过渡，**用 `t.motion.normalSpec` / `fastSpec`**，
 * 成为 `motion` token 的第一个消费方。选它的理由正是 P22 里写的「安全的第一个消费方」：
 * 这里的显隐只跟副标题文案有无有关，与阅读交互无关。
 *
 * ## 这个文件现在是正向守卫
 *
 * 断言 token **确实被消费**。如果哪天那个消费点被删了（比如有人为了简化把
 * `AnimatedVisibility` 改回 `if`），这条会红 —— 提醒「`motion` token 又变成零调用了」。
 *
 * **守卫的方向会随代码状态翻转，这不是测试不稳定，是它跟着事实走。**
 * P22 那次它红了并给出「请更新 KDoc 与 VALIDATION.md」的指引，就是设计意图。
 */
class MotionTokenConsumptionTest {

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

    @Test
    fun motionTokensHaveAConsumer() {
        val consumers = mainSources().filter { (name, text) ->
            // 排除 token 定义与转换器本身：它们是「提供方」，不是「消费方」。
            name !in setOf("DesignTokens.kt", "SkinRegistry.kt") &&
                (text.contains("t.motion.") || text.contains("fastSpec") || text.contains("normalSpec"))
        }.map { it.first }

        assertTrue(
            buildString {
                appendLine("`motion` token 又变成零消费方了。")
                appendLine("P22 登记过它曾经零调用、P23 给 SettingRow 加了 fade 过渡才用上。")
                appendLine("如果这是有意删除消费点（比如为了简化改回 if），")
                appendLine("请把本测试改回「断言零消费方」的形态，并更新 KDoc 与 VALIDATION.md。")
            },
            consumers.isNotEmpty()
        )
    }

    /**
     * 消费点必须用**具体的 spec**（`normalSpec` / `fastSpec`），而不是只读 `t.motion` 的其他字段。
     *
     * 这条防的是「读了 token 但没用它的时长」的假接线 —— 例如只读 `t.motion.easing`
     * 却仍写死 `tween(300)`，那 token 的时长部分依然无效。
     */
    @Test
    fun consumerUsesAnimationSpecsNotJustFields() {
        val src = File("src/main/java/com/linguareader/app/components/SettingRow.kt").readText()
        assertTrue(
            "SettingRow 应当通过 `t.motion.normalSpec` / `fastSpec` 使用动画时长，" +
                "而不是只读 easing 再手写 tween 数字。",
            src.contains("t.motion.normalSpec") || src.contains("t.motion.fastSpec")
        )
    }
}