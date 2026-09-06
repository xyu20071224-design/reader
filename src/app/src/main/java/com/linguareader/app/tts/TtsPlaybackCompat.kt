package com.linguareader.app.tts

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M2 刀9：播放状态机与合成接口的真相已迁入
// com.linguareader.shared.tts.*（TtsPlaybackEngine / TtsPlaybackState /
// TtsChapter / TtsSynthesizer / TtsSynthesizerListener）。
// Android 实现（SystemTtsSynthesizer 等）留在本包实现共享接口。
// 新代码请直接 import com.linguareader.shared.tts.*。
// TODO(M2): 全量替换旧引用后删除本文件。
// ─────────────────────────────────────────────────────────────────────────────

typealias TtsPlaybackEngine = com.linguareader.shared.tts.TtsPlaybackEngine
typealias TtsPlaybackState = com.linguareader.shared.tts.TtsPlaybackState
typealias TtsUtterance = com.linguareader.shared.tts.TtsUtterance
typealias TtsSynthesizer = com.linguareader.shared.tts.TtsSynthesizer
typealias TtsSynthesizerListener = com.linguareader.shared.tts.TtsSynthesizerListener
typealias ChapterTtsPreparer = com.linguareader.shared.tts.ChapterTtsPreparer
typealias BookTtsPreparer = com.linguareader.shared.tts.BookTtsPreparer
