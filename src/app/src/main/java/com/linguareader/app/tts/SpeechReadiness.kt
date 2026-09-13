package com.linguareader.app.tts

/**
 * 单词发音的初始化门控（issue #1 标题补充第 13 条 / `Q1-t13`）。
 *
 * 现象：显示单词发音时，首个音偶发消失。
 *
 * 代码级根因：`TextToSpeech` 的初始化在 `onInit` 回调里才完成（语言也在回调里才设），
 * 而调用方在构造之后立刻就能拿到实例并 `speak()`。落在初始化完成前的那次点击会被
 * 系统静默丢弃或截掉开头 —— 表现为「有时（尤其首次）首个音消失」。
 *
 * 这里把「未就绪先记账、就绪后补播一次」抽成不依赖 Android 的纯逻辑，便于 JVM 单测；
 * Android 侧的 TTS 装配留在 `rememberEnglishSpeaker`。
 */
internal class SpeechReadiness {

    private var ready = false
    private var pending: String? = null

    /** 未就绪时记下最后一次请求并返回 false；已就绪返回 true（调用方直接播）。 */
    fun request(text: String): Boolean {
        if (ready) return true
        pending = text
        return false
    }

    /** 引擎就绪：返回需要补播的文本（没有则 null），随后进入就绪态。 */
    fun onReady(): String? {
        ready = true
        val queued = pending
        pending = null
        return queued
    }

    /** 释放引擎时复位，避免把旧引擎的待播文本带进新引擎。 */
    fun reset() {
        ready = false
        pending = null
    }
}
