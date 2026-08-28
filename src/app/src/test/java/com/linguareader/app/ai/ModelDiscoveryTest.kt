package com.linguareader.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDiscoveryTest {
    @Test
    fun `parses a normal openai compatible listing`() {
        val body = JSONObject(
            """
            {"object":"list","data":[
                {"id":"deepseek-chat","object":"model","owned_by":"deepseek"},
                {"id":"deepseek-reasoner","name":"DeepSeek Reasoner",
                 "context_window":128000,"max_output_tokens":8192}
            ]}
            """.trimIndent()
        )
        val models = parseModelListing(body)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models.map { it.id })
        assertNull(models[0].contextWindow)
        val reasoner = models[1]
        assertEquals("DeepSeek Reasoner", reasoner.name)
        assertEquals(128_000L, reasoner.contextWindow)
        assertEquals(8_192L, reasoner.maxTokens)
    }

    @Test
    fun `reads gateway alias fields for name and capacities`() {
        // 各家网关的字段别名：display_name / context_length / max_tokens。
        val body = JSONObject(
            """
            {"data":[{"id":"glm-4","display_name":"GLM-4",
                "context_length":200000,"max_tokens":4096}]}
            """.trimIndent()
        )
        val model = parseModelListing(body).single()
        assertEquals("GLM-4", model.name)
        assertEquals(200_000L, model.contextWindow)
        assertEquals(4_096L, model.maxTokens)
    }

    @Test
    fun `skips entries without a usable id instead of failing the listing`() {
        val body = JSONObject(
            """
            {"data":[{"object":"model"},{"id":"ok-model"},{"id":42},{"id":""}]}
            """.trimIndent()
        )
        assertEquals(listOf("ok-model"), parseModelListing(body).map { it.id })
    }

    @Test
    fun `drops non positive or fractional capacities`() {
        val body = JSONObject(
            """
            {"data":[{"id":"m","context_window":-1,"max_output_tokens":0.5}]}
            """.trimIndent()
        )
        val model = parseModelListing(body).single()
        assertNull(model.contextWindow)
        assertNull(model.maxTokens)
    }

    @Test
    fun `missing data array fails with the hand entry hint`() {
        try {
            parseModelListing(JSONObject("""{"models":[]}"""))
            throw IllegalStateException("expected AiRequestException")
        } catch (expected: AiRequestException) {
            assertTrue(expected.message!!.contains("手动填写"))
        }
    }

    @Test
    fun `duplicate ids keep the first occurrence`() {
        val body = JSONObject(
            """
            {"data":[{"id":"m","name":"first"},{"id":"M","name":"second"}]}
            """.trimIndent()
        )
        val models = parseModelListing(body)
        assertEquals(1, models.size)
        assertEquals("first", models.single().name)
    }

    @Test
    fun `listing url trims trailing slashes but keeps path segments`() {
        assertEquals("https://api.deepseek.com/models", listingUrl("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/models", listingUrl("https://api.deepseek.com/"))
        assertEquals(
            "https://gw.example/openai/v1/models",
            listingUrl("https://gw.example/openai/v1///")
        )
    }

    @Test
    fun `probe key accepts blank raw ascii and refuses others`() {
        // 空 Key = 匿名探测本地网关，是合法路径。
        assertTrue(probeKeyUsable(""))
        assertTrue(probeKeyUsable("  "))
        assertTrue(probeKeyUsable("sk-abc123_-~.="))
        assertFalse(probeKeyUsable("sk key with spaces"))
        assertFalse(probeKeyUsable("中文key"))
        assertFalse(probeKeyUsable("two\nlines"))
    }
}
