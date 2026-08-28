package com.linguareader.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 多服务商持久化：旧版单插槽配置的内存迁移、providers 落盘、生效服务商
 * 镜像回旧字段。Robolectric 里 Keystore 不可用（CloudKeyStore.encrypt 返回
 * null），按项目惯例不回读 Key 值，只断言结构与非密钥字段。
 */
@RunWith(RobolectricTestRunner::class)
class AiSettingsStoreMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanPrefs() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `legacy single slot settings migrate to a default provider in memory`() {
        context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .putString("api_key", "legacy-key")
            .putString("base_url", "https://api.example.com")
            .putString("model", "test-model")
            .commit()

        val settings = AiSettingsStore(context).load()
        assertEquals(1, settings.providers.size)
        val provider = settings.providers.single()
        assertEquals(AiProviderProfile.DEFAULT_ID, provider.id)
        assertEquals("legacy-key", provider.apiKey)
        assertEquals("https://api.example.com", provider.baseUrl)
        assertEquals("test-model", provider.model)
        assertEquals(AiProtocol.OPENAI_COMPAT, provider.protocol)
        assertEquals(AiProviderProfile.DEFAULT_ID, settings.activeProviderId)
        // 兼容桥：镜像字段让旧 remoteReady 判定照常工作。
        assertTrue(settings.remoteReady)
    }

    @Test
    fun `blank legacy key yields no providers and stays offline`() {
        val settings = AiSettingsStore(context).load()
        assertTrue(settings.providers.isEmpty())
        assertTrue(!settings.remoteReady)
    }

    @Test
    fun `save persists providers and mirrors the active one into legacy fields`() {
        val store = AiSettingsStore(context)
        store.save(
            AiSettings(
                enabled = true,
                providers = listOf(
                    AiProviderProfile(id = "a", name = "A", baseUrl = "https://a", apiKey = "ka", model = "m1"),
                    AiProviderProfile(
                        id = "b", name = "B", baseUrl = "https://b", apiKey = "kb",
                        model = "m2", protocol = AiProtocol.ANTHROPIC
                    )
                ),
                activeProviderId = "b"
            )
        )

        val loaded = AiSettingsStore(context).load()
        assertEquals(listOf("a", "b"), loaded.providers.map { it.id })
        assertEquals("b", loaded.activeProviderId)
        assertEquals(AiProtocol.ANTHROPIC, loaded.effectiveProtocol)
        // 镜像：旧 base_url/model 来自生效服务商 B（Key 加密不回读）。
        assertEquals("https://b", loaded.baseUrl)
        assertEquals("m2", loaded.model)
        assertTrue(loaded.providers.all { it.baseUrl.isNotBlank() && it.model.isNotBlank() })
    }

    @Test
    fun `a stale stored active id falls back to the first provider`() {
        val store = AiSettingsStore(context)
        store.save(
            AiSettings(
                providers = listOf(
                    AiProviderProfile(id = "only", name = "Only", baseUrl = "https://o", apiKey = "k", model = "m")
                ),
                activeProviderId = "gone"
            )
        )
        val loaded = AiSettingsStore(context).load()
        assertEquals("only", loaded.activeProviderId)
    }
}
