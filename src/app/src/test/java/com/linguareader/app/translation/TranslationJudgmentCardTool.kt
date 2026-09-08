package com.linguareader.app.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * **工具测试（人工跑）**：把 [TranslationGoldenReplayTest] 写出的
 * `artifacts/alignment-eval/replay-report.json` 里**展示发生变化的样本**渲染成
 * 一份自包含的 HTML 判定卡，供人工逐条判 ok / ok2 / bad / skip。
 *
 * 这是「人工只看增量」这一环的落地：回归报告已经把变化样本挑出来了，这里把它们
 * 变成当年那种判定页（含一键收集 `sN=verdict;…` 判定串的按钮），判定结果直接贴进
 * `src/tools/alignment-eval/verdict-overrides.json` 的新一轮，然后重跑 fixture 工具
 * 与 bless 即可。
 *
 * 运行：
 * ```
 * ./toolchain/build.sh :app:testDebugUnitTest \
 *   --tests "com.linguareader.app.translation.TranslationJudgmentCardTool"
 * ```
 * 产出 `artifacts/alignment-eval/judgment-cards.html`（含书文，本地 gitignored）。
 *
 * **排序**：有 `artifacts/generalization/cedict.txt.gz` 时按 CC-CEDICT 语义代理分数
 * 升序排（越低越可疑，人工先看最可能出问题的）；缺词典时退回报告顺序，并在卡片顶部
 * 说明。代理只做排序参考，判定仍以人眼为准。
 */
@RunWith(RobolectricTestRunner::class)
class TranslationJudgmentCardTool {

    @Test
    fun generateCardsForChangedSamples() {
        val root = findGoldenRoot()
        assumeTrue("缺少 artifacts/alignment-eval 数据，跳过", root != null)
        val artifacts = File(root!!, "artifacts")
        val reportFile = File(artifacts, "alignment-eval/replay-report.json")
        assumeTrue(
            "还没有回归报告，先跑 TranslationGoldenReplayTest",
            reportFile.isFile
        )

        val report = JSONObject(reportFile.readText())
        val samples = report.getJSONArray("samples")
        val changed = mutableListOf<JSONObject>()
        var total = 0
        var notLocated = 0
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            if (sample.optBoolean("garbage", false)) continue
            total++
            if (!sample.has("current")) {
                // 重放时定位不到原文（例如 s22 的歌词句），没有「当前展示」可判
                notLocated++
                continue
            }
            if (sample.optBoolean("changed", false)) changed += sample
        }

        val outFile = File(artifacts, "alignment-eval/judgment-cards.html")
        if (changed.isEmpty()) {
            println("[cards] 没有展示变化的样本（非垃圾 $total 条，未定位 $notLocated 条），无需人工判定")
            outFile.delete()
            return
        }

        // 有 CC-CEDICT 就按语义代理分数升序排：分数越低越可疑，人工先看它们。
        // 缺词典不报错——排序只是省注意力，不影响判定流程。
        val cedict = File(artifacts, "generalization/cedict.txt.gz")
        val proxy = if (cedict.isFile) SemanticProxyIndex.build(cedict) else null
        val scores = if (proxy == null) emptyMap()
        else changed.associate { it.getString("id") to proxyScore(proxy, it) }
        val ordered = if (proxy == null) changed
        else changed.sortedBy { scores.getValue(it.getString("id")) }

        outFile.writeText(renderHtml(report, ordered, total, scores))
        println("[cards] 变化样本 ${ordered.size}/$total 条（未定位 $notLocated 条不参与）→ ${outFile.path}")
        if (proxy == null) {
            println("[cards] 缺少 CC-CEDICT，按报告顺序排列；跑 fetch-semantic-proxy-corpus.sh 可启用可疑度排序")
        } else {
            println(
                "[cards] 已按语义代理分数升序排列（最低 %.2f，最高 %.2f；越低越可疑）".format(
                    scores.getValue(ordered.first().getString("id")),
                    scores.getValue(ordered.last().getString("id"))
                )
            )
        }
        println("[cards] 判定串格式：s57=ok2;s62=bad;… 贴进 src/tools/alignment-eval/verdict-overrides.json 的新一轮")
        assertTrue("判定卡文件应已写出", outFile.isFile && outFile.length() > 0)
    }

    /** 对「当前展示」打分；展示为空（极少见）时排到最后，而不是当成最可疑。 */
    private fun proxyScore(index: Map<String, Set<String>>, sample: JSONObject): Double {
        val zh = sample.optJSONObject("current")?.optString("zh").orEmpty()
        if (zh.isBlank()) return 1.0
        return SemanticProxyIndex.score(index, sample.optString("en"), zh)
    }

    private fun renderHtml(
        report: JSONObject,
        changed: List<JSONObject>,
        total: Int,
        scores: Map<String, Double>
    ): String {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val cards = changed.joinToString("\n") { sample ->
            val id = sample.getString("id")
            val approved = sample.optJSONObject("approved")
            val current = sample.optJSONObject("current")
            val score = scores[id]
            val scoreText = if (score == null) "" else " · 代理%.2f".format(score)
            buildString {
                appendLine("""<div class="s" data-id="${escape(id)}">""")
                appendLine(
                    """  <div class="meta">#${escape(id)} · ${escape(sample.optString("stratum"))}""" +
                        """ · 章${sample.optInt("chapter") + 1} · 历史判定 ${escape(sample.optString("verdict"))}""" +
                        """$scoreText</div>"""
                )
                appendLine("""  <div class="en">EN：${escape(sample.optString("en"))}</div>""")
                appendLine(
                    """  <div class="zh old">批准：${escape(displayText(approved))}</div>"""
                )
                appendLine(
                    """  <div class="zh new">当前：${escape(displayText(current))}</div>"""
                )
                appendLine("""  <div class="opts">""")
                for ((value, label) in listOf("ok" to "对", "ok2" to "勉强对", "bad" to "错", "skip" to "跳过")) {
                    appendLine(
                        """    <label><input type="radio" name="${escape(id)}" value="$value"> $label</label>"""
                    )
                }
                appendLine("""  </div>""")
                appendLine("""</div>""")
            }
        }
        return """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>译本对齐判定卡（变化样本 ${changed.size}/$total）</title>
<style>
  body { font-family: system-ui, "Noto Sans CJK SC", sans-serif; margin: 24px; background: #f6f6f4; color: #222; }
  h1 { font-size: 18px; }
  .hint { color: #666; font-size: 13px; margin-bottom: 16px; }
  .s { background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 12px 14px; margin-bottom: 14px; }
  .meta { color: #888; font-size: 12px; margin-bottom: 8px; }
  .en { font-size: 14px; margin-bottom: 6px; }
  .zh { font-size: 14px; margin-bottom: 4px; }
  .zh.old { color: #777; }
  .zh.new { color: #0b6; font-weight: 600; }
  .opts { margin-top: 8px; display: flex; gap: 16px; font-size: 13px; }
  #collect { position: sticky; top: 0; background: #fffbe6; border: 1px solid #e8d98a; border-radius: 8px; padding: 12px; margin-bottom: 18px; }
  #result { width: 100%; height: 52px; margin-top: 8px; font-family: ui-monospace, monospace; font-size: 12px; }
  button { padding: 6px 14px; font-size: 13px; cursor: pointer; }
</style>
</head>
<body>
<h1>译本对齐判定卡 · 展示变化样本 ${changed.size} / ${total}</h1>
<div class="hint">生成于 $generatedAt · alignerVersion=${report.optInt("alignerVersion")}。
${if (scores.isEmpty()) "排列顺序：报告原顺序（缺 CC-CEDICT，跑 fetch-semantic-proxy-corpus.sh 可按可疑度排序）"
else "排列顺序：CC-CEDICT 语义代理分数升序，越低越可疑（只做排序参考，判定以人眼为准）"}。
逐条选择后点「收集判定」，把文本框里的 <code>sN=verdict;…</code> 贴进
<code>src/tools/alignment-eval/verdict-overrides.json</code> 的新一轮。</div>
<div id="collect">
  <button onclick="collect()">收集判定</button>
  <textarea id="result" placeholder="点上面的按钮后，这里会出现 sN=verdict;…"></textarea>
</div>
$cards
<script>
function collect() {
  const out = [];
  document.querySelectorAll('.s').forEach(card => {
    const checked = card.querySelector('input[type=radio]:checked');
    if (checked) out.push(card.dataset.id + '=' + checked.value);
  });
  document.getElementById('result').value = out.join(';');
}
</script>
</body>
</html>
"""
    }

    private fun displayText(display: JSONObject?): String {
        if (display == null) return "（未定位）"
        val zh = display.optString("zh")
        return "${display.optString("level")}${if (zh.isBlank()) "" else "：$zh"}"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun findGoldenRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val artifacts = File(dir, "artifacts")
            if (File(artifacts, "alignment-eval/golden-samples.json").isFile &&
                File(dir, "src/tools/alignment-eval/verdict-overrides.json").isFile
            ) return dir
            dir = dir.parentFile
        }
        return null
    }
}
