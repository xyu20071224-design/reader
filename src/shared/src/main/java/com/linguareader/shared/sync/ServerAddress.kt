package com.linguareader.shared.sync

/** 「服务器地址」输入的解析错误类别；UI 据此映射行内提示文案。 */
enum class ServerAddressError { EMPTY, BAD_IPV4, BAD_PORT, BAD_URL }

/** [ServerAddress] 的解析结果。 */
sealed class ServerAddressResult {
    /** 规范化后的 serverUrl（无尾斜杠）。 */
    data class Ok(val serverUrl: String) : ServerAddressResult()

    data class Invalid(val error: ServerAddressError) : ServerAddressResult()
}

/**
 * 服务器地址的纯逻辑解析与回填（F-160「只需填 IPv4 + 用户名 + 密码」）。
 *
 * - 主输入框只接受 `IPv4` 或 `IPv4:端口`；协议固定 `http://`，缺省端口 [DEFAULT_PORT]。
 * - 高级区可填完整 URL（http/https + 反代路径），非空时优先于主输入框。
 * - [toInput] 供 UI 打开时回填：只有简单形式才放回主输入框，否则交给高级区。
 * - [parseUrl] 保留路径（反代场景），只去掉尾部斜杠。
 *
 * 无平台依赖，可直接 JVM 单测。
 */
object ServerAddress {

    /** sync-server 的 `LR_SYNC_PORT` 默认值。 */
    const val DEFAULT_PORT = 8787

    private const val SCHEME = "http://"
    private const val SECURE_SCHEME = "https://"

    /** 主输入框：`IPv4` / `IPv4:端口` → `http://IPv4[:端口]`。 */
    fun parse(input: String): ServerAddressResult {
        val text = input.trim()
        if (text.isEmpty()) return ServerAddressResult.Invalid(ServerAddressError.EMPTY)
        val colon = text.indexOf(':')
        // 多个冒号既不是 IPv4:端口，也不接受 IPv6（服务端只有 IPv4）。
        if (colon >= 0 && text.indexOf(':', colon + 1) >= 0) {
            return ServerAddressResult.Invalid(ServerAddressError.BAD_IPV4)
        }
        val host = if (colon >= 0) text.substring(0, colon) else text
        val portText = if (colon >= 0) text.substring(colon + 1) else null
        if (!isIpv4(host)) return ServerAddressResult.Invalid(ServerAddressError.BAD_IPV4)
        val port = if (portText == null) {
            DEFAULT_PORT
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return ServerAddressResult.Invalid(ServerAddressError.BAD_PORT)
        }
        return ServerAddressResult.Ok(SCHEME + host + ":" + port)
    }

    /** 高级区：完整 URL，保留路径（反代），去掉尾部斜杠。 */
    fun parseUrl(input: String): ServerAddressResult {
        var text = input.trim()
        if (text.isEmpty()) return ServerAddressResult.Invalid(ServerAddressError.EMPTY)
        val lower = text.lowercase()
        if (!lower.startsWith(SCHEME) && !lower.startsWith(SECURE_SCHEME)) {
            return ServerAddressResult.Invalid(ServerAddressError.BAD_URL)
        }
        if (text.any { it.isWhitespace() }) {
            return ServerAddressResult.Invalid(ServerAddressError.BAD_URL)
        }
        while (text.length > SECURE_SCHEME.length && text.endsWith("/")) text = text.dropLast(1)
        val rest = text.substringAfter("://")
        if (rest.isEmpty() || rest.startsWith("/")) {
            return ServerAddressResult.Invalid(ServerAddressError.BAD_URL)
        }
        val authority = rest.substringBefore('/')
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            val port = authority.substring(colon + 1).toIntOrNull()
            if (colon == 0 || port == null || port !in 1..65535) {
                return ServerAddressResult.Invalid(ServerAddressError.BAD_PORT)
            }
        }
        return ServerAddressResult.Ok(text)
    }

    /** 推导最终 serverUrl：高级区非空时优先（覆盖 IP 推导结果）。 */
    fun compose(addressInput: String, fullUrlInput: String): ServerAddressResult =
        if (fullUrlInput.isBlank()) parse(addressInput) else parseUrl(fullUrlInput)

    /**
     * 打开 UI 时回填主输入框：`http://IPv4[:端口]`（无路径）返回 `IPv4[:端口]`，
     * 缺省端口会被省略；其他形式（https / 域名 / 带路径）返回 null，交给高级区。
     */
    fun toInput(serverUrl: String): String? {
        val text = serverUrl.trim().trimEnd('/')
        if (!text.startsWith(SCHEME)) return null
        val rest = text.removePrefix(SCHEME)
        if (rest.isEmpty() || rest.contains('/')) return null
        val colon = rest.indexOf(':')
        if (colon >= 0 && rest.indexOf(':', colon + 1) >= 0) return null
        val host = if (colon >= 0) rest.substring(0, colon) else rest
        val portText = if (colon >= 0) rest.substring(colon + 1) else null
        if (!isIpv4(host)) return null
        val port = if (portText == null) {
            DEFAULT_PORT
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return if (port == DEFAULT_PORT) host else host + ":" + port
    }

    /** 严格 IPv4：四段十进制 0-255，不接受符号、空段、前导非数字与 IPv6。 */
    fun isIpv4(input: String): Boolean {
        val parts = input.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            if (part.any { it !in '0'..'9' }) return false
            if (part.toInt() > 255) return false
        }
        return true
    }
}
