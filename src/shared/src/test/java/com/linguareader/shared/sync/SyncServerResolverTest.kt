package com.linguareader.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 默认服务器（构建期注入）与用户输入的优先级 / 回退。 */
class SyncServerResolverTest {

    // 只用文档保留地址与假指纹：真实默认值不进仓库，甚至不进测试字面量。
    // 203.0.113.0/24 是 RFC 5737 的 TEST-NET-3（永不可路由），AA:BB:… 是明显的占位指纹。
    private val defaults = SyncServerDefaults(
        serverUrl = "https://203.0.113.9:25000",
        pinnedCertSha256 = "AA:BB:CC:DD:EE:FF"
    )

    @Test
    fun buildConfigStringsAreTrimmedAndBlankMeansNone() {
        val parsed = SyncServerDefaults.of("  https://example.com:25000 ", "  AA:BB  ")
        assertEquals("https://example.com:25000", parsed.serverUrl)
        assertEquals("AA:BB", parsed.pinnedCertSha256)
        assertTrue(parsed.hasServer)

        val empty = SyncServerDefaults.of(null, null)
        assertEquals("", empty.serverUrl)
        assertFalse(empty.hasServer)
        assertFalse(SyncServerDefaults.NONE.hasServer)
    }

    @Test
    fun savedServerWinsOverDefaults() {
        val saved = SyncSettings(enabled = true, serverUrl = "http://192.168.1.5:8787", username = "alice")
        val initial = SyncServerResolver.initial(saved, defaults)
        assertEquals("http://192.168.1.5:8787", initial.serverUrl)
        // 成对语义：已保存指纹为空就保持为空，不把默认指纹混进来
        assertEquals("", initial.pinnedCertSha256)
        assertFalse(initial.fromDefaults)
        assertFalse(initial.needsManualAddress)
    }

    @Test
    fun fallsBackToDefaultsAsAPair() {
        val initial = SyncServerResolver.initial(SyncSettings(), defaults)
        assertEquals(defaults.serverUrl, initial.serverUrl)
        assertEquals(defaults.pinnedCertSha256, initial.pinnedCertSha256)
        assertTrue(initial.fromDefaults)
        assertFalse(initial.needsManualAddress)
    }

    @Test
    fun withoutSavedOrDefaultsManualEntryIsRequired() {
        val initial = SyncServerResolver.initial(SyncSettings(), SyncServerDefaults.NONE)
        assertEquals("", initial.serverUrl)
        assertFalse(initial.fromDefaults)
        assertTrue(initial.needsManualAddress)
    }

    @Test
    fun fullUrlInAdvancedAreaWinsOverAddress() {
        val initial = SyncServerResolver.initial(SyncSettings(), defaults)
        val input = SyncServerResolver.submitInput(initial, "192.168.1.5", "https://other.example:8443/x")
        assertEquals("", input.address)
        assertEquals("https://other.example:8443/x", input.fullUrl)
    }

    @Test
    fun typedAddressWinsOverDefaultUrl() {
        val initial = SyncServerResolver.initial(SyncSettings(), defaults)
        val input = SyncServerResolver.submitInput(initial, "10.0.0.9:9000", "")
        assertEquals("10.0.0.9:9000", input.address)
        assertEquals("", input.fullUrl)
    }

    @Test
    fun emptyInputsFallBackToDefaultWithoutAskingForAddress() {
        val initial = SyncServerResolver.initial(SyncSettings(), defaults)
        // 默认是 https 完整 URL → 走 fullUrl
        val https = SyncServerResolver.submitInput(initial, "", "")
        assertEquals("", https.address)
        assertEquals(defaults.serverUrl, https.fullUrl)
        // 默认是简单 IPv4 → 走 address
        val simple = SyncServerResolver.submitInput(
            SyncServerResolver.initial(SyncSettings(), SyncServerDefaults("http://192.168.1.5:8787", "")),
            "", ""
        )
        assertEquals("192.168.1.5", simple.address)
        assertEquals("", simple.fullUrl)
    }

    @Test
    fun emptyInputsWithoutDefaultsStayEmptyForValidation() {
        val initial = SyncServerResolver.initial(SyncSettings(), SyncServerDefaults.NONE)
        val input = SyncServerResolver.submitInput(initial, "", "")
        assertEquals("", input.address)
        assertEquals("", input.fullUrl)
        // 交给 ServerAddress 校验 → EMPTY（UI 才能提示「请填写服务器地址」）
        val result = ServerAddress.compose(input.address, input.fullUrl)
        assertTrue(result is ServerAddressResult.Invalid)
        assertEquals(ServerAddressError.EMPTY, (result as ServerAddressResult.Invalid).error)
    }
}
