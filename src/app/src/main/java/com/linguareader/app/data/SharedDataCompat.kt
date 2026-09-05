package com.linguareader.app.data

import com.linguareader.shared.data.ContextAnalyzer as SharedContextAnalyzer

// ─────────────────────────────────────────────────────────────────────────────
// 桌面迁移 M1（决策 4 资源间接层落地时）：以下类型的【真相已在 :shared】
// （com.linguareader.shared.data.*，Models.kt / ContextAnalyzer.kt）。
//
// 这里保留旧包路径的兼容别名，是为了让既有 45+ 个 import com.linguareader.app.data.*
// 的文件零改动通过编译——「移动 = 改包名」的纪律不因引用面大而打折。
//
// 新代码请直接 import com.linguareader.shared.data.*。
// TODO(M2): 全量替换旧 import 后删除本文件。
// ─────────────────────────────────────────────────────────────────────────────

typealias Book = com.linguareader.shared.data.Book
typealias Chapter = com.linguareader.shared.data.Chapter
typealias WordLookup = com.linguareader.shared.data.WordLookup
typealias SavedWord = com.linguareader.shared.data.SavedWord
typealias ReaderPreferences = com.linguareader.shared.data.ReaderPreferences
typealias ReaderTheme = com.linguareader.shared.data.ReaderTheme
typealias ReaderFont = com.linguareader.shared.data.ReaderFont
typealias PartOfSpeech = com.linguareader.shared.data.PartOfSpeech
typealias ContextToken = com.linguareader.shared.data.ContextToken
typealias PhraseWindow = com.linguareader.shared.data.PhraseWindow
typealias DictionarySense = com.linguareader.shared.data.DictionarySense
typealias ContextualDictionaryEntry = com.linguareader.shared.data.ContextualDictionaryEntry
typealias DictionaryLookupResult = com.linguareader.shared.data.DictionaryLookupResult
typealias ReviewMode = com.linguareader.shared.data.ReviewMode
typealias ReviewPace = com.linguareader.shared.data.ReviewPace
typealias ReviewReminders = com.linguareader.shared.data.ReviewReminders

/** object 不能 typealias，用同名单例 val 兼容 `ContextAnalyzer.tokenize(...)` 这类调用。 */
val ContextAnalyzer: com.linguareader.shared.data.ContextAnalyzer = SharedContextAnalyzer

/** 同上：object 不能 typealias，用同名单例 val 兼容旧包路径的 `ReviewScheduler` 调用。 */
val ReviewScheduler: com.linguareader.shared.data.ReviewScheduler = com.linguareader.shared.data.ReviewScheduler
