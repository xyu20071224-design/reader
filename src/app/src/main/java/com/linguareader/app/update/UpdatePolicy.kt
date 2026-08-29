package com.linguareader.app.update

/**
 * 纯逻辑：判断远端 Release 是否比当前安装版本新。
 *
 * GitHub Release 只带 tag（v<x.y.z>），不带 versionCode，而本仓库惯例是
 * versionName 与 versionCode 同步递增，所以按 versionName 语义化比较即可。
 */
object UpdatePolicy {

    /**
     * [latestTag] 形如 `v1.6.0` 或 `1.6.0`，[currentVersionName] 形如 `1.5.0`。
     * 任一畸形（无数字段、负数、非版本串）一律返回 false，宁可漏报不误报。
     */
    fun isNewer(latestTag: String, currentVersionName: String): Boolean {
        val latest = parse(latestTag) ?: return false
        val current = parse(currentVersionName) ?: return false
        for (i in 0 until maxOf(latest.size, current.size)) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    /** `v1.10.2` → [1, 10, 2]；无数字段或含负数返回 null。 */
    private fun parse(version: String): List<Int>? {
        val text = version.trim().removePrefix("v").removePrefix("V")
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.isEmpty()) return null
        val numbers = mutableListOf<Int>()
        for (part in parts) {
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            numbers.add(part.toInt())
        }
        return numbers
    }
}
