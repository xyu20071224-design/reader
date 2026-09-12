package com.linguareader.app.tts

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M2 刀9：播放状态机与合成接口的真相已迁入
// com.linguareader.shared.tts.*（TtsPlaybackEngine / TtsPlaybackState /
// TtsChapter / TtsSynthesizer / TtsSynthesizerListener）。
// Android 实现（SystemTtsSynthesizer 等）留在本包实现共享接口。
// 新代码请直接 import com.linguareader.shared.tts.*。
// TODO(M2): 全量替换旧引用后删除本文件。
// ⚠️ 状态（2026-09-12 第四轮审查 R5）：**延期，本轮不做**。仍有活消费者
// （ListeningBar / ReaderScreen / UiPreviews 显式 import，TtsPlaybackService 等同包调用）。
// ─────────────────────────────────────────────────────────────────────────────

typealias TtsPlaybackEngine = com.linguareader.shared.tts.TtsPlaybackEngine
typealias TtsPlaybackState = com.linguareader.shared.tts.TtsPlaybackState
typealias TtsUtterance = com.linguareader.shared.tts.TtsUtterance
typealias TtsSynthesizer = com.linguareader.shared.tts.TtsSynthesizer
typealias TtsSynthesizerListener = com.linguareader.shared.tts.TtsSynthesizerListener
typealias ChapterTtsPreparer = com.linguareader.shared.tts.ChapterTtsPreparer
typealias BookTtsPreparer = com.linguareader.shared.tts.BookTtsPreparer
