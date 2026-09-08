package com.linguareader.shared.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TtsCacheKeyTest {

    @Test
    fun `pipeline version is part of the cache key`() {
        val current = TtsCacheKey.segmentDir("server:http://a.local", "narrator.wav")
        val next = TtsCacheKey.segmentDir("server:http://a.local", "narrator.wav", pipelineVersion = 2)

        assertTrue(current.contains("~v${TtsPipelineContract.VERSION}~"), current)
        assertNotEquals(current, next, "版本变了键必须全变，否则旧音频会被当成新音频播")
    }

    @Test
    fun `same inputs produce stable key`() {
        val first = TtsCacheKey.relativePath(3, 12, 1, "mimo", "Mia")
        val second = TtsCacheKey.relativePath(3, 12, 1, "mimo", "Mia")
        assertEquals(first, second)
        assertEquals("3/" + TtsCacheKey.segmentDir("mimo", "Mia") + "/s12-1.mp3", first)
    }

    @Test
    fun `engine identity is hashed into the key`() {
        val a = TtsCacheKey.segmentDir("server:http://a.local:8000", "narrator.wav")
        val b = TtsCacheKey.segmentDir("server:http://b.local:8000", "narrator.wav")
        assertNotEquals(a, b, "两台服务器上的同名音色是两种声音，键必须不同")
    }

    @Test
    fun `voice segment keeps safe ids verbatim and hashes unsafe ones`() {
        assertEquals("Mia", TtsCacheKey.voiceSegment("Mia"))
        assertEquals("mimo-clone:hero", TtsCacheKey.voiceSegment("mimo-clone:hero"))
        // 会当目录名出事的才改写，且改写必须单射
        assertEquals(TtsCacheKey.voiceSegment("男声.wav"), TtsCacheKey.voiceSegment("男声.wav"))
        assertNotEquals(TtsCacheKey.voiceSegment("男声.wav"), TtsCacheKey.voiceSegment("女声.wav"))
        assertNotEquals(TtsCacheKey.voiceSegment("a/b"), TtsCacheKey.voiceSegment("a\\b"))
        assertNotEquals(TtsCacheKey.voiceSegment(""), TtsCacheKey.voiceSegment(".."))
        assertTrue(TtsCacheKey.voiceSegment("a/b").startsWith("h-"))
    }

    @Test
    fun `unsafe voice id cannot escape the cache directory`() {
        val dir = TtsCacheKey.segmentDir("engine", "../../etc")
        assertTrue('/' !in dir && '\\' !in dir && ".." !in dir, dir)
    }

    @Test
    fun `bumping the contract is a deliberate act`() {
        // 这条断言的作用不是「保护 1」，而是让任何 bump 都必须同时改测试 ——
        // 版本号是音频缓存与音频包的闸门，不能悄悄变。
        assertEquals(1, TtsPipelineContract.VERSION)
    }
}
