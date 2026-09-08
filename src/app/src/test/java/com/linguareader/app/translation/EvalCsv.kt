package com.linguareader.app.translation

/**
 * 六轮评估数据里的 CSV 极简解析（utf-8-sig、引号包裹字段、`""` 转义）。
 *
 * 只服务于 [TranslationGoldenFixtureTool] 与 [TranslationGoldenReplayTest] 读取
 * `artifacts/` 下的历史评估产物；不要拿它当通用 CSV 库。
 */
internal object EvalCsv {

    fun parse(text: String): List<Map<String, String>> {
        val clean = text.trimStart('\uFEFF')
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            if (inQuotes) {
                when {
                    ch == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> {
                        field.append('"'); i += 2; continue
                    }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString()); field = StringBuilder()
                    }
                    '\r' -> { /* 等 \n */ }
                    '\n' -> {
                        row.add(field.toString()); field = StringBuilder()
                        rows.add(row); row = mutableListOf()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        if (rows.isEmpty()) return emptyList()
        val header = rows.removeAt(0)
        return rows.map { cells -> header.indices.associate { header[it] to (cells.getOrNull(it) ?: "") } }
    }
}
