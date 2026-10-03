package com.linguareader.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ServerAddress] 纯逻辑单测：IPv4 推导、端口、完整 URL、非法输入与 UI 回填。 */
class ServerAddressTest {

    private fun ok(result: ServerAddressResult): String {
        assertTrue(result is ServerAddressResult.Ok, "expected Ok but was $result")
        return (result as ServerAddressResult.Ok).serverUrl
    }

    private fun error(result: ServerAddressResult): ServerAddressError {
        assertTrue(result is ServerAddressResult.Invalid, "expected Invalid but was $result")
        return (result as ServerAddressResult.Invalid).error
    }

    @Test
    fun plainIpv4UsesHttpAndDefaultPort() {
        assertEquals("http://192.168.1.5:8787", ok(ServerAddress.parse("192.168.1.5")))
        assertEquals("http://10.0.0.1:8787", ok(ServerAddress.parse("  10.0.0.1  ")))
        assertEquals("http://0.0.0.0:8787", ok(ServerAddress.parse("0.0.0.0")))
    }

    @Test
    fun ipv4WithPortKeepsIt() {
        assertEquals("http://192.168.1.5:9000", ok(ServerAddress.parse("192.168.1.5:9000")))
        assertEquals("http://192.168.1.5:1", ok(ServerAddress.parse("192.168.1.5:1")))
        assertEquals("http://192.168.1.5:65535", ok(ServerAddress.parse("192.168.1.5:65535")))
    }

    @Test
    fun invalidAddressInputsAreRejected() {
        assertEquals(ServerAddressError.EMPTY, error(ServerAddress.parse("")))
        assertEquals(ServerAddressError.EMPTY, error(ServerAddress.parse("   ")))
        // 段超界
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("256.1.1.1")))
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("192.168.1.999")))
        // 非数字 / 段数不对 / 域名
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("192.168.1.a")))
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("192.168.1")))
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("192.168.1.2.3")))
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("host.example.com")))
        // 非法端口
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parse("192.168.1.5:0")))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parse("192.168.1.5:65536")))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parse("192.168.1.5:abc")))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parse("192.168.1.5:")))
        // 主输入框不接受完整 URL（完整 URL 走高级区）
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("http://192.168.1.5")))
        // 不支持 IPv6
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("::1")))
    }

    @Test
    fun fullUrlIsAcceptedInAdvancedField() {
        assertEquals("https://example.com", ok(ServerAddress.parseUrl("https://example.com")))
        assertEquals("http://203.0.113.10:8787", ok(ServerAddress.parseUrl("http://203.0.113.10:8787")))
        // 反代路径保留，去掉尾部斜杠
        assertEquals("https://sync.example.com/lr", ok(ServerAddress.parseUrl("https://sync.example.com/lr/")))
        // 高级区非空时覆盖主输入框
        assertEquals("https://example.com", ok(ServerAddress.compose("192.168.1.5", "https://example.com")))
        assertEquals("http://192.168.1.5:8787", ok(ServerAddress.compose("192.168.1.5", "  ")))
    }

    @Test
    fun invalidFullUrlIsRejected() {
        assertEquals(ServerAddressError.EMPTY, error(ServerAddress.parseUrl("")))
        assertEquals(ServerAddressError.BAD_URL, error(ServerAddress.parseUrl("ftp://example.com")))
        assertEquals(ServerAddressError.BAD_URL, error(ServerAddress.parseUrl("https://")))
        assertEquals(ServerAddressError.BAD_URL, error(ServerAddress.parseUrl("https:///path")))
        assertEquals(ServerAddressError.BAD_URL, error(ServerAddress.parseUrl("http://exa mple.com")))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parseUrl("https://example.com:0")))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parseUrl("https://example.com:70000")))
    }

    @Test
    fun toInputRoundTripsSimpleForms() {
        assertEquals("192.168.1.5", ServerAddress.toInput("http://192.168.1.5:8787"))
        assertEquals("192.168.1.5", ServerAddress.toInput("http://192.168.1.5"))
        assertEquals("192.168.1.5:9000", ServerAddress.toInput("http://192.168.1.5:9000"))
        // task-6：https 的简单形式同样回填主框（协议由指纹/初值推导，见 secureParse* 用例）
        assertEquals("192.168.1.5", ServerAddress.toInput("https://192.168.1.5:8787"))
        assertEquals("198.51.100.9:25000", ServerAddress.toInput("https://198.51.100.9:25000"))
        // 非简单形式（域名 / 带路径 / 非法端口）一律交给高级区
        assertNull(ServerAddress.toInput("http://example.com:8787"))
        assertNull(ServerAddress.toInput("https://example.com:8443"))
        assertNull(ServerAddress.toInput("https://example.com/lr"))
        assertNull(ServerAddress.toInput("http://192.168.1.5:8787/api"))
        assertNull(ServerAddress.toInput("http://192.168.1.5:0"))
        assertNull(ServerAddress.toInput(""))
    }

    @Test
    fun secureFlagDerivesHttps() {
        assertEquals("https://192.168.1.5:8787", ok(ServerAddress.parse("192.168.1.5", secure = true)))
        assertEquals("https://192.168.1.5:25000", ok(ServerAddress.parse("192.168.1.5:25000", secure = true)))
        // 端口/格式校验与协议无关
        assertEquals(ServerAddressError.BAD_IPV4, error(ServerAddress.parse("256.1.1.1", secure = true)))
        assertEquals(ServerAddressError.BAD_PORT, error(ServerAddress.parse("192.168.1.5:0", secure = true)))
        assertEquals(ServerAddressError.EMPTY, error(ServerAddress.parse("", secure = true)))
        // 指纹驱动：secure=false 仍维持 http
        assertEquals("http://192.168.1.5:8787", ok(ServerAddress.compose("192.168.1.5", "")))
        assertEquals("https://192.168.1.5:8787", ok(ServerAddress.compose("192.168.1.5", "", secure = true)))
    }

    @Test
    fun advancedFullUrlBeatsSecureFlag() {
        // 高级区填了完整 URL → 以它为准，secure 不参与（协议与端口都由 URL 决定）
        assertEquals(
            "http://192.168.1.5:8787",
            ok(ServerAddress.compose("10.0.0.1", "http://192.168.1.5:8787", secure = true))
        )
        assertEquals(
            "https://sync.example.com/lr",
            ok(ServerAddress.compose("10.0.0.1", "https://sync.example.com/lr", secure = true))
        )
    }

    @Test
    fun httpsSimpleFormRoundTripsThroughInput() {
        // 存的是 https 简单形式 → 回填主框 → 再推导（secure=true）必须回到原值
        val stored = ok(ServerAddress.parse("203.0.113.9:25000", secure = true))
        assertEquals("https://203.0.113.9:25000", stored)
        val input = ServerAddress.toInput(stored)
        assertEquals("203.0.113.9:25000", input)
        assertEquals(stored, ok(ServerAddress.compose(input!!, "", secure = true)))
    }

    @Test
    fun isSecureDetectsHttpsScheme() {
        assertTrue(ServerAddress.isSecure("https://192.168.1.5:8787"))
        assertTrue(ServerAddress.isSecure("  HTTPS://example.com  "))
        assertFalse(ServerAddress.isSecure("http://192.168.1.5:8787"))
        assertFalse(ServerAddress.isSecure("192.168.1.5"))
        assertFalse(ServerAddress.isSecure(""))
    }

    @Test
    fun simpleFormSurvivesSaveAndReload() {
        // 简单形式 serverUrl → 回填 → 再推导必须回到原值，否则每次打开都会被推进高级区。
        val stored = ok(ServerAddress.parse("192.168.1.5:9000"))
        val input = ServerAddress.toInput(stored)
        assertEquals("192.168.1.5:9000", input)
        assertEquals(stored, ok(ServerAddress.compose(input!!, "")))
    }

    @Test
    fun ipv4ValidatorEdgeCases() {
        assertTrue(ServerAddress.isIpv4("0.0.0.0"))
        assertTrue(ServerAddress.isIpv4("255.255.255.255"))
        assertFalse(ServerAddress.isIpv4("256.0.0.1"))
        assertFalse(ServerAddress.isIpv4("1.2.3"))
        assertFalse(ServerAddress.isIpv4("1.2.3.4.5"))
        assertFalse(ServerAddress.isIpv4("1.2.3.-1"))
        assertFalse(ServerAddress.isIpv4("1.2.3. 4"))
        assertFalse(ServerAddress.isIpv4(""))
    }
}
